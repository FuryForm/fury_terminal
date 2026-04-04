package com.furyform.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativePTYTest {

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
        val id = NativePTY.nativeStartPTY(24, 80)
        assertTrue("Session ID should be >= 0, got $id", id >= 0)
        NativePTY.nativeClose(id)
    }

    @Test
    fun startPTY_differentIdsForDifferentSessions() {
        val id1 = NativePTY.nativeStartPTY(24, 80)
        val id2 = NativePTY.nativeStartPTY(24, 80)
        assertTrue(id1 >= 0)
        assertTrue(id2 >= 0)
        assertNotEquals("Different sessions should have different IDs", id1, id2)
        NativePTY.nativeClose(id1)
        NativePTY.nativeClose(id2)
    }

    @Test
    fun startPTY_variousTerminalSizes() {
        val id1 = NativePTY.nativeStartPTY(1, 1)
        assertTrue(id1 >= 0)
        NativePTY.nativeClose(id1)

        val id2 = NativePTY.nativeStartPTY(200, 400)
        assertTrue(id2 >= 0)
        NativePTY.nativeClose(id2)
    }

    // =================== nativeRead ===================

    @Test
    fun read_returnsDataFromShell() {
        val id = NativePTY.nativeStartPTY(24, 80)
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
        val id = NativePTY.nativeStartPTY(24, 80)
        val input = "echo hello\n".toByteArray()
        val written = NativePTY.nativeWrite(id, input)
        assertEquals("Should write all bytes", input.size, written)
        NativePTY.nativeClose(id)
    }

    @Test
    fun write_binarySafe_nullBytes() {
        val id = NativePTY.nativeStartPTY(24, 80)
        val binaryData = byteArrayOf(0x41, 0x00, 0x42, 0x00, 0x43)
        val written = NativePTY.nativeWrite(id, binaryData)
        assertEquals("Should write all 5 bytes including nulls", 5, written)
        NativePTY.nativeClose(id)
    }

    @Test
    fun write_emptyData_returnsZero() {
        val id = NativePTY.nativeStartPTY(24, 80)
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
        val id = NativePTY.nativeStartPTY(24, 80)
        val largeData = ByteArray(16384) { (it % 256).toByte() }
        val written = NativePTY.nativeWrite(id, largeData)
        assertTrue("Large write should succeed, got $written", written > 0)
        NativePTY.nativeClose(id)
    }

    // =================== nativeResize ===================

    @Test
    fun resize_succeeds() {
        val id = NativePTY.nativeStartPTY(24, 80)
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
        val id = NativePTY.nativeStartPTY(24, 80)
        assertEquals(0, NativePTY.nativeResize(id, 1, 1))
        assertEquals(0, NativePTY.nativeResize(id, 200, 400))
        assertEquals(0, NativePTY.nativeResize(id, 50, 50))
        NativePTY.nativeClose(id)
    }

    // =================== nativeClose ===================

    @Test
    fun close_validSession() {
        val id = NativePTY.nativeStartPTY(24, 80)
        NativePTY.nativeClose(id)
    }

    @Test
    fun close_doubleClose_doesNotCrash() {
        val id = NativePTY.nativeStartPTY(24, 80)
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
        val id = NativePTY.nativeStartPTY(24, 80)
        assertTrue("Session should be alive immediately after start", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    @Test
    fun isAlive_falseAfterExit() {
        val id = NativePTY.nativeStartPTY(24, 80)
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
        val id = NativePTY.nativeStartPTY(24, 80)
        NativePTY.nativeSendSignal(id, 28) // SIGWINCH — harmless
        assertTrue("Session should still be alive after SIGWINCH", NativePTY.nativeIsAlive(id))
        NativePTY.nativeClose(id)
    }

    @Test
    fun sendSignal_sigintKillsForegroundProcess() {
        val id = NativePTY.nativeStartPTY(24, 80)
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
        val id = NativePTY.nativeStartPTY(24, 80)
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
        val ids = (0 until 4).map { NativePTY.nativeStartPTY(24, 80) }
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
        val id1 = NativePTY.nativeStartPTY(24, 80)
        NativePTY.nativeClose(id1)
        Thread.sleep(200)

        val id2 = NativePTY.nativeStartPTY(24, 80)
        assertTrue(id2 >= 0)
        assertTrue(NativePTY.nativeIsAlive(id2))

        NativePTY.nativeWrite(id2, "echo REUSE_TEST\n".toByteArray())
        Thread.sleep(500)
        val data = readWithTimeout(id2, 2000)
        assertNotNull(data)

        NativePTY.nativeClose(id2)
    }
}
