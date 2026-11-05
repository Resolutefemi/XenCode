package com.xencode.core.agent

import com.xencode.core.goal.GoalDag
import com.xencode.core.goal.GoalDagEngine
import com.xencode.core.goal.GoalDagFile
import com.xencode.core.goal.GoalNode
import com.xencode.core.goal.GoalStatus
import com.xencode.core.provider.ChatTransport
import com.xencode.core.provider.ProviderConfig
import com.xencode.core.state.XenEventLog
import com.xencode.core.telemetry.XpMath
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * Executes READY goal-DAG nodes with the agent — the multi-agent swimlane
 * engine. Each node runs in its own isolated git worktree (branch
 * `xencode/<id>`); on success the worktree gets an atomic checkpoint commit,
 * the node flips to DONE, XP is awarded and a NODE_COMPLETED event is emitted.
 * Without git (or with parallelism 1) nodes run sequentially in place.
 *
 * Worktrees are intentionally NOT merged back automatically — the runner
 * records the branch in the node notes; merging is a human call.
 */
class GoalRunner(
    private val workspaceRoot: File,
    private val provider: ProviderConfig,
    private val client: ChatTransport,
    private val runtime: AgentRuntime
) {

    data class NodeResult(
        val nodeId: String,
        val ok: Boolean,
        val reply: String?,
        val error: String? = null,
        val worktreePath: String? = null,
        val branch: String? = null,
        val durationMs: Long
    )

    data class RunSummary(
        val results: List<NodeResult>,
        val startedAt: Long,
        val finishedAt: Long,
        val doneCount: Int,
        val failedCount: Int
    ) {
        val allDone: Boolean get() = results.isNotEmpty() && results.all { it.ok }
    }

    /** One agent turn working a single goal node inside [workRoot]. */
    suspend fun runNode(node: GoalNode, workRoot: File, maxToolRounds: Int = 16): NodeResult {
        val started = System.currentTimeMillis()
        events().emit(XenEventLog.Types.NODE_STARTED, "${node.id} ${node.title}", "node" to node.id)

        val prompt = buildString {
            appendLine("Complete this goal in the workspace at ${workRoot.absolutePath}:")
            appendLine("Goal ${node.id}: ${node.title}")
            if (node.detail.isNotBlank()) appendLine("Details: ${node.detail}")
            appendLine()
            appendLine("Work strictly inside that directory. Inspect first, make the change, then verify it (run the relevant build/test command if one exists).")
            appendLine("Finish with a short summary of what you changed and how it was verified.")
        }

        // A dedicated runtime for the node when it runs in a worktree (own .xencode state).
        val nodeRuntime = if (workRoot == workspaceRoot) runtime
        else AgentRuntime(workRoot, provider, client, autoCheckpoint = true)
        nodeRuntime.ensureMcp()

        val session = nodeRuntime.newSession(maxToolRounds = maxToolRounds)
        val reply = session.send(prompt)
        val ok = reply != null

        var worktreePath: String? = null
        var branch: String? = null
        if (workRoot != workspaceRoot) {
            branch = "xencode/${node.id}"
            worktreePath = workRoot.absolutePath
        }

        // Checkpoint the result (worktree or in-place).
        val cpStore = if (workRoot == workspaceRoot) runtime.checkpoints else com.xencode.core.state.CheckpointStore(workRoot)
        val cp = cpStore.create(node.id, (reply ?: "failed run").take(80))
        if (cp != null) {
            events().emit(XenEventLog.Types.CHECKPOINT, "${cp.id} ${cp.commit ?: "-"}", "node" to node.id, "commit" to cp.commit)
        }

        val result = NodeResult(
            nodeId = node.id,
            ok = ok,
            reply = reply,
            error = if (ok) null else (session.lastFailure ?: "no reply"),
            worktreePath = worktreePath,
            branch = branch,
            durationMs = System.currentTimeMillis() - started
        )
        events().emit(
            XenEventLog.Types.NODE_COMPLETED,
            "${node.id} ${if (ok) "ok" else "failed"}",
            "node" to node.id, "ok" to ok, "tests_passed" to if (ok) 1 else 0
        )
        return result
    }

    /**
     * Runs every currently-READY node (re-evaluating readiness after each wave),
     * up to [parallel] nodes concurrently. DAG dependencies are honored per wave;
     * a failed node stays FAILED and its dependants remain blocked.
     */
    suspend fun run(parallel: Int = 2, maxNodes: Int = 12): RunSummary {
        val startedAt = System.currentTimeMillis()
        val results = ArrayList<NodeResult>()
        val useGit = Worktrees.isGitRepo(workspaceRoot)
        val lanes = parallel.coerceIn(1, 8)
        var processed = 0

        while (true) {
            val dag = GoalDagFile.load(workspaceRoot.absolutePath)
            val ready = GoalDagEngine.readyNodes(dag).take(lanes)
            if (ready.isEmpty() || processed >= maxNodes) break
            processed += ready.size

            val wave = coroutineScope {
                ready.map { node ->
                    async {
                        val workRoot = if (useGit && lanes > 1) {
                            Worktrees.create(workspaceRoot, node.id)?.let { File(it.path) } ?: workspaceRoot
                        } else workspaceRoot
                        runNode(node, workRoot)
                    }
                }.awaitAll()
            }

            // Fold results into the DAG.
            var dagNow = GoalDagFile.load(workspaceRoot.absolutePath)
            for (r in wave) {
                results.add(r)
                dagNow = applyResult(dagNow, r)
            }
            GoalDagFile.save(workspaceRoot.absolutePath, dagNow)
        }

        return RunSummary(
            results = results,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            doneCount = results.count { it.ok },
            failedCount = results.count { !it.ok }
        )
    }

    /** Marks the node DONE/FAILED and records branch + reply in the notes. */
    private fun applyResult(dag: GoalDag, r: NodeResult): GoalDag {
        val now = System.currentTimeMillis()
        val note = buildString {
            if (r.branch != null) append("branch=${r.branch} ")
            append((r.reply ?: r.error ?: "").replace('\n', ' ').take(160))
        }
        val result = if (r.ok) GoalDagEngine.completeGoal(dag, r.nodeId, note, now)
        else GoalDagEngine.failGoal(dag, r.nodeId, note, now)
        val nextDag = (result as? com.xencode.core.goal.GoalDagResult.Ok)?.graph ?: dag
        if (r.ok) {
            runtime.ledger.awardGoal("goal ${r.nodeId}: ${note.take(40)}")
            runtime.events.emit(XenEventLog.Types.XP_AWARDED, "+${XpMath.goalAward()} XP goal ${r.nodeId}", "xp" to XpMath.goalAward())
        }
        return nextDag
    }

    private fun events(): XenEventLog = runtime.events
}
