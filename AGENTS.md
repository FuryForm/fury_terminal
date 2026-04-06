# AGENTS.md — FuryTerminal

Android PTY library (`com.furyform.terminal`) with native C backend.
Two modules: `terminal-lib` (AAR library) and `sample-app` (Compose UI demo).

## Project Structure

```
native/              Pure C — libterm.c (JNI bridge), ftyd.c (root daemon), protocol.h, pty_common.h
android/
  terminal-lib/      Library module (Kotlin API + prebuilt jniLibs)
  sample-app/        Sample app (Jetpack Compose)
libs/                Prebuilt native binaries (built by build.ps1/build.sh)
magisk/              Magisk module for daemon boot startup
```

## Build Commands

All Gradle commands run from `android/` directory.
Windows: `.\gradlew.bat`, Unix: `./gradlew`.

```powershell
# Build library
.\gradlew.bat :terminal-lib:assembleRelease

# Build everything
.\gradlew.bat build

# Lint
.\gradlew.bat :terminal-lib:lintRelease

# Rebuild native .so files (requires ANDROID_NDK_HOME)
.\build.ps1          # Windows
./build.sh           # Linux/macOS
```

## Test Commands

```powershell
# Unit tests (JVM, no device needed)
.\gradlew.bat :sample-app:testDebugUnitTest

# Instrumented tests (requires connected device)
$env:ANDROID_SERIAL = "R5CX21HZ9AX"
.\gradlew.bat :terminal-lib:connectedDebugAndroidTest

# Single test class
.\gradlew.bat :terminal-lib:connectedDebugAndroidTest --tests "com.furyform.terminal.TerminalSessionTest"
.\gradlew.bat :sample-app:testDebugUnitTest --tests "com.furyform.sample.TerminalViewModelTest"

# Single test method
.\gradlew.bat :sample-app:testDebugUnitTest --tests "com.furyform.sample.TerminalViewModelTest.testMethodName"

# Rerun (ignore cache)
.\gradlew.bat :terminal-lib:connectedDebugAndroidTest --rerun-tasks
```

**Important**: Set `$env:ANDROID_SERIAL = "R5CX21HZ9AX"` before instrumented tests.
An emulator on port 5554 can interfere — the serial env var forces the physical device.

The `ftyd` daemon must be running on the device for daemon-related tests. Those tests
gracefully skip (try/catch) if the daemon is unavailable.

## Test Frameworks

- **JUnit 4** for all tests (`@Test`, `@RunWith(AndroidJUnit4::class)`)
- **AndroidX Test** runner/rules for instrumented tests
- **kotlinx-coroutines-test** for coroutine testing (`runBlocking`, `withTimeoutOrNull`)
- No mocking framework — tests run against real native code on device

## Kotlin Code Style

### Formatting
- 4-space indentation, no tabs
- ~100–120 char line length (soft limit)
- Single blank line between functions; section separators: `// =================== Section ===================`

### Imports
- Kotlin stdlib first, then Java stdlib, then Android/AndroidX, then third-party
- Wildcard OK for `org.junit.Assert.*` in tests; prefer explicit imports elsewhere
- No unused imports

### Naming
- `camelCase` functions and properties
- `PascalCase` classes, interfaces, objects
- `UPPER_SNAKE_CASE` constants (`companion object` vals)
- Test methods: `camelCase` descriptive names (`testExecLocalExitCode`, `create_returnsSession`)
- Test class sections use comment separators: `// =================== Topic ===================`

### Types & Nullability
- Use Kotlin null-safety (`?`, `?.`, `?:`) — avoid `!!` except where crash is correct
- `ByteArray?` for reads that may return null (EOF/error)
- Return `-1` from native methods on error (C convention, propagated to Kotlin as `Int`)

### Error Handling
- `try/catch` for recoverable errors; catch specific exceptions
- `Closeable` + `.use {}` for sessions in tests
- `@Volatile` + `AtomicInteger` + `ReentrantReadWriteLock` for thread-safe state
- Close resources in `finally` blocks or `use` lambdas

### Coroutines
- Heavy/blocking work on `Dispatchers.IO`
- Flows use `flowOn(Dispatchers.IO)` for production
- Tests use `runBlocking` (not `runTest`) since they call real blocking JNI

### API Design
- Public API on `TerminalSession` companion object: `create()`, `createDaemon()`, `exec()`, `execSession()`
- Unified methods with `socketPath: String? = null` — null = local, non-null = daemon
- Default parameter values for shell (`"/system/bin/sh"`), rows (24), cols (80)
- `@JvmStatic` and `@JvmOverloads` on public API for Java interop
- `NativePTY` is `internal object` — never expose JNI layer publicly

## C Code Style

### Formatting
- 4-space indentation
- `/* block comments */` for documentation, `//` for inline notes
- Section separators: `/* =================== SECTION =================== */`
- Braces on same line: `if (x) {`

### Naming
- `snake_case` functions and variables
- `UPPER_SNAKE_CASE` macros and constants
- Prefix daemon functions: `ftyd_*`
- Prefix protocol constants: `FTYD_*`
- JNI functions: full `Java_com_furyform_terminal_NativePTY_nativeFunctionName` convention

### Error Handling
- Return `-1` on failure from functions that return `int`
- Always `close(fd)` on error paths before returning
- Log with `__android_log_print(ANDROID_LOG_ERROR, "ftyd", ...)`
- Check every `malloc`, `fork`, `pipe`, `open` return value
- Use `pthread_mutex_lock`/`unlock` around shared state (`ptys[]` table)
- `waitpid(WNOHANG)` for non-blocking checks, `waitpid(0)` for blocking reap

### Protocol
- Wire format: `[type:uint8][length:uint32 BE][data:length]`
- Types: DATA=0x01, RESIZE=0x02, SIGNAL=0x03, CLOSE=0x04, EXEC=0x05, EXIT_CODE=0x06
- Max payload: 1 MB (`FTYD_MAX_MSG_LEN`)
- Abstract Unix sockets (prefix `@`) to bypass SELinux

## Architecture Notes

- **Prebuilt jniLibs**: Native `.so` files are committed in `src/main/jniLibs/` for JitPack
  compatibility. Rebuild with `build.ps1`/`build.sh` after any C changes.
- **Session table**: Fixed array of 16 slots (`MAX_PTYS`) protected by a pthread mutex.
- **Four session types**: interactive PTY (local), interactive PTY (daemon), local exec (pipe-based),
  daemon exec (pipe-based). All share the same session table and read/write/close JNI methods.
- **Blocking waitpid**: `nativeGetExitCode` uses blocking `waitpid(0)` for local exec sessions
  (pipe EOF guarantees child is done). `nativeIsAlive` uses `waitpid(WNOHANG)`.
- **TOCTOU protection**: `read()`/`output()` acquire a read-lock around the `closed` check and
  `activeReaders.incrementAndGet()` atomically; the actual `nativeRead` call is outside the lock.

## CI

GitHub Actions (`.github/workflows/ci.yml`): build, unit tests, lint on push/PR to `main`.
Instrumented tests require a device and run locally only.

## Publishing

JitPack coordinate: `com.github.FuryForm:fury_terminal`
Version is set in `android/terminal-lib/build.gradle.kts` `afterEvaluate` block.
Tag format: `v0.4.0` (must match the version string in build.gradle.kts).

**Important**: please do code review for correctness, refactor, cleanup, and validate the code style/best practices before update version and update readme before commit/publish.