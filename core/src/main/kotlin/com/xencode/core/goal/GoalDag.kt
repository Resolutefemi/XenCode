package com.xencode.core.goal

/**
 * Goal DAG model + engine for the PC line (CLI / desktop).
 *
 * Twin of the Android implementation (app/.../feature/agent/domain/goal): the
 * shared contract is the `.xencode/GOAL_DAG.json` file format, so both sides
 * implement identical semantics. Do not change one without the other.
 */

enum class GoalStatus { PENDING, READY, ACTIVE, DONE, FAILED, CANCELLED }

data class GoalNode(
    val id: String,
    val title: String,
    val detail: String = "",
    val dependsOn: List<String> = emptyList(),
    val status: GoalStatus = GoalStatus.PENDING,
    val notes: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long
)

data class GoalDag(
    val format: Int = 1,
    val nodes: List<GoalNode> = emptyList(),
    val nextId: Int = 1,
    val updatedAt: Long
) {
    val doneCount: Int get() = nodes.count { it.status == GoalStatus.DONE }
    val totalActive: Int get() = nodes.count { it.status !in TERMINAL }
    val progressPercent: Int get() = if (nodes.isEmpty()) 100 else doneCount * 100 / nodes.size

    companion object {
        val TERMINAL = setOf(GoalStatus.DONE, GoalStatus.CANCELLED)
    }
}

sealed class GoalDagResult {
    data class Ok(val graph: GoalDag, val message: String) : GoalDagResult()
    data class Rejected(val reason: String) : GoalDagResult()
}

object GoalDagEngine {

    fun addGoal(graph: GoalDag, title: String, detail: String, dependsOn: List<String>, now: Long): GoalDagResult {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return GoalDagResult.Rejected("Goal title is empty")
        if (trimmed.length > 200) return GoalDagResult.Rejected("Goal title longer than 200 characters")

        val ids = dependsOn.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val byId = graph.nodes.associateBy { it.id }
        for (depId in ids) {
            val dep = byId[depId]
                ?: return GoalDagResult.Rejected("Unknown dependency `$depId` (existing: ${graph.nodes.joinToString(" ") { it.id }})")
            if (dep.status == GoalStatus.CANCELLED) {
                return GoalDagResult.Rejected("Dependency `$depId` is cancelled; drop it or restart that goal first")
            }
            if (depId == "g${graph.nextId}") {
                return GoalDagResult.Rejected("A goal cannot depend on itself")
            }
        }

        val node = GoalNode(
            id = "g${graph.nextId}",
            title = trimmed,
            detail = detail.trim(),
            dependsOn = ids,
            createdAt = now,
            updatedAt = now
        )
        val next = graph.copy(nodes = graph.nodes + node, nextId = graph.nextId + 1, updatedAt = now)
        return GoalDagResult.Ok(recomputeStatuses(next, now), "Added `$trimmed` as ${node.id}" + if (ids.isNotEmpty()) " (after ${ids.joinToString(" ")})" else "")
    }

    fun completeGoal(graph: GoalDag, id: String, note: String?, now: Long): GoalDagResult =
        transition(graph, id, GoalStatus.DONE, note, now) { it.status != GoalStatus.DONE }

    fun failGoal(graph: GoalDag, id: String, note: String?, now: Long): GoalDagResult =
        transition(graph, id, GoalStatus.FAILED, note, now) { it.status in setOf(GoalStatus.READY, GoalStatus.ACTIVE) }

    fun retryGoal(graph: GoalDag, id: String, now: Long): GoalDagResult =
        transition(graph, id, GoalStatus.READY, null, now) { it.status in setOf(GoalStatus.FAILED, GoalStatus.PENDING) }

    fun cancelGoal(graph: GoalDag, id: String, now: Long): GoalDagResult =
        transition(graph, id, GoalStatus.CANCELLED, null, now) { it.status !in GoalDag.TERMINAL }

    fun activateGoal(graph: GoalDag, id: String, now: Long): GoalDagResult =
        transition(graph, id, GoalStatus.ACTIVE, null, now) { it.status == GoalStatus.READY }

    private fun transition(
        graph: GoalDag,
        id: String,
        target: GoalStatus,
        note: String?,
        now: Long,
        allowed: (GoalNode) -> Boolean
    ): GoalDagResult {
        val node = graph.nodes.firstOrNull { it.id == id }
            ?: return GoalDagResult.Rejected("No goal with id `$id`")
        if (!allowed(node)) {
            return GoalDagResult.Rejected("`$id` (${node.title}) cannot move to $target from ${node.status}")
        }
        val replaced = node.copy(
            status = target,
            notes = if (note.isNullOrBlank()) node.notes else node.notes + "$now $note".trim(),
            updatedAt = now
        )
        val next = graph.copy(nodes = graph.nodes.map { if (it.id == id) replaced else it }, updatedAt = now)
        return GoalDagResult.Ok(recomputeStatuses(next, now), "`$id` ${node.title.trim()} -> $target")
    }

    fun recomputeStatuses(graph: GoalDag, now: Long): GoalDag {
        val byId = graph.nodes.associateBy { it.id }
        var changed = true
        val statuses = graph.nodes.associate { it.id to it.status }.toMutableMap()
        while (changed) {
            changed = false
            for (node in graph.nodes) {
                if ((statuses[node.id] ?: continue) !in setOf(GoalStatus.PENDING, GoalStatus.READY)) continue
                val depsDone = node.dependsOn.all { (byId[it]?.status) == GoalStatus.DONE }
                val want = if (depsDone) GoalStatus.READY else GoalStatus.PENDING
                if (statuses[node.id] != want) {
                    statuses[node.id] = want
                    changed = true
                }
            }
        }
        val nodes = graph.nodes.map { node ->
            val s = statuses[node.id] ?: node.status
            if (s != node.status) node.copy(status = s, updatedAt = now) else node
        }
        return if (nodes == graph.nodes) graph else graph.copy(nodes = nodes, updatedAt = now)
    }

    fun readyNodes(graph: GoalDag): List<GoalNode> =
        graph.nodes.filter { it.status == GoalStatus.READY }.sortedBy { it.id }

    fun blockers(graph: GoalDag, id: String): List<GoalNode> {
        val node = graph.nodes.firstOrNull { it.id == id } ?: return emptyList()
        return node.dependsOn.mapNotNull { dep -> graph.nodes.firstOrNull { it.id == dep } }
            .filter { it.status != GoalStatus.DONE }
    }
}
