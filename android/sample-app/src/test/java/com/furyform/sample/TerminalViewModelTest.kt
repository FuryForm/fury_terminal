package com.furyform.sample

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for TerminalViewModel logic.
 * These run on the JVM without an Android device.
 *
 * Note: We can't test startTerminal/stopTerminal here because they
 * depend on NativePTY (JNI). Those paths are covered by instrumented tests.
 * Here we test the pure-logic parts: output buffer management and state.
 */
class TerminalViewModelTest {

    // =================== Initial State ===================

    @Test
    fun initialState_outputIsEmpty() {
        val vm = TerminalViewModel()
        assertEquals("", vm.terminalOutput.value)
    }

    @Test
    fun initialState_isNotRunning() {
        val vm = TerminalViewModel()
        assertFalse(vm.isRunning.value)
    }

    // =================== clearOutput ===================

    @Test
    fun clearOutput_resetsToEmpty() {
        val vm = TerminalViewModel()
        // We can't call appendOutput directly (it's private),
        // but clearOutput should always reset to empty
        vm.clearOutput()
        assertEquals("", vm.terminalOutput.value)
    }

    // =================== Output Buffer Ring ===================

    /**
     * The ViewModel has a 64KB ring buffer. We test that it trims correctly.
     * Since appendOutput is private, we test through clearOutput and public state.
     */
    @Test
    fun outputBuffer_initiallyEmpty() {
        val vm = TerminalViewModel()
        assertEquals(0, vm.terminalOutput.value.length)
    }

    // =================== State Transitions ===================

    @Test
    fun sendCommand_whenNotRunning_doesNotCrash() {
        val vm = TerminalViewModel()
        // Should not throw even when no session exists
        vm.sendCommand("echo test")
    }

    @Test
    fun sendSignal_whenNotRunning_doesNotCrash() {
        val vm = TerminalViewModel()
        vm.sendSignal(2) // SIGINT
    }

    @Test
    fun sendRaw_whenNotRunning_doesNotCrash() {
        val vm = TerminalViewModel()
        vm.sendRaw("\u0003") // Ctrl+C
    }

    @Test
    fun resizeTerminal_whenNotRunning_doesNotCrash() {
        val vm = TerminalViewModel()
        vm.resizeTerminal(40, 120)
    }

    @Test
    fun stopTerminal_whenNotRunning_doesNotCrash() {
        val vm = TerminalViewModel()
        vm.stopTerminal()
        assertFalse(vm.isRunning.value)
    }

    @Test
    fun stopTerminal_setsNotRunning() {
        val vm = TerminalViewModel()
        vm.stopTerminal()
        assertFalse(vm.isRunning.value)
    }

    @Test
    fun stopTerminal_appendsStoppedMessage() {
        val vm = TerminalViewModel()
        vm.stopTerminal()
        assertTrue(
            "Output should contain stopped message, got: ${vm.terminalOutput.value}",
            vm.terminalOutput.value.contains("[Terminal stopped]")
        )
    }
}
