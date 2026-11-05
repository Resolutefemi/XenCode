package com.xencode.feature.agent.domain.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Branch coverage for [GoalDagEngine]: id minting, READY propagation, terminal
 * transitions, self/unknown dependency rejection, blockers, and progress math.
 */
class GoalDagEngineTest {

    private fun emptyGraph(): GoalDag = GoalDag(nodes = emptyList(), nextId = 1, updatedAt = 1_000L)

    private fun add(vararg titles: String, deps: List<String> = emptyList(), graph: GoalDag = emptyGraph()): GoalDag {
        var g = graph
        for ((i, t) in titles.withIndex()) {
            val r = GoalDagEngine.addGoal(g, t, "", if (i == 0) deps else emptyList(), 1_000L + i) as GoalDagResult.Ok
            g = r.graph
        }
        return g
    }

    @Test
    fun addGoal_mintsSequentialIds_andIsReadyWithoutDeps() {
        val g = add("Set up container", "Write README")
        assertEquals(listOf("g1", "g2"), g.nodes.map { it.id })
        assertEquals(GoalStatus.READY, g.nodes[0].status)
        assertEquals(GoalStatus.READY, g.nodes[1].status)
        assertEquals(2, g.nextId)
    }

    @Test
    fun addGoal_withDeps_startsPending_untilDepsDone() {
        var g = add("Base")
        val r = GoalDagEngine.addGoal(g, "Tests", "", listOf("g1"), 2_000L) as GoalDagResult.Ok
        g = r.graph
        assertEquals(GoalStatus.PENDING, g.nodes[1].status)
        assertEquals(listOf("g1"), g.nodes[1].dependsOn)
    }

    @Test
    fun addGoal_rejectsUnknownDependency_emptyTitle_andSelfReference() {
        val g = add("Base")
        assertTrue(GoalDagEngine.addGoal(g, "X", "", listOf("g9"), 1L) is GoalDagResult.Rejected)
        assertTrue(GoalDagEngine.addGoal(emptyGraph(), "   ", "", emptyList(), 1L) is GoalDagResult.Rejected)
        // next id will be g2: a brand-new goal referencing it is rejected before it exists
        assertTrue(GoalDagEngine.addGoal(g, "X", "", listOf("g2"), 1L) is GoalDagResult.Rejected)
    }

    @Test
    fun completeGoal_propagatesReadyToDependents() {
        var g = add("Base")
        g = (GoalDagEngine.addGoal(g, "Tests", "", listOf("g1"), 2_000L) as GoalDagResult.Ok).graph
        g = (GoalDagEngine.addGoal(g, "Ship", "", listOf("g2"), 3_000L) as GoalDagResult.Ok).graph
        assertEquals(GoalStatus.PENDING, g.nodes.first { it.id == "g3" }.status)

        g = (GoalDagEngine.completeGoal(g, "g1", "container OK", 4_000L) as GoalDagResult.Ok).graph
        assertEquals(GoalStatus.DONE, g.nodes[0].status)
        assertEquals(GoalStatus.READY, g.nodes.first { it.id == "g2" }.status)
        // g3 still waits on g2
        assertEquals(GoalStatus.PENDING, g.nodes.first { it.id == "g3" }.status)
        assertEquals(listOf("4000 container OK"), g.nodes[0].notes)

        g = (GoalDagEngine.completeGoal(g, "g2", null, 5_000L) as GoalDagResult.Ok).graph
        assertEquals(GoalStatus.READY, g.nodes.first { it.id == "g3" }.status)
    }

    @Test
    fun completeGoal_rejectsUnknownDoubleDone_andTerminalConflicts() {
        val g = add("Base")
        assertTrue(GoalDagEngine.completeGoal(g, "g9", null, 1L) is GoalDagResult.Rejected)
        val done = (GoalDagEngine.completeGoal(g, "g1", null, 1L) as GoalDagResult.Ok).graph
        assertTrue(GoalDagEngine.completeGoal(done, "g1", null, 2L) is GoalDagResult.Rejected)
        assertTrue(GoalDagEngine.failGoal(done, "g1", null, 2L) is GoalDagResult.Rejected)
    }

    @Test
    fun failThenRetry_roundTripsToReady() {
        var g = add("Base", "Write README")
        g = (GoalDagEngine.activateGoal(g, "g1", 1L) as GoalDagResult.Ok).graph
        assertEquals(GoalStatus.ACTIVE, g.nodes[0].status)
        g = (GoalDagEngine.failGoal(g, "g1", "flaky", 2L) as GoalDagResult.Ok).graph
        assertEquals(GoalStatus.FAILED, g.nodes[0].status)
        g = (GoalDagEngine.retryGoal(g, "g1", 3L) as GoalDagResult.Ok).graph
        assertEquals(GoalStatus.READY, g.nodes[0].status)
    }

    @Test
    fun activateGoal_onlyFromReady() {
        var g = add("Base")
        g = (GoalDagEngine.addGoal(g, "Tests", "", listOf("g1"), 2_000L) as GoalDagResult.Ok).graph
        // g2 is PENDING: activating it must be rejected
        assertTrue(GoalDagEngine.activateGoal(g, "g2", 1L) is GoalDagResult.Rejected)
        g = (GoalDagEngine.activateGoal(g, "g1", 1L) as GoalDagResult.Ok).graph
        assertEquals(GoalStatus.ACTIVE, g.nodes[0].status)
    }

    @Test
    fun cancelGoal_blocksDependentsAsPending() {
        var g = add("Base")
        g = (GoalDagEngine.addGoal(g, "Tests", "", listOf("g1"), 2_000L) as GoalDagResult.Ok).graph
        g = (GoalDagEngine.cancelGoal(g, "g1", 3_000L) as GoalDagResult.Ok).graph
        assertEquals(GoalStatus.CANCELLED, g.nodes[0].status)
        assertEquals(GoalStatus.PENDING, g.nodes[1].status)
        // new goals may not depend on a cancelled node
        assertTrue(GoalDagEngine.addGoal(g, "X", "", listOf("g1"), 4_000L) is GoalDagResult.Rejected)
    }

    @Test
    fun readyNodes_andBlockers_listCorrectly() {
        var g = add("A", "B")
        g = (GoalDagEngine.addGoal(g, "C", "", listOf("g1", "g2"), 3_000L) as GoalDagResult.Ok).graph
        assertEquals(listOf("g1", "g2"), GoalDagEngine.readyNodes(g).map { it.id })
        assertEquals(2, GoalDagEngine.blockers(g, "g3").size)
        assertEquals(0, GoalDagEngine.blockers(g, "g1").size)
        assertEquals(emptyList<GoalNode>(), GoalDagEngine.blockers(g, "g9"))
    }

    @Test
    fun progressMath_coversEmptyAndFull() {
        assertEquals(100, emptyGraph().progressPercent)
        var g = add("A", "B")
        assertEquals(0, g.progressPercent)
        g = (GoalDagEngine.completeGoal(g, "g1", null, 1L) as GoalDagResult.Ok).graph
        assertEquals(50, g.progressPercent)
        assertEquals(1, g.doneCount)
        assertEquals(1, g.totalActive)
    }
}
