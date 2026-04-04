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
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;

    int abstract_sock = (socket_path[0] == '@');
    if (abstract_sock) {
        addr.sun_path[0] = '\0';
        strncpy(addr.sun_path + 1, socket_path + 1, sizeof(addr.sun_path) - 2);
    } else {
        strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);
    }

    socklen_t addrlen = (socklen_t)(offsetof(struct sockaddr_un, sun_path)
                      + (abstract_sock ? strlen(socket_path) : strlen(socket_path) + 1));
    if (connect(fd, (struct sockaddr *)&addr, addrlen) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* =================== SESSION TABLE =================== */

typedef struct {
    int master;       /* PTY master fd (local) or socket fd (daemon) */
    int pid;          /* child PID (local) or -1 (daemon) */
    int in_use;
    int is_daemon;    /* 1 = daemon socket session */
    int daemon_alive; /* 0 = CLOSE received from daemon */
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
        ptys[i].daemon_alive = 0;
    }
}

static int store_pty(int master, int pid) {
    pthread_once(&ptys_once, init_ptys);
    pthread_mutex_lock(&ptys_mutex);
    int id = -1;
    for (int i = 0; i < MAX_PTYS; i++) {
        if (!ptys[i].in_use) {
            ptys[i].master       = master;
            ptys[i].pid          = pid;
            ptys[i].in_use       = 1;
            ptys[i].is_daemon    = 0;
            ptys[i].daemon_alive = 0;
            id = i;
            break;
        }
    }
    pthread_mutex_unlock(&ptys_mutex);
    return id;
}

static int store_daemon_session(int sock_fd) {
    pthread_once(&ptys_once, init_ptys);
    pthread_mutex_lock(&ptys_mutex);
    int id = -1;
    for (int i = 0; i < MAX_PTYS; i++) {
        if (!ptys[i].in_use) {
            ptys[i].master       = sock_fd;
            ptys[i].pid          = -1;
            ptys[i].in_use       = 1;
            ptys[i].is_daemon    = 1;
            ptys[i].daemon_alive = 1;
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
} session_info;

static session_info get_session_info(int id) {
    session_info info = { -1, 0 };
    if (id < 0 || id >= MAX_PTYS) return info;
    pthread_mutex_lock(&ptys_mutex);
    if (ptys[id].in_use) {
        info.fd = ptys[id].master;
        info.is_daemon = ptys[id].is_daemon;
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

/* =================== READ / CLOSE =================== */

typedef struct { char *data; int length; } read_result;

static read_result do_read(int fd, int buf_size) {
    read_result r = { NULL, 0 };
    char *buf = (char *)malloc(buf_size);
    if (!buf) return r;
    ssize_t n = read(fd, buf, buf_size);
    if (n <= 0) { free(buf); return r; }
    r.data   = buf;
    r.length = (int)n;
    return r;
}

static void do_close_pty(int id) {
    int master = -1, pid = -1, is_daemon = 0;

    pthread_mutex_lock(&ptys_mutex);
    if (id >= 0 && id < MAX_PTYS && ptys[id].in_use) {
        master    = ptys[id].master;
        pid       = ptys[id].pid;
        is_daemon = ptys[id].is_daemon;
        ptys[id].master       = -1;
        ptys[id].pid          = -1;
        ptys[id].in_use       = 0;
        ptys[id].is_daemon    = 0;
        ptys[id].daemon_alive = 0;
    }
    pthread_mutex_unlock(&ptys_mutex);

    if (is_daemon) {
        if (master >= 0) {
            proto_send(master, FTYD_CLOSE, NULL, 0);
            close(master);
        }
        return;
    }

    /* Local PTY: escalating kill (process group) */
    if (pid > 0) {
        int status;
        kill(-pid, SIGHUP);
        usleep(100000);
        if (waitpid(pid, &status, WNOHANG) == 0) {
            kill(-pid, SIGTERM);
            usleep(100000);
            if (waitpid(pid, &status, WNOHANG) == 0) {
                kill(-pid, SIGKILL);
                waitpid(pid, &status, 0);
            }
        }
    }
    if (master >= 0) close(master);
}

/* =================== JNI FUNCTIONS =================== */

JNIEXPORT jint JNICALL
Java_com_furyform_terminal_NativePTY_nativeStartPTY(JNIEnv *env, jobject thiz, jint rows, jint cols) {
    int master, slave;
    if (my_openpty(&master, &slave) != 0) return -1;
    if (set_winsize(master, (int)rows, (int)cols) != 0) { close(master); close(slave); return -1; }
    int pid = do_fork_exec(slave, "/system/bin/sh");
    if (pid < 0) { close(master); close(slave); return -1; }
    close(slave);
    int id = store_pty(master, pid);
    if (id < 0) { kill(pid, SIGKILL); waitpid(pid, NULL, 0); close(master); return -1; }
    return (jint)id;
}

JNIEXPORT jint JNICALL
Java_com_furyform_terminal_NativePTY_nativeStartDaemonSession(
        JNIEnv *env, jobject thiz, jstring jSocketPath, jint rows, jint cols) {
    const char *path = (*env)->GetStringUTFChars(env, jSocketPath, NULL);
    int fd = ftyd_connect(path);
    (*env)->ReleaseStringUTFChars(env, jSocketPath, path);
    if (fd < 0) return -1;

    /* Send initial RESIZE so daemon knows terminal dimensions and spawns the shell */
    uint8_t resize_data[4];
    resize_data[0] = (uint8_t)((int)rows >> 8);
    resize_data[1] = (uint8_t)((int)rows);
    resize_data[2] = (uint8_t)((int)cols >> 8);
    resize_data[3] = (uint8_t)((int)cols);
    if (proto_send(fd, FTYD_RESIZE, resize_data, 4) < 0) { close(fd); return -1; }

    int id = store_daemon_session(fd);
    if (id < 0) { close(fd); return -1; }
    return (jint)id;
}

JNIEXPORT jbyteArray JNICALL
Java_com_furyform_terminal_NativePTY_nativeRead(JNIEnv *env, jobject thiz, jint id) {
    session_info si = get_session_info((int)id);
    if (si.fd < 0) return NULL;

    if (si.is_daemon) {
        uint8_t buf[8192];
        int n = proto_recv_data(si.fd, buf, sizeof(buf));
        if (n <= 0) {
            set_daemon_dead((int)id);
            return NULL;
        }
        jbyteArray arr = (*env)->NewByteArray(env, n);
        if (arr) (*env)->SetByteArrayRegion(env, arr, 0, n, (jbyte *)buf);
        return arr;
    }

    read_result r = do_read(si.fd, 8192);
    if (!r.data || r.length <= 0) { if (r.data) free(r.data); return NULL; }
    jbyteArray arr = (*env)->NewByteArray(env, r.length);
    if (arr) (*env)->SetByteArrayRegion(env, arr, 0, r.length, (jbyte *)r.data);
    free(r.data);
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
    /* Send to the entire process group (negative pid) so children
     * (e.g. ping, top) also receive the signal, not just the shell. */
    if (pid > 0) kill(-pid, (int)signum);
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
        /* Exited (was zombie, now reaped). Clear PID in table. */
        pthread_mutex_lock(&ptys_mutex);
        if ((int)id >= 0 && (int)id < MAX_PTYS && ptys[(int)id].in_use && ptys[(int)id].pid == pid) {
            ptys[(int)id].pid = -1;
        }
        pthread_mutex_unlock(&ptys_mutex);
        return JNI_FALSE;
    }
    /* ret == -1: ECHILD (already reaped by someone else) */
    return JNI_FALSE;
}
