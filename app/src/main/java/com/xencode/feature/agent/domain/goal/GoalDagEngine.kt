package com.xencode.feature.agent.domain.goal

/**
 * Pure, deterministic operations on a [GoalDag] snapshot. All mutations return a new
 * graph (data classes are immutable); the caller is responsible for persisting it.
 *
 * Invariants enforced by the engine:
 * 1. `dependsOn` only references existing, non-CANCELLED ids.
 * 2. The graph is acyclic — adding an edge that would close a cycle is rejected.
 * 3. Statuses are always fully recomputed after every mutation, so a node is READY
 *    iff all its dependencies are DONE (and it is not itself in a terminal state).
 */
object GoalDagEngine {

    /** Adds a goal; [dependsOn] may reference existing nodes ("g2", "g2 g5"). */
    fun addGoal(
        graph: GoalDag,
        title: String,
        detail: String,
        dependsOn: List<String>,
        now: Long
    ): GoalDagResult {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return GoalDagResult.Rejected("Goal title is empty")
        if (trimmedTitle.length > 200) return GoalDagResult.Rejected("Goal title longer than 200 characters")

        val ids = dependsOn.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val byId = graph.nodes.associateBy { it.id }
        for (depId in ids) {
            val dep = byId[depId]
                ?: return GoalDagResult.Rejected("Unknown dependency `$depId` (existing: ${graph.nodes.map { it.id }.joinToString(" ")})")
            if (dep.status == GoalStatus.CANCELLED) {
                return GoalDagResult.Rejected("Dependency `$depId` is cancelled; drop it or restart that goal first")
            }
            if (depId == "g${graph.nextId}") {
                return GoalDagResult.Rejected("A goal cannot depend on itself")
            }
        }

        val id = "g${graph.nextId}"
        val node = GoalNode(
            id = id,
            title = trimmedTitle,
            detail = detail.trim(),
            dependsOn = ids,
            status = GoalStatus.PENDING,
            createdAt = now,
            updatedAt = now
        )
        val next = graph.copy(
            nodes = graph.nodes + node,
            nextId = graph.nextId + 1,
            updatedAt = now
        )
        val recomputed = recomputeStatuses(next, now)
        return GoalDagResult.Ok(recomputed, "Added `$trimmedTitle` as $id" + if (ids.isNotEmpty()) " (after ${ids.joinToString(" ")})" else "")
    }

    /** Marks a node DONE; dependents whose prerequisites are now satisfied flip to READY. */
    fun completeGoal(graph: GoalDag, id: String, note: String?, now: Long): GoalDagResult =
        transition(graph, id, GoalStatus.DONE, note, now) { it.status != GoalStatus.DONE }

    /** Marks a node FAILED (kept in the graph; can be retried). */
    fun failGoal(graph: GoalDag, id: String, note: String?, now: Long): GoalDagResult =
        transition(graph, id, GoalStatus.FAILED, note, now) { it.status in setOf(GoalStatus.READY, GoalStatus.ACTIVE) }

    /** Re-queues a FAILED node as READY (or forces a stuck PENDING node to READY). */
    fun retryGoal(graph: GoalDag, id: String, now: Long): GoalDagResult =
        transition(graph, id, GoalStatus.READY, null, now) { it.status in setOf(GoalStatus.FAILED, GoalStatus.PENDING) }

    /** Drops a node; DONE nodes can only be cancelled before any dependent is DONE. */
    fun cancelGoal(graph: GoalDag, id: String, now: Long): GoalDagResult =
        transition(graph, id, GoalStatus.CANCELLED, null, now) { it.status !in GoalDag.TERMINAL_STATUSES }

    /** Marks the node the user is currently working on. */
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
        val next = graph.copy(
            nodes = graph.nodes.map { if (it.id == id) replaced else it },
            updatedAt = now
        )
        return GoalDagResult.Ok(recomputeStatuses(next, now), "`$id` ${node.title.trim()} -> $target")
    }

    /**
     * Recomputes PENDING/READY from scratch. ACTIVE is kept as-is (a human/agent choice);
     * DONE/FAILED/CANCELLED are terminal and never touched here.
     */
    fun recomputeStatuses(graph: GoalDag, now: Long): GoalDag {
        val byId = graph.nodes.associateBy { it.id }
        var changed = true
        // Fixed-point: READY propagation only depends on DONE, which is stable, so at most one pass is needed;
        // the loop is kept as a safety net for graphs produced by older writers.
        val statuses = graph.nodes.associate { it.id to it.status }.toMutableMap()
        while (changed) {
            changed = false
            for (node in graph.nodes) {
                val current = statuses[node.id] ?: continue
                if (current !in setOf(GoalStatus.PENDING, GoalStatus.READY)) continue
                val depsDone = node.dependsOn.all { (byId[it]?.status) == GoalStatus.DONE }
                val want = if (depsDone) GoalStatus.READY else GoalStatus.PENDING
                if (statuses[node.id] != want) {
                    statuses[node.id] = want
                    changed = true
                }
            }
        }
        val nodes = graph.nodes.map { node ->
            val newStatus = statuses[node.id] ?: node.status
            if (newStatus != node.status) node.copy(status = newStatus, updatedAt = now) else node
        }
        return if (nodes == graph.nodes) graph else graph.copy(nodes = nodes, updatedAt = now)
    }

    /** Scheduling view: nodes ready to work on right now, in id order. */
    fun readyNodes(graph: GoalDag): List<GoalNode> =
        graph.nodes.filter { it.status == GoalStatus.READY }.sortedBy { it.id }

    /** Whether every dependency of [id] that is still unfinished blocks it, listed for the user. */
    fun blockers(graph: GoalDag, id: String): List<GoalNode> {
        val node = graph.nodes.firstOrNull { it.id == id } ?: return emptyList()
        return node.dependsOn.mapNotNull { dep -> graph.nodes.firstOrNull { it.id == dep } }
            .filter { it.status != GoalStatus.DONE }
    }
}
