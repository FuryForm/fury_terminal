package com.furyform.sample

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.furyform.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TerminalViewModel : ViewModel() {

    companion object {
        private const val TAG = "TerminalViewModel"
        private const val MAX_OUTPUT_LENGTH = 64 * 1024 // 64KB ring buffer
    }

    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var session: TerminalSession? = null
    private var readJob: Job? = null

    fun startTerminal(rows: Int = 24, cols: Int = 80, daemonSocketPath: String? = null) {
        if (_isRunning.value) return  // prevent double-start

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newSession = if (daemonSocketPath != null)
                    TerminalSession.createDaemon(daemonSocketPath, rows, cols)
                else
                    TerminalSession.create(rows, cols)
                session = newSession
                _isRunning.value = true

                appendOutput("Terminal started\n")

                // Use Flow-based output reading (blocking read, no polling delay)
                readJob = launch {
                    newSession.output().collect { bytes ->
                        val text = String(bytes, Charsets.UTF_8)
                        withContext(Dispatchers.Main) {
                            appendOutput(text)
                        }
                    }
                    // Flow completed = process exited
                    withContext(Dispatchers.Main) {
                        appendOutput("\n[Process exited]\n")
                        _isRunning.value = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting terminal", e)
                withContext(Dispatchers.Main) {
                    appendOutput("Error: ${e.message}\n")
                }
            }
        }
    }

    fun stopTerminal() {
        readJob?.cancel()
        readJob = null

        session?.close()
        session = null

        _isRunning.value = false
        appendOutput("\n[Terminal stopped]\n")
    }

    fun sendSignal(signum: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            session?.sendSignal(signum)
        }
    }

    fun sendRaw(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            session?.write(text)
        }
    }

    fun sendCommand(command: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = session ?: return@launch
            if (!_isRunning.value) return@launch

            val cmd = if (command.endsWith('\n')) command else "$command\n"
            s.write(cmd)
        }
    }

    fun clearOutput() {
        _terminalOutput.update { "" }
    }

    fun resizeTerminal(rows: Int, cols: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            session?.resize(rows, cols)
        }
    }

    private fun appendOutput(text: String) {
        _terminalOutput.update { current ->
            val combined = current + text
            if (combined.length > MAX_OUTPUT_LENGTH) combined.takeLast(MAX_OUTPUT_LENGTH)
            else combined
        }
    }

    override fun onCleared() {
        super.onCleared()
        readJob?.cancel()
        session?.close()
    }
}
