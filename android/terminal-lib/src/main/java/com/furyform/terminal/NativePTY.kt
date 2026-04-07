package com.furyform.terminal

/**
 * JNI bridge to the native PTY library (libterm.so).
 *
 * This class is internal to the library. Use [TerminalSession] for the public API.
 */
internal object NativePTY {

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("term")
            loaded = true
        }
    }

    /**
     * Start a new PTY session. Returns session ID >= 0, or -1 on failure.
     *
     * @param rows terminal height in rows
     * @param cols terminal width in cols
     * @param shell the shell binary to launch (e.g. "/system/bin/sh")
     * @param envVars array of "KEY=VALUE" strings for child environment (null for defaults)
     * @param cwd working directory for the child process (null for default)
     */
    external fun nativeStartPTY(rows: Int, cols: Int, shell: String, envVars: Array<String>?, cwd: String?): Int

    /** Read bytes from PTY. Blocks until data available. Returns null on EOF/error. */
    external fun nativeRead(id: Int): ByteArray?

    /** Write bytes to PTY. Returns bytes written, or -1 on error. */
    external fun nativeWrite(id: Int, data: ByteArray): Int

    /** Resize PTY terminal. Returns 0 on success. */
    external fun nativeResize(id: Int, rows: Int, cols: Int): Int

    /** Close PTY session and kill child process. */
    external fun nativeClose(id: Int)

    /** Check if PTY child process is alive. */
    external fun nativeIsAlive(id: Int): Boolean

    /** Send a POSIX signal to the PTY child process. */
    external fun nativeSendSignal(id: Int, signum: Int)

    /**
     * Connect to a ftyd daemon via Unix socket and start an interactive session.
     * The daemon spawns the shell as its own user (typically root).
     *
     * @param socketPath path to the daemon socket. Prefix with '@' for abstract socket.
     * @param rows terminal height in rows
     * @param cols terminal width in cols
     * @param shell the shell binary to launch (e.g. "/system/bin/sh")
     * @param envVars array of "KEY=VALUE" strings for child environment (null for defaults)
     * @param cwd working directory for the child process (null for default)
     * @return session ID >= 0, or -1 on failure (daemon not running or refused connection).
     */
    external fun nativeStartDaemonSession(socketPath: String, rows: Int, cols: Int, shell: String, envVars: Array<String>?, cwd: String?): Int

    /**
     * Connect to a ftyd daemon and execute a single command.
     * The daemon runs the command via pipes (no PTY) and streams output back.
     * When the command finishes, the daemon sends the exit code and closes.
     *
     * @param socketPath path to the daemon socket. Prefix with '@' for abstract socket.
     * @param command the shell command to execute (passed to sh -c).
     * @param shell the shell binary the daemon should use. Empty string means the daemon's default.
     * @param envVars array of "KEY=VALUE" strings for child environment (null for defaults)
     * @param cwd working directory for the child process (null for default)
     * @return session ID >= 0, or -1 on failure.
     */
    external fun nativeStartExecSession(socketPath: String, command: String, shell: String, envVars: Array<String>?, cwd: String?): Int

    /** Start a local exec session (fork+pipes, no daemon). Returns session ID >= 0, or -1 on failure.
     *
     * @param envVars array of "KEY=VALUE" strings for child environment (null for defaults)
     * @param cwd working directory for the child process (null for default)
     */
    external fun nativeStartLocalExecSession(shell: String, command: String, envVars: Array<String>?, cwd: String?): Int

    /**
     * Get the exit code from a completed exec session.
     * Returns -1 if the session hasn't finished or exit code wasn't received.
     */
    external fun nativeGetExitCode(id: Int): Int
}
