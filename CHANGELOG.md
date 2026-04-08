# Changelog

All notable changes to FuryTerminal are documented here.
Format based on [Keep a Changelog](https://keepachangelog.com/).

## [0.8.1] - 2026-04-08

### Added (Sample App)
- **3-tab layout** — Interactive, Exec, and Sessions tabs
- **Env/CWD inputs** — environment variables and working directory fields on Interactive and Exec tabs
- **Session state display** — real-time `SessionState` badge (Running/Exited/Closed) on Interactive tab
- **Sessions tab** — query and display active daemon sessions with session ID, type, PID, UID, start time
- Uses `outputText()` for interactive terminal output (text-based Flow)

### Changed (Sample App)
- Extracted `MonospaceTextField` and `EnvCwdInputs` composables (eliminated ~150 lines of duplication)
- Moved signal button lists to file-level constants (avoids recomposition allocations)
- Added section separators to both ViewModel and Screen files
- Wrapped long function signatures to respect ~120 char line limit

### Fixed (Sample App)
- Tab switching no longer kills sessions — sessions persist across tabs, allowing Sessions tab to see running sessions
- Removed unnecessary `withContext(Dispatchers.Main)` — `StateFlow.update()` is thread-safe
- `parseEnvString` now filters empty keys (edge case with `"=VAL"` input)

## [0.8.0] - 2026-04-08

### Added
- **Daemon session listing** — `listSessions()` and `listSessionsAsync()` query active sessions on the daemon
- New `DaemonSessionInfo` data class: `sessionId`, `pid`, `uid`, `type` ("pty"/"exec"), `alive`, `startTime`
- `FTYD_LIST` (0x07) protocol message — separate connection model (connect → LIST → response → close)
- Daemon-side session table (`MAX_DAEMON_SESSIONS=32`) tracks all active PTY and exec sessions
- `SO_PEERCRED` UID capture on every connection (for session tracking, independent of auth)
- `outputText(): Flow<String>` convenience method for text-based output streaming
- ProGuard keep rule for `DaemonSessionInfo`

### Fixed
- Flaky `startDaemonSession_withEnvAndCwd` test — added shell startup delay
- Session listing test timing — added registration delay after session creation
- NativePTY session listing test field indices (type at index 3, alive at index 4)
- `g_next_session_id` wrap-around — skips 0 sentinel after uint32 overflow

### Tests
- 9 new session listing tests (4 in NativePTYTest, 5 in TerminalSessionTest)
- All 192 tests passing (176 instrumented + 16 unit)

## [0.7.2] - 2026-04-08

### Fixed
- `exec_table_wait` timeout race — calls `waitpid(WNOHANG)` before freeing slot
- `nativeGetExitCode` tries non-blocking waitpid first, blocks only if still running
- `proto_recv_data_ex` CLOSE handler now drains payload before returning
- `write()` now holds read-lock around `nativeWrite` (prevents slot reuse race)
- `output()` delegates to `read()` internally instead of duplicating lock pattern
- `WriteException` KDoc corrected ("write or resize" → "write")
- Test signal assertion now directly checks `!session.isAlive`

### Changed
- Extracted `setup_sockaddr_un()` helper, used in both client and server socket setup
- Extracted `DEFAULT_PATH` constant — replaced 3 hardcoded PATH strings in native code
- Extracted `MAX_PAYLOAD_FIELDS` constant — replaced magic literals
- CLOSE handlers in `exec_client_reader_thread` and `handle_exec` now use `kill_escalate()`
- `my_openpty`, `set_winsize`, `do_fork_exec` changed to `static inline`
- `readAll(timeout)` KDoc now documents JNI blocking limitation
- `readJob`/`execJob` now `@Volatile` in sample app ViewModel

### Removed
- Redundant `@Volatile` on `NativePTY.loaded` (guarded by `@Synchronized`)

### Tests
- Added `@After fun shutdownExecutor()` to both test classes for cleanup
- Wrapped all 39 session-creating tests in NativePTYTest with try/finally
- All ~32 daemon tests now use `Assume.assumeTrue()` instead of early return
- `usePattern_closesSessionAutomatically` now asserts `isClosed` after `use{}`
- All 180 tests passing (164 instrumented + 16 unit)

## [0.7.1] - 2026-04-07

### Fixed
- `exec_table_wait` timeout race — re-checks `ready` flag before clearing slot
- `exitCode` caching — exit code preserved after `close()` frees native slot
- `write()` lock fix — prevents race with concurrent `close()`
- `resize()` now throws `NativeException` instead of `WriteException`

### Changed
- Extracted helpers: `apply_env_pairs()`, `exit_code_from_status()`, `kill_escalate()` in pty_common.h
- Removed heap allocation in `nativeRead` (uses stack buffer)
- Unified `store_pty`/`store_local_exec`/`store_daemon_session` → `store_session()`
- Extracted `jni_string_array` helpers in libterm.c (~80 lines eliminated)
- Added `transitionToExited()` helper in TerminalSession

### Removed
- Dead `SessionFullException` class
- Unnecessary `malloc` in `handle_exec`

### Tests
- Narrowed test catch to `TimeoutException`
- All 180 tests passing

## [0.7.0] - 2026-04-06

### Added
- **Daemon env/cwd support** — environment variables and working directory for daemon sessions (interactive PTY and exec)
- Extended RESIZE protocol payload: `[rows][cols][shell\0cwd\0env1\0env2\0...]`
- Extended EXEC protocol payload: `shell\0command\0cwd\0env1\0env2\0...`
- 15 new daemon env/cwd tests

### Fixed
- `handle_exec` buffer safety — bounds checking on payload parsing
- `EnsureLocalCapacity` JNI call for large env arrays
- Truncation warning for oversized payloads
- Flaky interactive daemon PTY test timing (increased sleep/iterations)

### Tests
- All 180 tests passing (164 instrumented + 16 unit)

## [0.6.0] - 2026-04-05

### Added
- **Typed exceptions** — sealed `TerminalException` hierarchy: `DaemonConnectionException`, `SessionClosedException`, `NativeException`, `WriteException`
- **Environment variables & working directory** for local sessions — `env` and `cwd` parameters on `create()`, `exec()`, `execSession()`
- **Session lifecycle StateFlow** — `state: StateFlow<SessionState>` (`Running` → `Exited` → `Closed`)
- **Suspend helpers** — `execAsync()`, `readText()`, `readAll(timeout: Duration)`
- Exit code capture for interactive PTY sessions (not just exec)
- `readAll()` blocking method for exec sessions
- `write()` now throws `WriteException` on failure (was silent)

### Tests
- All 165 tests passing

## [0.5.1] - 2026-04-04

### Fixed
- Process group signal delivery — `setsid()` + `kill(-pid)` ensures signals reach entire process tree
- `close()` blocking fix — no longer hangs waiting for readers
- Per-test timeout of 30 seconds via JUnit `@Rule Timeout`

### Tests
- 138 tests passing

## [0.5.0] - 2026-04-03

### Added
- **UID authentication** — optional `SO_PEERCRED`-based allowlist for daemon connections (`-a` flag)
- Config file support (`/data/local/tmp/ftyd.conf`): `allow <package>`, `allow_uid <N>`
- SIGHUP reload — `kill -HUP` reloads config without restart
- `pthread_rwlock` protects allowlist for thread-safe concurrent access
- `execlp` for PATH-searched shell names (e.g., `shell = "su"`)
- Signal constants on companion object (`SIGINT`, `SIGTERM`, etc.)

### Security
- UIDs 0 (root) and 2000 (shell/adb) always allowed when auth is enabled
- `SO_PEERCRED` cannot be spoofed — kernel-enforced at connect-time

## [0.4.0] - 2026-04-02

### Added
- Initial public release
- Local PTY sessions (fork + exec, no root required)
- Root daemon (`ftyd`) — PTY broker via abstract Unix socket
- Exec mode — pipe-based one-shot command execution (local and daemon)
- Unified API: `exec()`/`execSession()` with `socketPath` parameter
- Kotlin `Flow`-based output streaming via `output()`
- `Closeable` support for try-with-resources
- Signal delivery to sessions (`sendSignal()`)
- Terminal resize (`resize()`)
- Custom shell parameter for all session types
- Prebuilt native binaries for 4 ABIs (armeabi-v7a, arm64-v8a, x86, x86_64)
- Magisk module for boot startup
- JitPack publishing (`com.github.FuryForm:fury_terminal`)
