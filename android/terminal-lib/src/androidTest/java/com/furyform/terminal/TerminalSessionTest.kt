package com.furyform.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TerminalSessionTest {

    @get:Rule
    val globalTimeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    private val executor = Executors.newSingleThreadExecutor()

    /** Read with a timeout to prevent blocking forever. Returns null on timeout/error. */
    private fun readWithTimeout(session: TerminalSession, timeoutMs: Long = 3000): ByteArray? {
        val future = executor.submit(Callable { session.read() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            null
        }
    }

    // =================== Local Session: Creation ===================

    @Test
    fun create_returnsSession() {
        val session = TerminalSession.create()
        assertFalse(session.isClosed)
        assertTrue(session.isAlive)
        session.close()
    }

    @Test
    fun create_withCustomSize() {
        val session = TerminalSession.create(rows = 40, cols = 120)
        assertFalse(session.isClosed)
        assertTrue(session.isAlive)
        session.close()
    }

    @Test
    fun create_defaultParameters() {
        val session = TerminalSession.create()
        assertTrue(session.isAlive)
        session.close()
    }

    // =================== Local Session: Write ===================

    @Test
    fun write_string_returnsPositive() {
        val session = TerminalSession.create()
        val written = session.write("echo hello\n")
        assertTrue("Should write bytes, got $written", written > 0)
        session.close()
    }

    @Test
    fun write_byteArray_returnsPositive() {
        val session = TerminalSession.create()
        val data = "ls\n".toByteArray(Charsets.UTF_8)
        val written = session.write(data)
        assertEquals(data.size, written)
        session.close()
    }

    @Test(expected = SessionClosedException::class)
    fun write_afterClose_throws() {
        val session = TerminalSession.create()
        session.close()
        session.write("should fail")
    }

    // =================== Local Session: Read ===================

    @Test
    fun read_returnsShellOutput() {
        val session = TerminalSession.create()
        session.write("echo FURY_READ_TEST\n")
        Thread.sleep(500)
        val data = readWithTimeout(session)
        assertNotNull("Should read output", data)
        assertTrue(data!!.isNotEmpty())
        session.close()
    }

    @Test(expected = SessionClosedException::class)
    fun read_afterClose_throws() {
        val session = TerminalSession.create()
        session.close()
        session.read() // checks closed flag in Kotlin before native call
    }

    // =================== Local Session: Output Flow ===================

    @Test
    fun output_emitsData() = runBlocking {
        val session = TerminalSession.create()
        session.write("echo FURY_FLOW_TEST\n")

        val result = withTimeoutOrNull(3000L) {
            session.output().first()
        }

        assertNotNull("Flow should emit data within timeout", result)
        assertTrue(result!!.isNotEmpty())
        session.close()
    }

    @Test
    fun output_containsCommandOutput() = runBlocking {
        val session = TerminalSession.create()
        session.write("echo FURY_FLOW_MARKER\n")

        val chunks = mutableListOf<ByteArray>()
        val job = launch {
            session.output().take(10).toList(chunks)
        }

        withTimeoutOrNull(3000L) { job.join() } ?: job.cancel()

        val fullOutput = chunks.joinToString("") { String(it) }
        assertTrue(
            "Output should contain marker, got: ${fullOutput.take(200)}",
            fullOutput.contains("FURY_FLOW_MARKER")
        )
        session.close()
    }

    // =================== Local Session: Resize ===================

    @Test
    fun resize_succeeds() {
        val session = TerminalSession.create()
        val result = session.resize(40, 120)
        assertEquals(0, result)
        session.close()
    }

    @Test
    fun resize_multipleTimesSucceeds() {
        val session = TerminalSession.create()
        assertEquals(0, session.resize(10, 20))
        assertEquals(0, session.resize(200, 400))
        assertEquals(0, session.resize(24, 80))
        session.close()
    }

    @Test(expected = SessionClosedException::class)
    fun resize_afterClose_throws() {
        val session = TerminalSession.create()
        session.close()
        session.resize(24, 80)
    }

    // =================== Local Session: Signals ===================

    @Test
    fun sendSignal_doesNotKillShell() {
        val session = TerminalSession.create()
        session.write("sleep 60\n")
        Thread.sleep(300)
        session.sendSignal(2) // SIGINT
        Thread.sleep(300)
        assertTrue("Shell should survive SIGINT", session.isAlive)
        session.close()
    }

    @Test(expected = SessionClosedException::class)
    fun sendSignal_afterClose_throws() {
        val session = TerminalSession.create()
        session.close()
        session.sendSignal(2)
    }

    // =================== Local Session: Lifecycle ===================

    @Test
    fun isAlive_trueWhileRunning() {
        val session = TerminalSession.create()
        assertTrue(session.isAlive)
        session.close()
    }

    @Test
    fun isClosed_falseBeforeClose() {
        val session = TerminalSession.create()
        assertFalse(session.isClosed)
        session.close()
    }

    @Test
    fun isClosed_trueAfterClose() {
        val session = TerminalSession.create()
        session.close()
        assertTrue(session.isClosed)
    }

    @Test
    fun isAlive_falseAfterClose() {
        val session = TerminalSession.create()
        session.close()
        assertFalse(session.isAlive)
    }

    @Test
    fun close_idempotent() {
        val session = TerminalSession.create()
        session.close()
        session.close()
        session.close()
        assertTrue(session.isClosed)
    }

    @Test
    fun isAlive_falseAfterShellExit() {
        val session = TerminalSession.create()
        session.write("exit\n")
        Thread.sleep(1000)
        readWithTimeout(session, 2000) // drain with timeout
        Thread.sleep(200)
        assertFalse("isAlive should be false after shell exits", session.isAlive)
        session.close()
    }

    // =================== Closeable pattern ===================

    @Test
    fun usePattern_closesSessionAutomatically() {
        val closed: Boolean
        TerminalSession.create().use { session ->
            assertFalse(session.isClosed)
            session.write("echo USE_TEST\n")
            closed = session.isClosed
        }
        assertFalse("Should not be closed inside use{}", closed)
    }

    // =================== End-to-End ===================

    @Test
    fun endToEnd_commandExecution() {
        TerminalSession.create().use { session ->
            session.write("echo E2E_SESSION_TEST\n")
            Thread.sleep(500)
            val data = readWithTimeout(session)
            assertNotNull(data)
            val output = String(data!!)
            assertTrue(
                "Output should contain E2E_SESSION_TEST, got: ${output.take(200)}",
                output.contains("E2E_SESSION_TEST")
            )
        }
    }

    @Test
    fun endToEnd_binaryIO() {
        val session = TerminalSession.create()
        val binaryPayload = byteArrayOf(0x41, 0x00, 0x42, 0x00, 0x43)
        val written = session.write(binaryPayload)
        assertEquals("Should write all 5 bytes including nulls", 5, written)
        session.close()
    }

    @Test
    fun endToEnd_resizeThenCommand() {
        TerminalSession.create().use { session ->
            session.resize(50, 132)
            session.write("echo RESIZED\n")
            Thread.sleep(500)
            val data = readWithTimeout(session)
            assertNotNull("Should get output after resize + command", data)
        }
    }

    // =================== Daemon Session ===================

    @Test
    fun createDaemon_connectsToRunningDaemon() {
        try {
            val session = TerminalSession.createDaemon(socketPath = "@ftyd")
            assertFalse(session.isClosed)
            session.close()
        } catch (e: DaemonConnectionException) {
            assertEquals("@ftyd", e.socketPath)
        }
    }

    @Test
    fun createDaemon_writeAndRead() {
        val session = try {
            TerminalSession.createDaemon(socketPath = "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        session.write("echo DAEMON_TEST\n")
        Thread.sleep(500)

        val data = readWithTimeout(session)
        assertNotNull("Should read data from daemon session", data)
        val output = String(data!!)
        assertTrue(
            "Daemon output should contain DAEMON_TEST, got: ${output.take(200)}",
            output.contains("DAEMON_TEST")
        )
        session.close()
    }

    @Test
    fun createDaemon_resize() {
        val session = try {
            TerminalSession.createDaemon(socketPath = "@ftyd")
        } catch (_: DaemonConnectionException) {
            return
        }

        val result = session.resize(50, 132)
        assertEquals("Daemon resize should return 0", 0, result)
        session.close()
    }

    @Test
    fun createDaemon_sendSignal() {
        val session = try {
            TerminalSession.createDaemon(socketPath = "@ftyd")
        } catch (_: DaemonConnectionException) {
            return
        }

        session.write("sleep 60\n")
        Thread.sleep(300)
        session.sendSignal(2) // SIGINT
        Thread.sleep(300)
        session.write("echo AFTER_SIGNAL\n")
        Thread.sleep(300)
        val data = readWithTimeout(session)
        assertNotNull("Should still be able to read after signal", data)
        session.close()
    }

    @Test
    fun createDaemon_runAsRoot() {
        val session = try {
            TerminalSession.createDaemon(socketPath = "@ftyd")
        } catch (_: DaemonConnectionException) {
            return
        }

        session.write("id\n")
        Thread.sleep(500)

        val output = StringBuilder()
        for (i in 0 until 5) {
            val data = readWithTimeout(session, 1000) ?: break
            output.append(String(data))
            if (output.contains("uid=0")) break
        }

        assertTrue(
            "Daemon shell should run as root (uid=0), got: ${output.take(200)}",
            output.contains("uid=0")
        )
        session.close()
    }

    @Test
    fun createDaemon_outputFlow() = runBlocking {
        val session = try {
            TerminalSession.createDaemon(socketPath = "@ftyd")
        } catch (_: DaemonConnectionException) {
            return@runBlocking
        }

        session.write("echo DAEMON_FLOW_TEST\n")

        val result = withTimeoutOrNull(3000L) {
            session.output().first()
        }

        assertNotNull("Daemon flow should emit data", result)
        session.close()
    }

    @Test
    fun createDaemon_invalidSocket_throws() {
        try {
            TerminalSession.createDaemon(socketPath = "@nonexistent_ftyd_test")
            fail("Should throw for invalid socket path")
        } catch (e: DaemonConnectionException) {
            assertEquals("@nonexistent_ftyd_test", e.socketPath)
        }
    }

    @Test
    fun createDaemon_close_idempotent() {
        val session = try {
            TerminalSession.createDaemon(socketPath = "@ftyd")
        } catch (_: DaemonConnectionException) {
            return
        }

        session.close()
        session.close()
        session.close()
        assertTrue(session.isClosed)
    }

    // =================== Exec Mode ===================

    @Test
    fun testExecSimpleCommand() {
        val result = try {
            TerminalSession.exec("echo hello", "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        assertTrue(
            "Output should contain 'hello', got: ${result.output.take(200)}",
            result.output.contains("hello")
        )
        assertEquals("Exit code should be 0", 0, result.exitCode)
        assertTrue("isSuccess should be true", result.isSuccess)
    }

    @Test
    fun testExecExitCode() {
        val result = try {
            TerminalSession.exec("exit 42", "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        assertEquals("Exit code should be 42", 42, result.exitCode)
        assertFalse("isSuccess should be false for exit code 42", result.isSuccess)
    }

    @Test
    fun testExecCommandNotFound() {
        val result = try {
            TerminalSession.exec("nonexistent_command_xyz", "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        assertEquals("Exit code should be 127 for command not found", 127, result.exitCode)
    }

    @Test
    fun testExecMultilineOutput() {
        val result = try {
            TerminalSession.exec("echo line1; echo line2; echo line3", "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        assertTrue(
            "Output should contain 'line1', got: ${result.output.take(200)}",
            result.output.contains("line1")
        )
        assertTrue(
            "Output should contain 'line2', got: ${result.output.take(200)}",
            result.output.contains("line2")
        )
        assertTrue(
            "Output should contain 'line3', got: ${result.output.take(200)}",
            result.output.contains("line3")
        )
    }

    @Test
    fun testExecNoEcho() {
        val result = try {
            TerminalSession.exec("echo test123", "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        assertTrue(
            "Output should contain 'test123', got: ${result.output.take(200)}",
            result.output.contains("test123")
        )
        assertFalse(
            "Output should NOT contain the command text 'echo test123', got: ${result.output.take(200)}",
            result.output.contains("echo test123")
        )
    }

    @Test
    fun testExecSessionStreaming() {
        val session = try {
            TerminalSession.execSession("echo a; echo b; echo c", "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        session.use {
            val output = StringBuilder()
            while (true) {
                val chunk = readWithTimeout(it, 3000) ?: break
                output.append(String(chunk, Charsets.UTF_8))
            }
            val fullOutput = output.toString()

            assertTrue(
                "Streaming output should contain 'a', got: ${fullOutput.take(200)}",
                fullOutput.contains("a")
            )
            assertTrue(
                "Streaming output should contain 'b', got: ${fullOutput.take(200)}",
                fullOutput.contains("b")
            )
            assertTrue(
                "Streaming output should contain 'c', got: ${fullOutput.take(200)}",
                fullOutput.contains("c")
            )
            assertEquals("Exit code should be 0 after EOF", 0, it.exitCode)
        }
    }

    @Test
    fun testExecLargeOutput() {
        val result = try {
            TerminalSession.exec("seq 1 1000", "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        assertTrue(
            "Output should contain '1', got: ${result.output.take(200)}",
            result.output.contains("1")
        )
        assertTrue(
            "Output should contain '1000', got: ${result.output.takeLast(200)}",
            result.output.contains("1000")
        )
        assertEquals("Exit code should be 0", 0, result.exitCode)
    }

    @Test
    fun testExecStderrCaptured() {
        val result = try {
            TerminalSession.exec("echo err >&2", "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        assertTrue(
            "Output should contain 'err' (stderr merged with stdout), got: ${result.output.take(200)}",
            result.output.contains("err")
        )
    }

    @Test
    fun testExecPipeSupport() {
        val result = try {
            TerminalSession.exec("echo hello world | tr ' ' '\n'", "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        assertTrue(
            "Output should contain 'hello', got: ${result.output.take(200)}",
            result.output.contains("hello")
        )
        assertTrue(
            "Output should contain 'world', got: ${result.output.take(200)}",
            result.output.contains("world")
        )
        // Verify they appear on separate lines
        val lines = result.output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(
            "Output should have 'hello' and 'world' on separate lines, got: ${result.output.take(200)}",
            lines.any { it == "hello" } && lines.any { it == "world" }
        )
    }

    // =================== Local Exec Mode (via unified API) ===================

    @Test
    fun testExecLocalSimpleCommand() {
        val result = TerminalSession.exec("echo hello_local")
        assertTrue(
            "Output should contain 'hello_local', got: ${result.output.take(200)}",
            result.output.contains("hello_local")
        )
        assertEquals("Exit code should be 0", 0, result.exitCode)
        assertTrue("isSuccess should be true", result.isSuccess)
    }

    @Test
    fun testExecLocalExitCode() {
        val result = TerminalSession.exec("exit 42")
        assertEquals("Exit code should be 42", 42, result.exitCode)
        assertFalse("isSuccess should be false for exit code 42", result.isSuccess)
    }

    @Test
    fun testExecLocalCommandNotFound() {
        val result = TerminalSession.exec("nonexistent_command_xyz_local")
        assertEquals("Exit code should be 127 for command not found", 127, result.exitCode)
    }

    @Test
    fun testExecLocalMultilineOutput() {
        val result = TerminalSession.exec("echo line1; echo line2; echo line3")
        assertTrue(result.output.contains("line1"))
        assertTrue(result.output.contains("line2"))
        assertTrue(result.output.contains("line3"))
    }

    @Test
    fun testExecLocalStderrCaptured() {
        val result = TerminalSession.exec("echo err >&2")
        assertTrue(
            "Output should contain 'err' (stderr merged with stdout), got: ${result.output.take(200)}",
            result.output.contains("err")
        )
    }

    @Test
    fun testExecLocalPipeSupport() {
        val result = TerminalSession.exec("echo hello world | tr ' ' '\n'")
        assertTrue(result.output.contains("hello"))
        assertTrue(result.output.contains("world"))
    }

    @Test
    fun testExecLocalSessionStreaming() {
        val session = TerminalSession.execSession("echo a; echo b; echo c")
        session.use {
            val output = StringBuilder()
            while (true) {
                val chunk = readWithTimeout(it, 3000) ?: break
                output.append(String(chunk, Charsets.UTF_8))
            }
            val fullOutput = output.toString()
            assertTrue(fullOutput.contains("a"))
            assertTrue(fullOutput.contains("b"))
            assertTrue(fullOutput.contains("c"))
            assertEquals("Exit code should be 0 after EOF", 0, it.exitCode)
        }
    }

    @Test
    fun testExecLocalLargeOutput() {
        val result = TerminalSession.exec("seq 1 1000")
        assertTrue(result.output.contains("1"))
        assertTrue(result.output.contains("1000"))
        assertEquals("Exit code should be 0", 0, result.exitCode)
    }

    @Test
    fun testExecLocalNoEcho() {
        val result = TerminalSession.exec("echo test_local_123")
        assertTrue(result.output.contains("test_local_123"))
        assertFalse(
            "Output should NOT contain the command text",
            result.output.contains("echo test_local_123")
        )
    }

    @Test
    fun testExecLocalCustomShell() {
        // Test that we can specify a custom shell (still /system/bin/sh, but explicitly)
        val result = TerminalSession.exec("echo custom_shell_test", shell = "/system/bin/sh")
        assertTrue(result.output.contains("custom_shell_test"))
        assertEquals(0, result.exitCode)
    }

    // =================== Local Exec Session: Lifecycle ===================

    @Test
    fun testExecLocalSessionIsAlive() {
        val session = TerminalSession.execSession("sleep 2")
        try {
            assertTrue("execSession('sleep 2') should be alive immediately", session.isAlive)
        } finally {
            session.close()
        }
    }

    @Test
    fun testExecLocalSessionIsAlive_falseAfterExit() {
        val session = TerminalSession.execSession("echo done")
        session.readAll() // drain output
        session.exitCode  // blocking waitpid ensures child is fully reaped
        assertFalse("isAlive should be false after process exits", session.isAlive)
        session.close()
    }

    @Test
    fun testExecLocalSessionIsClosed() {
        val session = TerminalSession.execSession("echo test")
        try {
            assertFalse("isClosed should be false before close()", session.isClosed)
        } finally {
            session.close()
        }
        assertTrue("isClosed should be true after close()", session.isClosed)
    }

    @Test
    fun testExecLocalSessionClose() {
        val session = TerminalSession.execSession("sleep 60")
        session.close()
        assertTrue("isClosed should be true after close()", session.isClosed)
    }

    @Test
    fun testExecLocalSessionCloseIdempotent() {
        val session = TerminalSession.execSession("echo idempotent")
        session.close()
        session.close()
        session.close()
        assertTrue("isClosed should still be true after multiple close() calls", session.isClosed)
    }

    // =================== Local Exec Session: readAll & Flow ===================

    @Test
    fun testExecLocalSessionReadAll() {
        val session = TerminalSession.execSession("echo line1; echo line2")
        session.use {
            val output = it.readAll()
            assertTrue(
                "readAll() output should contain 'line1', got: ${output.take(200)}",
                output.contains("line1")
            )
            assertTrue(
                "readAll() output should contain 'line2', got: ${output.take(200)}",
                output.contains("line2")
            )
            assertEquals("Exit code should be 0", 0, it.exitCode)
        }
    }

    @Test
    fun testExecLocalSessionOutputFlow() = runBlocking {
        val session = TerminalSession.execSession("echo flow1; echo flow2")
        session.use {
            val chunks = mutableListOf<ByteArray>()
            withTimeoutOrNull(5000L) {
                it.output().toList(chunks)
            }
            val combined = chunks.joinToString("") { chunk -> String(chunk, Charsets.UTF_8) }
            assertTrue(
                "Flow output should contain 'flow1', got: ${combined.take(200)}",
                combined.contains("flow1")
            )
            assertTrue(
                "Flow output should contain 'flow2', got: ${combined.take(200)}",
                combined.contains("flow2")
            )
        }
    }

    @Test
    fun testExecLocalSessionOutputFlowCompletesOnExit() = runBlocking {
        val session = TerminalSession.execSession("echo done")
        session.use {
            val completed = withTimeoutOrNull(5000L) {
                it.output().toList()
                true
            }
            assertNotNull("Flow should complete (not hang) after process exits", completed)
        }
    }

    // =================== Local Exec Session: Signals ===================

    @Test
    fun testExecLocalSessionSendSignalInterrupt() {
        val session = TerminalSession.execSession("sleep 60")
        session.sendSignal(2) // SIGINT
        Thread.sleep(500)
        val alive = session.isAlive
        val chunk = readWithTimeout(session, 500)
        assertFalse(
            "Process should not be alive after SIGINT (isAlive=$alive, read=${chunk != null})",
            alive && chunk != null
        )
        session.close()
    }

    @Test
    fun testExecLocalSessionSendSignalTerm() {
        val session = TerminalSession.execSession("sleep 60")
        session.sendSignal(15) // SIGTERM
        Thread.sleep(500)
        assertFalse("Process should have exited after SIGTERM", session.isAlive)
        session.close()
    }

    @Test
    fun testExecLocalSessionSignalReachesChildProcess() {
        // Verify that sendSignal reaches grandchild processes via process group kill.
        // "sh -c 'sleep 60'" forks sleep as a child of sh. SIGTERM via kill(-pid)
        // should reach the entire process group including the sleep grandchild.
        val session = TerminalSession.execSession("sh -c 'sleep 60'")
        Thread.sleep(300) // let child tree start
        assertTrue("Process tree should be alive", session.isAlive)
        session.sendSignal(TerminalSession.SIGTERM)
        Thread.sleep(500)
        assertFalse("Process tree should have exited after SIGTERM", session.isAlive)
        session.close()
    }

    // =================== Local Exec Session: Exit Code ===================

    @Test
    fun testExecLocalExitCodeCachedAfterClose() {
        val session = TerminalSession.execSession("exit 7")
        session.readAll() // drain and let process exit
        val exitCodeBeforeClose = session.exitCode
        assertEquals("Exit code should be 7 before close()", 7, exitCodeBeforeClose)
        session.close()
        assertEquals("Exit code should still be 7 after close()", 7, session.exitCode)
    }

    @Test
    fun testExecLocalExitCodeBlocksUntilExit() {
        // exitCode uses blocking waitpid for local exec, so it waits for exit
        val session = TerminalSession.execSession("exit 42")
        try {
            assertEquals("Blocking exit code should return 42", 42, session.exitCode)
        } finally {
            session.close()
        }
    }

    // =================== Custom Shell Parameter ===================

    @Test
    fun testCreateWithCustomShell() {
        val session = TerminalSession.create(shell = "/system/bin/sh")
        session.use {
            session.write("echo CUSTOM_SHELL_OK\n")
            Thread.sleep(500)
            val data = readWithTimeout(session)
            assertNotNull("Should receive output from custom shell session", data)
            val output = String(data!!, Charsets.UTF_8)
            assertTrue(
                "Output should contain 'CUSTOM_SHELL_OK', got: ${output.take(200)}",
                output.contains("CUSTOM_SHELL_OK")
            )
        }
    }

    @Test
    fun testExecLocalWithInvalidShell() {
        // fork() succeeds, child calls execl("/nonexistent/shell", ...) which fails,
        // child calls _exit(127) — so we expect exit code 127 and no crash
        val result = TerminalSession.exec("echo hi", shell = "/nonexistent/shell")
        assertEquals("Invalid shell execl failure should yield exit code 127", 127, result.exitCode)
    }

    @Test
    fun testExecSessionLocalCustomShell() {
        val session = TerminalSession.execSession("echo shell_test", shell = "/system/bin/sh")
        session.use {
            val output = it.readAll()
            assertTrue(
                "Output should contain 'shell_test', got: ${output.take(200)}",
                output.contains("shell_test")
            )
        }
    }

    // =================== Unified API Routing ===================

    @Test
    fun testExecNullSocketPathIsLocal() {
        // socketPath = null routes to local exec (runs as app UID, not root)
        val result = TerminalSession.exec("id", socketPath = null)
        assertFalse(
            "Local exec (socketPath=null) should NOT run as uid=0, got: ${result.output.take(200)}",
            result.output.contains("uid=0")
        )
        assertEquals("Exit code should be 0", 0, result.exitCode)
    }

    @Test
    fun testExecNonNullSocketPathIsDaemon() {
        // socketPath = "@ftyd" routes to daemon exec; skip if daemon not running
        val result = try {
            TerminalSession.exec("id", socketPath = "@ftyd")
        } catch (_: DaemonConnectionException) {
            return // Daemon not running — skip
        }
        assertTrue(
            "Daemon exec (socketPath='@ftyd') should run as uid=0, got: ${result.output.take(200)}",
            result.output.contains("uid=0")
        )
    }

    // =================== Exec Edge Cases ===================

    @Test
    fun testExecLocalEmptyCommand() {
        // sh -c "" is valid and succeeds with exit code 0
        val result = TerminalSession.exec("")
        assertEquals("sh -c '' should exit with code 0", 0, result.exitCode)
    }

    @Test
    fun testExecLocalVeryLargeOutput() {
        val result = TerminalSession.exec("seq 1 50000")
        assertTrue(
            "Large output should contain '1', got first 200: ${result.output.take(200)}",
            result.output.contains("1")
        )
        assertTrue(
            "Large output should contain '50000', got last 200: ${result.output.takeLast(200)}",
            result.output.contains("50000")
        )
        assertEquals("Exit code should be 0", 0, result.exitCode)
    }

    @Test
    fun testCloseDuringBlockedRead() {
        val session = TerminalSession.create()
        // Launch a blocking read on a background thread via the existing executor
        val future = executor.submit(Callable { session.read() })
        Thread.sleep(200)
        // Close the session while read() is blocked — should unblock read and not deadlock
        session.close()
        // Verify the test completes within timeout (no deadlock)
        try {
            future.get(3000, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // Expected: read may return null, throw, or be cancelled — all acceptable
        }
        assertTrue("Session should be closed after close()", session.isClosed)
    }

    @Test
    fun testRapidOpenCloseCycles() {
        // Verify no crash or resource leak over repeated create/close cycles
        repeat(20) { i ->
            val session = TerminalSession.create()
            session.close()
            assertTrue("Session $i should be closed", session.isClosed)
        }
    }

    // =================== Env Vars + Working Directory ===================

    @Test
    fun testExecLocalWithEnvVars() {
        val result = TerminalSession.exec(
            "echo \$MY_TEST_VAR",
            env = mapOf("MY_TEST_VAR" to "fury_env_test_123")
        )
        assertTrue(
            "Output should contain env var value 'fury_env_test_123', got: ${result.output.take(200)}",
            result.output.contains("fury_env_test_123")
        )
        assertEquals(0, result.exitCode)
    }

    @Test
    fun testExecLocalWithMultipleEnvVars() {
        val result = TerminalSession.exec(
            "echo \$VAR_A \$VAR_B",
            env = mapOf("VAR_A" to "alpha", "VAR_B" to "beta")
        )
        assertTrue(result.output.contains("alpha"))
        assertTrue(result.output.contains("beta"))
    }

    @Test
    fun testExecLocalWithCwd() {
        val result = TerminalSession.exec("pwd", cwd = "/data/local/tmp")
        assertTrue(
            "Output should contain '/data/local/tmp', got: ${result.output.take(200)}",
            result.output.trim().contains("/data/local/tmp")
        )
        assertEquals(0, result.exitCode)
    }

    @Test
    fun testExecLocalWithEnvAndCwd() {
        val result = TerminalSession.exec(
            "echo \$FURY_CWD_TEST && pwd",
            env = mapOf("FURY_CWD_TEST" to "combined"),
            cwd = "/data/local/tmp"
        )
        assertTrue(result.output.contains("combined"))
        assertTrue(result.output.contains("/data/local/tmp"))
    }

    @Test
    fun testExecLocalEnvOverridesDefaults() {
        // Custom PATH should override the default
        val result = TerminalSession.exec(
            "echo \$PATH",
            env = mapOf("PATH" to "/custom/path:/system/bin")
        )
        assertTrue(
            "Custom PATH should appear, got: ${result.output.take(200)}",
            result.output.contains("/custom/path")
        )
    }

    @Test
    fun testExecLocalWithInvalidCwd() {
        // Invalid cwd is best-effort — chdir fails silently, command still runs
        val result = TerminalSession.exec("echo still_works", cwd = "/nonexistent/path")
        assertTrue(result.output.contains("still_works"))
        assertEquals(0, result.exitCode)
    }

    @Test
    fun testCreateWithCwd() {
        val session = TerminalSession.create(cwd = "/data/local/tmp")
        session.use {
            it.write("pwd\n")
            Thread.sleep(500)
            val data = readWithTimeout(it)
            assertNotNull(data)
            val output = String(data!!)
            assertTrue(
                "PTY session should start in /data/local/tmp, got: ${output.take(200)}",
                output.contains("/data/local/tmp")
            )
        }
    }

    @Test
    fun testCreateWithEnvVars() {
        val session = TerminalSession.create(env = mapOf("FURY_TEST_ENV" to "pty_env_val"))
        session.use {
            it.write("echo \$FURY_TEST_ENV\n")
            Thread.sleep(500)
            val data = readWithTimeout(it)
            assertNotNull(data)
            val output = String(data!!)
            assertTrue(
                "PTY session should have custom env, got: ${output.take(200)}",
                output.contains("pty_env_val")
            )
        }
    }

    // =================== Daemon Env Vars + Working Directory ===================

    @Test
    fun testCreateDaemonWithEnvVars() {
        val session = try {
            TerminalSession.createDaemon(
                socketPath = "@ftyd",
                env = mapOf("FURY_DAEMON_TEST" to "daemon_env_val")
            )
        } catch (_: DaemonConnectionException) {
            return // Daemon not running
        }

        session.use {
            it.write("echo \$FURY_DAEMON_TEST\n")
            Thread.sleep(1500)
            val output = StringBuilder()
            for (i in 0 until 8) {
                val data = readWithTimeout(it, 1000) ?: break
                output.append(String(data))
                if (output.contains("daemon_env_val")) break
            }
            assertTrue(
                "Daemon output should contain 'daemon_env_val', got: ${output.take(200)}",
                output.contains("daemon_env_val")
            )
        }
    }

    @Test
    fun testCreateDaemonWithCwd() {
        val session = try {
            TerminalSession.createDaemon(socketPath = "@ftyd", cwd = "/data/local/tmp")
        } catch (_: DaemonConnectionException) {
            return
        }

        session.use {
            it.write("pwd\n")
            Thread.sleep(1500)
            val output = StringBuilder()
            for (i in 0 until 8) {
                val data = readWithTimeout(it, 1000) ?: break
                output.append(String(data))
                if (output.contains("/data/local/tmp")) break
            }
            assertTrue(
                "Daemon cwd should be /data/local/tmp, got: ${output.take(200)}",
                output.contains("/data/local/tmp")
            )
        }
    }

    @Test
    fun testCreateDaemonWithEnvAndCwd() {
        val session = try {
            TerminalSession.createDaemon(
                socketPath = "@ftyd",
                env = mapOf("FURY_DAEMON_DUAL" to "dual_val"),
                cwd = "/data/local/tmp"
            )
        } catch (_: DaemonConnectionException) {
            return
        }

        session.use {
            it.write("echo \$FURY_DAEMON_DUAL && pwd\n")
            Thread.sleep(1500)
            val output = StringBuilder()
            for (i in 0 until 8) {
                val data = readWithTimeout(it, 1000) ?: break
                output.append(String(data))
                if (output.contains("dual_val") && output.contains("/data/local/tmp")) break
            }
            assertTrue(
                "Should contain env var, got: ${output.take(200)}",
                output.contains("dual_val")
            )
            assertTrue(
                "Should contain cwd, got: ${output.take(200)}",
                output.contains("/data/local/tmp")
            )
        }
    }

    @Test
    fun testExecDaemonWithEnvVars() {
        val result = try {
            TerminalSession.exec(
                "echo \$DAEMON_EXEC_VAR",
                socketPath = "@ftyd",
                env = mapOf("DAEMON_EXEC_VAR" to "daemon_exec_env_123")
            )
        } catch (_: DaemonConnectionException) {
            return
        }
        assertTrue(
            "Daemon exec should have env var, got: ${result.output.take(200)}",
            result.output.contains("daemon_exec_env_123")
        )
    }

    @Test
    fun testExecDaemonWithCwd() {
        val result = try {
            TerminalSession.exec("pwd", socketPath = "@ftyd", cwd = "/data/local/tmp")
        } catch (_: DaemonConnectionException) {
            return
        }
        assertTrue(
            "Daemon exec cwd should be /data/local/tmp, got: ${result.output.take(200)}",
            result.output.trim().contains("/data/local/tmp")
        )
    }

    @Test
    fun testExecDaemonWithEnvAndCwd() {
        val result = try {
            TerminalSession.exec(
                "echo \$DAEMON_DUAL_VAR && pwd",
                socketPath = "@ftyd",
                env = mapOf("DAEMON_DUAL_VAR" to "daemon_dual_val"),
                cwd = "/data/local/tmp"
            )
        } catch (_: DaemonConnectionException) {
            return
        }
        assertTrue(result.output.contains("daemon_dual_val"))
        assertTrue(result.output.contains("/data/local/tmp"))
    }

    @Test
    fun testExecSessionDaemonWithEnvAndCwd() {
        val session = try {
            TerminalSession.execSession(
                "echo \$SESS_DAEMON_VAR && pwd",
                socketPath = "@ftyd",
                env = mapOf("SESS_DAEMON_VAR" to "sess_daemon_val"),
                cwd = "/data/local/tmp"
            )
        } catch (_: DaemonConnectionException) {
            return
        }

        session.use {
            val output = it.readAll()
            assertTrue(
                "Session daemon output should have env var, got: ${output.take(200)}",
                output.contains("sess_daemon_val")
            )
            assertTrue(
                "Session daemon output should have cwd, got: ${output.take(200)}",
                output.contains("/data/local/tmp")
            )
        }
    }

    @Test
    fun testExecAsyncDaemonWithEnvAndCwd() = runBlocking {
        val result = try {
            TerminalSession.execAsync(
                "echo \$ASYNC_DAEMON_VAR && pwd",
                socketPath = "@ftyd",
                env = mapOf("ASYNC_DAEMON_VAR" to "async_daemon_val"),
                cwd = "/data/local/tmp"
            )
        } catch (_: DaemonConnectionException) {
            return@runBlocking
        }
        assertTrue(result.output.contains("async_daemon_val"))
        assertTrue(result.output.contains("/data/local/tmp"))
    }

    @Test
    fun testExecDaemonWithMultipleEnvVars() {
        val result = try {
            TerminalSession.exec(
                "echo \$DAEMON_A \$DAEMON_B \$DAEMON_C",
                socketPath = "@ftyd",
                env = mapOf("DAEMON_A" to "alpha", "DAEMON_B" to "beta", "DAEMON_C" to "gamma")
            )
        } catch (_: DaemonConnectionException) {
            return
        }
        assertTrue(result.output.contains("alpha"))
        assertTrue(result.output.contains("beta"))
        assertTrue(result.output.contains("gamma"))
    }

    // =================== Session State (StateFlow) ===================

    @Test
    fun testStateInitiallyRunning() {
        val session = TerminalSession.create()
        assertEquals(SessionState.Running, session.state.value)
        session.close()
    }

    @Test
    fun testStateClosedAfterClose() {
        val session = TerminalSession.create()
        session.close()
        assertEquals(SessionState.Closed, session.state.value)
    }

    @Test
    fun testStateExitedAfterReadAll() {
        val session = TerminalSession.execSession("echo state_test")
        session.readAll()
        val state = session.state.value
        assertTrue(
            "State should be Exited after readAll(), got: $state",
            state is SessionState.Exited
        )
        assertEquals(0, (state as SessionState.Exited).exitCode)
        session.close()
    }

    @Test
    fun testStateExitedThenClosed() {
        val session = TerminalSession.execSession("echo state_lifecycle")
        session.readAll()
        assertTrue(session.state.value is SessionState.Exited)
        session.close()
        assertEquals(SessionState.Closed, session.state.value)
    }

    @Test
    fun testStateExitedViaOutputFlow() = runBlocking {
        val session = TerminalSession.execSession("echo flow_state")
        withTimeoutOrNull(5000L) {
            session.output().toList()
        }
        val state = session.state.value
        assertTrue(
            "State should be Exited after output flow completes, got: $state",
            state is SessionState.Exited
        )
        session.close()
    }

    // =================== readText() ===================

    @Test
    fun testReadText() {
        val session = TerminalSession.create()
        session.write("echo READTEXT_TEST\n")
        Thread.sleep(500)
        val text = session.readText()
        assertNotNull("readText() should return non-null", text)
        assertTrue(text!!.contains("READTEXT_TEST"))
        session.close()
    }

    // =================== readAll(timeout) ===================

    @Test
    fun testReadAllWithTimeout() = runBlocking {
        val session = TerminalSession.execSession("echo timeout_test")
        val output = session.readAll(5.seconds)
        assertTrue(output.contains("timeout_test"))
        session.close()
    }

    // =================== execAsync ===================

    @Test
    fun testExecAsync() = runBlocking {
        val result = TerminalSession.execAsync("echo async_test")
        assertTrue(result.output.contains("async_test"))
        assertEquals(0, result.exitCode)
    }

    @Test
    fun testExecAsyncWithEnvAndCwd() = runBlocking {
        val result = TerminalSession.execAsync(
            "echo \$ASYNC_VAR && pwd",
            env = mapOf("ASYNC_VAR" to "async_env"),
            cwd = "/data/local/tmp"
        )
        assertTrue(result.output.contains("async_env"))
        assertTrue(result.output.contains("/data/local/tmp"))
    }

    // =================== Exit Code for PTY Sessions (#5) ===================

    @Test
    fun testPtySessionExitCode() {
        val session = TerminalSession.create()
        session.write("exit 42\n")
        // Drain output until EOF
        while (readWithTimeout(session, 2000) != null) { /* drain */ }
        Thread.sleep(500)
        val exitCode = session.exitCode
        assertEquals("PTY session exit code should be 42", 42, exitCode)
        session.close()
    }

    @Test
    fun testPtySessionExitCodeZero() {
        val session = TerminalSession.create()
        session.write("exit 0\n")
        while (readWithTimeout(session, 2000) != null) { /* drain */ }
        Thread.sleep(500)
        val exitCode = session.exitCode
        assertEquals("PTY session exit code should be 0", 0, exitCode)
        session.close()
    }

    @Test
    fun testPtySessionExitCodeCachedAfterClose() {
        val session = TerminalSession.create()
        session.write("exit 7\n")
        while (readWithTimeout(session, 2000) != null) { /* drain */ }
        Thread.sleep(500)
        session.close()
        assertEquals("Exit code should be cached after close", 7, session.exitCode)
    }

    // =================== write() Throws on Error (#6) ===================

    @Test
    fun testWriteAfterProcessExitThrowsOrSucceeds() {
        // After the shell exits, write may succeed (kernel buffers) or fail.
        // If it fails, it should throw WriteException, not return -1.
        val session = TerminalSession.execSession("exit 0")
        session.readAll() // wait for exit
        Thread.sleep(200)
        try {
            session.write("should fail or succeed")
            // If no exception, that's ok — kernel may buffer
        } catch (e: WriteException) {
            assertTrue("WriteException bytesOrCode should be negative", e.bytesOrCode < 0)
        } catch (e: SessionClosedException) {
            // Also acceptable if session auto-closed
        }
        session.close()
    }
}
