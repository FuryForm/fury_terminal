package com.furyform.sample

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.furyform.terminal.DaemonSessionInfo
import com.furyform.terminal.SessionState
import com.furyform.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TerminalViewModel : ViewModel() {

    companion object {
        private const val TAG = "TerminalViewModel"
        private const val MAX_OUTPUT_LENGTH = 64 * 1024 // 64KB ring buffer
    }

    // =================== State ===================

    private val _terminalOutput = MutableStateFlow("")
    val terminalOutput: StateFlow<String> = _terminalOutput.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _execOutput = MutableStateFlow("")
    val execOutput: StateFlow<String> = _execOutput.asStateFlow()

    private val _isExecRunning = MutableStateFlow(false)
    val isExecRunning: StateFlow<Boolean> = _isExecRunning.asStateFlow()

    private val _sessionState = MutableStateFlow("Idle")
    val sessionState: StateFlow<String> = _sessionState.asStateFlow()

    private val _daemonSessions = MutableStateFlow<List<DaemonSessionInfo>>(emptyList())
    val daemonSessions: StateFlow<List<DaemonSessionInfo>> = _daemonSessions.asStateFlow()

    @Volatile private var session: TerminalSession? = null
    @Volatile private var readJob: Job? = null

    @Volatile private var execSession: TerminalSession? = null
    @Volatile private var execJob: Job? = null

    // =================== Interactive Terminal ===================

    fun startTerminal(
        rows: Int = 24,
        cols: Int = 80,
        daemonSocketPath: String? = null,
        shell: String = "/system/bin/sh",
        env: Map<String, String>? = null,
        cwd: String? = null
    ) {
        if (_isRunning.value) return  // prevent double-start

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newSession = if (daemonSocketPath != null)
                    TerminalSession.createDaemon(daemonSocketPath, rows, cols, shell, env, cwd)
                else
                    TerminalSession.create(rows, cols, shell, env, cwd)
                session = newSession

                // Observe session lifecycle state
                launch {
                    newSession.state.collect { state ->
                        val label = when (state) {
                            is SessionState.Running -> "Running"
                            is SessionState.Exited -> "Exited (${state.exitCode})"
                            is SessionState.Closed -> "Closed"
                        }
                        _sessionState.value = label
                    }
                }

                _isRunning.value = true
                appendOutput("Terminal started\n")

                // Use Flow-based output reading (blocking read, no polling delay)
                readJob = launch {
                    newSession.outputText().collect { text ->
                        appendOutput(text)
                    }
                    // Flow completed = process exited
                    appendOutput("\n[Process exited]\n")
                    _isRunning.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting terminal", e)
                appendOutput("Error: ${e.message}\n")
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
        _sessionState.value = "Idle"

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

    // =================== Exec ===================

    fun execCommand(
        command: String,
        socketPath: String? = null,
        shell: String = "/system/bin/sh",
        env: Map<String, String>? = null,
        cwd: String? = null
    ) {
        execJob = viewModelScope.launch(Dispatchers.IO) {
            _isExecRunning.value = true
            try {
                val es = TerminalSession.execSession(command, socketPath, shell, env, cwd)
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

    fun execCommandStreaming(
        command: String,
        socketPath: String? = null,
        shell: String = "/system/bin/sh",
        env: Map<String, String>? = null,
        cwd: String? = null
    ) {
        execJob = viewModelScope.launch(Dispatchers.IO) {
            _isExecRunning.value = true
            try {
                val es = TerminalSession.execSession(command, socketPath, shell, env, cwd)
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

    // =================== Daemon Sessions ===================

    fun refreshDaemonSessions(socketPath: String = "@ftyd") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessions = TerminalSession.listSessions(socketPath)
                _daemonSessions.value = sessions
            } catch (e: Exception) {
                Log.e(TAG, "Error listing daemon sessions", e)
                _daemonSessions.value = emptyList()
            }
        }
    }

    fun resizeTerminal(rows: Int, cols: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            session?.resize(rows, cols)
        }
    }

    // =================== Output Buffer ===================

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

    // =================== Lifecycle ===================

    override fun onCleared() {
        super.onCleared()
        readJob?.cancel()
        session?.close()
        execJob?.cancel()
        execSession?.close()
        _sessionState.value = "Idle"
    }
}
