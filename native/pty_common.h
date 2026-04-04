/**
 * FuryTerminal — PTY common operations
 *
 * Android Bionic-compatible PTY allocation, window sizing, and fork/exec.
 * No dependency on <pty.h> or <utmp.h> (unavailable on Android).
 */

#pragma once

#include <stdlib.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <signal.h>
#include <sys/wait.h>
#include <string.h>
#include <errno.h>

/**
 * Open a PTY master/slave pair using posix_openpt.
 * Android Bionic lacks openpty() — this is the portable alternative.
 *
 * @param amaster  receives the master fd
 * @param aslave   receives the slave fd
 * @return 0 on success, -1 on failure
 */
static int my_openpty(int *amaster, int *aslave) {
    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master == -1) return -1;
    fcntl(master, F_SETFD, FD_CLOEXEC);  /* prevent leaking to children */
    if (grantpt(master) == -1)  { close(master); return -1; }
    if (unlockpt(master) == -1) { close(master); return -1; }
    char slave_name[64];
    if (ptsname_r(master, slave_name, sizeof(slave_name)) != 0) { close(master); return -1; }
    int slave = open(slave_name, O_RDWR | O_NOCTTY);
    if (slave == -1)            { close(master); return -1; }
    *amaster = master;
    *aslave  = slave;
    return 0;
}

/**
 * Set the terminal window size.
 *
 * @param fd    PTY master fd
 * @param rows  terminal height in rows
 * @param cols  terminal width in columns
 * @return 0 on success, -1 on failure
 */
static int set_winsize(int fd, int rows, int cols) {
    struct winsize ws;
    ws.ws_row    = (unsigned short)rows;
    ws.ws_col    = (unsigned short)cols;
    ws.ws_xpixel = 0;
    ws.ws_ypixel = 0;
    return ioctl(fd, TIOCSWINSZ, &ws);
}

/**
 * Fork and exec a shell process connected to the given slave PTY.
 *
 * This is pure C — safe to call from any runtime (Go, Rust, JVM).
 * The child process:
 *   1. Starts a new session (setsid)
 *   2. Acquires the slave PTY as controlling terminal (TIOCSCTTY)
 *   3. Redirects stdin/stdout/stderr to the slave PTY
 *   4. Closes all inherited FDs > 2 to prevent master fd leaks
 *   5. Sets up TERM, HOME, PATH environment
 *   6. Execs the shell
 *   7. Calls _exit(127) on failure (never returns to caller's runtime)
 *
 * @param slave_fd   slave PTY file descriptor
 * @param shell_path path to the shell binary
 * @return child PID on success, -1 on failure
 */
static int do_fork_exec(int slave_fd, const char *shell_path) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        /* Child process — pure C, no runtime dependencies */
        setsid();
        /* Acquire slave PTY as controlling terminal.
         * Without this, /dev/tty doesn't exist and the shell warns
         * "no controlling tty". Control characters (Ctrl+C → SIGINT)
         * also won't work via the line discipline without a ctty. */
        ioctl(slave_fd, TIOCSCTTY, 0);
        dup2(slave_fd, 0);
        dup2(slave_fd, 1);
        dup2(slave_fd, 2);
        if (slave_fd > 2) close(slave_fd);
        /* Close all inherited FDs except stdin/stdout/stderr to prevent
         * leaking master FDs from other sessions into the child. */
        int maxfd = (int)sysconf(_SC_OPEN_MAX);
        if (maxfd < 0) maxfd = 256;
        for (int fd = 3; fd < maxfd; fd++) close(fd);
        setenv("TERM", "xterm-256color", 1);
        setenv("HOME", "/data/local/tmp", 0);
        setenv("PATH", "/product/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin:/system/bin/applets", 1);
        execl(shell_path, shell_path, (char *)NULL);
        _exit(127);
    }
    return (int)pid;
}
