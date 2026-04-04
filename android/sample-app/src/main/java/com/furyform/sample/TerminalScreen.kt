package com.furyform.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** Signal/control-char button for the interactive PTY tab. */
private data class InteractiveSignalButton(
    val label: String,
    val controlChar: String? = null,
    val signum: Int? = null
)

/** Signal button for the exec (pipe-based) tab. */
private data class ExecSignalButton(val label: String, val signum: Int)

@Composable
fun TerminalScreen(viewModel: TerminalViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Interactive", "Exec")

    // Stop active sessions when switching tabs
    LaunchedEffect(selectedTab) {
        when (selectedTab) {
            0 -> viewModel.stopExec()
            1 -> viewModel.stopTerminal()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color(0xFF111111),
            contentColor = Color.Green
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = if (selectedTab == index) Color.Green else Color(0xFF888888)
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> InteractiveTab(viewModel)
            1 -> ExecTab(viewModel)
        }
    }
}

@Composable
private fun InteractiveTab(viewModel: TerminalViewModel) {
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
            val signals = listOf(
                InteractiveSignalButton("SIGINT",  "\u0003"),       // Ctrl+C → SIGINT to foreground pgrp
                InteractiveSignalButton("SIGTSTP", "\u001A"),       // Ctrl+Z → SIGTSTP to foreground pgrp
                InteractiveSignalButton("EOF",     "\u0004"),       // Ctrl+D → EOF
                InteractiveSignalButton("SIGQUIT", "\u001C"),       // Ctrl+\ → SIGQUIT to foreground pgrp
                InteractiveSignalButton("SIGTERM", signum = 15),    // kill() to shell
                InteractiveSignalButton("SIGKILL", signum = 9),     // force kill() to shell
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

@Composable
private fun ExecTab(viewModel: TerminalViewModel) {
    val execOutput by viewModel.execOutput.collectAsState()
    val isExecRunning by viewModel.isExecRunning.collectAsState()
    var commandInput by remember { mutableStateOf("") }
    var socketPath by remember { mutableStateOf("@ftyd") }
    val scrollState = rememberLazyListState()

    LaunchedEffect(execOutput) {
        if (execOutput.isNotEmpty()) {
            scrollState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp)
    ) {
        // Exec Output
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
                        text = execOutput.ifEmpty { "[No output yet]" },
                        style = TextStyle(
                            color = if (execOutput.isEmpty()) Color(0xFF555555) else Color.Green,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp
                        ),
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }
        }

        // Running indicator
        if (isExecRunning) {
            Text(
                text = "⏳ Running...",
                color = Color(0xFFFFAA00),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }

        // Signal Buttons Row (visible when streaming exec is running)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp)
        ) {
            val signals = listOf(
                ExecSignalButton("SIGINT",  2),
                ExecSignalButton("SIGTERM", 15),
                ExecSignalButton("SIGKILL", 9),
                ExecSignalButton("SIGHUP",  1),
                ExecSignalButton("SIGQUIT", 3),
            )
            signals.forEach { (label, signum) ->
                Button(
                    onClick = { viewModel.sendExecSignal(signum) },
                    enabled = isExecRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
                    modifier = Modifier.padding(end = 6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Socket path input
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Socket:",
                color = Color(0xFF888888),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 8.dp)
            )
            BasicTextField(
                value = socketPath,
                onValueChange = { socketPath = it },
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF222222))
                    .padding(8.dp)
            )
        }

        // Command input
        BasicTextField(
            value = commandInput,
            onValueChange = { commandInput = it },
            textStyle = TextStyle(
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF222222))
                .padding(12.dp),
            decorationBox = { innerTextField ->
                Box {
                    if (commandInput.isEmpty()) {
                        Text(
                            "Enter command to execute...",
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

        // Run / Stream / Stop buttons row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (commandInput.isNotEmpty()) {
                        viewModel.execCommand(commandInput, socketPath)
                    }
                },
                enabled = !isExecRunning && commandInput.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF225522)),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                Text("Run", fontFamily = FontFamily.Monospace)
            }

            Button(
                onClick = {
                    if (commandInput.isNotEmpty()) {
                        viewModel.execCommandStreaming(commandInput, socketPath)
                    }
                },
                enabled = !isExecRunning && commandInput.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF225588)),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
            ) {
                Text("Stream", fontFamily = FontFamily.Monospace)
            }

            Button(
                onClick = { viewModel.stopExec() },
                enabled = isExecRunning,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF882222)),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) {
                Text("Stop", fontFamily = FontFamily.Monospace)
            }
        }

        // Clear button
        Button(
            onClick = { viewModel.clearExecOutput() },
            enabled = execOutput.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF444444)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("Clear", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}
