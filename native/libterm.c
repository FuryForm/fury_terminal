/**
 * FuryTerminal — Native PTY library for Android
 *
 * Pure C implementation of PTY operations and JNI bridge.
 * Build with Android NDK (clang) or via CMake externalNativeBuild.
 *
 * Copyright (c) FuryForm. MIT License.
 */

#include "pty_common.h"
#include "protocol.h"
#include <pthread.h>
#include <jni.h>
#include <sys/socket.h>
#include <sys/un.h>

/* Connect to daemon via Unix socket path.
 * Prefix with '@' for abstract socket (e.g. "@ftyd"). */
static int ftyd_connect(const char *socket_path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un addr;
    socklen_t addrlen = setup_sockaddr_un(&addr, socket_path);
    if (connect(fd, (struct sockaddr *)&addr, addrlen) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* =================== SESSION TABLE =================== */

typedef struct {
    int master;       /* PTY master fd (local) or socket fd (daemon) or pipe fd (local exec) */
    int pid;          /* child PID (local/local-exec) or -1 (daemon) */
    int in_use;
    int is_daemon;    /* 1 = daemon socket session */
    int is_local_exec;/* 1 = local pipe-based exec session */
    int daemon_alive; /* 0 = CLOSE received from daemon */
    int exit_code;    /* exit code from FTYD_EXIT_CODE or waitpid, -1 if not received */
} pty_entry;

#define MAX_PTYS 16
static pty_entry ptys[MAX_PTYS];
static pthread_mutex_t ptys_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_once_t ptys_once = PTHREAD_ONCE_INIT;

static void init_ptys(void) {
    for (int i = 0; i < MAX_PTYS; i++) {
        ptys[i].master = -1;
        ptys[i].pid    = -1;
        ptys[i].in_use = 0;
        ptys[i].is_daemon    = 0;
        ptys[i].is_local_exec = 0;
        ptys[i].daemon_alive = 0;
        ptys[i].exit_code    = -1;
    }
}

static int store_session(int fd, int pid, int is_daemon, int is_local_exec) {
    pthread_once(&ptys_once, init_ptys);
    pthread_mutex_lock(&ptys_mutex);
    int id = -1;
    for (int i = 0; i < MAX_PTYS; i++) {
        if (!ptys[i].in_use) {
            ptys[i].master        = fd;
            ptys[i].pid           = pid;
            ptys[i].in_use        = 1;
            ptys[i].is_daemon     = is_daemon;
            ptys[i].is_local_exec = is_local_exec;
            ptys[i].daemon_alive  = is_daemon ? 1 : 0;
            ptys[i].exit_code     = -1;
            id = i;
            break;
        }
    }
    pthread_mutex_unlock(&ptys_mutex);
    return id;
}

typedef struct {
    int fd;
    int is_daemon;
    int is_local_exec;
} session_info;

static session_info get_session_info(int id) {
    session_info info = { -1, 0, 0 };
    if (id < 0 || id >= MAX_PTYS) return info;
    pthread_mutex_lock(&ptys_mutex);
    if (ptys[id].in_use) {
        info.fd = ptys[id].master;
        info.is_daemon = ptys[id].is_daemon;
        info.is_local_exec = ptys[id].is_local_exec;
    }
    pthread_mutex_unlock(&ptys_mutex);
    return info;
}

static int get_pid(int id) {
    if (id < 0 || id >= MAX_PTYS) return -1;
    pthread_mutex_lock(&ptys_mutex);
    int pid = ptys[id].in_use ? ptys[id].pid : -1;
    pthread_mutex_unlock(&ptys_mutex);
    return pid;
}

static void set_daemon_dead(int id) {
    if (id < 0 || id >= MAX_PTYS) return;
    pthread_mutex_lock(&ptys_mutex);
    if (ptys[id].in_use) ptys[id].daemon_alive = 0;
    pthread_mutex_unlock(&ptys_mutex);
}

static int get_daemon_alive(int id) {
    if (id < 0 || id >= MAX_PTYS) return 0;
    pthread_mutex_lock(&ptys_mutex);
    int a = ptys[id].in_use ? ptys[id].daemon_alive : 0;
    pthread_mutex_unlock(&ptys_mutex);
    return a;
}

static void set_exit_code(int id, int code) {
    if (id < 0 || id >= MAX_PTYS) return;
    pthread_mutex_lock(&ptys_mutex);
    if (ptys[id].in_use) ptys[id].exit_code = code;
    pthread_mutex_unlock(&ptys_mutex);
}

static int get_exit_code(int id) {
    if (id < 0 || id >= MAX_PTYS) return -1;
    pthread_mutex_lock(&ptys_mutex);
    int c = ptys[id].in_use ? ptys[id].exit_code : -1;
    pthread_mutex_unlock(&ptys_mutex);
    return c;
}

/* =================== JNI STRING ARRAY HELPERS =================== */

typedef struct {
    const char **strs;   /* C string pointers (null-terminated array) */
    jstring *jstrs;      /* JNI string refs for cleanup */
    int count;
} jni_string_array;

/**
 * Convert a Java String[] to C char*[] via GetStringUTFChars.
 * Returns array with count=0 if input is NULL or empty.
 * Caller must release via jni_release_string_array().
 */
static jni_string_array jni_get_string_array(JNIEnv *env, jobjectArray arr) {
    jni_string_array sa = { NULL, NULL, 0 };
    if (!arr) return sa;
    int count = (*env)->GetArrayLength(env, arr);
    if (count <= 0) return sa;
    if (count > 16) (*env)->EnsureLocalCapacity(env, count);
    sa.strs = (const char **)malloc(sizeof(char *) * (count + 1));
    sa.jstrs = (jstring *)malloc(sizeof(jstring) * count);
    if (!sa.strs || !sa.jstrs) {
        free(sa.strs); free(sa.jstrs);
        sa.strs = NULL; sa.jstrs = NULL;
        return sa;
    }
    for (int i = 0; i < count; i++) {
        sa.jstrs[i] = (jstring)(*env)->GetObjectArrayElement(env, arr, i);
        sa.strs[i] = (*env)->GetStringUTFChars(env, sa.jstrs[i], NULL);
    }
    sa.strs[count] = NULL;
    sa.count = count;
    return sa;
}

/** Release all JNI string refs and free arrays. Safe to call on empty/zeroed struct. */
static void jni_release_string_array(JNIEnv *env, jni_string_array *sa) {
    if (!sa->strs || !sa->jstrs) return;
    for (int i = 0; i < sa->count; i++) {
        (*env)->ReleaseStringUTFChars(env, sa->jstrs[i], sa->strs[i]);
    }
    free(sa->strs);
    free(sa->jstrs);
    sa->strs = NULL;
    sa->jstrs = NULL;
    sa->count = 0;
}

/* =================== READ / CLOSE =================== */

static void do_close_pty(int id) {
    int master = -1, pid = -1, is_daemon = 0, is_local_exec = 0;

    pthread_mutex_lock(&ptys_mutex);
    if (id >= 0 && id < MAX_PTYS && ptys[id].in_use) {
        master    = ptys[id].master;
        pid       = ptys[id].pid;
        is_daemon = ptys[id].is_daemon;
        is_local_exec = ptys[id].is_local_exec;
        ptys[id].master       = -1;
        ptys[id].pid          = -1;
        ptys[id].in_use       = 0;
        ptys[id].is_daemon    = 0;
        ptys[id].is_local_exec = 0;
        ptys[id].daemon_alive = 0;
        ptys[id].exit_code    = -1;
        /* NOTE: callers must cache exit_code before calling do_close_pty,
         * since this wipes the slot. The Kotlin layer does this in close(). */
    }
    pthread_mutex_unlock(&ptys_mutex);

    if (is_daemon) {
        if (master >= 0) {
            proto_send(master, FTYD_CLOSE, NULL, 0);
            close(master);
        }
        return;
    }

    if (is_local_exec) {
        /* Local exec: close pipe first (causes child to get SIGPIPE/EOF),
         * then kill process group and wait. Try -pid first (setsid subtree),
         * fall back to pid if the process group doesn't exist (ESRCH). */
        if (master >= 0) close(master);
        if (pid > 0) {
            kill_escalate(pid, 0); /* start with SIGTERM for exec */
        }
        return;
    }

    /* Local PTY: escalating kill (process group, ESRCH fallback) */
    if (pid > 0) {
        kill_escalate(pid, 1); /* start with SIGHUP for PTY */
    }
    if (master >= 0) close(master);
}

/* =================== JNI FUNCTIONS =================== */

JNIEXPORT jint JNICALL
Java_com_furyform_terminal_NativePTY_nativeStartPTY(
        JNIEnv *env, jobject thiz, jint rows, jint cols, jstring jShell,
        jobjectArray jEnvVars, jstring jCwd) {
    const char *shell = (*env)->GetStringUTFChars(env, jShell, NULL);
    const char *cwd = jCwd ? (*env)->GetStringUTFChars(env, jCwd, NULL) : NULL;

    jni_string_array env_sa = jni_get_string_array(env, jEnvVars);

    int master, slave;
    if (my_openpty(&master, &slave) != 0) {
        (*env)->ReleaseStringUTFChars(env, jShell, shell);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        jni_release_string_array(env, &env_sa);
        return -1;
    }
    if (set_winsize(master, (int)rows, (int)cols) != 0) {
        (*env)->ReleaseStringUTFChars(env, jShell, shell);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        jni_release_string_array(env, &env_sa);
        close(master); close(slave);
        return -1;
    }
    int pid = do_fork_exec(slave, shell, env_sa.strs, cwd);

    /* Release all JNI strings */
    (*env)->ReleaseStringUTFChars(env, jShell, shell);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    jni_release_string_array(env, &env_sa);

    if (pid < 0) { close(master); close(slave); return -1; }
    close(slave);
    int id = store_session(master, pid, 0, 0);
    if (id < 0) {
        if (kill(-pid, SIGKILL) != 0 && errno == ESRCH)
            kill(pid, SIGKILL);
        waitpid(pid, NULL, 0);
        close(master);
        return -1;
    }
    return (jint)id;
}

JNIEXPORT jint JNICALL
Java_com_furyform_terminal_NativePTY_nativeStartDaemonSession(
        JNIEnv *env, jobject thiz, jstring jSocketPath, jint rows, jint cols, jstring jShell,
        jobjectArray jEnvVars, jstring jCwd) {
    const char *path = (*env)->GetStringUTFChars(env, jSocketPath, NULL);
    int fd = ftyd_connect(path);
    (*env)->ReleaseStringUTFChars(env, jSocketPath, path);
    if (fd < 0) return -1;

    const char *shell = (*env)->GetStringUTFChars(env, jShell, NULL);
    const char *cwd = jCwd ? (*env)->GetStringUTFChars(env, jCwd, NULL) : NULL;

    jni_string_array env_sa = jni_get_string_array(env, jEnvVars);

    /* Build extended RESIZE payload: [rows:2][cols:2][shell\0cwd\0env1\0env2\0...] */
    int has_extended = (shell[0] != '\0') || (cwd && cwd[0] != '\0') || env_sa.count > 0;
    uint32_t payload_len = 4; /* rows + cols */

    if (has_extended) {
        uint32_t shell_len = (uint32_t)strlen(shell);
        uint32_t cwd_len = cwd ? (uint32_t)strlen(cwd) : 0;
        payload_len += shell_len + 1 + cwd_len + 1; /* shell\0cwd\0 */
        for (int i = 0; i < env_sa.count; i++) {
            payload_len += (uint32_t)strlen(env_sa.strs[i]) + 1; /* envN\0 */
        }
    }

    uint8_t *payload = (uint8_t *)malloc(payload_len);
    if (!payload) {
        (*env)->ReleaseStringUTFChars(env, jShell, shell);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        jni_release_string_array(env, &env_sa);
        close(fd);
        return -1;
    }

    payload[0] = (uint8_t)((int)rows >> 8);
    payload[1] = (uint8_t)((int)rows);
    payload[2] = (uint8_t)((int)cols >> 8);
    payload[3] = (uint8_t)((int)cols);

    if (has_extended) {
        uint32_t off = 4;
        uint32_t shell_len = (uint32_t)strlen(shell);
        memcpy(payload + off, shell, shell_len);
        off += shell_len;
        payload[off++] = '\0';

        if (cwd) {
            uint32_t cwd_len = (uint32_t)strlen(cwd);
            memcpy(payload + off, cwd, cwd_len);
            off += cwd_len;
        }
        payload[off++] = '\0';

        for (int i = 0; i < env_sa.count; i++) {
            uint32_t elen = (uint32_t)strlen(env_sa.strs[i]);
            memcpy(payload + off, env_sa.strs[i], elen);
            off += elen;
            payload[off++] = '\0';
        }
    }

    int rc = proto_send(fd, FTYD_RESIZE, payload, payload_len);
    free(payload);

    /* Release JNI strings */
    (*env)->ReleaseStringUTFChars(env, jShell, shell);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    jni_release_string_array(env, &env_sa);

    if (rc < 0) { close(fd); return -1; }

    int id = store_session(fd, -1, 1, 0);
    if (id < 0) { close(fd); return -1; }
    return (jint)id;
}

JNIEXPORT jint JNICALL
Java_com_furyform_terminal_NativePTY_nativeStartExecSession(
        JNIEnv *env, jobject thiz, jstring jSocketPath, jstring jCommand, jstring jShell,
        jobjectArray jEnvVars, jstring jCwd) {
    const char *path = (*env)->GetStringUTFChars(env, jSocketPath, NULL);
    int fd = ftyd_connect(path);
    (*env)->ReleaseStringUTFChars(env, jSocketPath, path);
    if (fd < 0) return -1;

    const char *cmd = (*env)->GetStringUTFChars(env, jCommand, NULL);
    const char *shell = (*env)->GetStringUTFChars(env, jShell, NULL);
    const char *cwd = jCwd ? (*env)->GetStringUTFChars(env, jCwd, NULL) : NULL;

    jni_string_array env_sa = jni_get_string_array(env, jEnvVars);

    /* Build EXEC payload: shell\0command[\0cwd[\0env1\0env2\0...]] */
    uint32_t shell_len = (uint32_t)strlen(shell);
    uint32_t cmd_len = (uint32_t)strlen(cmd);
    uint32_t cwd_len = cwd ? (uint32_t)strlen(cwd) : 0;
    uint32_t payload_len = shell_len + 1 + cmd_len; /* shell\0command */

    int has_cwd_or_env = (cwd && cwd[0] != '\0') || env_sa.count > 0;
    if (has_cwd_or_env) {
        payload_len += 1 + cwd_len; /* \0cwd */
        for (int i = 0; i < env_sa.count; i++) {
            payload_len += 1 + (uint32_t)strlen(env_sa.strs[i]); /* \0envN */
        }
    }

    uint8_t *payload = (uint8_t *)malloc(payload_len);
    if (!payload) {
        (*env)->ReleaseStringUTFChars(env, jCommand, cmd);
        (*env)->ReleaseStringUTFChars(env, jShell, shell);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        jni_release_string_array(env, &env_sa);
        close(fd);
        return -1;
    }

    uint32_t off = 0;
    memcpy(payload + off, shell, shell_len);
    off += shell_len;
    payload[off++] = '\0';
    memcpy(payload + off, cmd, cmd_len);
    off += cmd_len;

    if (has_cwd_or_env) {
        payload[off++] = '\0';
        if (cwd) {
            memcpy(payload + off, cwd, cwd_len);
            off += cwd_len;
        }
        for (int i = 0; i < env_sa.count; i++) {
            payload[off++] = '\0';
            uint32_t elen = (uint32_t)strlen(env_sa.strs[i]);
            memcpy(payload + off, env_sa.strs[i], elen);
            off += elen;
        }
    }

    int rc = proto_send(fd, FTYD_EXEC, payload, payload_len);
    free(payload);
    (*env)->ReleaseStringUTFChars(env, jCommand, cmd);
    (*env)->ReleaseStringUTFChars(env, jShell, shell);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    jni_release_string_array(env, &env_sa);
    if (rc < 0) { close(fd); return -1; }

    int id = store_session(fd, -1, 1, 0);
    if (id < 0) { close(fd); return -1; }
    return (jint)id;
}

/**
 * Start a local exec session: fork + pipes, no daemon needed.
 * The child runs as the app's own UID.
 * Output is read via nativeRead (plain pipe read).
 * Exit code is retrieved via nativeGetExitCode (waitpid).
 */
JNIEXPORT jint JNICALL
Java_com_furyform_terminal_NativePTY_nativeStartLocalExecSession(
        JNIEnv *env, jobject thiz, jstring jShell, jstring jCommand,
        jobjectArray jEnvVars, jstring jCwd) {
    const char *shell = (*env)->GetStringUTFChars(env, jShell, NULL);
    const char *cmd = (*env)->GetStringUTFChars(env, jCommand, NULL);
    const char *cwd = jCwd ? (*env)->GetStringUTFChars(env, jCwd, NULL) : NULL;

    jni_string_array env_sa = jni_get_string_array(env, jEnvVars);

    /* Create pipes: stdout_pipe for stdout+stderr */
    int stdout_pipe[2]; /* [0]=read, [1]=write */
    if (pipe(stdout_pipe) < 0) {
        (*env)->ReleaseStringUTFChars(env, jShell, shell);
        (*env)->ReleaseStringUTFChars(env, jCommand, cmd);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        jni_release_string_array(env, &env_sa);
        return -1;
    }

    pid_t pid = fork();
    if (pid < 0) {
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        (*env)->ReleaseStringUTFChars(env, jShell, shell);
        (*env)->ReleaseStringUTFChars(env, jCommand, cmd);
        if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        jni_release_string_array(env, &env_sa);
        return -1;
    }

    if (pid == 0) {
        /* Child: wire up pipes and exec */
        setsid();
        close(stdout_pipe[0]);
        dup2(stdout_pipe[1], 1);
        dup2(stdout_pipe[1], 2);
        close(stdout_pipe[1]);

        /* Close all FDs > 2 */
        int maxfd = (int)sysconf(_SC_OPEN_MAX);
        if (maxfd < 0) maxfd = 256;
        for (int fd = 3; fd < maxfd; fd++) close(fd);

        /* Change working directory if specified */
        if (cwd && cwd[0] != '\0') {
            chdir(cwd);
        }

        /* Set environment */
        setenv("PATH", DEFAULT_PATH, 1);
        setenv("HOME", "/data/local/tmp", 0);

        /* Apply custom environment variables */
        apply_env_pairs(env_sa.strs);

        execlp(shell, shell, "-c", cmd, (char *)NULL);
        _exit(127);
    }

    /* Parent */
    close(stdout_pipe[1]);
    (*env)->ReleaseStringUTFChars(env, jShell, shell);
    (*env)->ReleaseStringUTFChars(env, jCommand, cmd);
    if (cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
    jni_release_string_array(env, &env_sa);

    int id = store_session(stdout_pipe[0], pid, 0, 1);
    if (id < 0) {
        if (kill(-pid, SIGKILL) != 0 && errno == ESRCH)
            kill(pid, SIGKILL);
        waitpid(pid, NULL, 0);
        close(stdout_pipe[0]);
        return -1;
    }
    return (jint)id;
}

JNIEXPORT jbyteArray JNICALL
Java_com_furyform_terminal_NativePTY_nativeRead(JNIEnv *env, jobject thiz, jint id) {
    session_info si = get_session_info((int)id);
    if (si.fd < 0) return NULL;

    if (si.is_daemon) {
        uint8_t buf[8192];
        int exit_code = -1;
        int n = proto_recv_data_ex(si.fd, buf, sizeof(buf), &exit_code);
        if (n <= 0) {
            if (exit_code >= 0) set_exit_code((int)id, exit_code);
            set_daemon_dead((int)id);
            return NULL;
        }
        jbyteArray arr = (*env)->NewByteArray(env, n);
        if (arr) (*env)->SetByteArrayRegion(env, arr, 0, n, (jbyte *)buf);
        return arr;
    }

    uint8_t buf[8192];
    ssize_t n = read(si.fd, buf, sizeof(buf));
    if (n <= 0) return NULL;
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)n);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, (jsize)n, (jbyte *)buf);
    return arr;
}

JNIEXPORT jint JNICALL
Java_com_furyform_terminal_NativePTY_nativeWrite(JNIEnv *env, jobject thiz, jint id, jbyteArray data) {
    session_info si = get_session_info((int)id);
    if (si.fd < 0) return -1;
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return -1;
    int n;
    if (si.is_daemon) {
        n = proto_send(si.fd, FTYD_DATA, (uint8_t *)buf, (uint32_t)len) == 0 ? (int)len : -1;
    } else {
        n = (int)write(si.fd, buf, (size_t)len);
    }
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return (jint)n;
}

JNIEXPORT jint JNICALL
Java_com_furyform_terminal_NativePTY_nativeResize(JNIEnv *env, jobject thiz, jint id, jint rows, jint cols) {
    session_info si = get_session_info((int)id);
    if (si.fd < 0) return -1;
    if (si.is_daemon) {
        uint8_t d[4] = { (uint8_t)((int)rows>>8), (uint8_t)(rows),
                         (uint8_t)((int)cols>>8), (uint8_t)(cols) };
        return proto_send(si.fd, FTYD_RESIZE, d, 4) == 0 ? 0 : -1;
    }
    return (jint)set_winsize(si.fd, (int)rows, (int)cols);
}

JNIEXPORT void JNICALL
Java_com_furyform_terminal_NativePTY_nativeClose(JNIEnv *env, jobject thiz, jint id) {
    do_close_pty((int)id);
}

JNIEXPORT void JNICALL
Java_com_furyform_terminal_NativePTY_nativeSendSignal(JNIEnv *env, jobject thiz, jint id, jint signum) {
    session_info si = get_session_info((int)id);
    if (si.fd < 0) return;
    if (si.is_daemon) {
        uint8_t s = (uint8_t)(int)signum;
        proto_send(si.fd, FTYD_SIGNAL, &s, 1);
        return;
    }
    int pid = get_pid((int)id);
    if (pid <= 0) return;

    /* Try process-group kill first: setsid() in child makes -pid target the
     * entire subtree (e.g. su → sh → ping).  Some shells (mksh) may reset
     * the process group, causing ESRCH; fall back to direct pid kill. */
    if (kill(-pid, (int)signum) != 0 && errno == ESRCH) {
        kill(pid, (int)signum);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_furyform_terminal_NativePTY_nativeIsAlive(JNIEnv *env, jobject thiz, jint id) {
    session_info si = get_session_info((int)id);
    if (si.is_daemon) {
        return get_daemon_alive((int)id) ? JNI_TRUE : JNI_FALSE;
    }
    int pid = get_pid((int)id);
    if (pid <= 0) return JNI_FALSE;

    /* Use waitpid(WNOHANG) to detect zombies (exited but not yet reaped).
     * kill(pid, 0) would return true for zombies — misleading.
     * If the zombie IS reaped here, clear the PID so do_close_pty()
     * won't send signals to a potentially recycled PID. */
    int status;
    int ret = waitpid(pid, &status, WNOHANG);
    if (ret == 0) {
        /* Still running */
        return JNI_TRUE;
    }
    if (ret == pid) {
        /* Exited (was zombie, now reaped). Clear PID in table.
         * For local exec sessions, also store the exit code. */
        pthread_mutex_lock(&ptys_mutex);
        if ((int)id >= 0 && (int)id < MAX_PTYS && ptys[(int)id].in_use && ptys[(int)id].pid == pid) {
            ptys[(int)id].pid = -1;
            int exit_code = exit_code_from_status(status);
            ptys[(int)id].exit_code = exit_code;
        }
        pthread_mutex_unlock(&ptys_mutex);
        return JNI_FALSE;
    }
    /* ret == -1: ECHILD (already reaped by someone else) */
    return JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_furyform_terminal_NativePTY_nativeGetExitCode(JNIEnv *env, jobject thiz, jint id) {
    int code = get_exit_code((int)id);
    if (code >= 0) return (jint)code;

    /* Reap the child (blocking) to get exit code.
     * For exec sessions this is called after readAll() (pipe at EOF).
     * For PTY sessions this is called after output EOF / process death.
     * The child is exiting or already exited — blocking waitpid returns fast. */
    if ((int)id >= 0 && (int)id < MAX_PTYS) {
        pthread_mutex_lock(&ptys_mutex);
        int pid = ptys[(int)id].in_use ? ptys[(int)id].pid : -1;
        pthread_mutex_unlock(&ptys_mutex);

        if (pid > 0) {
            int status;
            /* Try non-blocking first — nativeIsAlive may have already reaped */
            int ret = waitpid(pid, &status, WNOHANG);
            if (ret == 0) {
                /* Still running; block for it */
                ret = waitpid(pid, &status, 0);
            }
            if (ret == pid) {
                int exit_code = exit_code_from_status(status);
                set_exit_code((int)id, exit_code);
                /* Clear PID to prevent double-reap */
                pthread_mutex_lock(&ptys_mutex);
                if (ptys[(int)id].in_use && ptys[(int)id].pid == pid) {
                    ptys[(int)id].pid = -1;
                }
                pthread_mutex_unlock(&ptys_mutex);
                return (jint)exit_code;
            }
            /* waitpid failed (ECHILD) — child may have been reaped by
             * nativeIsAlive on another thread. Re-check stored exit code. */
            code = get_exit_code((int)id);
            if (code >= 0) return (jint)code;
        }
    }
    return (jint)code;
}

/* =================== SESSION LISTING =================== */

/**
 * List active daemon sessions via the FTYD_LIST protocol message.
 * Opens a temporary connection, sends LIST, reads the binary response,
 * and returns a flat int array with stride 7 per session:
 *   [sessionId, pid, uid, type, alive, startTimeHigh, startTimeLow]
 * Returns NULL on connection or protocol error.
 */
JNIEXPORT jintArray JNICALL
Java_com_furyform_terminal_NativePTY_nativeListDaemonSessions(
        JNIEnv *env, jobject thiz, jstring jSocketPath) {
    const char *path = (*env)->GetStringUTFChars(env, jSocketPath, NULL);
    int fd = ftyd_connect(path);
    (*env)->ReleaseStringUTFChars(env, jSocketPath, path);
    if (fd < 0) return NULL;

    /* Send LIST request (no payload) */
    if (proto_send(fd, FTYD_LIST, NULL, 0) < 0) {
        close(fd);
        return NULL;
    }

    /* Read response */
    uint8_t msg_type;
    uint8_t msg_buf[8192]; /* 32 sessions * 22 bytes + 2 = 706 bytes max */
    uint32_t msg_len;
    if (proto_recv(fd, &msg_type, msg_buf, sizeof(msg_buf), &msg_len) < 0) {
        close(fd);
        return NULL;
    }
    close(fd);

    if (msg_type != FTYD_LIST || msg_len < 2) return NULL;

    /* Parse count */
    uint16_t count = ((uint16_t)msg_buf[0] << 8) | (uint16_t)msg_buf[1];
    if (msg_len < 2 + (uint32_t)count * 22) return NULL;

    /* Build jintArray: 7 ints per session */
    jint total = (jint)count * 7;
    jintArray result = (*env)->NewIntArray(env, total);
    if (!result) return NULL;

    jint *arr = (*env)->GetIntArrayElements(env, result, NULL);
    if (!arr) return NULL;

    size_t off = 2;
    for (int i = 0; i < count; i++) {
        uint32_t sid = ((uint32_t)msg_buf[off] << 24) | ((uint32_t)msg_buf[off+1] << 16)
                     | ((uint32_t)msg_buf[off+2] << 8) | (uint32_t)msg_buf[off+3];
        off += 4;

        uint32_t pid = ((uint32_t)msg_buf[off] << 24) | ((uint32_t)msg_buf[off+1] << 16)
                     | ((uint32_t)msg_buf[off+2] << 8) | (uint32_t)msg_buf[off+3];
        off += 4;

        uint32_t uid = ((uint32_t)msg_buf[off] << 24) | ((uint32_t)msg_buf[off+1] << 16)
                     | ((uint32_t)msg_buf[off+2] << 8) | (uint32_t)msg_buf[off+3];
        off += 4;

        uint8_t type = msg_buf[off++];
        uint8_t alive = msg_buf[off++];

        uint64_t stime = ((uint64_t)msg_buf[off] << 56) | ((uint64_t)msg_buf[off+1] << 48)
                       | ((uint64_t)msg_buf[off+2] << 40) | ((uint64_t)msg_buf[off+3] << 32)
                       | ((uint64_t)msg_buf[off+4] << 24) | ((uint64_t)msg_buf[off+5] << 16)
                       | ((uint64_t)msg_buf[off+6] << 8)  | (uint64_t)msg_buf[off+7];
        off += 8;

        int base = i * 7;
        arr[base]     = (jint)sid;
        arr[base + 1] = (jint)pid;
        arr[base + 2] = (jint)uid;
        arr[base + 3] = (jint)type;
        arr[base + 4] = (jint)alive;
        arr[base + 5] = (jint)(stime >> 32);        /* high 32 bits */
        arr[base + 6] = (jint)(stime & 0xFFFFFFFF); /* low 32 bits */
    }

    (*env)->ReleaseIntArrayElements(env, result, arr, 0);
    return result;
}
