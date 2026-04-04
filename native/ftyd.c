/**
 * FuryTerminal Daemon (ftyd) — Root PTY broker for Android
 *
 * Listens on a Unix socket and spawns a root shell for each connecting client.
 * Protocol matches the client in libterm.c:
 *   Message format: [type:uint8][length:uint32 BE][data:length]
 *   Types: DATA=0x01, RESIZE=0x02, SIGNAL=0x03, CLOSE=0x04
 *
 * Client flow:
 *   1. Connect to socket
 *   2. Send RESIZE (4 bytes: rows BE16 + cols BE16) — daemon spawns shell
 *   3. Exchange DATA messages
 *   4. Send CLOSE or disconnect to terminate
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

/* =================== CLIENT SESSION =================== */

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
 * Called from the accept loop (or a thread).
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
        LOGE("Client fd=%d: expected RESIZE as first message, got type=%d len=%u\n",
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

    /* Step 3: Heap-allocate session (BUG-1: avoid use-after-free on stack) */
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
    /* BUG-1: Do NOT detach — we join at cleanup to avoid use-after-free */

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
                /* SEC-2: Whitelist signals — reject arbitrary signal injection */
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

    /* BUG-1: Join (not detach) so we don't free session while thread runs.
     * Reader will exit because master_fd is now closed → read() returns -1. */
    pthread_join(session->reader_tid, NULL);

    close(client_fd);
    LOGI("Client fd=%d: session ended (shell pid=%d)\n", client_fd, shell_pid);

    free(session);
}

/* Thread wrapper for handle_client */
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

static volatile int g_running = 1;

static void sig_handler(int sig) {
    (void)sig;
    g_running = 0;
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

    /* SEC-5: Restrict file creation mask (was umask(0) — too permissive) */
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
    while (waitpid(-1, NULL, WNOHANG) > 0) { /* reap all */ }
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
        "  -h         Show this help\n"
        "\n"
        "Examples:\n"
        "  %s                          # Daemonize with defaults\n"
        "  %s -f                       # Foreground mode\n"
        "  %s -s @ftyd              # Abstract socket\n"
        "  %s -s /data/local/tmp/td.sock -S /system/bin/sh\n"
        "\n",
        prog, DEFAULT_SOCKET_PATH, DEFAULT_SHELL, PID_FILE,
        prog, prog, prog, prog);
}

int main(int argc, char *argv[]) {
    const char *socket_path = DEFAULT_SOCKET_PATH;
    const char *shell_path  = DEFAULT_SHELL;
    const char *pid_file    = PID_FILE;
    int foreground = 0;

    int opt;
    while ((opt = getopt(argc, argv, "s:S:fp:h")) != -1) {
        switch (opt) {
        case 's': socket_path = optarg; break;
        case 'S': shell_path  = optarg; break;
        case 'f': foreground  = 1;      break;
        case 'p': pid_file    = optarg; break;
        case 'h': usage(argv[0]); return 0;
        default:  usage(argv[0]); return 1;
        }
    }

    /* Verify shell exists */
    if (access(shell_path, X_OK) != 0) {
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

    signal(SIGPIPE, SIG_IGN);

    /* Daemonize unless -f */
    if (!foreground) {
        daemonize();
    }

    /* Create listening socket */
    int listen_fd = create_listen_socket(socket_path);
    if (listen_fd < 0) return 1;

    /* Write PID file */
    write_pid_file(pid_file);

    LOGI("ftyd started: socket=%s shell=%s pid=%d\n",
         socket_path, shell_path, getpid());

    /* Accept loop */
    while (g_running) {
        struct sockaddr_un client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(listen_fd, (struct sockaddr *)&client_addr, &client_len);

        if (client_fd < 0) {
            if (errno == EINTR) continue; /* signal interrupted accept */
            LOGE("accept() failed: %s\n", strerror(errno));
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
