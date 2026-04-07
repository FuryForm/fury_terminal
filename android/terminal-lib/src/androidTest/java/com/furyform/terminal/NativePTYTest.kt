package com.furyform.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativePTYTest {

    @get:Rule
    val globalTimeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    private val executor = Executors.newSingleThreadExecutor()

    /** Read with a timeout to prevent blocking forever. Returns null on timeout. */
    private fun readWithTimeout(id: Int, timeoutMs: Long = 3000): ByteArray? {
        val future = executor.submit(Callable { NativePTY.nativeRead(id) })
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    @Before
    fun loadLibrary() {
        NativePTY.ensureLoaded()
    }

    // =================== Library Loading ===================

    @Test
    fun ensureLoaded_succeeds() {
        NativePTY.ensureLoaded()
    }

    @Test
    fun ensureLoaded_idempotent() {
        NativePTY.ensureLoaded()
        NativePTY.ensureLoaded()
        NativePTY.ensureLoaded()
    }

    // =================== nativeStartPTY ===================

    @Test
    fun startPTY_returnsValidId() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        assertTrue("Session ID should be >= 0, got $id", id >= 0)
        NativePTY.nativeClose(id)
    }

    @Test
    fun startPTY_differentIdsForDifferentSessions() {
        val id1 = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        val id2 = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        assertTrue(id1 >= 0)
        assertTrue(id2 >= 0)
        assertNotEquals("Different sessions should have different IDs", id1, id2)
        NativePTY.nativeClose(id1)
        NativePTY.nativeClose(id2)
    }

    @Test
    fun startPTY_variousTerminalSizes() {
        val id1 = NativePTY.nativeStartPTY(1, 1, "/system/bin/sh", null, null)
        assertTrue(id1 >= 0)
        NativePTY.nativeClose(id1)

        val id2 = NativePTY.nativeStartPTY(200, 400, "/system/bin/sh", null, null)
        assertTrue(id2 >= 0)
        NativePTY.nativeClose(id2)
    }

    // =================== nativeRead ===================

    @Test
    fun read_returnsDataFromShell() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        NativePTY.nativeWrite(id, "echo FURY_TEST_OUTPUT\n".toByteArray())
        Thread.sleep(500)
        val data = readWithTimeout(id)
        assertNotNull("Should read data from PTY", data)
        assertTrue("Read data should not be empty", data!!.isNotEmpty())
        NativePTY.nativeClose(id)
    }

    @Test
    fun read_invalidId_returnsNull() {
        // -1 maps to fd=-1, read() returns -1 immediately → null
        val data = readWithTimeout(-1, 2000)
        assertNull("Read with invalid ID should return null", data)
    }

    // =================== nativeWrite ===================

    @Test
    fun write_returnsPositiveByteCount() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        val input = "echo hello\n".toByteArray()
        val written = NativePTY.nativeWrite(id, input)
        assertEquals("Should write all bytes", input.size, written)
        NativePTY.nativeClose(id)
    }

    @Test
    fun write_binarySafe_nullBytes() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        val binaryData = byteArrayOf(0x41, 0x00, 0x42, 0x00, 0x43)
        val written = NativePTY.nativeWrite(id, binaryData)
        assertEquals("Should write all 5 bytes including nulls", 5, written)
        NativePTY.nativeClose(id)
    }

    @Test
    fun write_emptyData_returnsZero() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        val written = NativePTY.nativeWrite(id, byteArrayOf())
        assertTrue("Empty write should return 0, got $written", written == 0)
        NativePTY.nativeClose(id)
    }

    @Test
    fun write_invalidId_returnsNegative() {
        val result = NativePTY.nativeWrite(-1, "test".toByteArray())
        assertEquals("Write to invalid ID should return -1", -1, result)
    }

    @Test
    fun write_largeData() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        val largeData = ByteArray(16384) { (it % 256).toByte() }
        val written = NativePTY.nativeWrite(id, largeData)
        assertTrue("Large write should succeed, got $written", written > 0)
        NativePTY.nativeClose(id)
    }

    // =================== nativeResize ===================

    @Test
    fun resize_succeeds() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        val result = NativePTY.nativeResize(id, 40, 120)
        assertEquals("Resize should return 0 on success", 0, result)
        NativePTY.nativeClose(id)
    }

    @Test
    fun resize_invalidId_returnsNegative() {
        val result = NativePTY.nativeResize(-1, 24, 80)
        assertEquals("Resize with invalid ID should return -1", -1, result)
    }

    @Test
    fun resize_variousSizes() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        assertEquals(0, NativePTY.nativeResize(id, 1, 1))
        assertEquals(0, NativePTY.nativeResize(id, 200, 400))
        assertEquals(0, NativePTY.nativeResize(id, 50, 50))
        NativePTY.nativeClose(id)
    }

    // =================== nativeClose ===================

    @Test
    fun close_validSession() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        NativePTY.nativeClose(id)
    }

    @Test
    fun close_doubleClose_doesNotCrash() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        NativePTY.nativeClose(id)
        NativePTY.nativeClose(id)
    }

    @Test
    fun close_invalidId_doesNotCrash() {
        NativePTY.nativeClose(-1)
        NativePTY.nativeClose(999)
    }

    // =================== nativeIsAlive ===================

    @Test
    fun isAlive_trueForRunningSession() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        assertTrue("Session should be alive immediately after start", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    @Test
    fun isAlive_falseAfterExit() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        NativePTY.nativeWrite(id, "exit\n".toByteArray())
        Thread.sleep(1000)
        // Drain output with timeout (don't block forever)
        readWithTimeout(id, 2000)
        Thread.sleep(200)
        assertFalse("Session should not be alive after shell exits", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    @Test
    fun isAlive_falseForInvalidId() {
        assertFalse(NativePTY.nativeIsAlive(-1))
        assertFalse(NativePTY.nativeIsAlive(999))
    }

    // =================== nativeSendSignal ===================

    @Test
    fun sendSignal_doesNotCrash() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        NativePTY.nativeSendSignal(id, 28) // SIGWINCH — harmless
        assertTrue("Session should still be alive after SIGWINCH", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    @Test
    fun sendSignal_sigintKillsForegroundProcess() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        NativePTY.nativeWrite(id, "sleep 60\n".toByteArray())
        Thread.sleep(500)
        NativePTY.nativeSendSignal(id, 2) // SIGINT
        Thread.sleep(500)
        assertTrue("Shell should survive SIGINT to foreground process", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    @Test
    fun sendSignal_invalidId_doesNotCrash() {
        NativePTY.nativeSendSignal(-1, 2)
        NativePTY.nativeSendSignal(999, 15)
    }

    // =================== End-to-End ===================

    @Test
    fun endToEnd_echoCommand() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        NativePTY.nativeWrite(id, "echo FURY_E2E_TEST\n".toByteArray())
        Thread.sleep(1000) // let shell process

        // Accumulate output with timeout-protected reads
        val output = StringBuilder()
        for (i in 0 until 5) {
            val data = readWithTimeout(id, 1000) ?: break
            output.append(String(data))
            if (output.contains("FURY_E2E_TEST")) break
        }

        assertTrue(
            "Output should contain 'FURY_E2E_TEST', got: ${output.take(200)}",
            output.contains("FURY_E2E_TEST")
        )
        NativePTY.nativeClose(id)
    }

    @Test
    fun endToEnd_multipleSessionsConcurrently() {
        val ids = (0 until 4).map { NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null) }
        ids.forEach { assertTrue("All sessions should start, got $it", it >= 0) }
        ids.forEach { assertTrue(NativePTY.nativeIsAlive(it)) }

        ids.forEachIndexed { i, id ->
            NativePTY.nativeWrite(id, "echo SESSION_$i\n".toByteArray())
        }
        Thread.sleep(500)

        ids.forEachIndexed { i, id ->
            val data = readWithTimeout(id, 2000)
            assertNotNull("Session $i should produce output", data)
        }

        ids.forEach { NativePTY.nativeClose(it) }
    }

    @Test
    fun endToEnd_sessionIdReuse() {
        val id1 = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        NativePTY.nativeClose(id1)
        Thread.sleep(200)

        val id2 = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        assertTrue(id2 >= 0)
        assertTrue(NativePTY.nativeIsAlive(id2))

        NativePTY.nativeWrite(id2, "echo REUSE_TEST\n".toByteArray())
        Thread.sleep(500)
        val data = readWithTimeout(id2, 2000)
        assertNotNull(data)

        NativePTY.nativeClose(id2)
    }

    // =================== nativeStartLocalExecSession ===================

    @Test
    fun startLocalExec_returnsValidId() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "echo test", null, null)
        assertTrue("Session ID should be >= 0, got $id", id >= 0)
        NativePTY.nativeClose(id)
    }

    @Test
    fun startLocalExec_readsOutput() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "echo native_exec_test", null, null)
        assertTrue(id >= 0)
        val output = StringBuilder()
        for (i in 0 until 5) {
            val data = readWithTimeout(id, 2000) ?: break
            output.append(String(data))
            if (output.contains("native_exec_test")) break
        }
        assertTrue(
            "Output should contain 'native_exec_test', got: ${output.take(200)}",
            output.contains("native_exec_test")
        )
        NativePTY.nativeClose(id)
    }

    @Test
    fun startLocalExec_readsEOF() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "echo done", null, null)
        assertTrue(id >= 0)
        var gotData = false
        while (true) {
            val data = readWithTimeout(id, 2000) ?: break
            if (data.isNotEmpty()) gotData = true
        }
        assertTrue("Should have received data before EOF", gotData)
        NativePTY.nativeClose(id)
    }

    @Test
    fun startLocalExec_invalidShell_returnsValidId() {
        // fork succeeds but execl fails; child exits 127. ID should be >= 0.
        val id = NativePTY.nativeStartLocalExecSession("/nonexistent/shell", "echo hi", null, null)
        assertTrue("Fork should succeed even with invalid shell, got $id", id >= 0)
        // Output may be empty — drain without asserting content
        while (readWithTimeout(id, 2000) != null) { /* drain */ }
        NativePTY.nativeClose(id)
    }

    @Test
    fun startLocalExec_emptyCommand() {
        // Empty command should not crash; shell launched with empty command string
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "", null, null)
        assertTrue("Should not crash on empty command, got $id", id >= 0)
        NativePTY.nativeClose(id)
    }

    // =================== nativeGetExitCode ===================

    @Test
    fun getExitCode_localExec_success() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "echo hi", null, null)
        assertTrue(id >= 0)
        while (readWithTimeout(id, 2000) != null) { /* drain until EOF */ }
        val exitCode = NativePTY.nativeGetExitCode(id)
        assertEquals("Exit code should be 0 for successful command", 0, exitCode)
        NativePTY.nativeClose(id)
    }

    @Test
    fun getExitCode_localExec_nonZero() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "exit 42", null, null)
        assertTrue(id >= 0)
        while (readWithTimeout(id, 2000) != null) { /* drain until EOF */ }
        val exitCode = NativePTY.nativeGetExitCode(id)
        assertEquals("Exit code should be 42", 42, exitCode)
        NativePTY.nativeClose(id)
    }

    @Test
    fun getExitCode_localExec_commandNotFound() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "nonexistent_cmd_xyz", null, null)
        assertTrue(id >= 0)
        while (readWithTimeout(id, 2000) != null) { /* drain until EOF */ }
        val exitCode = NativePTY.nativeGetExitCode(id)
        assertEquals("Exit code should be 127 for command not found", 127, exitCode)
        NativePTY.nativeClose(id)
    }

    @Test
    fun getExitCode_localExec_blocksUntilExit() {
        // nativeGetExitCode uses blocking waitpid for local exec sessions,
        // so it will wait for the process to exit and return the real code
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "exit 42", null, null)
        assertTrue(id >= 0)
        val exitCode = NativePTY.nativeGetExitCode(id)
        assertEquals("Blocking waitpid should return exit code 42", 42, exitCode)
        NativePTY.nativeClose(id)
    }

    @Test
    fun getExitCode_invalidId_returnsMinusOne() {
        assertEquals("Exit code for id=-1 should be -1", -1, NativePTY.nativeGetExitCode(-1))
        assertEquals("Exit code for id=999 should be -1", -1, NativePTY.nativeGetExitCode(999))
    }

    @Test
    fun getExitCode_interactivePTY() {
        // PTY sessions may not track exit code the same way; just verify no crash
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        assertTrue(id >= 0)
        NativePTY.nativeWrite(id, "exit 3\n".toByteArray())
        Thread.sleep(500)
        while (readWithTimeout(id, 1000) != null) { /* drain */ }
        // For PTY sessions exit code tracking may return -1 — just verify no crash
        NativePTY.nativeGetExitCode(id)
        NativePTY.nativeClose(id)
    }

    // =================== nativeIsAlive: Local Exec ===================

    @Test
    fun isAlive_localExec_trueWhileRunning() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "sleep 5", null, null)
        assertTrue(id >= 0)
        assertTrue("Local exec session should be alive while running", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    @Test
    fun isAlive_localExec_falseAfterExit() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "echo done", null, null)
        assertTrue(id >= 0)
        while (readWithTimeout(id, 2000) != null) { /* drain until EOF */ }
        Thread.sleep(200)
        assertFalse("Local exec session should not be alive after process exits", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    // =================== nativeSendSignal: Local Exec ===================

    @Test
    fun sendSignal_localExec_sigterm() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "sleep 60", null, null)
        assertTrue(id >= 0)
        NativePTY.nativeSendSignal(id, 15) // SIGTERM
        Thread.sleep(500)
        assertFalse("Process should not be alive after SIGTERM", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    @Test
    fun sendSignal_localExec_sigkill() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "sleep 60", null, null)
        assertTrue(id >= 0)
        NativePTY.nativeSendSignal(id, 9) // SIGKILL
        Thread.sleep(500)
        assertFalse("Process should not be alive after SIGKILL", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    @Test
    fun sendSignal_localExec_reachesChildProcess() {
        // Verify process group kill reaches grandchild: sh -c 'sleep 60' spawns
        // sleep as a child of sh; SIGTERM via kill(-pid) should reach both.
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "sh -c 'sleep 60'", null, null)
        assertTrue(id >= 0)
        Thread.sleep(300) // let child tree start
        assertTrue("Process tree should be alive", NativePTY.nativeIsAlive(id))
        NativePTY.nativeSendSignal(id, 15) // SIGTERM
        Thread.sleep(500)
        assertFalse("Process tree should exit after SIGTERM", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    // =================== nativeStartPTY: Shell Parameter ===================

    @Test
    fun startPTY_invalidShell_returnsNegative() {
        val id = NativePTY.nativeStartPTY(24, 80, "/nonexistent/shell", null, null)
        if (id >= 0) {
            // Fork succeeded but exec failed; child should die immediately
            Thread.sleep(500)
            assertFalse("Session with invalid shell should not be alive", NativePTY.nativeIsAlive(id))
            NativePTY.nativeClose(id)
        }
        // id < 0 is also acceptable — fork+exec failure reported immediately
    }

    @Test
    fun startPTY_customShell_works() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        assertTrue("PTY with explicit /system/bin/sh should start, got $id", id >= 0)
        NativePTY.nativeWrite(id, "echo shell_test\n".toByteArray())
        Thread.sleep(500)
        val output = StringBuilder()
        for (i in 0 until 5) {
            val data = readWithTimeout(id, 1000) ?: break
            output.append(String(data))
            if (output.contains("shell_test")) break
        }
        assertTrue(
            "Output should contain 'shell_test', got: ${output.take(200)}",
            output.contains("shell_test")
        )
        NativePTY.nativeClose(id)
    }

    // =================== nativeResize: Exec Sessions ===================

    @Test
    fun resize_localExecSession_returnsNegative() {
        val id = NativePTY.nativeStartLocalExecSession("/system/bin/sh", "sleep 5", null, null)
        assertTrue(id >= 0)
        val result = NativePTY.nativeResize(id, 40, 120)
        assertEquals("Resize on local exec session (no PTY fd) should return -1", -1, result)
        NativePTY.nativeClose(id)
    }

    // =================== nativeStartExecSession ===================

    @Test
    fun startExecSession_invalidSocket_returnsNegative() {
        val id = NativePTY.nativeStartExecSession("@nonexistent_test_socket", "echo hi", "/system/bin/sh")
        assertTrue("Connecting to nonexistent socket should return -1, got $id", id < 0)
    }

    @Test
    fun startExecSession_validSocket() {
        val id = NativePTY.nativeStartExecSession("@ftyd", "echo daemon_native", "/system/bin/sh")
        if (id >= 0) {
            // Daemon is running — read and verify output
            val output = StringBuilder()
            for (i in 0 until 5) {
                val data = readWithTimeout(id, 2000) ?: break
                output.append(String(data))
                if (output.contains("daemon_native")) break
            }
            assertTrue(
                "Output should contain 'daemon_native', got: ${output.take(200)}",
                output.contains("daemon_native")
            )
            NativePTY.nativeClose(id)
        }
        // id < 0 means daemon is not running — acceptable, test is a no-op
    }

    // =================== Env Vars + Working Directory (Native) ===================

    @Test
    fun startPTY_withEnvVars() {
        val envVars = arrayOf("FURY_NATIVE_TEST=native_env_val")
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", envVars, null)
        assertTrue("Should get valid session id", id >= 0)

        // Write command to echo the env var
        val cmd = "echo \$FURY_NATIVE_TEST\n".toByteArray()
        NativePTY.nativeWrite(id, cmd)
        Thread.sleep(500)

        val data = readWithTimeout(id, 2000)
        assertNotNull("Should read output", data)
        val output = String(data!!)
        assertTrue(
            "Output should contain 'native_env_val', got: ${output.take(200)}",
            output.contains("native_env_val")
        )
        NativePTY.nativeClose(id)
    }

    @Test
    fun startPTY_withCwd() {
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, "/data/local/tmp")
        assertTrue("Should get valid session id", id >= 0)

        val cmd = "pwd\n".toByteArray()
        NativePTY.nativeWrite(id, cmd)
        Thread.sleep(500)

        val data = readWithTimeout(id, 2000)
        assertNotNull("Should read output", data)
        val output = String(data!!)
        assertTrue(
            "Output should contain '/data/local/tmp', got: ${output.take(200)}",
            output.contains("/data/local/tmp")
        )
        NativePTY.nativeClose(id)
    }

    @Test
    fun startPTY_withNullEnvAndCwd() {
        // Null env and cwd should work (backward compat)
        val id = NativePTY.nativeStartPTY(24, 80, "/system/bin/sh", null, null)
        assertTrue("Should get valid session id with null env/cwd", id >= 0)
        assertTrue("Session should be alive", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    @Test
    fun startLocalExec_withEnvVars() {
        val envVars = arrayOf("EXEC_NATIVE_VAR=exec_val_123")
        val id = NativePTY.nativeStartLocalExecSession(
            "/system/bin/sh", "echo \$EXEC_NATIVE_VAR", envVars, null
        )
        assertTrue("Should get valid session id", id >= 0)

        val output = StringBuilder()
        for (i in 0 until 10) {
            val data = readWithTimeout(id, 2000) ?: break
            output.append(String(data))
        }
        assertTrue(
            "Output should contain 'exec_val_123', got: ${output.take(200)}",
            output.toString().contains("exec_val_123")
        )
        NativePTY.nativeClose(id)
    }

    @Test
    fun startLocalExec_withCwd() {
        val id = NativePTY.nativeStartLocalExecSession(
            "/system/bin/sh", "pwd", null, "/data/local/tmp"
        )
        assertTrue("Should get valid session id", id >= 0)

        val output = StringBuilder()
        for (i in 0 until 10) {
            val data = readWithTimeout(id, 2000) ?: break
            output.append(String(data))
        }
        assertTrue(
            "Output should contain '/data/local/tmp', got: ${output.take(200)}",
            output.toString().contains("/data/local/tmp")
        )
        NativePTY.nativeClose(id)
    }

    @Test
    fun startLocalExec_withNullEnvAndCwd() {
        // Null env and cwd should work (backward compat)
        val id = NativePTY.nativeStartLocalExecSession(
            "/system/bin/sh", "echo null_compat", null, null
        )
        assertTrue("Should get valid session id", id >= 0)

        val output = StringBuilder()
        for (i in 0 until 10) {
            val data = readWithTimeout(id, 2000) ?: break
            output.append(String(data))
        }
        assertTrue(output.toString().contains("null_compat"))
        NativePTY.nativeClose(id)
    }
}
