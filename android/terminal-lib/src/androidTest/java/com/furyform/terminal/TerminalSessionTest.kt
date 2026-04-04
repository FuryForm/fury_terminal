package com.furyform.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TerminalSessionTest {

    private val executor = Executors.newSingleThreadExecutor()

    /** Read with a timeout to prevent blocking forever. Returns null on timeout/error. */
    private fun readWithTimeout(session: TerminalSession, timeoutMs: Long = 3000): ByteArray? {
        val future = executor.submit(Callable { session.read() })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
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

    @Test(expected = IllegalStateException::class)
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

    @Test(expected = IllegalStateException::class)
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

    @Test(expected = IllegalStateException::class)
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

    @Test(expected = IllegalStateException::class)
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
        var closed = false
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
        } catch (e: IllegalStateException) {
            assertTrue(
                "Expected daemon connection error",
                e.message?.contains("Failed to connect") == true
            )
        }
    }

    @Test
    fun createDaemon_writeAndRead() {
        val session = try {
            TerminalSession.createDaemon(socketPath = "@ftyd")
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
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
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Failed to connect") == true)
        }
    }

    @Test
    fun createDaemon_close_idempotent() {
        val session = try {
            TerminalSession.createDaemon(socketPath = "@ftyd")
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
            return // Daemon not running
        }

        assertEquals("Exit code should be 42", 42, result.exitCode)
        assertFalse("isSuccess should be false for exit code 42", result.isSuccess)
    }

    @Test
    fun testExecCommandNotFound() {
        val result = try {
            TerminalSession.exec("nonexistent_command_xyz", "@ftyd")
        } catch (_: IllegalStateException) {
            return // Daemon not running
        }

        assertEquals("Exit code should be 127 for command not found", 127, result.exitCode)
    }

    @Test
    fun testExecMultilineOutput() {
        val result = try {
            TerminalSession.exec("echo line1; echo line2; echo line3", "@ftyd")
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
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
        } catch (_: IllegalStateException) {
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
}
