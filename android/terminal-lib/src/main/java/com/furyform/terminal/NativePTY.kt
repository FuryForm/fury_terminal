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

    /** Start a new PTY session. Returns session ID >= 0, or -1 on failure. */
    external fun nativeStartPTY(rows: Int, cols: Int): Int

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
     * Connect to a ftyd daemon via Unix socket and start a session.
     * The daemon spawns the shell as its own user (typically root).
     *
     * @param socketPath path to the daemon socket. Prefix with '@' for abstract socket.
     * @return session ID >= 0, or -1 on failure (daemon not running or refused connection).
     */
    external fun nativeStartDaemonSession(socketPath: String, rows: Int, cols: Int): Int
}
