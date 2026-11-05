package com.xencode.desktop

import com.xencode.core.goal.GoalDagResult
import com.xencode.core.goal.GoalStatus
import com.xencode.core.telemetry.XpLedger
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * XP-wiring tests: the award constants [DesktopAgentController] and
 * [GoalBoard] guard on, the ledger path they write to, and the full
 * GoalBoard mutation flow (engine + file save + goal XP) against a
 * temp workspace and temp ledger. Never touches ~/.xencode.
 */
class XpWiringTest {

    @Test
    fun awardsMatchTheCoreCurve() {
        assertEquals(5L, DesktopXp.turnAwardFor(turnSuccessful = true))
        assertEquals(0L, DesktopXp.turnAwardFor(turnSuccessful = false))
        assertEquals(50L, DesktopXp.goalAwardFor(goalCompleted = true))
        assertEquals(0L, DesktopXp.goalAwardFor(goalCompleted = false))
    }

    @Test
    fun ledgerPathIsThePerMachineLedger() {
        assertTrue(
            DesktopXp.ledgerPath().replace('\\', '/').endsWith(".xencode/pc-xp-ledger.json"),
            "got: ${DesktopXp.ledgerPath()}"
        )
    }

    @Test
    fun turnAwardLandsInTheLedgerAsFiveXp() {
        // Mirrors DesktopAgentController.awardTurnXp: guard, then ledger.awardTurn.
        val home = createTempDirectory(prefix = "xencode-turn-ledger")
        val ledger = XpLedger(File(home.toFile(), "pc-xp-ledger.json"))
        try {
            if (DesktopXp.turnAwardFor(turnSuccessful = true) > 0L) {
                ledger.awardTurn("demo question")
            }
            val summary = ledger.summary()
            assertEquals(5L, summary.totalXp)
            assertEquals("TURN_COMPLETED", summary.recent.single().kind)
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    @Test
    fun goalBoardSavesDagAndAwardsGoalXp() {
        val workspace = createTempDirectory(prefix = "xencode-ws")
        val ledgerHome = createTempDirectory(prefix = "xencode-goal-ledger")
        try {
            val ledger = XpLedger(File(ledgerHome.toFile(), "pc-xp-ledger.json"))
            val board = GoalBoard(workspace.toString(), ledger)

            assertTrue(board.add("Ship the desktop build", emptyList(), "") is GoalDagResult.Ok)
            assertTrue(board.add("Blocked", listOf("g99"), "") is GoalDagResult.Rejected)

            val goal = board.dag.nodes.single()
            assertEquals(GoalStatus.READY, goal.status)
            assertTrue(board.start(goal.id) is GoalDagResult.Ok)
            assertTrue(board.complete(goal.id) is GoalDagResult.Ok)
            assertEquals(GoalStatus.DONE, board.dag.nodes.single().status)

            // +50 for the goal; a first-ever award carries no streak bonus.
            val summary = ledger.summary()
            assertEquals(50L, summary.totalXp)
            assertEquals(1, summary.recent.count { it.kind == "GOAL_COMPLETED" })

            // The DAG file is persisted for the next pane open / CLI run.
            val reloaded = GoalBoard(workspace.toString(), ledger)
            assertEquals(GoalStatus.DONE, reloaded.dag.nodes.single().status)
        } finally {
            workspace.toFile().deleteRecursively()
            ledgerHome.toFile().deleteRecursively()
        }
    }

    @Test
    fun goalActionsMatchTheStatusMachine() {
        assertEquals(listOf("Drop" to "drop"), actionsFor(GoalStatus.PENDING))
        assertEquals(
            listOf("Start" to "start", "Done" to "done", "Fail" to "fail", "Drop" to "drop"),
            actionsFor(GoalStatus.READY)
        )
        assertEquals(
            listOf("Done" to "done", "Fail" to "fail", "Drop" to "drop"),
            actionsFor(GoalStatus.ACTIVE)
        )
        assertEquals(listOf("Retry" to "retry", "Drop" to "drop"), actionsFor(GoalStatus.FAILED))
        assertEquals(emptyList(), actionsFor(GoalStatus.DONE))
        assertEquals(emptyList(), actionsFor(GoalStatus.CANCELLED))
    }

    @Test
    fun systemPromptNamesWorkspaceAndTools() {
        val prompt = DesktopAgentController.systemPromptFor("/tmp/xencode-demo")
        assertTrue(prompt.contains("/tmp/xencode-demo"))
        assertTrue(prompt.contains("read_file"))
        assertTrue(prompt.contains("run_command"))
    }
}
