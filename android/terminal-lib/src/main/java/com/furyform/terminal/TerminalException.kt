package com.furyform.terminal

/**
 * Base exception for all FuryTerminal errors.
 *
 * Sealed hierarchy enables exhaustive `when` handling:
 * ```kotlin
 * try {
 *     session.write("ls\n")
 * } catch (e: TerminalException) {
 *     when (e) {
 *         is SessionClosedException -> { /* session was already closed */ }
 *         is DaemonConnectionException -> { /* daemon not running */ }
 *         is NativeException -> { /* native layer error */ }
 *         is WriteException -> { /* write/resize failed */ }
 *     }
 * }
 * ```
 */
sealed class TerminalException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Failed to connect to the ftyd daemon.
 * The daemon may not be running, or the socket path may be wrong.
 */
class DaemonConnectionException(
    val socketPath: String,
    message: String = "Failed to connect to ftyd at $socketPath — is the daemon running?"
) : TerminalException(message)

/**
 * Operation attempted on a session that has already been closed.
 */
class SessionClosedException(message: String = "Session is closed") : TerminalException(message)

/**
 * A native (C/JNI) operation failed.
 * The [nativeReturnCode] is the raw value returned by the native function (-1 typically).
 */
class NativeException(
    val nativeReturnCode: Int = -1,
    message: String = "Native operation failed (returned $nativeReturnCode)"
) : TerminalException(message)

/**
 * A write operation failed at the native layer.
 * The [bytesOrCode] is the raw return value: -1 for errors.
 */
class WriteException(
    val bytesOrCode: Int = -1,
    message: String = "Write operation failed (returned $bytesOrCode)"
) : TerminalException(message)
