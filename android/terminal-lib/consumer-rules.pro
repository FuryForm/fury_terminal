# Consumer ProGuard rules for FuryTerminal library
# These rules are automatically applied to any app that depends on this library.

# Keep the JNI class and all native methods
-keep class com.furyform.terminal.NativePTY { *; }

# Keep the public API
-keep class com.furyform.terminal.TerminalSession { *; }
-keep class com.furyform.terminal.TerminalSession$Companion { *; }
