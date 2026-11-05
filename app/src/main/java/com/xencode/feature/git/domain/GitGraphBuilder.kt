package com.xencode.feature.git.domain

import com.xencode.feature.git.domain.model.GitGraph
import com.xencode.feature.git.domain.model.GitGraphRef
import com.xencode.feature.git.domain.model.GraphCommit
import com.xencode.feature.git.domain.model.GraphEdge

/**
 * Pure Kotlin parsing and swimlane layout for the commit topology graph (IDE style). Stateless, no Android
 * dependencies: takes `git log` output (with `%P` parent hashes) plus a refs map, and outputs [GitGraph] for the UI to draw with Canvas.
 */
internal object GitGraphBuilder {

    /** Parses `git log --pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s%x1f%P%x1f%b` output into a list of [GraphCommit]. */
    fun parseGraphCommits(raw: String): List<GraphCommit> =
        raw.split('\n').mapNotNull { line ->
            // Fields are separated by 0x1f: commit messages/author names may contain '|' (commit 7aba60f's
            // message actually contained '|', and split('|') once parsed bogus parents, misjudging the commit as a merge and creating ghost lanes).
            val parts = line.removeSuffix("\r").split('\u001f')
            if (parts.size < 6) null
            else {
                val parents = parts[5].split(' ').filter { it.isNotBlank() }
                GraphCommit(parts[0], parts[1], parts[2], parts[3], parts[4], parents = parents, body = parts.getOrNull(6) ?: "")
            }
        }

    /**
     * Recomputes the swimlane layout over the full commit list and assembles [GitGraph]. [refs] is the full refs-by-commit
     * map passed in from outside, filtered to only entries with loaded commit hashes, reducing data shipped to the UI (3000+ total → a few dozen for the current view).
     */
    fun buildGraph(
        commits: List<GraphCommit>,
        refs: Map<String, List<GitGraphRef>>,
        hasMore: Boolean
    ): GitGraph {
        if (commits.isEmpty()) return GitGraph.EMPTY
        val commitHashes = commits.mapTo(HashSet()) { it.hash }
        val filteredRefs = refs.filterKeys { it in commitHashes }
        val layout = computeLanes(commits)
        return GitGraph(
            commits,
            filteredRefs,
            layout.lanes,
            layout.edges,
            layout.activeTopLanes,
            layout.activeBottomLanes,
            layout.activeLanes,
            layout.maxLane,
            hasMore
        )
    }

    /**
     * Pure Kotlin swimlane assignment algorithm (IDE-style topological layout).
     *
     * Maintains an "active lanes" array `active: Array<String?>` where each slot records the commit hash currently occupying that column.
     * Commits are processed newest to oldest (matching git log output):
     * - Check whether the current commit already occupies a lane (its hash is already in active, i.e. reserved by some child commit's parent reference) → reuse that lane as the current column.
     * - If not, take the leftmost free slot as a new lane (the commit is the tip of some branch, referenced by no child).
     * - Record the current commit's lane.
     * - Snapshot the column numbers of all non-null slots in [active] right now as the commit's [GitGraph.activeLanes]: these lanes
     *   carry over from the previous row and pass through this one; the UI must draw through-lines for them so branch legs do not break mid-row.
     * - Process parents: if the first parent is unoccupied it reuses the current commit's lane (mainline continues); if it is already in another
     *   active lane, emit a cross-column out-edge and release the current lane (mainline jumps columns); remaining parents (second and later of a
     *   merge) each get a lane - reused if that parent is already in an active lane, otherwise the leftmost free slot.
     * - A commit with no parents (root commit) releases its lane.
     * - Also emit one [GraphEdge] per parent: a cross-column first parent yields an out-edge (fromLane=current column, toLane=parent column,
     *   lane=current column); a same-column first parent yields a vertical edge. Other parents yield merge in-edges (fromLane=current column,
     *   toLane=parent column, lane=parent column); a cross-column one is the merge polyline.
     *
     * A parent commit may lie outside the current limit window (its hash has no corresponding commit
     * record in active); the lane reservation is kept to preserve edge continuity until a later commit releases it.
     */
    private fun computeLanes(commits: List<GraphCommit>): GraphLayout {
        val commitMap = commits.associateBy { it.hash }
        val lanes = mutableMapOf<String, Int>()
        val edges = mutableListOf<GraphEdge>()
        val activeTopLanes = mutableMapOf<String, List<Int>>()
        val activeBottomLanes = mutableMapOf<String, List<Int>>()
        val activeLanes = mutableMapOf<String, List<Int>>()
        // Active lanes: slot index is the column number; value is the commit hash occupying that column (or null = free).
        val active = mutableListOf<String?>()
        var maxLane = 0

        for (commit in commits) {
            // Whether the current commit is already reserved by some child commit's parent reference.
            var lane = active.indexOf(commit.hash)
            if (lane < 0) {
                // Not reserved: take the leftmost free slot.
                lane = active.indexOf(null)
                if (lane < 0) {
                    lane = active.size
                    active.add(commit.hash)
                } else {
                    active[lane] = commit.hash
                }
            }
            if (lane > maxLane) maxLane = lane
            lanes[commit.hash] = lane

            // Snapshot the upper half (0 -> centerY) active lanes: active here reflects the branch channels above, before entering this node.
            val topSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
            activeTopLanes[commit.hash] = topSnapshot

            if (commit.parents.isEmpty()) {
                // Root commit: release the current lane, then snapshot the lower half.
                active[lane] = null
                val botSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
                activeBottomLanes[commit.hash] = botSnapshot
                activeLanes[commit.hash] = botSnapshot
                continue
            }

            // First parent reuses the current lane (mainline continues).
            val parents = commit.parents
            val firstParent = parents[0]
            val firstParentLane = active.indexOf(firstParent)
            if (firstParentLane >= 0 && firstParentLane != lane) {
                edges.add(GraphEdge(lane, firstParentLane, lane, isMergeIn = false))
                active[lane] = null
            } else {
                edges.add(GraphEdge(lane, lane, lane, isMergeIn = false))
                active[lane] = firstParent
            }

            // Remaining parents (second and later of the merge): prefer reusing a lane in the active list that will later inherit this parent; otherwise allocate a new lane.
            for (i in 1 until parents.size) {
                val p = parents[i]
                val existing = active.indexOf(p)
                val pLane = if (existing >= 0) {
                    existing
                } else {
                    // Look for a placeholder commit X in active (not the current column) whose parent references directly contain p.
                    // If found, p is X's parent and will naturally inherit that column once X is processed, so the in-edge points at that column without grabbing active.
                    val reuse = active.indexOfFirst { aHash ->
                        aHash != null && active.indexOf(aHash) != lane && commitMap[aHash]?.parents?.contains(p) == true
                    }
                    if (reuse >= 0) {
                        reuse
                    } else {
                        val free = active.indexOf(null)
                        if (free < 0) {
                            active.add(p)
                            active.size - 1
                        } else {
                            active[free] = p
                            free
                        }
                    }
                }
                if (pLane > maxLane) maxLane = pLane
                edges.add(GraphEdge(lane, pLane, pLane, isMergeIn = true))
            }

            // Snapshot the lower half (centerY -> height) active lanes: after all parents are assigned, this reflects the branch channels leaving this node downward.
            val botSnapshot = active.mapIndexedNotNull { idx, h -> if (h != null) idx else null }
            activeBottomLanes[commit.hash] = botSnapshot
            activeLanes[commit.hash] = botSnapshot
        }
        return GraphLayout(lanes, edges, activeTopLanes, activeBottomLanes, activeLanes, maxLane)
    }

    /** Output of [computeLanes]: lane map + edge list + per-row active-lane snapshots + max column number. */
    private class GraphLayout(
        val lanes: Map<String, Int>,
        val edges: List<GraphEdge>,
        val activeTopLanes: Map<String, List<Int>>,
        val activeBottomLanes: Map<String, List<Int>>,
        val activeLanes: Map<String, List<Int>>,
        val maxLane: Int
    )
}
