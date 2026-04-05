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

    private val _execOutput = MutableStateFlow("")
    val execOutput: StateFlow<String> = _execOutput.asStateFlow()

    private val _isExecRunning = MutableStateFlow(false)
    val isExecRunning: StateFlow<Boolean> = _isExecRunning.asStateFlow()

    private var session: TerminalSession? = null
    private var readJob: Job? = null

    private var execSession: TerminalSession? = null
    private var execJob: Job? = null

    fun startTerminal(rows: Int = 24, cols: Int = 80, daemonSocketPath: String? = null, shell: String = "/system/bin/sh") {
        if (_isRunning.value) return  // prevent double-start

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newSession = if (daemonSocketPath != null)
                    TerminalSession.createDaemon(daemonSocketPath, rows, cols, shell)
                else
                    TerminalSession.create(rows, cols, shell)
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

        val s = session
        session = null
        _isRunning.value = false
        appendOutput("\n[Terminal stopped]\n")

        // Close off Main thread — session.close() can block up to 1s
        if (s != null) {
            viewModelScope.launch(Dispatchers.IO) { s.close() }
        }
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

    fun execCommand(command: String, socketPath: String? = null, shell: String = "/system/bin/sh") {
        execJob = viewModelScope.launch(Dispatchers.IO) {
            _isExecRunning.value = true
            try {
                val es = TerminalSession.execSession(command, socketPath, shell)
                execSession = es
                try {
                    val output = es.readAll()
                    val exitCode = es.exitCode
                    val formatted = buildString {
                        if (output.isNotEmpty()) append(output)
                        append("\n[Exit code: $exitCode]\n")
                    }
                    appendExecOutput(formatted)
                } finally {
                    es.close()
                    execSession = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error running exec command", e)
                appendExecOutput("Error: ${e.message}\n")
            } finally {
                _isExecRunning.value = false
            }
        }
    }

    fun execCommandStreaming(command: String, socketPath: String? = null, shell: String = "/system/bin/sh") {
        execJob = viewModelScope.launch(Dispatchers.IO) {
            _isExecRunning.value = true
            try {
                val es = TerminalSession.execSession(command, socketPath, shell)
                execSession = es
                try {
                    es.output().collect { bytes ->
                        val text = String(bytes, Charsets.UTF_8)
                        appendExecOutput(text)
                    }
                    val exitCode = es.exitCode
                    appendExecOutput("\n[Exit code: $exitCode]\n")
                } finally {
                    es.close()
                    execSession = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error running streaming exec command", e)
                appendExecOutput("Error: ${e.message}\n")
            } finally {
                _isExecRunning.value = false
            }
        }
    }

    fun stopExec() {
        execJob?.cancel()
        execJob = null

        val es = execSession
        execSession = null
        _isExecRunning.value = false
        appendExecOutput("\n[Exec stopped]\n")

        // Close off Main thread — session.close() can block up to 1s
        if (es != null) {
            viewModelScope.launch(Dispatchers.IO) { es.close() }
        }
    }

    fun sendExecSignal(signum: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                execSession?.sendSignal(signum)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending signal to exec session", e)
            }
        }
    }

    fun clearExecOutput() {
        _execOutput.update { "" }
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

    private fun appendExecOutput(text: String) {
        _execOutput.update { current ->
            val combined = current + text
            if (combined.length > MAX_OUTPUT_LENGTH) combined.takeLast(MAX_OUTPUT_LENGTH)
            else combined
        }
    }

    override fun onCleared() {
        super.onCleared()
        readJob?.cancel()
        session?.close()
        execJob?.cancel()
        execSession?.close()
    }
}
