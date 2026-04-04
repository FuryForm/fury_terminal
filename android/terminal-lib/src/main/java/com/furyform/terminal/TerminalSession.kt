package com.furyform.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.Closeable
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.coroutineContext

/**
 * A PTY (pseudo-terminal) session on Android.
 *
 * Creates a native PTY connected to a shell process (/system/bin/sh).
 * Provides read/write access to the terminal and lifecycle management.
 *
 * Usage:
 * ```kotlin
 * val session = TerminalSession.create(rows = 24, cols = 80)
 * session.use {
 *     it.write("ls -la\n")
 *     val output = it.read()  // blocking
 *     println(String(output ?: byteArrayOf()))
 * }
 * ```
 *
 * Or with coroutines:
 * ```kotlin
 * session.output().collect { bytes ->
 *     val text = String(bytes)
 *     // update UI
 * }
 * ```
 *
 * This class is thread-safe. The native layer uses mutex-protected session management.
 */
class TerminalSession private constructor(
    private val id: Int
) : Closeable {

    @Volatile
    private var closed = false
    private val lock = ReentrantReadWriteLock()

    /**
     * Whether the shell process is still running.
     */
    val isAlive: Boolean
        get() {
            if (closed) return false
            return lock.read {
                if (closed) return false
                NativePTY.nativeIsAlive(id)
            }
        }

    /**
     * Whether this session has been closed.
     */
    val isClosed: Boolean
        get() = closed

    /**
     * Read bytes from the terminal output.
     *
     * This call **blocks** until data is available.
     * Returns null on EOF (process exited) or error.
     *
     * For non-blocking usage, use [output] which returns a Flow.
     */
    fun read(): ByteArray? {
        lock.read {
            check(!closed) { "Session is closed" }
            return NativePTY.nativeRead(id)
        }
    }

    /**
     * Write raw bytes to the terminal input.
     *
     * @return number of bytes written, or -1 on error
     */
    fun write(data: ByteArray): Int {
        lock.read {
            check(!closed) { "Session is closed" }
            return NativePTY.nativeWrite(id, data)
        }
    }

    /**
     * Write a string to the terminal input (UTF-8 encoded).
     *
     * @return number of bytes written, or -1 on error
     */
    fun write(text: String): Int {
        return write(text.toByteArray(Charsets.UTF_8))
    }

    /**
     * Resize the terminal window.
     *
     * @return 0 on success, -1 on error
     */
    fun resize(rows: Int, cols: Int): Int {
        lock.read {
            check(!closed) { "Session is closed" }
            return NativePTY.nativeResize(id, rows, cols)
        }
    }

    /**
     * A cold [Flow] that continuously reads terminal output.
     *
     * Emits [ByteArray] chunks as they become available from the PTY.
     * The flow completes when the session is closed or the process exits.
     * Runs on [Dispatchers.IO].
     *
     * ```kotlin
     * session.output().collect { bytes ->
     *     textView.append(String(bytes))
     * }
     * ```
     */
    fun output(): Flow<ByteArray> = flow {
        while (coroutineContext.isActive && !closed) {
            val data = NativePTY.nativeRead(id)
            if (data != null && data.isNotEmpty()) {
                emit(data)
            } else {
                // EOF or error — process likely exited
                break
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Send a POSIX signal to the shell process.
     *
     * Common signals:
     * - `2`  = SIGINT  (Ctrl+C — interrupt current command)
     * - `20` = SIGTSTP (Ctrl+Z — suspend current command)
     * - `15` = SIGTERM (graceful terminate)
     *
     * Example: `session.sendSignal(2)` is equivalent to pressing Ctrl+C.
     */
    fun sendSignal(signum: Int) {
        lock.read {
            check(!closed) { "Session is closed" }
            NativePTY.nativeSendSignal(id, signum)
        }
    }

    /**
     * Close this terminal session.
     *
     * Sends SIGHUP → SIGTERM → SIGKILL to the child process and releases all resources.
     * Safe to call multiple times.
     */
    override fun close() {
        lock.write {
            if (closed) return
            closed = true
            NativePTY.nativeClose(id)
        }
    }

    companion object {
        /**
         * Create a local PTY session. The shell runs as the app's own UID.
         *
         * @param rows terminal height in rows (default 24)
         * @param cols terminal width in cols (default 80)
         */
        fun create(rows: Int = 24, cols: Int = 80): TerminalSession {
            NativePTY.ensureLoaded()
            val id = NativePTY.nativeStartPTY(rows, cols)
            check(id >= 0) { "Failed to start PTY session (native returned $id)" }
            return TerminalSession(id)
        }

        /**
         * Connect to a running ftyd daemon and open a session.
         * The daemon runs the shell under its own privileges (typically root),
         * so no `su` call is needed.
         *
         * The daemon must already be running before calling this. Start it with:
         *   `adb shell /system/xbin/ftyd &`
         * or via a Magisk service script on boot.
         *
         * @param socketPath path to the daemon socket.
         *   Use '@ftyd' for an abstract socket, or a file path like
         *   '/data/local/tmp/ftyd.sock' (default).
         * @param rows terminal height in rows (default 24)
         * @param cols terminal width in cols (default 80)
         * @throws IllegalStateException if the daemon is not running or refused the connection
         */
        fun createDaemon(
            socketPath: String = "@ftyd",
            rows: Int = 24,
            cols: Int = 80
        ): TerminalSession {
            NativePTY.ensureLoaded()
            val id = NativePTY.nativeStartDaemonSession(socketPath, rows, cols)
            check(id >= 0) { "Failed to connect to ftyd at $socketPath — is the daemon running?" }
            return TerminalSession(id)
        }
    }
}
