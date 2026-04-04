package com.furyform.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun TerminalScreen(viewModel: TerminalViewModel = viewModel()) {
    val terminalOutput by viewModel.terminalOutput.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    var commandInput by remember { mutableStateOf("") }
    var daemonMode by remember { mutableStateOf(false) }
    var socketPath by remember { mutableStateOf("@ftyd") }
    val scrollState = rememberLazyListState()

    LaunchedEffect(terminalOutput) {
        if (terminalOutput.isNotEmpty()) {
            scrollState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp)
    ) {
        // Terminal Output
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
        ) {
            LazyColumn(
                state = scrollState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Text(
                        text = terminalOutput,
                        style = TextStyle(
                            color = Color.Green,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        ),
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }

        // Daemon mode toggle + socket path (only when stopped)
        if (!isRunning) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { daemonMode = !daemonMode },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (daemonMode) Color(0xFF225588) else Color(0xFF444444)
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(if (daemonMode) "Daemon" else "Local", fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                if (daemonMode) {
                    BasicTextField(
                        value = socketPath,
                        onValueChange = { socketPath = it },
                        textStyle = TextStyle(color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        cursorBrush = SolidColor(Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                            .background(Color(0xFF222222))
                            .padding(8.dp)
                    )
                }
            }
        }

        // Start/Stop Button
        Button(
            onClick = {
                if (isRunning) viewModel.stopTerminal()
                else viewModel.startTerminal(daemonSocketPath = if (daemonMode) socketPath else null)
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isRunning) Color(0xFF882222) else Color(0xFF228822)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        ) {
            Text(if (isRunning) "Stop Terminal" else "Start Terminal")
        }

        // Signal Buttons Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            // Control chars go through PTY line discipline → signals the foreground process group.
            // Direct kill() only reaches the shell, not children like ping/top/etc.
            data class SignalButton(val label: String, val controlChar: String? = null, val signum: Int? = null)
            val signals = listOf(
                SignalButton("Ctrl+C", "\u0003"),       // INTR  → SIGINT  to foreground pgrp
                SignalButton("Ctrl+Z", "\u001A"),       // SUSP  → SIGTSTP to foreground pgrp
                SignalButton("Ctrl+D", "\u0004"),       // EOF
                SignalButton("Ctrl+\\", "\u001C"),      // QUIT  → SIGQUIT to foreground pgrp
                SignalButton("SIGTERM", signum = 15),   // graceful kill to shell
                SignalButton("SIGKILL", signum = 9),    // force  kill to shell
            )
            signals.forEach { (label, controlChar, signum) ->
                Button(
                    onClick = {
                        if (controlChar != null) {
                            viewModel.sendRaw(controlChar)
                        } else if (signum != null) {
                            viewModel.sendSignal(signum)
                        }
                    },
                    enabled = isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                    modifier = Modifier.padding(end = 6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Command Input Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (commandInput.isNotEmpty() && isRunning) {
                            viewModel.sendCommand(commandInput)
                            commandInput = ""
                        }
                    }
                ),
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF222222))
                    .padding(12.dp),
                decorationBox = { innerTextField ->
                    Box {
                        if (commandInput.isEmpty()) {
                            Text(
                                "Enter command...",
                                color = Color.Gray,
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 14.sp
                                )
                            )
                        }
                        innerTextField()
                    }
                }
            )

            Button(
                onClick = {
                    if (commandInput.isNotEmpty()) {
                        viewModel.sendCommand(commandInput)
                        commandInput = ""
                    }
                },
                enabled = isRunning && commandInput.isNotEmpty(),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text("Send")
            }
        }
    }
}
