package com.xencode.feature.git.domain.model

/**
 * Domain models for the git commit topology graph. [com.xencode.feature.git.domain.GitRepository.graph]
 * parses `git log` (with `%P` parent hashes) and `git for-each-ref` output and computes the swimlane layout in pure Kotlin,
 * letting the UI draw IDE-style colored branch connectors with Canvas. Pure data, no Android dependencies.
 */

/**
 * One commit record (for the topology graph). Extends [GitCommit] with [parents]: the parent hash list;
 * one means a normal commit, two a merge, empty a root commit. Topology edges and lane assignment depend on this field.
 */
data class GraphCommit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val message: String,
    /** Commit body (paragraphs after the message; empty string when absent). */
    val body: String = "",
    /** Full parent commit hashes (in git output order; first parent is the mainline). */
    val parents: List<String>
) {
    /** Whether this is a merge commit (≥2 parents). Used to draw merge nodes. */
    val isMerge: Boolean get() = parents.size >= 2
}

/**
 * A reference (branch or tag) pointing at a commit. Used to label branch names next to graph nodes and color branches.
 */
data class GitGraphRef(
    val name: String,
    /** true = local/remote branch, false = tag. */
    val isBranch: Boolean,
    /** Whether this is the branch the current HEAD is on (only local branches can be true). */
    val isCurrent: Boolean,
    /** Whether this is a remote branch (under refs/remotes). */
    val isRemote: Boolean
)

/**
 * One connector: from the lane of [fromLane] to the lane of [toLane], the parent-child relation between two adjacent commits.
 * Same column means a vertical line; cross-column means a fork/merge polyline. Color maps from the lane number to a palette, looked up by the UI at render time.
 */
data class GraphEdge(
    val fromLane: Int,
    val toLane: Int,
    /** Lane number this edge belongs to (decides color): out-edges (forks) take [fromLane] (the column of the commit that forks),
     *  in-edges (merged branch legs joining) take [toLane] (the parent leg keeps its branch's main color). */
    val lane: Int,
    /** true = merge in-edge (a parent leg joins this commit from below; draw the lower Bezier half);
     *  false = out-edge (this commit forks toward a parent; draw the lower Bezier half). */
    val isMergeIn: Boolean
)

/**
 * Whole topology graph view.
 *
 * @property commits Commit list ordered newest to oldest.
 * @property refs Commit hash → list of refs (branches/tags) pointing at it, used for labels next to nodes.
 * @property lanes Commit hash → its lane column number (0-based).
 * @property edges All connectors between adjacent commits (including cross-lane fork/merge edges), flat and ordered by commit order.
 * @property activeLanes Commit hash → list of active lane column numbers needing through-lines in that row (including this commit's column).
 *   Lanes not owned by this commit but crossed by a branch are also listed; the UI draws through-lines from this to avoid breaks.
 * @property maxLane Max lane column number, decides the Canvas width.
 * @property hasMore Whether older commits can still be paged in. Judged by whether the first batch and post-write refresh reached the page size;
 *   the UI decides from this whether to trigger the next page on scroll to bottom. False at the end of history.
 */
data class GitGraph(
    val commits: List<GraphCommit>,
    val refs: Map<String, List<GitGraphRef>>,
    val lanes: Map<String, Int>,
    val edges: List<GraphEdge>,
    val activeTopLanes: Map<String, List<Int>>,
    val activeBottomLanes: Map<String, List<Int>>,
    val activeLanes: Map<String, List<Int>>,
    val maxLane: Int,
    val hasMore: Boolean = false
) {
    companion object {
        val EMPTY = GitGraph(emptyList(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap(), emptyMap(), 0, false)
    }
}
