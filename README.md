# FuryTerminal

A native PTY (pseudo-terminal) library for Android, powered by pure C and the Android NDK. Includes a root PTY daemon for privileged shell access via Unix socket. Published on JitPack for easy Gradle integration.

## Features

- Native PTY implementation (no root required for local sessions)
- **Root daemon** (`ftyd`) — spawns root shells via Unix socket, runs on boot
- Fork + exec `/system/bin/sh` with proper terminal setup (`TIOCSCTTY`, process groups)
- Thread-safe session management (up to 16 concurrent sessions)
- Binary-safe I/O (no null-byte truncation)
- Clean Kotlin API with `Closeable` and `Flow` support
- Shared protocol layer with message size limits (1 MB max)
- Signal whitelist for daemon sessions (security hardened)
- ProGuard/R8 compatible with consumer rules included
- Magisk module for automatic boot startup

## Installation

Add JitPack to your project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.FuryForm:fury_terminal:0.1.0")
}
```

## Usage

### Local Session (No Root)

```kotlin
import com.furyform.terminal.TerminalSession

// Create a session (starts /system/bin/sh as app UID)
val session = TerminalSession.create(rows = 24, cols = 80)

session.write("ls -la\n")

val output = session.read()  // blocking
if (output != null) {
    println(String(output))
}

session.close()
```

### Root Daemon Session

```kotlin
// Connect to the ftyd daemon (shell runs as root)
val session = TerminalSession.createDaemon(
    socketPath = "@ftyd",  // abstract socket (default)
    rows = 24,
    cols = 80
)

session.write("id\n")  // uid=0(root) gid=0(root)

session.output().collect { bytes ->
    println(String(bytes))
}
```

### With Kotlin Coroutines

```kotlin
val session = TerminalSession.create()

session.output().collect { bytes ->
    val text = String(bytes, Charsets.UTF_8)
    // Update your UI here
}
```

### With try-with-resources

```kotlin
TerminalSession.create().use { session ->
    session.write("echo Hello World\n")
    val data = session.read()
    println(String(data ?: byteArrayOf()))
}
```

### Resize Terminal

```kotlin
session.resize(rows = 40, cols = 120)
```

### Send Signals

```kotlin
session.sendSignal(2)   // SIGINT (Ctrl+C)
session.sendSignal(20)  // SIGTSTP (Ctrl+Z)
```

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌─────────────┐
│ Kotlin API   │────▶│  JNI Bridge  │────▶│  C / NDK    │
│TerminalSession│    │  (libterm.so)│     │  PTY Core   │
└─────────────┘     └──────────────┘     └─────────────┘
        │                                       │
        │                                  fork/exec
        │                                       │
        │  ┌─────────────┐               /system/bin/sh
        └─▶│  ftyd daemon │ (daemon mode)
           │  Unix socket  │──── fork/exec ──── /system/bin/sh (as root)
           └─────────────┘
```

- **Kotlin layer**: `TerminalSession` — clean, lifecycle-aware API with `ReentrantReadWriteLock` for thread safety
- **JNI layer**: Compiled directly into `libterm.so` via Android NDK CMake
- **Native layer**: Pure C (~430 lines), no Go/Rust runtime overhead
- **PTY**: Manual `posix_openpt` + `ptsname_r` (Android Bionic lacks `openpty`)
- **Daemon**: `ftyd` — root PTY broker with heap-allocated sessions, atomic flags, shared protocol

### Daemon Protocol

All messages: `[type:uint8][length:uint32 BE][data:length]` (max 1 MB per message)

| Type   | Code | Data                     | Direction        |
|--------|------|--------------------------|------------------|
| DATA   | 0x01 | Raw bytes                | Both             |
| RESIZE | 0x02 | rows(BE16) + cols(BE16)  | Client → Daemon  |
| SIGNAL | 0x03 | signum(uint8)            | Client → Daemon  |
| CLOSE  | 0x04 | (none)                   | Both             |

Allowed signals: SIGHUP, SIGINT, SIGQUIT, SIGKILL, SIGTERM, SIGCONT, SIGTSTP, SIGWINCH.

## Root Daemon (ftyd)

The daemon runs as root and brokers PTY sessions for the app. Each connecting client gets its own isolated shell with proper cleanup (escalating SIGHUP → SIGTERM → SIGKILL).

### Manual Start

```bash
# Push binary to device
adb push libs/arm64-v8a/ftyd /data/local/tmp/
adb shell chmod 755 /data/local/tmp/ftyd

# Start daemon (daemonizes by default, uses abstract socket @ftyd)
adb shell su -c '/data/local/tmp/ftyd'

# Or with custom options
adb shell su -c '/data/local/tmp/ftyd -f'               # foreground
adb shell su -c '/data/local/tmp/ftyd -s @myapp'         # custom abstract socket
adb shell su -c '/data/local/tmp/ftyd -s /tmp/ftyd.sock' # filesystem socket
```

### Magisk Module (Auto-Start on Boot)

For rooted devices with Magisk:

1. Build binaries: `./build.sh` (or `.\build.ps1` on Windows)
2. Package the Magisk module:
   ```bash
   cp libs/arm64-v8a/ftyd magisk/bin/arm64-v8a/
   cd magisk && zip -r ../fury-terminal-daemon.zip .
   ```
3. Flash `fury-terminal-daemon.zip` via Magisk Manager
4. Reboot — daemon starts automatically as root

### init.d Fallback (No Magisk)

```bash
adb push libs/arm64-v8a/ftyd /system/bin/
adb shell chmod 755 /system/bin/ftyd
adb push initd/99-ftyd /system/etc/init.d/
adb shell chmod 755 /system/etc/init.d/99-ftyd
```

### Daemon Options

```
ftyd [options]

  -s PATH    Socket path (default: @ftyd)
             Prefix with '@' for abstract socket
  -S SHELL   Shell binary (default: /system/bin/sh)
  -f         Run in foreground (don't daemonize)
  -p FILE    PID file path (default: /data/local/tmp/ftyd.pid)
  -h         Show help
```

## Building

Prerequisites:
- Android NDK (r25+ recommended)

```bash
# Linux/macOS
export NDK_HOME=/path/to/android/ndk
./build.sh

# Windows (PowerShell)
.\build.ps1 -NdkPath "D:\path\to\ndk"
```

This compiles both `libterm.so` and `ftyd` for all 4 ABIs (armeabi-v7a, arm64-v8a, x86, x86_64).

For day-to-day development, Gradle's `externalNativeBuild` handles CMake compilation of `libterm.so` automatically — no manual script needed. The build scripts are for prebuilding `ftyd` daemon binaries.

## Project Structure

```
fury_terminal/
├── native/                         # Pure C source
│   ├── libterm.c                   # PTY operations + JNI bridge
│   ├── ftyd.c                      # Root PTY daemon server
│   ├── pty_common.h                # Shared PTY ops (openpty, fork/exec)
│   ├── protocol.h                  # Shared protocol definitions
│   └── CMakeLists.txt              # CMake build definition
├── android/
│   ├── terminal-lib/               # Android library module (AAR)
│   │   └── src/main/java/...       # Kotlin API
│   └── sample-app/                 # Sample application (Compose UI)
├── magisk/                         # Magisk module for boot startup
├── initd/                          # init.d fallback script
├── build.sh / build.ps1            # Native build scripts (daemon + .so)
└── jitpack.yml                     # JitPack build configuration
```

## Security Notes

- **Local sessions** run as the app's UID — no elevated privileges
- **Daemon sessions** run as root — the daemon is accessible to any process that can connect to the abstract socket `@ftyd`
- Signal injection is restricted to a whitelist (no arbitrary `kill()`)
- Message size is capped at 1 MB to prevent DoS
- File creation mask is `022` (not world-writable)
- Child FDs are closed with `O_CLOEXEC` to prevent leaking across sessions

## License

MIT
