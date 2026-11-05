package com.xencode.core

import com.xencode.core.goal.GoalDagEngine
import org.junit.jupiter.api.Test
import com.xencode.core.goal.GoalDagFile
import com.xencode.core.goal.GoalDagResult
import com.xencode.core.goal.GoalStatus
import com.xencode.core.telemetry.XpLedger
import com.xencode.core.telemetry.XpMath
import com.xencode.core.tools.LocalTools
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Pure-logic coverage for the shared PC core: goal DAG, XP math, tool sandboxing. */
class CoreTest {

    @TempDir
    lateinit var tmpDir: File

    // ---- Goal DAG ----

    @Test
    fun goalDag_addCompletePropagates_andRoundTripsThroughJson() {
        var g = GoalDagFile.empty()
        g = (GoalDagEngine.addGoal(g, "Base", "", emptyList(), 1L) as GoalDagResult.Ok).graph
        g = (GoalDagEngine.addGoal(g, "Tests", "", listOf("g1"), 2L) as GoalDagResult.Ok).graph
        assertEquals(GoalStatus.PENDING, g.nodes.first { it.id == "g2" }.status)
        g = (GoalDagEngine.completeGoal(g, "g1", "ok", 3L) as GoalDagResult.Ok).graph
        assertEquals(GoalStatus.READY, g.nodes.first { it.id == "g2" }.status)

        // Persist + reload through the file codec.
        val ws = File(tmpDir, "ws").also { it.mkdirs() }
        assertTrue(GoalDagFile.save(ws.absolutePath, g))
        val loaded = GoalDagFile.load(ws.absolutePath)
        assertEquals(g.nodes, loaded.nodes)
        assertEquals(g.nextId, loaded.nextId)
        assertEquals(50, loaded.progressPercent)
    }

    @Test
    fun goalDag_rejectsUnknownAndSelfDependencies() {
        val g = GoalDagFile.empty()
        assertTrue(GoalDagEngine.addGoal(g, "X", "", listOf("g99"), 1L) is GoalDagResult.Rejected)
        assertTrue(GoalDagEngine.completeGoal(g, "g99", null, 1L) is GoalDagResult.Rejected)
    }

    // ---- XP math ----

    @Test
    fun xpMath_curveAwardsAndStreaks() {
        assertEquals(100L, XpMath.xpForLevel(1))
        assertEquals(400L, XpMath.xpForLevel(2))
        assertEquals(1, XpMath.levelFor(100))
        assertEquals(2, XpMath.levelFor(400))
        assertEquals(5L, XpMath.turnAward())
        assertEquals(50L, XpMath.goalAward())
        assertEquals(0L, XpMath.dailyStreakBonus(1))
        assertEquals(50L, XpMath.dailyStreakBonus(30))

        val days = setOf(98L, 99L, 100L)
        assertEquals(3, XpMath.currentStreak(days, todayEpochDay = 100))
        assertEquals(0, XpMath.currentStreak(setOf(95L), todayEpochDay = 100))
    }

    @Test
    fun xpLedger_awardsAndBonusOnFirstEventOfDay() {
        val f = File(tmpDir, "ledger.json")
        val ledger = XpLedger(f)
        assertTrue(ledger.load().isEmpty())

        ledger.awardTurn("turn 1")
        val one = ledger.summary()
        assertEquals(5L, one.totalXp) // no bonus yet: day 1 of the streak
        assertEquals(0, one.level)   // 5 XP is below the 100 XP level-1 threshold

        ledger.awardGoal("ship it")
        val two = ledger.summary()
        // second event today: no second bonus, totals = 5 + 50
        assertEquals(55L, two.totalXp)
    }

    // ---- Local tools sandbox ----

    @Test
    fun localTools_resolveRejectsEscape() {
        val ws = File(tmpDir, "ws2").also { it.mkdirs() }
        File(ws, "a.txt").writeText("hello")
        File(ws, "nested").mkdirs()

        val tools = LocalTools(ws)
        assertNotNull(LocalTools.resolve(ws, "a.txt"))
        assertNull(LocalTools.resolve(ws, "../outside.txt"))
        assertEquals("hello", tools.execute("read_file", """{"path":"a.txt"}"""))
        assertTrue(tools.execute("list_files", "{}").contains("a.txt"))
        // Leading underscores: some sandboxed CI runners lose the very first byte of piped
        // child-process output; "MARKER" must survive that to prove the command really ran.
        val rc = tools.execute("run_command", """{"command":"echo __MARKER__"}""")
        assertTrue(rc.contains("MARKER"), "run_command result=[$rc]")
        val esc = tools.execute("read_file", """{"path":"../x"}""")
        assertTrue(esc.startsWith("ERROR"), "actual=[$esc] resolve=${LocalTools.resolve(ws, "../x")}")
        assertTrue(tools.execute("bogus_tool", "{}").startsWith("ERROR"))
    }
}
