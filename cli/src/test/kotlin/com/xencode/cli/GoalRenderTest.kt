package com.xencode.cli

import com.xencode.core.goal.GoalDagFile
import com.xencode.core.goal.GoalDagEngine
import com.xencode.core.goal.GoalDagResult
import com.xencode.core.goal.GoalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Render-level coverage: the CLI output must match the Android /goal and /dag
 * render (glyph table, waiting-on lines, "Next up:", progress header, footer).
 */
class GoalRenderTest {

    private fun dag(): com.xencode.core.goal.GoalDag {
        var g = GoalDagFile.empty()
        g = ok(GoalDagEngine.addGoal(g, "Setup repo", "", emptyList(), 1L))
        g = ok(GoalDagEngine.addGoal(g, "Write tests", "", listOf("g1"), 2L))
        g = ok(GoalDagEngine.addGoal(g, "Ship CLI", "", listOf("g1", "g2"), 3L))
        g = ok(GoalDagEngine.completeGoal(g, "g1", null, 4L)) // unblocks g2
        return g
    }

    private fun ok(r: GoalDagResult): com.xencode.core.goal.GoalDag =
        (r as GoalDagResult.Ok).graph

    @Test
    fun emptyDagHasFriendlyHint() {
        val out = renderGoalDag("/ws", GoalDagFile.empty())
        assertTrue(out.contains("**Goal DAG** — no goals yet for this workspace."))
        assertTrue(out.contains("Add one with `xencode goal add <title> [after g2 g5] [note <text>]`."))
        // Android parity: the empty branch returns early, so no file footer is printed.
        assertFalse(out.contains("_File:"))
    }

    @Test
    fun fullRenderHasHeaderGlyphsWaitingAndNextUp() {
        val out = renderGoalDag("/ws", dag())
        // Progress header reflects 1/3 done.
        assertTrue(out.contains("**Goal DAG** — 1/3 done (33%)"))
        // Line per node with the exact glyph prefix (deps render as _(g1 g2)_ after the title).
        assertTrue(out.contains("- ✓ `g1` Setup repo — **DONE**"))
        assertTrue(out.contains("- ▸ `g2` Write tests _(g1)_ — **READY**"))
        assertTrue(out.contains("- · `g3` Ship CLI _(g1 g2)_ — **PENDING**"))
        // Pending node with unfinished deps gets a waiting-on line.
        assertTrue(out.contains("    - waiting on: `g2` Write tests"))
        // Ready nodes are surfaced after the list.
        assertTrue(out.contains("Next up: `g2` Write tests"))
        // Footer names the file (root passed through, trailing slash trimmed).
        assertTrue(out.contains("_File: `/ws/.xencode/GOAL_DAG.json`_"))
    }

    @Test
    fun headerOnlySuppressesBodyButKeepsHeaderAndFooter() {
        val out = renderGoalDag("/ws", dag(), headerOnly = true)
        assertTrue(out.contains("**Goal DAG** — 1/3 done (33%)"))
        assertFalse(out.contains("Next up:"))
        assertFalse(out.contains("waiting on:"))
        assertTrue(out.contains("_File: `/ws/.xencode/GOAL_DAG.json`_"))
    }

    @Test
    fun glyphTableCoversEveryStatus() {
        assertEquals("✓", statusGlyph(GoalStatus.DONE))
        assertEquals("●", statusGlyph(GoalStatus.ACTIVE))
        assertEquals("▸", statusGlyph(GoalStatus.READY))
        assertEquals("✗", statusGlyph(GoalStatus.FAILED))
        assertEquals("⊘", statusGlyph(GoalStatus.CANCELLED))
        assertEquals("·", statusGlyph(GoalStatus.PENDING))
    }

    @Test
    fun waitingOnLineOmitsFinishedDeps() {
        var g = dag()
        val byId = g.nodes.associateBy { it.id }
        val g3 = g.nodes.first { it.id == "g3" }
        val line = waitingOnLine(g3, byId)
        // g1 is DONE, so only g2 remains.
        assertEquals("    - waiting on: `g2` Write tests", line)
        // A node with all deps done has no waiting line.
        val g2 = g.nodes.first { it.id == "g2" }
        assertEquals(null, waitingOnLine(g2, byId))
    }
}
