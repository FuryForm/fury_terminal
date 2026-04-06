/**
 * FuryTerminal Daemon (ftyd) — Root PTY broker for Android
 *
 * Listens on a Unix socket and spawns a root shell for each connecting client.
 * Protocol matches the client in libterm.c:
 *   Message format: [type:uint8][length:uint32 BE][data:length]
 *   Types: DATA=0x01, RESIZE=0x02, SIGNAL=0x03, CLOSE=0x04,
 *          EXEC=0x05, EXIT_CODE=0x06
 *
 * Client flow (interactive):
 *   1. Connect to socket
 *   2. Send RESIZE (4 bytes: rows BE16 + cols BE16) — daemon spawns shell
 *   3. Exchange DATA messages
 *   4. Send CLOSE or disconnect to terminate
 *
 * Client flow (exec):
 *   1. Connect to socket
 *   2. Send EXEC (payload = "shell\0command" or just "command") — daemon runs sh -c via pipes
 *   3. Receive DATA (stdout+stderr), then EXIT_CODE, then CLOSE
 *
 * Build: Android NDK clang, dynamic linking (liblog.so always available).
 *   aarch64-linux-android21-clang -o ftyd ftyd.c -llog
 *
 * Copyright (c) FuryForm. MIT License.
 */

#include "pty_common.h"
#include "protocol.h"
#include <stdio.h>
#include <stdatomic.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>

#define DEFAULT_SOCKET_PATH "@ftyd"
#define DEFAULT_SHELL       "/system/bin/sh"
#define MAX_CLIENTS         16
#define BUF_SIZE            8192
#define PID_FILE            "/data/local/tmp/ftyd.pid"
#define AUTH_CONF_PATH      "/data/local/tmp/ftyd.conf"
#define PACKAGES_LIST_PATH  "/data/system/packages.list"
#define MAX_ALLOWED_UIDS    64

/* =================== LOGGING =================== */

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "ftyd"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...) fprintf(stderr, __VA_ARGS__)
#define LOGI(...) fprintf(stderr, __VA_ARGS__)
#define LOGE(...) fprintf(stderr, __VA_ARGS__)
#endif

/* =================== AUTH: UID ALLOWLIST =================== */

/**
 * Optional UID-based authentication via SO_PEERCRED.
 * Disabled by default — enable with -a flag.
 *
 * When enabled, only connections from allowed UIDs are accepted.
 * UIDs 0 (root) and 2000 (shell/adb) are always allowed.
 *
 * Config file format (one directive per line, '#' comments):
 *   allow com.example.myapp      # Allow by package name
 *   allow_uid 10245              # Allow by raw UID
 *
 * Package names are resolved to UIDs via /data/system/packages.list
 * at startup and on SIGHUP. Unresolvable packages are logged and skipped.
 */

static struct {
    uid_t uids[MAX_ALLOWED_UIDS];
    int   count;
    int   enabled;  /* 0 = auth disabled (default), 1 = enabled via -a */
} g_auth;

static pthread_rwlock_t g_auth_lock = PTHREAD_RWLOCK_INITIALIZER;

/* Add a UID to the allowlist (no duplicates). Returns 0 on success. */
static int auth_add_uid(uid_t uid) {
    for (int i = 0; i < g_auth.count; i++) {
        if (g_auth.uids[i] == uid) return 0; /* already present */
    }
    if (g_auth.count >= MAX_ALLOWED_UIDS) {
        LOGE("Auth: allowlist full (max %d UIDs)\n", MAX_ALLOWED_UIDS);
        return -1;
    }
    g_auth.uids[g_auth.count++] = uid;
    return 0;
}

/* Resolve a package name to UID via /data/system/packages.list.
 * Format: "package_name uid gid ..." one per line.
 * Returns UID >= 0 on success, -1 if not found. */
static int auth_resolve_package(const char *package) {
    FILE *f = fopen(PACKAGES_LIST_PATH, "r");
    if (!f) {
        LOGE("Auth: cannot open %s: %s\n", PACKAGES_LIST_PATH, strerror(errno));
        return -1;
    }
    char line[512];
    int result = -1;
    while (fgets(line, sizeof(line), f)) {
        char pkg[256];
        unsigned int uid;
        if (sscanf(line, "%255s %u", pkg, &uid) == 2) {
            if (strcmp(pkg, package) == 0) {
                result = (int)uid;
                break;
            }
        }
    }
    fclose(f);
    return result;
}

/* Load auth config file. Resets allowlist and re-adds defaults + config entries. */
static void auth_load_config(const char *conf_path) {
    pthread_rwlock_wrlock(&g_auth_lock);

    /* Reset to defaults */
    g_auth.count = 0;
    auth_add_uid(0);    /* root — always allowed */
    auth_add_uid(2000); /* shell (adb) — always allowed */

    FILE *f = fopen(conf_path, "r");
    if (!f) {
        LOGI("Auth: no config at %s — defaults only (root + shell)\n", conf_path);
        pthread_rwlock_unlock(&g_auth_lock);
        return;
    }

    char line[512];
    while (fgets(line, sizeof(line), f)) {
        /* Strip comments */
        char *hash = strchr(line, '#');
        if (hash) *hash = '\0';

        /* Try "allow_uid <N>" */
        unsigned int uid;
        if (sscanf(line, " allow_uid %u", &uid) == 1) {
            auth_add_uid((uid_t)uid);
            LOGI("Auth: allow_uid %u\n", uid);
            continue;
        }

        /* Try "allow <package_name>" */
        char package[256];
        if (sscanf(line, " allow %255s", package) == 1) {
            int resolved = auth_resolve_package(package);
            if (resolved >= 0) {
                auth_add_uid((uid_t)resolved);
                LOGI("Auth: allow %s (uid=%d)\n", package, resolved);
            } else {
                LOGE("Auth: package '%s' not found in %s — skipped\n",
                     package, PACKAGES_LIST_PATH);
            }
            continue;
        }

        /* Ignore blank/whitespace-only lines */
    }
    fclose(f);
    LOGI("Auth: loaded %d allowed UIDs from %s\n", g_auth.count, conf_path);

    pthread_rwlock_unlock(&g_auth_lock);
}

/* Check if a UID is in the allowlist. */
static int auth_uid_allowed(uid_t uid) {
    pthread_rwlock_rdlock(&g_auth_lock);
    for (int i = 0; i < g_auth.count; i++) {
        if (g_auth.uids[i] == uid) {
            pthread_rwlock_unlock(&g_auth_lock);
            return 1;
        }
    }
    pthread_rwlock_unlock(&g_auth_lock);
    return 0;
}

/* Check connecting client via SO_PEERCRED.
 * Returns 0 if allowed (or auth disabled), -1 if rejected. */
static int auth_check_peer(int client_fd) {
    if (!g_auth.enabled) return 0; /* Auth disabled — allow all */

    struct ucred cred;
    socklen_t len = sizeof(cred);
    if (getsockopt(client_fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) < 0) {
        LOGE("Auth: SO_PEERCRED failed fd=%d: %s\n", client_fd, strerror(errno));
        return -1; /* Cannot verify — reject (fail-closed) */
    }

    if (auth_uid_allowed(cred.uid)) {
        LOGD("Auth: accepted UID=%u PID=%d\n", cred.uid, cred.pid);
        return 0;
    }

    LOGE("Auth: rejected UID=%u PID=%d (not in allowlist)\n", cred.uid, cred.pid);
    return -1;
}

/* =================== EXEC PID TABLE =================== */

/**
 * Track PIDs of exec'd children so reap_children() can save their exit
 * status instead of discarding it.  The SIGCHLD handler runs in any
 * thread that hasn't blocked the signal — which means it races with
 * handle_exec()'s waitpid().  This table breaks the race:
 *   1. handle_exec registers the PID immediately after fork()
 *   2. reap_children finds the PID here and stores the wait status
 *   3. handle_exec polls until ready==1, then reads the status
 *
 * Lock-free from the handler side (atomic ready flag).
 * CAS-protected for register (thread-safe without mutex).
 */
#define MAX_EXEC_PIDS 32

static struct {
    struct {
        pid_t pid;   /* 0 = slot free; accessed via __atomic_* */
        int   status; /* accessed via __atomic_* */
        int   ready; /* 1 = reaped by handler; accessed via __atomic_* */
    } entries[MAX_EXEC_PIDS];
} exec_table;

/* Called right after fork() in handle_exec, before child can exit.
 * Uses CAS to avoid two threads claiming the same slot.
 * Returns slot index, or -1 if table full. */
static int exec_table_register(pid_t pid) {
    for (int i = 0; i < MAX_EXEC_PIDS; i++) {
        pid_t expected = 0;
        /* Pre-initialize status/ready BEFORE publishing PID.
         * Since pid==0 means "free slot", the handler ignores this slot
         * until the CAS below publishes the PID.  We must ensure status
         * and ready are visible before the PID becomes visible. */
        if (__atomic_load_n(&exec_table.entries[i].pid, __ATOMIC_ACQUIRE) == 0) {
            __atomic_store_n(&exec_table.entries[i].status, 0, __ATOMIC_RELAXED);
            __atomic_store_n(&exec_table.entries[i].ready, 0, __ATOMIC_RELAXED);
            /* Release fence ensures status/ready are visible before PID */
            expected = 0;
            if (__atomic_compare_exchange_n(&exec_table.entries[i].pid, &expected, pid,
                                            0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
                return i;
            }
        }
    }
    return -1;
}

/* Called by handle_exec after pipe EOF.  Spins briefly — the child is
 * already dead so the handler fires within microseconds.
 * Times out after 5 seconds as a safety net, falling back to waitpid. */
static int exec_table_wait(int slot, pid_t pid, int *status) {
    int waited = 0;
    while (!__atomic_load_n(&exec_table.entries[slot].ready, __ATOMIC_ACQUIRE)) {
        usleep(1000); /* 1ms — child is already dead, won't spin long */
        if (++waited > 5000) { /* 5 second timeout */
            LOGE("exec_table_wait: timeout waiting for PID %d, falling back to waitpid\n", pid);
            __atomic_store_n(&exec_table.entries[slot].pid, 0, __ATOMIC_RELEASE);
            /* Try direct waitpid as fallback */
            if (waitpid(pid, status, 0) == pid) return 0;
            *status = 0;
            return -1;
        }
    }
    *status = __atomic_load_n(&exec_table.entries[slot].status, __ATOMIC_ACQUIRE);
    __atomic_store_n(&exec_table.entries[slot].pid, 0, __ATOMIC_RELEASE); /* free slot */
    return 0;
}

/* =================== CLIENT SESSION =================== */

/* Forward declaration for exec handler */
static void handle_exec(int client_fd, const char *shell_path,
                        const uint8_t *cmd_buf, uint32_t cmd_len);

typedef struct {
    int client_fd;     /* socket to the app */
    int master_fd;     /* PTY master */
    int shell_pid;     /* child shell PID */
    _Atomic int alive; /* RACE-4/BP-3: atomic flag */
    pthread_t reader_tid; /* for join at cleanup */
} client_session;

/**
 * Thread: reads PTY master output → sends DATA messages to client.
 * Runs until PTY EOF or client disconnect.
 */
static void *pty_reader_thread(void *arg) {
    client_session *s = (client_session *)arg;
    uint8_t buf[BUF_SIZE];

    while (atomic_load(&s->alive)) {
        ssize_t n = read(s->master_fd, buf, sizeof(buf));
        if (n <= 0) break;
        if (proto_send(s->client_fd, FTYD_DATA, buf, (uint32_t)n) < 0) break;
    }

    /* Signal client that we're done */
    proto_send(s->client_fd, FTYD_CLOSE, NULL, 0);
    atomic_store(&s->alive, 0);
    return NULL;
}

/**
 * Handle a single client connection.
 * Called from a per-client thread.
 *
 * Protocol:
 *   1. Wait for RESIZE message (contains initial rows/cols)
 *   2. Spawn shell with those dimensions
 *   3. Relay DATA/RESIZE/SIGNAL/CLOSE until session ends
 */
static void handle_client(int client_fd, const char *shell_path) {
    LOGI("Client connected (fd=%d)\n", client_fd);

    /* Step 1: Wait for initial RESIZE to get terminal dimensions */
    uint8_t msg_type;
    uint8_t msg_buf[BUF_SIZE];
    uint32_t msg_len;

    if (proto_recv(client_fd, &msg_type, msg_buf, sizeof(msg_buf), &msg_len) < 0) {
        LOGE("Client fd=%d: failed to read initial message\n", client_fd);
        close(client_fd);
        return;
    }

    if (msg_type != FTYD_RESIZE || msg_len < 4) {
        if (msg_type == FTYD_EXEC && msg_len > 0) {
            /* EXEC mode: run command via pipes, not interactive shell */
            handle_exec(client_fd, shell_path, msg_buf, msg_len);
            return;
        }
        LOGE("Client fd=%d: expected RESIZE or EXEC as first message, got type=%d len=%u\n",
             client_fd, msg_type, msg_len);
        proto_send(client_fd, FTYD_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }

    int rows = ((int)msg_buf[0] << 8) | (int)msg_buf[1];
    int cols = ((int)msg_buf[2] << 8) | (int)msg_buf[3];
    if (rows <= 0) rows = 24;
    if (cols <= 0) cols = 80;

    LOGI("Client fd=%d: initial size %dx%d\n", client_fd, rows, cols);

    /* Step 2: Open PTY and spawn shell */
    int master, slave;
    if (my_openpty(&master, &slave) != 0) {
        LOGE("Client fd=%d: openpty failed: %s\n", client_fd, strerror(errno));
        proto_send(client_fd, FTYD_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }

    set_winsize(master, rows, cols);

    int shell_pid = do_fork_exec(slave, shell_path);
    close(slave); /* parent doesn't need slave */

    if (shell_pid < 0) {
        LOGE("Client fd=%d: fork/exec failed: %s\n", client_fd, strerror(errno));
        proto_send(client_fd, FTYD_CLOSE, NULL, 0);
        close(master);
        close(client_fd);
        return;
    }

    LOGI("Client fd=%d: shell started (pid=%d)\n", client_fd, shell_pid);

    /* Heap-allocate session to avoid use-after-free if reader thread outlives stack frame */
    client_session *session = (client_session *)malloc(sizeof(client_session));
    if (!session) {
        LOGE("Client fd=%d: malloc failed for session\n", client_fd);
        kill(shell_pid, SIGKILL);
        waitpid(shell_pid, NULL, 0);
        close(master);
        proto_send(client_fd, FTYD_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }
    session->client_fd = client_fd;
    session->master_fd = master;
    session->shell_pid = shell_pid;
    atomic_store(&session->alive, 1);

    if (pthread_create(&session->reader_tid, NULL, pty_reader_thread, session) != 0) {
        LOGE("Client fd=%d: failed to create reader thread\n", client_fd);
        kill(shell_pid, SIGKILL);
        waitpid(shell_pid, NULL, 0);
        close(master);
        proto_send(client_fd, FTYD_CLOSE, NULL, 0);
        close(client_fd);
        free(session);
        return;
    }
    /* Join (not detach) so reader_thread_fn doesn't outlive the heap-allocated session */

    /* Step 4: Main loop — read messages from client, dispatch to PTY */
    while (atomic_load(&session->alive)) {
        if (proto_recv(client_fd, &msg_type, msg_buf, sizeof(msg_buf), &msg_len) < 0) {
            break; /* Client disconnected */
        }

        switch (msg_type) {
        case FTYD_DATA:
            if (msg_len > 0) {
                proto_write_all(master, msg_buf, (int)msg_len);
            }
            break;

        case FTYD_RESIZE:
            if (msg_len >= 4) {
                int r = ((int)msg_buf[0] << 8) | (int)msg_buf[1];
                int c = ((int)msg_buf[2] << 8) | (int)msg_buf[3];
                if (r > 0 && c > 0) {
                    set_winsize(master, r, c);
                    /* Send SIGWINCH to shell's process group */
                    kill(-shell_pid, SIGWINCH);
                }
            }
            break;

        case FTYD_SIGNAL:
            if (msg_len >= 1) {
                int sig = (int)msg_buf[0];
                /* Whitelist signals — reject arbitrary signal injection */
                if (ftyd_signal_allowed(sig)) {
                    kill(-shell_pid, sig);
                } else {
                    LOGD("Client fd=%d: rejected signal %d\n", client_fd, sig);
                }
            }
            break;

        case FTYD_CLOSE:
            goto cleanup;

        default:
            LOGD("Client fd=%d: unknown message type %d\n", client_fd, msg_type);
            break;
        }
    }

cleanup:
    atomic_store(&session->alive, 0);

    /* Kill the shell first — this causes master_fd to get EOF,
     * which unblocks the reader thread's read(master_fd). */
    if (shell_pid > 0) {
        int status;
        kill(-shell_pid, SIGHUP);
        usleep(100000);
        if (waitpid(shell_pid, &status, WNOHANG) == 0) {
            kill(-shell_pid, SIGTERM);
            usleep(100000);
            if (waitpid(shell_pid, &status, WNOHANG) == 0) {
                kill(-shell_pid, SIGKILL);
                waitpid(shell_pid, &status, 0);
            }
        }
    }

    /* Close master fd to ensure reader thread gets EOF if shell kill
     * didn't fully unblock it (e.g., orphaned grandchildren). */
    close(master);

    /* Join reader thread — it will exit because master_fd is now closed → read() returns -1.
     * Must join (not detach) so we don't free session while the thread still runs. */
    pthread_join(session->reader_tid, NULL);

    close(client_fd);
    LOGI("Client fd=%d: session ended (shell pid=%d)\n", client_fd, shell_pid);

    free(session);
}

/**
 * Context for the exec client-reader thread.
 * Reads SIGNAL/CLOSE messages from the client while handle_exec()
 * reads stdout from the child process.
 */
typedef struct {
    int client_fd;
    pid_t child_pid;
    _Atomic int running; /* 1 while main thread is still in stdout loop */
} exec_client_reader_ctx;

/**
 * Thread: reads protocol messages from client_fd during exec.
 * Handles SIGNAL (forwarded to child) and CLOSE (kills child).
 * Exits when client disconnects, CLOSE received, or running flag cleared.
 */
static void *exec_client_reader_thread(void *arg) {
    exec_client_reader_ctx *ctx = (exec_client_reader_ctx *)arg;
    uint8_t msg_type;
    uint8_t msg_buf[64]; /* only need small buffer for SIGNAL (1 byte) */
    uint32_t msg_len;

    while (atomic_load(&ctx->running)) {
        if (proto_recv(ctx->client_fd, &msg_type, msg_buf, sizeof(msg_buf), &msg_len) < 0) {
            /* Client disconnected or read error */
            break;
        }

        switch (msg_type) {
        case FTYD_SIGNAL:
            if (msg_len >= 1) {
                int sig = (int)msg_buf[0];
                if (ftyd_signal_allowed(sig)) {
                    LOGD("EXEC client fd=%d: sending signal %d to pid %d\n",
                         ctx->client_fd, sig, ctx->child_pid);
                    kill(ctx->child_pid, sig);
                } else {
                    LOGD("EXEC client fd=%d: rejected signal %d\n",
                         ctx->client_fd, sig);
                }
            }
            break;

        case FTYD_CLOSE:
            LOGD("EXEC client fd=%d: CLOSE received, killing child %d\n",
                 ctx->client_fd, ctx->child_pid);
            kill(ctx->child_pid, SIGTERM);
            usleep(50000);
            kill(ctx->child_pid, SIGKILL);
            goto done;

        default:
            LOGD("EXEC client fd=%d: ignoring message type %d\n",
                 ctx->client_fd, msg_type);
            break;
        }
    }

done:
    return NULL;
}

/**
 * Handle EXEC mode: fork a command via pipes (no PTY), stream stdout+stderr
 * as DATA messages, send EXIT_CODE when process exits, then CLOSE.
 *
 * No echo, no prompt, no line discipline — clean command output.
 */
static void handle_exec(int client_fd, const char *shell_path,
                        const uint8_t *cmd_buf, uint32_t cmd_len) {
    /* Parse payload: "shell\0command" or just "command" (backward compat).
     * If a NUL byte is found, everything before it is the shell path,
     * everything after is the command.  Empty shell = use daemon default. */
    const char *exec_shell = shell_path;
    const uint8_t *cmd_start = cmd_buf;
    uint32_t cmd_actual_len = cmd_len;
    char client_shell[256];

    const uint8_t *nul = (const uint8_t *)memchr(cmd_buf, '\0', cmd_len);
    if (nul != NULL && nul < cmd_buf + cmd_len) {
        /* Found separator: payload is shell\0command */
        uint32_t shell_len = (uint32_t)(nul - cmd_buf);
        if (shell_len > 0) {
            /* Client specified a shell — use it (it's already null-terminated at nul) */
            if (shell_len < sizeof(client_shell)) {
                memcpy(client_shell, cmd_buf, shell_len);
                client_shell[shell_len] = '\0';
                exec_shell = client_shell;
            }
            /* else: shell path too long, fall back to daemon default */
        }
        /* Command starts after the NUL separator */
        cmd_start = nul + 1;
        cmd_actual_len = cmd_len - shell_len - 1;
    }

    /* Null-terminate the command string */
    char *command = (char *)malloc(cmd_actual_len + 1);
    if (!command) {
        LOGE("Client fd=%d: malloc failed for exec command\n", client_fd);
        proto_send(client_fd, FTYD_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }
    memcpy(command, cmd_start, cmd_actual_len);
    command[cmd_actual_len] = '\0';

    LOGI("Client fd=%d: EXEC shell='%s' cmd='%s'\n", client_fd, exec_shell, command);

    /* Create pipes: stdout_pipe for stdout+stderr, stdin_pipe for stdin */
    int stdout_pipe[2]; /* [0]=read, [1]=write */
    int stdin_pipe[2];  /* [0]=read, [1]=write */

    if (pipe(stdout_pipe) < 0) {
        LOGE("Client fd=%d: pipe(stdout) failed: %s\n", client_fd, strerror(errno));
        free(command);
        proto_send(client_fd, FTYD_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }
    if (pipe(stdin_pipe) < 0) {
        LOGE("Client fd=%d: pipe(stdin) failed: %s\n", client_fd, strerror(errno));
        free(command);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        proto_send(client_fd, FTYD_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }

    /* Block SIGCHLD around fork+register to prevent the handler from
     * reaping the child before we register its PID in the exec table. */
    sigset_t chld_set, old_set;
    sigemptyset(&chld_set);
    sigaddset(&chld_set, SIGCHLD);
    pthread_sigmask(SIG_BLOCK, &chld_set, &old_set);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("Client fd=%d: fork() failed: %s\n", client_fd, strerror(errno));
        pthread_sigmask(SIG_SETMASK, &old_set, NULL);
        free(command);
        close(stdout_pipe[0]); close(stdout_pipe[1]);
        close(stdin_pipe[0]); close(stdin_pipe[1]);
        proto_send(client_fd, FTYD_CLOSE, NULL, 0);
        close(client_fd);
        return;
    }

    if (pid == 0) {
        /* Child: wire up pipes and exec */
        close(stdout_pipe[0]); /* parent reads this end */
        close(stdin_pipe[1]);  /* parent writes this end */

        dup2(stdin_pipe[0], 0);   /* stdin from pipe */
        dup2(stdout_pipe[1], 1);  /* stdout to pipe */
        dup2(stdout_pipe[1], 2);  /* stderr to same pipe */
        close(stdin_pipe[0]);
        close(stdout_pipe[1]);

        /* Close all FDs > 2 */
        int maxfd = (int)sysconf(_SC_OPEN_MAX);
        if (maxfd < 0) maxfd = 256;
        for (int fd = 3; fd < maxfd; fd++) close(fd);

        /* Set environment */
        setenv("PATH", "/product/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:/system/bin/applets", 1);
        setenv("HOME", "/data/local/tmp", 0);

        /* Use sh -c to support pipes, redirects, etc. */
        execlp(exec_shell, exec_shell, "-c", command, (char *)NULL);
        _exit(127);
    }

    /* Parent: register PID in exec table BEFORE the child can exit,
     * so reap_children() saves its status instead of discarding it.
     * SIGCHLD is blocked here — unblock after registration. */
    int exec_slot = exec_table_register(pid);
    pthread_sigmask(SIG_SETMASK, &old_set, NULL);

    free(command);
    close(stdout_pipe[1]); /* we read from stdout_pipe[0] */
    close(stdin_pipe[0]);  /* we write to stdin_pipe[1] */

    /* Spawn client-reader thread to handle SIGNAL/CLOSE from client
     * while we read stdout from the child process. */
    exec_client_reader_ctx reader_ctx;
    reader_ctx.client_fd = client_fd;
    reader_ctx.child_pid = pid;
    atomic_store(&reader_ctx.running, 1);

    pthread_t reader_tid;
    int reader_started = 0;
    if (pthread_create(&reader_tid, NULL, exec_client_reader_thread, &reader_ctx) == 0) {
        reader_started = 1;
    } else {
        LOGE("Client fd=%d: failed to create exec client reader thread\n", client_fd);
    }

    /* Read stdout in this thread, relay to client */
    uint8_t buf[BUF_SIZE];
    ssize_t n;
    while ((n = read(stdout_pipe[0], buf, sizeof(buf))) > 0) {
        if (proto_send(client_fd, FTYD_DATA, buf, (uint32_t)n) < 0) {
            /* Client disconnected — kill the child to avoid leaking processes */
            kill(pid, SIGTERM);
            usleep(50000);
            kill(pid, SIGKILL);
            break;
        }
    }

    /* stdout EOF — child's stdout closed (process exiting or exited) */
    close(stdout_pipe[0]);
    close(stdin_pipe[1]); /* close stdin to child in case it's still running */

    /* Stop the client reader thread.  Shutting down client_fd's read side
     * unblocks proto_recv in the reader thread. */
    atomic_store(&reader_ctx.running, 0);
    shutdown(client_fd, SHUT_RD);
    if (reader_started) {
        pthread_join(reader_tid, NULL);
    }

    /* Get exit code — either from exec table (if handler reaped) or waitpid */
    int status = 0;
    int exit_code;
    if (exec_slot >= 0) {
        /* Wait for reap_children() to store the status.  The child is
         * already dead (pipe EOF), so this returns almost immediately. */
        exec_table_wait(exec_slot, pid, &status);
        if (WIFEXITED(status)) {
            exit_code = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            exit_code = 128 + WTERMSIG(status);
        } else {
            exit_code = -1;
        }
    } else {
        /* Table full — fallback to direct waitpid (racy but unlikely) */
        int wait_ret = waitpid(pid, &status, 0);
        if (wait_ret == pid) {
            if (WIFEXITED(status)) {
                exit_code = WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                exit_code = 128 + WTERMSIG(status);
            } else {
                exit_code = -1;
            }
        } else {
            exit_code = -1;
        }
    }

    LOGI("Client fd=%d: EXEC finished (exit_code=%d)\n", client_fd, exit_code);

    /* Send EXIT_CODE message: 4-byte int32 BE */
    uint8_t ec[4];
    ec[0] = (uint8_t)((uint32_t)exit_code >> 24);
    ec[1] = (uint8_t)((uint32_t)exit_code >> 16);
    ec[2] = (uint8_t)((uint32_t)exit_code >> 8);
    ec[3] = (uint8_t)((uint32_t)exit_code);
    proto_send(client_fd, FTYD_EXIT_CODE, ec, 4);

    /* Send CLOSE */
    proto_send(client_fd, FTYD_CLOSE, NULL, 0);
    close(client_fd);
}

typedef struct {
    int client_fd;
    char shell_path[256];
} client_thread_args;

static void *client_thread_fn(void *arg) {
    client_thread_args *a = (client_thread_args *)arg;
    handle_client(a->client_fd, a->shell_path);
    free(a);
    return NULL;
}

/* =================== SIGNAL HANDLING =================== */

static volatile sig_atomic_t g_running = 1;
static volatile sig_atomic_t g_reload  = 0;

static void sig_handler(int sig) {
    (void)sig;
    g_running = 0;
}

static void sighup_handler(int sig) {
    (void)sig;
    g_reload = 1;
}

/* =================== DAEMONIZE =================== */

static void daemonize(void) {
    pid_t pid = fork();
    if (pid < 0) { perror("fork"); exit(1); }
    if (pid > 0) _exit(0); /* Parent exits */

    /* Child becomes session leader */
    setsid();

    /* Fork again to prevent terminal re-acquisition */
    pid = fork();
    if (pid < 0) { perror("fork"); exit(1); }
    if (pid > 0) _exit(0);

    /* Redirect stdio to /dev/null */
    int devnull = open("/dev/null", O_RDWR);
    if (devnull >= 0) {
        dup2(devnull, 0);
        dup2(devnull, 1);
        dup2(devnull, 2);
        if (devnull > 2) close(devnull);
    }

    /* Set working directory */
    chdir("/");

    /* Restrict file creation mask — not world-writable */
    umask(022);
}

static void write_pid_file(const char *path) {
    FILE *f = fopen(path, "w");
    if (f) {
        fprintf(f, "%d\n", getpid());
        fclose(f);
    }
}

/* =================== SOCKET SETUP =================== */

static int create_listen_socket(const char *socket_path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        LOGE("socket() failed: %s\n", strerror(errno));
        return -1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;

    int is_abstract = (socket_path[0] == '@');

    if (is_abstract) {
        addr.sun_path[0] = '\0';
        strncpy(addr.sun_path + 1, socket_path + 1, sizeof(addr.sun_path) - 2);
    } else {
        /* Remove existing socket file */
        unlink(socket_path);
        strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);
    }

    socklen_t addrlen = (socklen_t)(offsetof(struct sockaddr_un, sun_path)
                      + (is_abstract ? strlen(socket_path) : strlen(socket_path) + 1));

    if (bind(fd, (struct sockaddr *)&addr, addrlen) < 0) {
        LOGE("bind(%s) failed: %s\n", socket_path, strerror(errno));
        close(fd);
        return -1;
    }

    if (!is_abstract) {
        /* Allow any app to connect (the socket runs as root) */
        chmod(socket_path, 0666);
    }

    if (listen(fd, MAX_CLIENTS) < 0) {
        LOGE("listen() failed: %s\n", strerror(errno));
        close(fd);
        if (!is_abstract) unlink(socket_path);
        return -1;
    }

    return fd;
}

/* =================== CHILD REAPER =================== */

static void reap_children(int sig) {
    (void)sig;
    int   status;
    pid_t pid;

    while ((pid = waitpid(-1, &status, WNOHANG)) > 0) {
        /* Check if this is a tracked exec PID — save its status instead
         * of discarding it.  Lock-free: only atomic reads/writes. */
        for (int i = 0; i < MAX_EXEC_PIDS; i++) {
            if (__atomic_load_n(&exec_table.entries[i].pid, __ATOMIC_ACQUIRE) == pid) {
                __atomic_store_n(&exec_table.entries[i].status, status, __ATOMIC_RELAXED);
                __atomic_store_n(&exec_table.entries[i].ready, 1, __ATOMIC_RELEASE);
                break;
            }
        }
        /* If not an exec PID, it's an interactive session child — already
         * reaped, nothing more to do (same as before). */
    }
}

/* =================== MAIN =================== */

static void usage(const char *prog) {
    fprintf(stderr,
        "Usage: %s [options]\n"
        "\n"
        "FuryTerminal daemon — root PTY broker for Android.\n"
        "\n"
        "Options:\n"
        "  -s PATH    Socket path (default: %s)\n"
        "             Prefix with '@' for abstract socket\n"
        "  -S SHELL   Shell binary (default: %s)\n"
        "  -f         Run in foreground (don't daemonize)\n"
        "  -p FILE    PID file path (default: %s)\n"
        "  -a         Enable UID authentication (default: disabled)\n"
        "  -c FILE    Auth config file (default: %s)\n"
        "             Requires -a. Send SIGHUP to reload.\n"
        "  -h         Show this help\n"
        "\n"
        "Auth config format (one per line, '#' comments):\n"
        "  allow com.example.myapp      # Allow by package name\n"
        "  allow_uid 10245              # Allow by raw UID\n"
        "  UIDs 0 (root) and 2000 (shell) are always allowed.\n"
        "\n"
        "Examples:\n"
        "  %s                          # Daemonize, no auth\n"
        "  %s -f                       # Foreground mode\n"
        "  %s -a                       # Enable auth (root+shell only)\n"
        "  %s -a -c /data/local/tmp/ftyd.conf  # Auth with config\n"
        "  %s -s @ftyd              # Abstract socket\n"
        "  %s -s /data/local/tmp/td.sock -S /system/bin/sh\n"
        "\n",
        prog, DEFAULT_SOCKET_PATH, DEFAULT_SHELL, PID_FILE, AUTH_CONF_PATH,
        prog, prog, prog, prog, prog, prog);
}

int main(int argc, char *argv[]) {
    const char *socket_path = DEFAULT_SOCKET_PATH;
    const char *shell_path  = DEFAULT_SHELL;
    const char *pid_file    = PID_FILE;
    const char *auth_conf   = AUTH_CONF_PATH;
    int foreground = 0;

    int opt;
    while ((opt = getopt(argc, argv, "s:S:fp:ac:h")) != -1) {
        switch (opt) {
        case 's': socket_path = optarg; break;
        case 'S': shell_path  = optarg; break;
        case 'f': foreground  = 1;      break;
        case 'p': pid_file    = optarg; break;
        case 'a': g_auth.enabled = 1;   break;
        case 'c': auth_conf   = optarg; break;
        case 'h': usage(argv[0]); return 0;
        default:  usage(argv[0]); return 1;
        }
    }

    /* Verify shell exists (only for explicit paths; short names are PATH-searched) */
    if (strchr(shell_path, '/') != NULL && access(shell_path, X_OK) != 0) {
        LOGE("Shell not found or not executable: %s\n", shell_path);
        return 1;
    }

    /* Set up signal handlers */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));

    sa.sa_handler = sig_handler;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT,  &sa, NULL);

    sa.sa_handler = reap_children;
    sa.sa_flags = SA_RESTART | SA_NOCLDSTOP;
    sigaction(SIGCHLD, &sa, NULL);

    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sighup_handler;
    sa.sa_flags = SA_RESTART;
    sigaction(SIGHUP, &sa, NULL);

    signal(SIGPIPE, SIG_IGN);

    /* Load auth config if auth enabled */
    if (g_auth.enabled) {
        auth_load_config(auth_conf);
    }

    /* Daemonize unless -f */
    if (!foreground) {
        daemonize();
    }

    /* Create listening socket */
    int listen_fd = create_listen_socket(socket_path);
    if (listen_fd < 0) return 1;

    /* Write PID file */
    write_pid_file(pid_file);

    LOGI("ftyd started: socket=%s shell=%s pid=%d auth=%s\n",
         socket_path, shell_path, getpid(),
         g_auth.enabled ? "on" : "off");

    /* Accept loop */
    while (g_running) {
        struct sockaddr_un client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(listen_fd, (struct sockaddr *)&client_addr, &client_len);

        if (client_fd < 0) {
            if (errno == EINTR) {
                /* Check for SIGHUP reload request */
                if (g_reload) {
                    g_reload = 0;
                    if (g_auth.enabled) {
                        LOGI("SIGHUP received, reloading auth config\n");
                        auth_load_config(auth_conf);
                    }
                }
                continue; /* signal interrupted accept */
            }
            LOGE("accept() failed: %s\n", strerror(errno));
            continue;
        }

        /* Check for SIGHUP reload (in case accept succeeded before we checked) */
        if (g_reload) {
            g_reload = 0;
            if (g_auth.enabled) {
                LOGI("SIGHUP received, reloading auth config\n");
                auth_load_config(auth_conf);
            }
        }

        /* Auth check: verify client UID */
        if (auth_check_peer(client_fd) != 0) {
            close(client_fd);
            continue;
        }

        /* Spawn a thread per client */
        client_thread_args *args = (client_thread_args *)malloc(sizeof(client_thread_args));
        if (!args) {
            LOGE("malloc failed for client thread args\n");
            close(client_fd);
            continue;
        }
        args->client_fd = client_fd;
        strncpy(args->shell_path, shell_path, sizeof(args->shell_path) - 1);
        args->shell_path[sizeof(args->shell_path) - 1] = '\0';

        pthread_t tid;
        if (pthread_create(&tid, NULL, client_thread_fn, args) != 0) {
            LOGE("pthread_create failed: %s\n", strerror(errno));
            close(client_fd);
            free(args);
            continue;
        }
        pthread_detach(tid);
    }

    /* Cleanup */
    LOGI("ftyd shutting down\n");
    close(listen_fd);

    if (socket_path[0] != '@') {
        unlink(socket_path);
    }
    unlink(pid_file);

    return 0;
}
