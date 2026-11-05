package com.xencode.cli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.xencode.core.agent.GoalRunner
import com.xencode.core.goal.GoalDag
import com.xencode.core.goal.GoalDagEngine
import com.xencode.core.goal.GoalDagFile
import com.xencode.core.goal.GoalDagResult
import com.xencode.core.goal.GoalStatus
import com.xencode.core.telemetry.XpMath
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * `xencode goal` — manage the workspace goal DAG without a model turn.
 *
 * Grammar and output mirror the Android `/goal` handler exactly; the shared
 * contract is the `.xencode/GOAL_DAG.json` file (see core/goal/GoalDag.kt).
 *
 * Usage:
 * - `xencode goal` or `xencode goal list`                  list goals with status and progress
 * - `xencode goal add <title> [after g2 g5] [note <text>]`  add a goal
 * - `xencode goal done <id> [note <text>]`                 mark DONE (+50 XP, per-machine ledger)
 * - `xencode goal fail|retry|start|drop <id>`              other transitions
 * - `xencode goal run [--parallel N]`                      agent-execute the READY nodes (swimlanes)
 */
class GoalCommand : XeCommand(
    name = "goal",
    helpText = "Manage the workspace goal DAG (list/add/done/fail/retry/start/drop)"
) {
    private val args by argument("args").multiple()
    private val runtime by requireObject<CliRuntime>()

    override fun run() {
        val root = runtime.workspaceRoot
        val dag = GoalDagFile.load(root)
        when (val request = parseGoalArgs(args)) {
            is GoalRequest.Listing -> {
                echo(renderGoalDag(root, dag))
            }

            is GoalRequest.Run -> runGoal(request)

            is GoalRequest.Add -> mutate(root, dag) { g ->
                GoalDagEngine.addGoal(g, request.title, request.note ?: "", request.deps, System.currentTimeMillis())
            }

            is GoalRequest.ById -> mutate(root, dag) { g ->
                val now = System.currentTimeMillis()
                when (request.verb) {
                    "done" -> GoalDagEngine.completeGoal(g, request.id, request.note, now)
                    "fail" -> GoalDagEngine.failGoal(g, request.id, request.note, now)
                    "retry" -> GoalDagEngine.retryGoal(g, request.id, now)
                    "start" -> GoalDagEngine.activateGoal(g, request.id, now)
                    "drop" -> GoalDagEngine.cancelGoal(g, request.id, now)
                    else -> GoalDagResult.Rejected("Unknown verb `${request.verb}`")
                }
            }

            is GoalRequest.MissingId -> throw UsageError(
                "Missing goal id. Usage: `xencode goal ${request.verb} <id> [note <text>]`"
            )

            is GoalRequest.Unknown -> throw UsageError(
                "Unknown subcommand `${request.verb}`.\n\n$GOAL_USAGE"
            )
        }
    }

    /** Swimlane execution: READY nodes run as agent turns, in parallel worktrees. */
    private fun runGoal(request: GoalRequest.Run) {
        val provider = CliAgents.activeProvider()
        val agent = CliAgents.runtime(runtime, provider)
        agent.events.emit(com.xencode.core.state.XenEventLog.Types.GOAL_INITIALIZED, "goal run parallel=${request.parallel}")
        echo("Running READY goals (parallel=${request.parallel}). Every node gets its own agent turn; git workspaces get isolated worktrees.")
        val summary = runBlocking {
            GoalRunner(
                workspaceRoot = File(runtime.workspaceRoot),
                provider = provider,
                client = com.xencode.core.provider.ChatClient(),
                runtime = agent
            ).run(parallel = request.parallel)
        }
        summary.results.forEach { r ->
            val where = r.worktreePath?.let { " (branch ${r.branch})" } ?: ""
            if (r.ok) echo("[done] ${r.nodeId}$where — ${(r.reply ?: "").take(140)}")
            else echo("[fail] ${r.nodeId} — ${r.error}", err = true)
        }
        echo("Goals: ${summary.doneCount} done, ${summary.failedCount} failed.")
        if (summary.results.any { it.worktreePath != null }) {
            echo("Worktrees are NOT merged back automatically — review and merge the xencode/<id> branches, or `xencode worktree list`.")
        }
        if (summary.failedCount > 0) throw ProgramResult(1)
    }

    /**
     * Run one engine mutation, save, and report — awarding XP on a successful
     * completion, exactly like the Android applyGoalMutation tail.
     */
    private fun mutate(
        root: String,
        dag: GoalDag,
        op: (GoalDag) -> GoalDagResult
    ) {
        when (val result = op(dag)) {
            is GoalDagResult.Ok -> {
                if (!GoalDagFile.save(root, result.graph)) {
                    echo("Could not write ${GoalDagFile.filePathFor(root)}", err = true)
                    throw ProgramResult(1)
                }
                val before = dag.nodes.associate { it.id to it.status }
                val completed = result.graph.nodes.firstOrNull {
                    it.status == GoalStatus.DONE && before[it.id] != GoalStatus.DONE
                }
                val lines = mutableListOf(result.message)
                if (completed != null) {
                    MachineStores.ledger().awardGoal(completed.title)
                    lines += " (+${XpMath.GOAL_AWARD} XP)"
                }
                lines += renderGoalDag(root, result.graph, headerOnly = true)
                echo(lines.joinToString("\n"))
            }

            is GoalDagResult.Rejected -> {
                echo(result.reason, err = true)
                throw ProgramResult(1)
            }
        }
    }
}
