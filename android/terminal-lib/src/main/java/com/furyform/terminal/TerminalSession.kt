package com.furyform.terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.coroutineContext

/**
 * Result of executing a command via [TerminalSession.exec].
 *
 * @property output the full stdout+stderr output of the command
 * @property exitCode the process exit code (0 = success, 127 = command not found,
 *   128+N = killed by signal N, -1 = unknown/not received)
 */
data class ExecResult(
    val output: String,
    val exitCode: Int
) {
    /** Whether the command succeeded (exit code 0). */
    val isSuccess: Boolean get() = exitCode == 0
}

/**
 * A PTY (pseudo-terminal) session on Android.
 *
 * Creates a native PTY connected to a shell process (/system/bin/sh).
 * Provides read/write access to the terminal and lifecycle management.
 *
 * ## Interactive mode (PTY)
 * ```kotlin
 * val session = TerminalSession.create(rows = 24, cols = 80)
 * session.use {
 *     it.write("ls -la\n")
 *     val output = it.read()  // blocking
 *     println(String(output ?: byteArrayOf()))
 * }
 * ```
 *
 * ## Interactive daemon mode (root PTY via ftyd)
 * ```kotlin
 * val session = TerminalSession.createDaemon(socketPath = "@ftyd")
 * session.output().collect { bytes -> println(String(bytes)) }
 * ```
 *
 * ## Exec mode (run command, get result — no PTY, no echo)
 * ```kotlin
 * val result = TerminalSession.exec("ls -la /")
 * println(result.output)    // clean output, no echo
 * println(result.exitCode)  // 0
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

    /** Tracks in-flight [nativeRead] calls so [close] can wait for them to drain. */
    private val activeReaders = AtomicInteger(0)

    /** Cached exit code — set before native close wipes the slot. */
    @Volatile
    private var cachedExitCode: Int? = null

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
     * The exit code of the remote process (exec sessions only).
     *
     * Returns -1 if the session is still running, not an exec session,
     * or the exit code hasn't been received yet.
     * Read all output first (until [read] returns null) before checking this.
     *
     * Safe to call after [close] — the exit code is cached before the native
     * session is freed.
     */
    val exitCode: Int
        get() {
            cachedExitCode?.let { return it }
            lock.read {
                if (closed) return cachedExitCode ?: -1
                return NativePTY.nativeGetExitCode(id)
            }
        }

    /**
     * Read bytes from the terminal output.
     *
     * This call **blocks** until data is available.
     * Returns null on EOF (process exited) or error.
     *
     * For non-blocking usage, use [output] which returns a Flow.
     */
    fun read(): ByteArray? {
        check(!closed) { "Session is closed" }
        activeReaders.incrementAndGet()
        try {
            if (closed) return null
            return NativePTY.nativeRead(id)
        } finally {
            activeReaders.decrementAndGet()
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
            activeReaders.incrementAndGet()
            val data = try {
                if (closed) null
                else NativePTY.nativeRead(id)
            } finally {
                activeReaders.decrementAndGet()
            }
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
     * Safe to call multiple times. Caches exit code before freeing native resources.
     */
    override fun close() {
        if (closed) return
        lock.write {
            if (closed) return
            // Cache exit code before native close wipes the session slot
            if (cachedExitCode == null) {
                val code = NativePTY.nativeGetExitCode(id)
                if (code >= 0) cachedExitCode = code
            }
            closed = true
            // Kills child + closes fd, which unblocks any in-flight nativeRead()
            NativePTY.nativeClose(id)
        }
        // Wait for in-flight readers to see the EOF and exit.
        // nativeClose already made nativeRead return, so this should be near-instant.
        var spins = 0
        while (activeReaders.get() > 0 && spins < 100) {
            Thread.sleep(10)
            spins++
        }
    }

    /**
     * Read all remaining output and return it as a string.
     * Blocks until the process exits (EOF).
     * Useful for exec sessions where you want the complete output at once.
     *
     * @return all output concatenated as a UTF-8 string
     */
    fun readAll(): String {
        val sb = StringBuilder()
        while (true) {
            val chunk = read() ?: break
            sb.append(String(chunk, Charsets.UTF_8))
        }
        return sb.toString()
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
         * Connect to a running ftyd daemon and open an interactive session.
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

        /**
         * Execute a single command via the ftyd daemon and return the complete result.
         *
         * Uses pipes (not a PTY) — no echo, no prompt, no terminal noise.
         * The command runs as the daemon's user (typically root).
         * Blocks until the command finishes.
         *
         * ```kotlin
         * val result = TerminalSession.exec("ls -la /")
         * if (result.isSuccess) {
         *     println(result.output)
         * } else {
         *     println("Failed with exit code ${result.exitCode}")
         * }
         * ```
         *
         * For streaming output as it arrives, use [execSession] instead.
         *
         * @param command the shell command to execute (passed to `sh -c`)
         * @param socketPath path to the daemon socket (default: "@ftyd")
         * @return [ExecResult] with the complete output and exit code
         * @throws IllegalStateException if the daemon is not running or refused the connection
         */
        fun exec(
            command: String,
            socketPath: String = "@ftyd"
        ): ExecResult {
            val session = execSession(command, socketPath)
            session.use {
                val output = it.readAll()
                val exitCode = it.exitCode
                return ExecResult(output, exitCode)
            }
        }

        /**
         * Connect to a running ftyd daemon and start an exec session.
         *
         * Unlike [exec], this returns a [TerminalSession] that you can read from
         * incrementally (e.g., for live streaming of long-running commands).
         * Uses pipes (not a PTY) — no echo, no prompt.
         *
         * Read output via [read]/[output], then check [exitCode] after EOF.
         *
         * ```kotlin
         * val session = TerminalSession.execSession("ping -c 5 google.com")
         * session.use {
         *     it.output().collect { bytes ->
         *         print(String(bytes))  // live streaming
         *     }
         *     println("Exit code: ${it.exitCode}")
         * }
         * ```
         *
         * @param command the shell command to execute (passed to `sh -c`)
         * @param socketPath path to the daemon socket (default: "@ftyd")
         * @throws IllegalStateException if the daemon is not running or refused the connection
         */
        fun execSession(
            command: String,
            socketPath: String = "@ftyd"
        ): TerminalSession {
            NativePTY.ensureLoaded()
            val id = NativePTY.nativeStartExecSession(socketPath, command)
            check(id >= 0) { "Failed to connect to ftyd at $socketPath for exec — is the daemon running?" }
            return TerminalSession(id)
        }
    }
}
