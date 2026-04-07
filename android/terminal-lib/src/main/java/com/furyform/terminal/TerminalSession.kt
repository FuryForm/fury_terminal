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
 * // Local (no root, no daemon needed)
 * val result = TerminalSession.exec("ls -la /")
 * println(result.output)    // clean output, no echo
 * println(result.exitCode)  // 0
 *
 * // Via daemon (root)
 * val result = TerminalSession.exec("ls -la /data", socketPath = "@ftyd")
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
        lock.read {
            check(!closed) { "Session is closed" }
            activeReaders.incrementAndGet()
        }
        try {
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
            lock.read {
                if (closed) return@flow
                activeReaders.incrementAndGet()
            }
            val data = try {
                NativePTY.nativeRead(id)
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
     * Use the signal constants defined in [TerminalSession.Companion]:
     * - [SIGINT]  — Ctrl+C (interrupt current command)
     * - [SIGTSTP] — Ctrl+Z (suspend current command)
     * - [SIGTERM] — graceful terminate
     * - [SIGKILL] — force kill (cannot be caught)
     *
     * Example: `session.sendSignal(TerminalSession.SIGINT)`
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
            // Cache exit code before native close wipes the session slot.
            // Only attempt if the process already exited — nativeGetExitCode
            // uses blocking waitpid for local exec and would hang otherwise.
            if (cachedExitCode == null && !NativePTY.nativeIsAlive(id)) {
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
        // =================== Signal Constants ===================

        /** Hangup — sent when terminal is closed. */
        const val SIGHUP = 1
        /** Interrupt — equivalent to Ctrl+C. */
        const val SIGINT = 2
        /** Quit — equivalent to Ctrl+\. Produces core dump. */
        const val SIGQUIT = 3
        /** Kill — cannot be caught or ignored. Force-terminates the process. */
        const val SIGKILL = 9
        /** Terminate — graceful termination request. */
        const val SIGTERM = 15
        /** Continue — resume a stopped process. */
        const val SIGCONT = 18
        /** Stop — equivalent to Ctrl+Z. Suspends the process. */
        const val SIGTSTP = 20
        /** Window change — notify process of terminal resize. */
        const val SIGWINCH = 28
        /**
         * Create a local PTY session. The shell runs as the app's own UID.
         *
         * @param rows terminal height in rows (default 24)
         * @param cols terminal width in cols (default 80)
         * @param shell the shell binary to launch (default: "/system/bin/sh")
         */
        @JvmStatic
        @JvmOverloads
        fun create(rows: Int = 24, cols: Int = 80, shell: String = "/system/bin/sh"): TerminalSession {
            NativePTY.ensureLoaded()
            val id = NativePTY.nativeStartPTY(rows, cols, shell)
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
         * @param shell the shell binary the daemon should launch (default: "/system/bin/sh")
         * @throws IllegalStateException if the daemon is not running or refused the connection
         */
        @JvmStatic
        @JvmOverloads
        fun createDaemon(
            socketPath: String = "@ftyd",
            rows: Int = 24,
            cols: Int = 80,
            shell: String = "/system/bin/sh"
        ): TerminalSession {
            NativePTY.ensureLoaded()
            val id = NativePTY.nativeStartDaemonSession(socketPath, rows, cols, shell)
            check(id >= 0) { "Failed to connect to ftyd at $socketPath — is the daemon running?" }
            return TerminalSession(id)
        }

        /**
         * Execute a single command and return the complete result.
         *
         * Uses pipes (not a PTY) — no echo, no prompt, no terminal noise.
         * Blocks until the command finishes.
         *
         * - If [socketPath] is `null` (default): runs locally as the app's own UID.
         * - If [socketPath] is non-null (e.g. `"@ftyd"`): runs via daemon as root.
         *
         * ```kotlin
         * // Local (no root)
         * val result = TerminalSession.exec("ls -la /")
         *
         * // Via daemon (root)
         * val result = TerminalSession.exec("ls -la /data", socketPath = "@ftyd")
         * ```
         *
         * For streaming output as it arrives, use [execSession] instead.
         *
         * @param command the shell command to execute (passed to `shell -c`)
         * @param socketPath daemon socket path, or null for local exec (default: null)
         * @param shell the shell binary to use (default: "/system/bin/sh")
         * @return [ExecResult] with the complete output and exit code
         * @throws IllegalStateException if local fork fails or daemon is not running
         */
        @JvmStatic
        @JvmOverloads
        fun exec(
            command: String,
            socketPath: String? = null,
            shell: String = "/system/bin/sh"
        ): ExecResult {
            val session = execSession(command, socketPath, shell)
            session.use {
                val output = it.readAll()
                val exitCode = it.exitCode
                return ExecResult(output, exitCode)
            }
        }

        /**
         * Start an exec session for streaming output.
         *
         * Unlike [exec], this returns a [TerminalSession] that you can read from
         * incrementally (e.g., for live streaming of long-running commands).
         * Uses pipes (not a PTY) — no echo, no prompt.
         *
         * - If [socketPath] is `null` (default): runs locally as the app's own UID.
         * - If [socketPath] is non-null (e.g. `"@ftyd"`): runs via daemon as root.
         *
         * Read output via [read]/[output], then check [exitCode] after EOF.
         *
         * ```kotlin
         * // Local streaming
         * val session = TerminalSession.execSession("ping -c 5 google.com")
         *
         * // Daemon streaming
         * val session = TerminalSession.execSession("dmesg -w", socketPath = "@ftyd")
         *
         * session.use {
         *     it.output().collect { bytes ->
         *         print(String(bytes))  // live streaming
         *     }
         *     println("Exit code: ${it.exitCode}")
         * }
         * ```
         *
         * @param command the shell command to execute (passed to `shell -c`)
         * @param socketPath daemon socket path, or null for local exec (default: null)
         * @param shell the shell binary to use (default: "/system/bin/sh")
         * @throws IllegalStateException if local fork fails or daemon is not running
         */
        @JvmStatic
        @JvmOverloads
        fun execSession(
            command: String,
            socketPath: String? = null,
            shell: String = "/system/bin/sh"
        ): TerminalSession {
            NativePTY.ensureLoaded()
            if (socketPath != null) {
                val id = NativePTY.nativeStartExecSession(socketPath, command, shell)
                check(id >= 0) { "Failed to connect to ftyd at $socketPath for exec — is the daemon running?" }
                return TerminalSession(id)
            } else {
                val id = NativePTY.nativeStartLocalExecSession(shell, command)
                check(id >= 0) { "Failed to start local exec session (native returned $id)" }
                return TerminalSession(id)
            }
        }
    }
}
