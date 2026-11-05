package com.xencode.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Conversation pane: streaming transcript (user right / assistant left, tool
 * calls inline in monospace) plus the input row. Enter sends, Shift+Enter
 * inserts a newline. Without a configured provider it shows a friendly empty
 * state that jumps straight to Settings.
 */
@Composable
fun ChatPane(
    controller: DesktopAgentController?,
    workspace: String,
    onNavigateToSettings: () -> Unit
) {
    if (controller == null) {
        NoProviderState(onNavigateToSettings)
        return
    }

    val messages by controller.messages.collectAsState()
    val streamingText by controller.streamingText.collectAsState()
    val busy by controller.busy.collectAsState()
    val error by controller.error.collectAsState()

    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val send: () -> Unit = {
        val text = input.trim()
        if (text.isNotEmpty() && !busy) {
            input = ""
            controller.send(text)
        }
    }

    // Keep the newest entry visible: on new rows and while text streams in.
    LaunchedEffect(messages.size, streamingText.length) {
        val target = messages.size - 1 + if (streamingText.isNotEmpty()) 1 else 0
        if (target >= 0) listState.animateScrollToItem(target)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pane header: title, workspace hint, clear action.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Chat",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    "The agent can read, write, list files and run commands inside: $workspace",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(
                onClick = { controller.clearConversation() },
                enabled = messages.isNotEmpty()
            ) { Text("Clear") }
        }

        // Transcript.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty() && streamingText.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Ask XenCode anything about your workspace.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Replies stream in live; file and command activity shows up inline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages) { entry ->
                    when (entry) {
                        is ChatEntry.User -> UserBubble(entry.text)
                        is ChatEntry.Assistant -> AssistantBubble(entry.text)
                        is ChatEntry.ToolCall -> ToolCallRow(entry.name, entry.argsJson)
                        is ChatEntry.ErrorLine -> ErrorRow(entry.message)
                    }
                }
                if (streamingText.isNotEmpty()) {
                    item { AssistantBubble(streamingText + " ▍", streaming = true) }
                }
            }
        }

        if (error != null && !busy) {
            Text(
                text = "Last error: ${error}",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Input row: Enter sends, Shift+Enter inserts a newline.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        val plainEnter =
                            (event.key == Key.Enter || event.key == Key.NumPadEnter) && !event.isShiftPressed
                        if (event.type == KeyEventType.KeyDown && plainEnter) {
                            send()
                            true
                        } else {
                            false
                        }
                    },
                enabled = !busy,
                placeholder = {
                    Text("Message XenCode — Enter to send, Shift+Enter for a new line")
                },
                maxLines = 8,
                shape = RoundedCornerShape(12.dp)
            )
            if (busy) {
                OutlinedButton(onClick = { controller.cancelTurn() }) {
                    Text("Stop")
                }
            } else {
                Button(onClick = send, enabled = input.isNotBlank()) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
private fun NoProviderState(onNavigateToSettings: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("⚡", fontSize = 44.sp, color = MaterialTheme.colorScheme.primary)
            Text(
                "No provider configured",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "XenCode needs an AI provider (any OpenAI-compatible endpoint or the native Anthropic API) before it can chat.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onNavigateToSettings) { Text("Open Settings") }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 2.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.widthIn(max = 560.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}

@Composable
private fun AssistantBubble(text: String, streaming: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (streaming) 0.6f else 1f),
            shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 2.dp, bottomEnd = 12.dp)
        ) {
            Text(
                text = text,
                modifier = Modifier.widthIn(max = 640.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ToolCallRow(name: String, argsJson: String) {
    Text(
        text = Formatters.toolLine(name, argsJson),
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ErrorRow(message: String) {
    Text(
        text = "⚠ $message",
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.error
    )
}
