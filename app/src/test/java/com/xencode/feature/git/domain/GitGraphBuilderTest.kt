package com.xencode.feature.git.domain

import com.xencode.feature.git.domain.model.GitGraph
import com.xencode.feature.git.domain.model.GitGraphRef
import com.xencode.feature.git.domain.model.GraphCommit
import com.xencode.feature.git.domain.model.GraphEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing and swim-lane layout of the commit topology graph ([GitGraphBuilder]). Pure JVM logic, no Android dependencies:
 * - parseGraphCommits: `git log --pretty=format:%H%x1f...` output → [GraphCommit] list
 * - buildGraph: commit list + refs map + hasMore → [GitGraph] (refs filtering + lane layout)
 */
class GitGraphBuilderTest {

    // ── parseGraphCommits: field parsing ──────────────────────────────

    @Test
    fun parsesFullLine_allFieldsIncludingBody() {
        // body is the 7th segment. Note that a multi-line %b body gets split per line (parsed line by line); a single-line body is used here to verify fields are complete
        val raw = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f2 days ago\u001ffix: issue\u001fp1p2p3 q1q2q3\u001ffirst body line"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertEquals(
            GraphCommit(
                hash = "a1b2c3d4e5f6",
                shortHash = "a1b2c3d",
                author = "Alice",
                date = "2 days ago",
                message = "fix: issue",
                parents = listOf("p1p2p3", "q1q2q3"),
                body = "first body line"
            ),
            commits[0]
        )
    }

    @Test
    fun parsesLine_withoutBody_bodyEmpty() {
        // 6 segments (no %b); body defaults to an empty string
        val raw = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f2 days ago\u001ffix: issue\u001fr1r2r3"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertEquals("", commits[0].body)
        assertEquals(listOf("r1r2r3"), commits[0].parents)
    }

    @Test
    fun parsesLine_withbodySegment_keepsBody() {
        // The 7th segment exists and contains |, which must not affect other fields
        val raw = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f2 days ago\u001ffix\u001f\u001fbody | with pipe"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals("body | with pipe", commits[0].body)
    }

    @Test
    fun parsesLine_withoutParents_parentsEmpty() {
        // %P empty (root commit) → empty parents list
        val raw = "a1b2c3d4e5f6\u001fa1b2c3d\u001fAlice\u001f2 days ago\u001ffix\u001f"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertTrue(commits[0].parents.isEmpty())
        assertFalse(commits[0].isMerge)
    }

    @Test
    fun parsesMultipleLines_removesCarriageReturn() {
        // git output through the terminal channel may carry \r\n; strip the trailing \r per line
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix: 1\u001f\r\n" +
            "d4e5f6\u001fd4e5f6\u001fBob\u001f3 days ago\u001ffeat: 2\u001fr0r0r0\r\n"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(2, commits.size)
        assertEquals("a1b2c3", commits[0].hash)
        assertEquals("d4e5f6", commits[1].hash)
    }

    @Test
    fun parentsSeparatedByMultipleSpaces_filteredToHashList() {
        // The parent list may contain extra internal spaces (normally a single space; defensive filtering)
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001fmerge\u001fp1  p2  \u001f"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(listOf("p1", "p2"), commits[0].parents)
        assertTrue(commits[0].isMerge)
    }

    // ── parseGraphCommits: fault tolerance ─────────────────────────────────

    @Test
    fun shortLine_lessThan6Segments_returnsNull() {
        // 5 segments (missing %P) → drop the whole line
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix"
        assertEquals(0, GitGraphBuilder.parseGraphCommits(raw).size)
    }

    @Test
    fun blankLines_skipped() {
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix\u001f\n\n"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
    }

    @Test
    fun emptyRaw_returnsEmpty() {
        assertTrue(GitGraphBuilder.parseGraphCommits("").isEmpty())
    }

    // ── parseGraphCommits: historical bug regression (commit message containing '|') ────────

    @Test
    fun messageContainingPipe_doesNotSplitParents() {
        // Regression: a commit message containing '|' once caused parents to be mis-split into phantom parents (ghost lanes);
        // the delimiter must be 0x1f, not '|'. Here parents must stay intact as the two real parent hashes.
        val raw = "m1m2m3\u001fm1m2m3\u001fAlice\u001f2 days ago\u001ffix: merge | conflict\u001fp1p2p3 p4p5p6\u001f"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(1, commits.size)
        assertEquals(listOf("p1p2p3", "p4p5p6"), commits[0].parents)
        assertTrue(commits[0].isMerge)
    }

    @Test
    fun messageContainingPipe_simpleCommit_staysSingleParent() {
        // A plain commit whose message contains | must not be misjudged as a merge (only one parent)
        val raw = "a1b2c3\u001fa1b2c3\u001fAlice\u001f2 days ago\u001fupdate a | b\u001fr0r0r0\u001f"
        val commits = GitGraphBuilder.parseGraphCommits(raw)
        assertEquals(listOf("r0r0r0"), commits[0].parents)
        assertFalse(commits[0].isMerge)
    }

    // ── buildGraph: empty input ──────────────────────────────────────

    @Test
    fun emptyCommits_returnsEmptyGraph() {
        assertEquals(GitGraph.EMPTY, GitGraphBuilder.buildGraph(emptyList(), emptyMap(), hasMore = false))
    }

    // ── buildGraph: refs filtering ───────────────────────────────────

    @Test
    fun refs_filteredToLoadedCommitHashes() {
        val loaded = GraphCommit("a1b2c3", "a1b2c3", "Alice", "d", "fix", parents = emptyList())
        val refs = mapOf(
            "a1b2c3" to listOf(GitGraphRef("main", isBranch = true, isCurrent = true, isRemote = false)),
            "unloaded1" to listOf(GitGraphRef("other", isBranch = true, isCurrent = false, isRemote = false))
        )
        val graph = GitGraphBuilder.buildGraph(listOf(loaded), refs, hasMore = true)
        assertEquals(setOf("a1b2c3"), graph.refs.keys)
        assertEquals("main", graph.refs.getValue("a1b2c3")[0].name)
    }

    @Test
    fun refs_allUnloaded_returnsEmptyRefs() {
        val loaded = GraphCommit("a1b2c3", "a1b2c3", "Alice", "d", "fix", parents = emptyList())
        val refs = mapOf(
            "unloaded1" to listOf(GitGraphRef("x", isBranch = true, isCurrent = false, isRemote = false)),
            "unloaded2" to listOf(GitGraphRef("y", isBranch = true, isCurrent = false, isRemote = false))
        )
        val graph = GitGraphBuilder.buildGraph(listOf(loaded), refs, hasMore = false)
        assertTrue(graph.refs.isEmpty())
    }

    @Test
    fun hasMore_passedThrough() {
        val loaded = GraphCommit("a1b2c3", "a1b2c3", "Alice", "d", "fix", parents = emptyList())
        assertTrue(GitGraphBuilder.buildGraph(listOf(loaded), emptyMap(), hasMore = true).hasMore)
        assertFalse(GitGraphBuilder.buildGraph(listOf(loaded), emptyMap(), hasMore = false).hasMore)
    }

    // ── buildGraph: lane layout ────────────────────────────────────

    @Test
    fun simpleChain_computesSingleLane() {
        val head = GraphCommit("h1h2h3", "h1h2h3", "Alice", "d", "c2", parents = listOf("r0r0r0"))
        val root = GraphCommit("r0r0r0", "r0r0r0", "Bob", "d", "c1", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(head, root), emptyMap(), hasMore = false)
        assertEquals(mapOf("h1h2h3" to 0, "r0r0r0" to 0), graph.lanes)
        assertEquals(listOf(GraphEdge(0, 0, 0, isMergeIn = false)), graph.edges)
        assertEquals(0, graph.maxLane)
    }

    @Test
    fun rootCommit_noEdges() {
        val root = GraphCommit("r0r0r0", "r0r0r0", "Alice", "d", "root", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(root), emptyMap(), hasMore = false)
        assertTrue(graph.edges.isEmpty())
        assertEquals(mapOf("r0r0r0" to 0), graph.lanes)
        assertEquals(0, graph.maxLane)
    }

    @Test
    fun fork_branchComputesCrossEdge() {
        // Two child commits pointing at the same parent (a fork): the second child takes a new lane and crosses columns to the parent
        val c1 = GraphCommit("c1c1c1", "c1c1c1", "Alice", "d", "branch A", parents = listOf("r0r0r0"))
        val c2 = GraphCommit("c2c2c2", "c2c2c2", "Bob", "d", "branch B", parents = listOf("r0r0r0"))
        val root = GraphCommit("r0r0r0", "r0r0r0", "Dan", "d", "root", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(c1, c2, root), emptyMap(), hasMore = false)
        assertEquals(mapOf("c1c1c1" to 0, "c2c2c2" to 1, "r0r0r0" to 0), graph.lanes)
        assertEquals(
            listOf(
                GraphEdge(0, 0, 0, isMergeIn = false),
                GraphEdge(1, 0, 1, isMergeIn = false)
            ),
            graph.edges
        )
        assertEquals(1, graph.maxLane)
    }

    @Test
    fun mergeCommit_computesMergeInEdge() {
        // Merge commit: the second parent takes a new lane, producing an isMergeIn incoming edge
        val merge = GraphCommit("m1m1m1", "m1m1m1", "Alice", "d", "Merge", parents = listOf("p1p1p1", "p2p2p2"))
        val p1 = GraphCommit("p1p1p1", "p1p1p1", "Bob", "d", "feat x", parents = listOf("r0r0r0"))
        val p2 = GraphCommit("p2p2p2", "p2p2p2", "Carol", "d", "feat y", parents = listOf("r0r0r0"))
        val root = GraphCommit("r0r0r0", "r0r0r0", "Dan", "d", "root", parents = emptyList())
        val graph = GitGraphBuilder.buildGraph(listOf(merge, p1, p2, root), emptyMap(), hasMore = false)
        assertTrue(graph.commits[0].isMerge)
        assertEquals(mapOf("m1m1m1" to 0, "p1p1p1" to 0, "p2p2p2" to 1, "r0r0r0" to 0), graph.lanes)
        assertEquals(4, graph.edges.size)
        // Incoming edge of the second parent: fromLane=current column 0, toLane=parent column 1, lane=parent column 1, isMergeIn=true
        assertTrue(graph.edges.any { it.isMergeIn && it.fromLane == 0 && it.toLane == 1 && it.lane == 1 })
        assertEquals(1, graph.maxLane)
    }
}