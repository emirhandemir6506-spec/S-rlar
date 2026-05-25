package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.api.AiProvider
import com.example.db.TerminalLog
import com.example.ui.TerminalViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    TerminalScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = viewModel()
) {
    val logs by viewModel.logs.collectAsState()
    val activeProvider by viewModel.currentProvider.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var inputState by remember { mutableStateOf("") }
    val lazyListState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Automatically scroll to the end on new logs
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            try {
                lazyListState.scrollToItem(logs.size - 1)
            } catch (e: Exception) {
                // Ignore safe fallback
            }
        }
    }

    // Terminal colors
    val terminalColorMap = mapOf(
        "bg" to Color(0xFF030612),
        "green" to Color(0xFF00FF66),
        "cyan" to Color(0xFF00E5FF),
        "amber" to Color(0xFFFFA726),
        "red" to Color(0xFFFF5252),
        "text_gray" to Color(0xFFCFD8DC),
        "card_bg" to Color(0xFF0D1224)
    )

    Column(
        modifier = modifier
            .background(terminalColorMap["bg"]!!)
            .imePadding()
    ) {
        // --- Custom Terminal Top Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(terminalColorMap["card_bg"]!!)
                .padding(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                    bottom = 8.dp,
                    start = 16.dp,
                    end = 16.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Terminal Logo Indicator
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isLoading) terminalColorMap["amber"]!! else terminalColorMap["green"]!!)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AI_TERMINAL://root",
                    color = terminalColorMap["green"]!!,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1
                )
            }

            // Provider indicator chip
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF1E2640))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = activeProvider.displayName,
                    color = terminalColorMap["cyan"]!!,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        // --- Terminal Logs Console Output Window ---
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            state = lazyListState
        ) {
            items(logs) { log ->
                TerminalLogEntry(log = log, colors = terminalColorMap)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        // Loader indicator inside console
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = terminalColorMap["amber"]!!,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "[AI IS THINKING / SYSTEM RUNNING...]",
                    color = terminalColorMap["amber"]!!,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // --- Quick Action Suggestion Chips ---
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // !help quick action
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, terminalColorMap["cyan"]!!, RoundedCornerShape(4.dp))
                    .clickable { inputState = "!help" }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "!help",
                    color = terminalColorMap["cyan"]!!,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // !clear quick action
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, terminalColorMap["red"]!!, RoundedCornerShape(4.dp))
                    .clickable { viewModel.handleCommand("!clear") }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "!clear",
                    color = terminalColorMap["red"]!!,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // cycle provider quick action
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, terminalColorMap["green"]!!, RoundedCornerShape(4.dp))
                    .clickable { viewModel.handleCommand("!provider") }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "PROVIDER [${activeProvider.name}]",
                    color = terminalColorMap["green"]!!,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Quick Question filler
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF455A64), RoundedCornerShape(4.dp))
                    .clickable { inputState = "!ai " }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "!ai Ask",
                    color = terminalColorMap["text_gray"]!!,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // --- Terminal Prompt Inputs and Actions ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(terminalColorMap["card_bg"]!!)
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = 8.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // CLI indicator
            Text(
                text = "$",
                color = terminalColorMap["green"]!!,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(end = 6.dp)
            )

            // Text input line
            OutlinedTextField(
                value = inputState,
                onValueChange = { inputState = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                placeholder = {
                    Text(
                        text = "Komut girin (!ai, !run, !help...)",
                        color = Color(0xFF5A6990),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                },
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputState.trim().isNotEmpty()) {
                            viewModel.handleCommand(inputState)
                            inputState = ""
                            keyboardController?.hide()
                        }
                    }
                ),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = terminalColorMap["green"]!!,
                    unfocusedBorderColor = Color(0xFF283560),
                    focusedContainerColor = Color(0xFF060B1E),
                    unfocusedContainerColor = Color(0xFF060B1E)
                )
            )

            Spacer(modifier = Modifier.width(6.dp))

            // UI History Controls
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // History Up
                IconButton(
                    onClick = {
                        val text = viewModel.navigateHistory(up = true)
                        if (text.isNotEmpty()) {
                            inputState = text
                        }
                    },
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color(0xFF1E2640)
                    )
                ) {
                    Text(
                        text = "▲",
                        color = terminalColorMap["green"]!!,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // History Down
                IconButton(
                    onClick = {
                        val text = viewModel.navigateHistory(up = false)
                        inputState = text
                    },
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color(0xFF1E2640)
                    )
                ) {
                    Text(
                        text = "▼",
                        color = terminalColorMap["green"]!!,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Command Submit Button
                IconButton(
                    onClick = {
                        if (inputState.trim().isNotEmpty()) {
                            viewModel.handleCommand(inputState)
                            inputState = ""
                            keyboardController?.hide()
                        }
                    },
                    enabled = inputState.trim().isNotEmpty(),
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = terminalColorMap["green"]!!,
                        disabledContainerColor = Color(0xFF1E2640)
                    )
                ) {
                    Text(
                        text = "▶",
                        color = if (inputState.trim().isNotEmpty()) Color.Black else Color.Gray,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun TerminalLogEntry(
    log: TerminalLog,
    colors: Map<String, Color>
) {
    val textColor = when (log.type) {
        "COMMAND" -> colors["green"]!!
        "SYSTEM" -> colors["cyan"]!!
        "OUTPUT" -> colors["text_gray"]!!
        "ERROR" -> colors["red"]!!
        else -> Color.White
    }

    val typeLabel = when (log.type) {
        "COMMAND" -> "$ "
        "SYSTEM" -> "[SYS] "
        "OUTPUT" -> ""
        "ERROR" -> "[ERR] "
        else -> ""
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (log.type == "COMMAND") Color(0xFF081C15) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            text = "$typeLabel${log.text}",
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            style = TextStyle(lineHeight = 18.sp)
        )
    }
}
