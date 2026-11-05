package com.xencode.core.agent

import com.xencode.core.goal.GoalDagEngine
import com.xencode.core.goal.GoalDagFile
import com.xencode.core.goal.GoalDagResult
import com.xencode.core.goal.GoalStatus
import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.ChatTransport
import com.xencode.core.provider.ProviderConfig
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

/**
 * GoalRunner with a scripted transport: nodes complete, the DAG flips to DONE,
 * XP is awarded and events land in the ndjson log. Also Worktrees (git-backed,
 * skipped without git).
 */
class GoalRunnerTest {

    @TempDir
    lateinit var tmp: File

    /** Replies "goal done: <first goal word>" once per request. */
    private class ScriptedTransport : ChatTransport {
        var calls = 0
        override fun streamChat(
            config: ProviderConfig,
            messages: List<ChatClient.ChatMessage>,
            tools: List<ChatClient.ToolSpec>,
            systemPrompt: String?
        ) = flow {
            calls++
            val goal = messages.last { it.role == "user" }.content!!.lineSequence()
                .firstOrNull { it.startsWith("Goal ") } ?: "unknown"
            emit(ChatClient.ChatEvent.TextDelta("goal done: $goal"))
            emit(ChatClient.ChatEvent.Completed)
        }
    }

    @Test
    fun goalRunner_completesReadyNodesSequentiallyAndAwardsXp() = runBlocking {
        val ws = File(tmp, "ws").also { it.mkdirs() }
        val provider = ProviderConfig(name = "t", kind = "openai", baseUrl = "http://x", apiKey = "k", model = "m")
        val runtime = AgentRuntime(ws, provider, ScriptedTransport(), ledgerFile = File(tmp, "ledger1.json"))
        val transport = ScriptedTransport()

        // g1 (ready) <- g2 depends on g1
        var dag = GoalDagFile.empty()
        dag = (GoalDagEngine.addGoal(dag, "first goal", "", emptyList(), 1L) as GoalDagResult.Ok).graph
        dag = (GoalDagEngine.addGoal(dag, "second goal", "", listOf("g1"), 2L) as GoalDagResult.Ok).graph
        GoalDagFile.save(ws.absolutePath, dag)

        val runner = GoalRunner(ws, provider, transport, runtime)
        val summary = runner.run(parallel = 1)

        assertEquals(2, summary.doneCount)
        assertEquals(0, summary.failedCount)
        assertTrue(summary.allDone)

        val after = GoalDagFile.load(ws.absolutePath)
        assertEquals(GoalStatus.DONE, after.nodes.first { it.id == "g1" }.status)
        assertEquals(GoalStatus.DONE, after.nodes.first { it.id == "g2" }.status)
        assertTrue(after.nodes.first { it.id == "g1" }.notes.single().contains("goal done"))
        // XP awarded for both goals.
        assertEquals(100L, runtime.ledger.summary().totalXp)
        // NODE_COMPLETED events for both.
        val lines = runtime.events.latest(50)
        assertTrue(lines.count { it.contains("NODE_COMPLETED") } >= 2, lines.toString())
    }

    @Test
    fun goalRunner_failedNodeMarksFailedAndBlocksDependants() = runBlocking {
        val ws = File(tmp, "ws2").also { it.mkdirs() }
        val provider = ProviderConfig(name = "t", kind = "openai", baseUrl = "http://x", apiKey = "k", model = "m")

        // Transport that always fails with a transport-level error.
        val failing = object : ChatTransport {
            override fun streamChat(
                config: ProviderConfig,
                messages: List<ChatClient.ChatMessage>,
                tools: List<ChatClient.ToolSpec>,
                systemPrompt: String?
            ) = flow {
                emit(ChatClient.ChatEvent.Failed("provider down"))
            }
        }

        var dag = GoalDagFile.empty()
        dag = (GoalDagEngine.addGoal(dag, "doomed", "", emptyList(), 1L) as GoalDagResult.Ok).graph
        dag = (GoalDagEngine.addGoal(dag, "dependent", "", listOf("g1"), 2L) as GoalDagResult.Ok).graph
        GoalDagFile.save(ws.absolutePath, dag)

        val runtime = AgentRuntime(ws, provider, failing, ledgerFile = File(tmp, "ledger2.json"))
        val runner = GoalRunner(ws, provider, failing, runtime)
        val summary = runner.run(parallel = 1)

        assertEquals(1, summary.failedCount)
        assertEquals(0, summary.doneCount)
        val after = GoalDagFile.load(ws.absolutePath)
        assertEquals(GoalStatus.FAILED, after.nodes.first { it.id == "g1" }.status)
        // Dependent stays pending (blocked), not run.
        assertEquals(GoalStatus.PENDING, after.nodes.first { it.id == "g2" }.status)
        assertEquals(0L, runtime.ledger.summary().totalXp)
    }

    // ---- Worktrees (git) ----

    @Test
    fun worktrees_createListRemove() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { ProcessBuilder("git", "--version").start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false),
            "git not available"
        )
        val ws = File(tmp, "repo").also { it.mkdirs() }
        fun git(vararg args: String) {
            val p = ProcessBuilder(listOf("git") + args).directory(ws).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        git("init", "-q"); git("config", "user.email", "t@t"); git("config", "user.name", "t")
        File(ws, "f.txt").writeText("x")
        git("add", "-A"); git("commit", "-qm", "init")

        assertTrue(Worktrees.isGitRepo(ws))
        val wt = Worktrees.create(ws, "g7")
        assertTrue(wt != null, Worktrees.lastError)
        assertEquals("xencode/g7", wt!!.branch)
        assertTrue(File(wt.path).isDirectory)
        assertEquals(1, Worktrees.list(ws).size)
        assertTrue(Worktrees.remove(ws, "g7"))
        assertEquals(0, Worktrees.list(ws).size)
    }
}
