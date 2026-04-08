# Consumer ProGuard rules for FuryTerminal library
# These rules are automatically applied to any app that depends on this library.

# Keep the JNI class and all native methods
-keep class com.furyform.terminal.NativePTY { *; }

# Keep the public API
-keep class com.furyform.terminal.TerminalSession { *; }
-keep class com.furyform.terminal.TerminalSession$Companion { *; }
-keep class com.furyform.terminal.ExecResult { *; }
-keep class com.furyform.terminal.DaemonSessionInfo { *; }
-keep class com.furyform.terminal.SessionState { *; }
-keep class com.furyform.terminal.SessionState$* { *; }

# Keep the exception hierarchy (sealed class + subclasses)
-keep class com.furyform.terminal.TerminalException { *; }
-keep class com.furyform.terminal.DaemonConnectionException { *; }
-keep class com.furyform.terminal.SessionClosedException { *; }
-keep class com.furyform.terminal.NativeException { *; }
-keep class com.furyform.terminal.WriteException { *; }
