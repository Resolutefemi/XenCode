package com.xencode.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.io.File
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xencode.core.goal.GoalDag
import com.xencode.core.goal.GoalDagEngine
import com.xencode.core.goal.GoalDagFile
import com.xencode.core.goal.GoalDagResult
import com.xencode.core.goal.GoalNode
import com.xencode.core.goal.GoalStatus
import com.xencode.core.telemetry.XpLedger

/**
 * Non-UI board behind the Goals pane: loads `.xencode/GOAL_DAG.json` from the
 * workspace, applies all mutations through [GoalDagEngine], saves through
 * [GoalDagFile] and awards the +50 goal XP on completion. Kept free of Compose
 * imports so the whole mutation flow is unit-testable (see XpWiringTest).
 */
class GoalBoard(
    private val workspaceRoot: String,
    private val ledger: XpLedger = DesktopXp.defaultLedger()
) {
    var dag: GoalDag = GoalDagFile.load(workspaceRoot)
        private set

    val filePath: String get() = GoalDagFile.filePathFor(workspaceRoot)

    fun reload() {
        dag = GoalDagFile.load(workspaceRoot)
    }

    fun add(title: String, after: List<String>, note: String): GoalDagResult =
        mutate(GoalDagEngine.addGoal(dag, title, note, after, System.currentTimeMillis()))

    fun start(id: String): GoalDagResult =
        mutate(GoalDagEngine.activateGoal(dag, id, System.currentTimeMillis()))

    fun complete(id: String): GoalDagResult {
        val title = dag.nodes.firstOrNull { it.id == id }?.title
        return mutate(
            GoalDagEngine.completeGoal(dag, id, null, System.currentTimeMillis()),
            awardGoalTitle = title
        )
    }

    fun fail(id: String): GoalDagResult =
        mutate(GoalDagEngine.failGoal(dag, id, null, System.currentTimeMillis()))

    fun retry(id: String): GoalDagResult =
        mutate(GoalDagEngine.retryGoal(dag, id, System.currentTimeMillis()))

    fun drop(id: String): GoalDagResult =
        mutate(GoalDagEngine.cancelGoal(dag, id, System.currentTimeMillis()))

    private fun mutate(result: GoalDagResult, awardGoalTitle: String? = null): GoalDagResult {
        if (result is GoalDagResult.Ok) {
            dag = result.graph
            GoalDagFile.save(workspaceRoot, dag)
            awardGoalTitle?.let { title ->
                if (DesktopXp.goalAwardFor(goalCompleted = true) > 0L) {
                    runCatching { ledger.awardGoal(title) }
                }
            }
        }
        return result
    }
}

/** Action button sets per goal status; the engine rejects everything not listed here. */
internal fun actionsFor(status: GoalStatus): List<Pair<String, String>> = when (status) {
    GoalStatus.PENDING -> listOf("Drop" to "drop")
    GoalStatus.READY -> listOf(
        "Start" to "start", "Done" to "done", "Fail" to "fail", "Drop" to "drop"
    )
    GoalStatus.ACTIVE -> listOf("Done" to "done", "Fail" to "fail", "Drop" to "drop")
    GoalStatus.FAILED -> listOf("Retry" to "retry", "Drop" to "drop")
    GoalStatus.DONE, GoalStatus.CANCELLED -> emptyList()
}

/**
 * Non-UI driver for the "Run ready goals" action: one [GoalRunner] pass over
 * the READY nodes. Returns one status line per node so the pane can render
 * swimlane results without knowing about transports or coroutines.
 */
class GoalRunAction(
    private val workspaceRoot: String,
    private val buildRuntime: () -> com.xencode.core.agent.AgentRuntime
) {
    fun run(parallel: Int, onLine: (String) -> Unit): Int {
        val runtime = buildRuntime()
        val runner = com.xencode.core.agent.GoalRunner(
            workspaceRoot = File(workspaceRoot),
            provider = runtime.provider,
            client = com.xencode.core.provider.ChatClient(),
            runtime = runtime
        )
        val summary = kotlinx.coroutines.runBlocking { runner.run(parallel = parallel) }
        summary.results.forEach { r ->
            val where = r.worktreePath?.let { " (branch ${r.branch})" } ?: ""
            onLine(if (r.ok) "[done] ${r.nodeId}$where — ${(r.reply ?: "").take(140)}" else "[fail] ${r.nodeId} — ${r.error}")
        }
        return summary.failedCount
    }
}

/**
 * Goal DAG screen: progress header, "Next up" row, add-goal form and the node
 * list with per-status actions. All mutations go through [GoalBoard] (engine +
 * file save + goal XP), so the UI only mirrors state.
 */
@Composable
fun GoalsPane(appState: DesktopAppState) {
    val workspace by appState.workspace.collectAsState()
    val config by appState.config.collectAsState()
    val board = remember(workspace) { GoalBoard(workspace) }
    var dag by remember(workspace) { mutableStateOf(board.dag) }
    var statusMessage by remember(workspace) { mutableStateOf<String?>(null) }
    var runLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var runBusy by remember { mutableStateOf(false) }
    var parallel by remember { mutableStateOf(2) }

    fun run(action: GoalBoard.() -> GoalDagResult) {
        val result = board.action()
        dag = board.dag
        statusMessage = when (result) {
            is GoalDagResult.Ok -> result.message
            is GoalDagResult.Rejected -> result.reason
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Progress header + reload.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Goals",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    Formatters.progressLine(dag.doneCount, dag.nodes.size) +
                        " · ${dag.totalActive} open · updated by CLI and Android too",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    board.filePath,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = {
                board.reload()
                dag = board.dag
                statusMessage = "Reloaded goals from ${board.filePath}"
            }) { Text("Reload") }
        }

        // Next up.
        val nextUp = GoalDagEngine.readyNodes(dag).firstOrNull()
        when {
            nextUp != null -> Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Next up",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "${nextUp.id} · ${nextUp.title}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { run { start(nextUp.id) } }) { Text("Start") }
                }
            }
            dag.nodes.isEmpty() -> Hint("No goals yet. Add your first goal below — small steps beat big plans.")
            else -> Hint("No goal is ready right now; finish or drop the blocking goals to unblock the rest.")
        }

        // Swimlane runner: agent-executes the READY nodes, in parallel git worktrees.
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Run ready goals",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Each READY node gets its own agent turn; git workspaces run in isolated worktrees (never auto-merged).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("lanes", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf(1, 2, 4).forEach { n ->
                            val selected = parallel == n
                            OutlinedButton(
                                onClick = { parallel = n },
                                colors = if (selected) androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ) else androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                            ) { Text("$n") }
                        }
                        Button(
                            enabled = !runBusy && config.activeProvider() != null && GoalDagEngine.readyNodes(dag).isNotEmpty(),
                            onClick = {
                                runBusy = true
                                runLines = emptyList()
                                val provider = config.activeProvider() ?: return@Button
                                Thread {
                                    val action = GoalRunAction(workspace) {
                                        com.xencode.core.agent.AgentRuntime(
                                            workspaceRoot = File(workspace),
                                            provider = provider,
                                            client = com.xencode.core.provider.ChatClient()
                                        )
                                    }
                                    val failed = action.run(parallel) { line ->
                                        runLines = runLines + line
                                    }
                                    board.reload()
                                    dag = board.dag
                                    runLines = runLines + "Run finished — ${if (failed == 0) "all nodes completed" else "$failed node(s) failed (see lines above)"}."
                                    runBusy = false
                                }.start()
                            }
                        ) { Text(if (runBusy) "Running…" else "Run") }
                    }
                }
                if (runLines.isNotEmpty()) {
                    runLines.forEach { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = if (line.startsWith("[fail]")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        AddGoalForm(
            existingIds = dag.nodes.map { it.id },
            onAdd = { title, after, note -> run { add(title, after, note) } }
        )

        statusMessage?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Node list (insertion order, same as the CLI prints it).
        dag.nodes.forEach { node ->
            val blockers = GoalDagEngine.blockers(dag, node.id)
            GoalNodeRow(
                node = node,
                waitingOn = Formatters.waitingOnLine(blockers.map { "${it.id} (${it.title})" }),
                onAction = { key ->
                    run {
                        when (key) {
                            "start" -> start(node.id)
                            "done" -> complete(node.id)
                            "fail" -> fail(node.id)
                            "retry" -> retry(node.id)
                            "drop" -> drop(node.id)
                            else -> GoalDagResult.Rejected("Unknown action `$key`")
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AddGoalForm(
    existingIds: List<String>,
    onAdd: (title: String, after: List<String>, note: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var after by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Add a goal",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = after,
                onValueChange = { after = it },
                label = { Text("After (optional goal ids, e.g. g1 g3)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        onAdd(title, Formatters.parseIds(after), note)
                        title = ""
                        after = ""
                        note = ""
                    },
                    enabled = title.isNotBlank()
                ) { Text("Add goal") }
                if (existingIds.isNotEmpty()) {
                    Text(
                        "Existing ids: ${existingIds.joinToString(" ")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalNodeRow(node: GoalNode, waitingOn: String?, onAction: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                Formatters.statusGlyph(node.status),
                fontSize = 16.sp,
                color = glyphColor(node.status),
                modifier = Modifier.padding(top = 1.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    "${node.id} · ${node.title}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (node.status == GoalStatus.ACTIVE) FontWeight.Bold else FontWeight.Normal,
                    textDecoration = if (node.status == GoalStatus.CANCELLED) TextDecoration.LineThrough else null,
                    color = if (node.status == GoalStatus.CANCELLED) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                if (node.detail.isNotBlank()) {
                    Text(
                        node.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (waitingOn != null) {
                    Text(
                        waitingOn,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (node.status == GoalStatus.FAILED) {
                    Text(
                        "failed — retry or drop",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                node.notes.lastOrNull()?.let {
                    Text(
                        "note: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                actionsFor(node.status).forEach { (label, key) ->
                    TextButton(
                        onClick = { onAction(key) },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(label, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun glyphColor(status: GoalStatus): Color = when (status) {
    GoalStatus.DONE, GoalStatus.ACTIVE -> MaterialTheme.colorScheme.primary
    GoalStatus.READY -> MaterialTheme.colorScheme.onSurface
    GoalStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    GoalStatus.FAILED -> MaterialTheme.colorScheme.error
    GoalStatus.CANCELLED -> MaterialTheme.colorScheme.outline
}
