package com.xencode.cli

import com.xencode.core.goal.GoalDag
import com.xencode.core.goal.GoalDagEngine
import com.xencode.core.goal.GoalNode
import com.xencode.core.goal.GoalStatus

/**
 * Pure helpers for `xencode goal` / `xencode dag`.
 *
 * The grammar and the renderer are 1:1 ports of the Android `/goal` and `/dag`
 * handlers (app/.../feature/agent/presentation/AIAgentViewModel.kt); the shared
 * contract is the `.xencode/GOAL_DAG.json` file, so both sides must stay in
 * sync. Everything in this file is side-effect free and unit-tested.
 */

/** One parsed `xencode goal ...` invocation. */
sealed class GoalRequest {
    /** `goal`, `goal list`, `goal ls` (also the empty form). */
    object Listing : GoalRequest()

    /** `goal add <title> [after g2 g5] [note <text>]`. */
    data class Add(val title: String, val deps: List<String>, val note: String?) : GoalRequest()

    /** `goal run [--parallel N]` — agent-executes the READY nodes. */
    data class Run(val parallel: Int) : GoalRequest()

    /** `goal <done|fail|retry|start|drop> <id> [note <text>]`. */
    data class ById(val verb: String, val id: String, val note: String?) : GoalRequest()

    /** A verb-with-id form where the id token is missing. */
    data class MissingId(val verb: String) : GoalRequest()

    /** Anything else. */
    data class Unknown(val verb: String) : GoalRequest()
}

/**
 * Token-level parser, mirroring the Android token loop exactly:
 * - `after` / `note` are keyword markers (case-insensitive);
 * - the title runs from token 1 up to the first marker;
 * - deps are the tokens between `after` and (if it comes later) `note`;
 * - a note extends to the end of the line.
 *
 * Divergence from Android (intentional): when `note` appears before `after`,
 * Android computes `subList(after+1, noteIdx)` with from > to and crashes;
 * here the deps simply run to the end of the line instead. No crash, same
 * intent.
 */
fun parseGoalArgs(tokens: List<String>): GoalRequest {
    val verb = tokens.firstOrNull()?.lowercase()
    return when (verb) {
        null, "", "list", "ls" -> GoalRequest.Listing

        "add" -> {
            val afterIdx = tokens.indexOfFirst { it.equals("after", ignoreCase = true) }
            val noteIdx = tokens.indexOfFirst { it.equals("note", ignoreCase = true) }
            val titleEnd = minOf(
                afterIdx.takeIf { it > 0 } ?: tokens.size,
                noteIdx.takeIf { it > 0 } ?: tokens.size
            )
            val title = tokens.subList(1, titleEnd.coerceAtLeast(1)).joinToString(" ").trim()
            val deps = if (afterIdx > 0) {
                val end = if (noteIdx > afterIdx) noteIdx else tokens.size
                tokens.subList(afterIdx + 1, end)
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
            val note = if (noteIdx > 0) {
                tokens.subList(noteIdx + 1, tokens.size).joinToString(" ").trim().takeIf { it.isNotEmpty() }
            } else null
            GoalRequest.Add(title, deps, note)
        }

        "run" -> {
            // `goal run`, `goal run --parallel 4` / `goal run parallel 4`
            var parallel = 2
            val pIdx = tokens.indexOfFirst { it.equals("--parallel", ignoreCase = true) || it.equals("parallel", ignoreCase = true) }
            if (pIdx >= 0) {
                tokens.getOrNull(pIdx + 1)?.toIntOrNull()?.let { parallel = it }
            }
            GoalRequest.Run(parallel.coerceIn(1, 8))
        }

        "done", "fail", "retry", "start", "drop" -> {
            val id = tokens.getOrNull(1)?.trim()?.lowercase()
            if (id.isNullOrEmpty()) {
                GoalRequest.MissingId(verb)
            } else {
                val noteIdx = tokens.indexOfFirst { it.equals("note", ignoreCase = true) }
                val note = if (noteIdx >= 2) {
                    tokens.subList(noteIdx + 1, tokens.size).joinToString(" ").trim().takeIf { it.isNotEmpty() }
                } else null
                GoalRequest.ById(verb, id, note)
            }
        }

        else -> GoalRequest.Unknown(verb ?: "")
    }
}

/**
 * Render a goal DAG snapshot. Ported line-for-line from the Android renderer so
 * the two outputs stay visually identical (glyphs, waiting-on lines, "Next up:"
 * line, progress header, file footer).
 *
 * @param headerOnly suppresses the per-node body; used after a mutation, where
 * Android re-prints just the progress header.
 */
fun renderGoalDag(root: String, dag: GoalDag, headerOnly: Boolean = false): String = buildString {
    if (dag.nodes.isEmpty()) {
        appendLine("**Goal DAG** — no goals yet for this workspace.")
        appendLine()
        appendLine("Add one with `xencode goal add <title> [after g2 g5] [note <text>]`.")
        return@buildString
    }
    appendLine("**Goal DAG** — ${dag.doneCount}/${dag.nodes.size} done (${dag.progressPercent}%)")
    if (!headerOnly) {
        appendLine()
        val byId = dag.nodes.associateBy { it.id }
        for (n in dag.nodes.sortedBy { it.id }) {
            val glyph = statusGlyph(n.status)
            val deps = if (n.dependsOn.isEmpty()) "" else " _(${n.dependsOn.joinToString(" ")})_"
            appendLine("- $glyph `${n.id}` ${n.title}$deps — **${n.status}**")
            if (n.status == GoalStatus.PENDING) {
                val waiting = n.dependsOn.mapNotNull { byId[it] }.filter { it.status != GoalStatus.DONE }
                if (waiting.isNotEmpty()) {
                    appendLine("    - waiting on: " + waiting.joinToString(" · ") { "`${it.id}` ${it.title}" })
                }
            }
        }
        val ready = GoalDagEngine.readyNodes(dag)
        if (ready.isNotEmpty()) {
            appendLine()
            appendLine("Next up: " + ready.joinToString(" · ") { "`${it.id}` ${it.title}" })
        }
    }
    appendLine()
    appendLine("_File: `${root.trimEnd('/')}/.xencode/GOAL_DAG.json`_")
}

/** Status glyph table, identical to the Android renderer. */
fun statusGlyph(status: GoalStatus): String = when (status) {
    GoalStatus.DONE -> "✓"
    GoalStatus.ACTIVE -> "●"
    GoalStatus.READY -> "▸"
    GoalStatus.FAILED -> "✗"
    GoalStatus.CANCELLED -> "⊘"
    else -> "·"
}

/** The one-line usage text shared by `goal` rejection and help screens. */
const val GOAL_USAGE: String =
    "Usage: `xencode goal list` · `xencode goal add <title> [after g2 g5] [note <text>]` · " +
        "`xencode goal done <id> [note <text>]` · `xencode goal fail|retry|start|drop <id>` · " +
        "`xencode goal run [--parallel N]` (agent-executes READY nodes)"

/** Waiting-on text for a pending node, used by tests and the renderer above. */
fun waitingOnLine(node: GoalNode, byId: Map<String, GoalNode>): String? {
    val waiting = node.dependsOn.mapNotNull { byId[it] }.filter { it.status != GoalStatus.DONE }
    if (waiting.isEmpty()) return null
    return "    - waiting on: " + waiting.joinToString(" · ") { "`${it.id}` ${it.title}" }
}
