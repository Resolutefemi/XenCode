package com.xencode.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xencode.core.mjs.MjsEngine
import com.xencode.core.state.CheckpointStore
import com.xencode.core.state.DecisionStore
import com.xencode.core.state.XenEventLog
import com.xencode.core.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Automation pane: run `.mjs` workflow scripts on the embedded engine (the
 * `xencode:core` bindings — fs / ast / pty / git / sync) and watch the live
 * event feed everything in the PC line writes to `.xencode/events.ndjson`.
 *
 * Scripts are picked up from the `.xencode/scripts` directory (plus any
 * `.mjs` files directly in the workspace root).
 */
@Composable
fun AutomationPane(appState: DesktopAppState) {
    val workspace by appState.workspace.collectAsState()
    val provider = appState.activeProvider()

    var scripts by remember { mutableStateOf<List<File>>(emptyList()) }
    var lastResult by remember { mutableStateOf<MjsEngine.MjsResult?>(null) }
    var running by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<String>>(emptyList()) }
    var newScript by remember { mutableStateOf("") }

    fun rescan() {
        scripts = discoverScripts(workspace)
    }

    LaunchedEffect(workspace) { rescan() }

    // Live event feed while the pane is open.
    LaunchedEffect(workspace) {
        while (true) {
            events = withContext(Dispatchers.IO) { XenEventLog(File(workspace)).latest(30) }
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Automation",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                ".mjs workflows on the embedded engine — import { fs, ast, pty, git, sync } from \"xencode:core\".",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Script list.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Scripts (.xencode/scripts/*.mjs)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (scripts.isEmpty()) {
                    Text(
                        "No scripts yet. Create one below, or print a full example with `xencode run --example`.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                scripts.forEach { script ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                script.name,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "${script.relativeToOrSelf(File(workspace)).path} · ${script.length()} bytes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            enabled = !running && provider != null,
                            onClick = {
                                running = true
                                lastResult = null
                                val root = File(workspace)
                                val events2 = XenEventLog(root)
                                val engine = MjsEngine(root, events2, DecisionStore(root), CheckpointStore(root), Telemetry(root, events2))
                                Thread {
                                    val result = engine.run(script)
                                    lastResult = result
                                    running = false
                                }.start()
                            }
                        ) { Text("Run") }
                    }
                }

                // Inline creator.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = newScript,
                        onValueChange = { newScript = it },
                        label = { Text("New script name (e.g. report.mjs)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        enabled = newScript.isNotBlank() && newScript.endsWith(".mjs"),
                        onClick = {
                            val dir = File(File(workspace), ".xencode/scripts")
                            dir.mkdirs()
                            val f = File(dir, newScript.trim())
                            if (!f.exists()) {
                                f.writeText(MjsEngine.EXAMPLE_SCRIPT.trimIndent() + "\n")
                            }
                            newScript = ""
                            rescan()
                        }
                    ) { Text("Create") }
                }
            }
        }

        // Last run result.
        lastResult?.let { result ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, if (result.ok) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        if (result.ok) "Last run: OK (${result.durationMs} ms)" else "Last run: FAILED (${result.durationMs} ms)",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (result.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                    )
                    Text(
                        listOfNotNull(
                            result.value,
                            result.error
                        ).joinToString("\n").ifBlank { "(no output)" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "files: ${result.filesWritten.joinToString(", ").ifEmpty { "-" }} · commands: ${result.commandsRun.joinToString(" | ").ifEmpty { "-" }}" +
                            (if (result.decisionsCreated > 0) " · decision cards: ${result.decisionsCreated}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Live event feed.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Event feed (.xencode/events.ndjson, refreshed every 2s)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (events.isEmpty()) {
                    Text(
                        "No events yet — chat, goal runs and scripts all log here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.height(180.dp)) {
                        items(events.reversed()) { line ->
                            Text(
                                XenEventLog.pretty(line),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Script discovery, separated for testability: `.xencode/scripts` directory plus workspace-root scripts. */
internal fun discoverScripts(workspace: String): List<File> {
    val root = File(workspace)
    val inScripts = File(root, ".xencode/scripts").listFiles { f -> f.isFile && f.name.endsWith(".mjs") }?.toList() ?: emptyList()
    val inRoot = root.listFiles { f -> f.isFile && f.name.endsWith(".mjs") }?.toList() ?: emptyList()
    return (inScripts + inRoot).distinct().sortedBy { it.name }
}
