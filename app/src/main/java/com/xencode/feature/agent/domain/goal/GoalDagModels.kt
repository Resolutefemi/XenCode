package com.xencode.feature.agent.domain.goal

/**
 * Pure model layer for the workspace Goal DAG, persisted as `.xencode/GOAL_DAG.json`
 * in the active workspace root. Kept free of Android dependencies so the engine
 * logic can be unit-tested on the JVM and later reused by the CLI / desktop clients.
 */

/**
 * Lifecycle of a goal node.
 *
 * - [PENDING]  not started; at least one dependency is not DONE yet.
 * - [READY]    all dependencies DONE — eligible to be picked up next.
 * - [ACTIVE]   currently being worked on.
 * - [DONE]     completed (terminal for scheduling purposes).
 * - [FAILED]   attempted but failed; can be retried (back to READY) or cancelled.
 * - [CANCELLED] dropped, no longer scheduled.
 */
enum class GoalStatus { PENDING, READY, ACTIVE, DONE, FAILED, CANCELLED }

/** One node of the DAG: a concrete, verifiable goal plus its dependency edges. */
data class GoalNode(
    /** Stable id (`g1`, `g2`, ... — assigned by the engine, never reused within a graph). */
    val id: String,
    val title: String,
    val detail: String = "",
    /** Ids of nodes that must be DONE before this node may become READY. */
    val dependsOn: List<String> = emptyList(),
    val status: GoalStatus = GoalStatus.PENDING,
    /** Append-only human/agent remarks (each entry prefixed with its epoch millis by the writer). */
    val notes: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
)

/** Whole-graph snapshot; a single file per workspace. */
data class GoalDag(
    /** Format version of the JSON file; readers reject unknown newer majors. */
    val format: Int = 1,
    val nodes: List<GoalNode> = emptyList(),
    /** Monotonic counter used to mint node ids; never decreases. */
    val nextId: Int = 1,
    val updatedAt: Long
) {
    val doneCount: Int get() = nodes.count { it.status == GoalStatus.DONE }
    val totalActive: Int get() = nodes.count { it.status !in TERMINAL_STATUSES }
    val progressPercent: Int
        get() = if (nodes.isEmpty()) 100 else (doneCount * 100 / nodes.size)

    companion object {
        val TERMINAL_STATUSES = setOf(GoalStatus.DONE, GoalStatus.CANCELLED)
    }
}

/** Result of a mutating engine operation, carrying user-readable feedback. */
sealed class GoalDagResult {
    /** Operation applied; [graph] is the new snapshot (already status-recomputed). */
    data class Ok(val graph: GoalDag, val message: String) : GoalDagResult()

    /** Rejected (validation failed); the graph is unchanged. */
    data class Rejected(val reason: String) : GoalDagResult()
}
