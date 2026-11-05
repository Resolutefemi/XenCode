package com.xencode.feature.agent.domain.workflow

import com.xencode.feature.agent.data.local.dao.AgentMessageDao
import com.xencode.feature.agent.data.local.dao.LlmCallRecordDao
import com.xencode.feature.agent.data.local.entity.AgentMessageEntity
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.model.id
import com.xencode.feature.agent.domain.prompt.SystemPromptProvider
import com.xencode.feature.agent.domain.provider.AIProvider
import com.xencode.feature.agent.domain.provider.AIResponse
import com.xencode.feature.agent.domain.session.MessagePersistenceUseCase
import com.xencode.feature.agent.domain.tool.ToolCall
import com.xencode.feature.settings.data.remote.ModelMetadataService
import com.xencode.feature.settings.data.repository.GeneralSettingsRepository
import com.xencode.feature.settings.domain.model.ModelMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContextCompactorTest {
    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val records = mockk<LlmCallRecordDao>(relaxed = true)
    private val metadata = mockk<ModelMetadataService>()
    private val prompts = mockk<SystemPromptProvider>()
    private val settings = mockk<GeneralSettingsRepository>()
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)
    private val compactor = ContextCompactor(dao, metadata, prompts, records, settings, persistence)

    private fun prepare(context: Int = 16_000) {
        every { provider.providerId } returns "provider"
        every { provider.model } returns "summary"
        every { provider.maxOutputTokens } returns 8_000
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata(id = "summary", contextTokens = context)
        coEvery { settings.compactionThresholdPercent() } returns 90
        every { prompts.resolvePrompt(any()) } returns "{{INSTRUCTION}}"
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("Concise handoff", stopReason = "stop")
    }

    private fun history(size: Int = 20_000): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "old", content = "汉".repeat(size)),
        AgentMessage.AssistantMessage(id = "answer", content = "done"),
        AgentMessage.UserMessage(id = "goal", content = "continue the task")
    )

    /** A session where one oversized tool output blows the context: most "context over threshold" cases are exactly this. */
    private fun toolHeavyHistory(toolChars: Int = 20_000): List<AgentMessage> = listOf(
        AgentMessage.UserMessage(id = "goal", content = "run the command"),
        AgentMessage.AssistantMessage(id = "call", content = "", toolCalls = listOf(ToolCall("c1", "shell", emptyMap()))),
        AgentMessage.ToolResultMessage(id = "c1", toolName = "shell", result = "R".repeat(toolChars)),
        AgentMessage.AssistantMessage(id = "reply", content = "done"),
        AgentMessage.UserMessage(id = "next", content = "continue")
    )

    @Test
    fun projectionUsesModelResultAndPreservesBothEnds() {
        val message = AgentMessage.ToolResultMessage(id = "call", toolName = "read", result = "not sent to model",
            modelResult = "BEGIN" + "x".repeat(3_000) + "END")
        val text = CompactionText.project(message)
        assertTrue(text.contains("BEGIN"))
        assertTrue(text.endsWith("END"))
        assertFalse(text.contains("not sent to model"))
        assertEquals(3_008, message.modelResult!!.length)
    }

    @Test
    fun projectionRemovesMediaAndProtocolThinkingForEveryRole() {
        val image = AgentImage("image/png", "secret-media")
        val messages = listOf(
            AgentMessage.UserMessage(content = "data:image/png;base64,c2VjcmV0", images = listOf(image)),
            AgentMessage.AssistantMessage(content = "answer", reasoning = "private-thinking", signature = "signature",
                thinkingBlocksJson = "snapshot", images = listOf(image)),
            AgentMessage.ToolResultMessage(toolName = "image", result = "{\"images\":[{\"base64Data\":\"secret-media\"}],\"value\":\"kept\"}", images = listOf(image))
        )
        val text = messages.joinToString { CompactionText.project(it) }
        assertFalse(text.contains("secret-media"))
        assertFalse(text.contains("c2VjcmV0"))
        assertFalse(text.contains("private-thinking"))
        assertFalse(text.contains("signature"))
        assertFalse(text.contains("snapshot"))
        assertTrue(text.contains("kept"))
    }

    @Test
    fun splitKeepsAllToolResultsWithAssistant() {
        val messages = listOf(
            AgentMessage.UserMessage(content = "goal"),
            AgentMessage.AssistantMessage(content = "", toolCalls = listOf(ToolCall("one", "read", emptyMap()), ToolCall("two", "read", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "one", toolName = "read", result = "one"),
            AgentMessage.ToolResultMessage(id = "two", toolName = "read", result = "two")
        )
        assertEquals(1, CompactionText.adjustSplitIndex(messages, 3))
        assertEquals(2, CompactionText.units(messages).size)
    }

    @Test
    fun splitDoesNotSeparatePreviousSummaryPair() {
        val messages = listOf(
            AgentMessage.UserMessage(content = com.xencode.feature.agent.domain.model.CONTEXT_COMPACTION_MARKER),
            AgentMessage.AssistantMessage(content = "summary"),
            AgentMessage.UserMessage(content = "continue")
        )
        assertEquals(0, CompactionText.adjustSplitIndex(messages, 1))
    }

    @Test
    fun cursorNeverDiscardsOversizedOrLeadingAssistantMaterial() {
        val source = "汉".repeat(1_000)
        val cursor = CompactionText.Cursor(listOf(source, "LAST"))
        val chunks = mutableListOf<String>()
        repeat(32) { if (!cursor.finished) chunks.add(cursor.next(100)) }
        assertTrue(cursor.finished)
        assertEquals(1_000, chunks.sumOf { chunk -> chunk.count { it == '汉' } })
        assertTrue(chunks.last().contains("LAST"))
        assertTrue(chunks.all { CompactionText.tokens(it) <= 100 })
        assertTrue(CompactionText.units(listOf(AgentMessage.AssistantMessage(content = "leading"))).single().contains("leading"))
    }

    @Test
    fun multipleBlocksFusePreviousSummaryAndRetainLatestGoal() = runTest {
        prepare()
        val requests = mutableListOf<List<AgentMessage>>()
        coEvery { provider.complete(any(), capture(requests), any(), any()) } returns AIResponse("Concise handoff", stopReason = "stop")
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertTrue(result.compacted)
        assertTrue(requests.size > 1)
        assertTrue((requests[1].single() as AgentMessage.UserMessage).content.contains("<previous-summary>\nConcise handoff"))
        assertTrue(requests.flatten().all { it is AgentMessage.UserMessage })
        assertSame(original.last(), result.messages.last())
        coVerify { provider.maxOutputTokens = 8_000 }
        coVerify(exactly = requests.size) { records.insert(any()) }
    }

    @Test
    fun currentUsageTriggersBeforeSending() = runTest {
        prepare()
        val original = history(100)
        assertFalse(compactor.compactIfNeeded(original, provider).compacted)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        val events = mutableListOf<AgentEvent>()
        compactor.compactIfNeeded(original, provider, currentInputTokens = 99_999, onEvent = { events.add(it) })
        assertTrue(events.any { it is AgentEvent.CompactionStarted })
    }

    @Test
    fun truncatedSummaryIsKeptRatherThanDiscarded() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("partial handoff", stopReason = "length")
        val result = compactor.compactIfNeeded(history(), provider, force = true)
        assertTrue(result.compacted)
        val summary = result.messages[1] as AgentMessage.AssistantMessage
        assertTrue(summary.content.contains("partial handoff"))
        coVerify(exactly = 0) { records.insert(match { it.status == "error" }) }
    }

    @Test
    fun summaryCarriesAntiContinuationBoundary() = runTest {
        prepare()
        val result = compactor.compactIfNeeded(history(), provider, force = true)
        val summary = result.messages[1] as AgentMessage.AssistantMessage
        // The summary is an assistant message right after the marker; without a boundary the model treats it as its own unfinished words and continues writing.
        assertTrue(summary.content.startsWith(SUMMARY_HEADER))
        assertTrue(summary.content.endsWith(SUMMARY_FOOTER))
        assertTrue(summary.content.contains("Concise handoff"))
    }

    @Test
    fun failedSummaryNeverDropsHistoryOrCommits() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("")
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, sessionId = "session", force = true)
        // Fails honestly when there is no tool output to fold: the context does not shrink, but not a single message may be lost.
        // The old implementation dropped the whole head here and inserted a placeholder, leaving the model without context and producing garbage.
        assertFalse(result.compacted)
        assertEquals(original.map { it.id }, result.messages.map { it.id })
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
        coVerify(exactly = 0) { persistence.invalidateHistory(any()) }
    }

    @Test
    fun summaryTimeoutCountsAsFailureWithoutDroppingHistory() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } coAnswers { kotlinx.coroutines.delay(120_000) ; AIResponse("late") }
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertEquals(original.map { it.id }, result.messages.map { it.id })
    }

    @Test
    fun repeatedFailuresEnterCooldownAndStopCallingModel() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } returns AIResponse("")
        repeat(3) { compactor.compactIfNeeded(history(), provider, force = true) }
        coVerify(exactly = 3) { provider.complete(any(), any(), any(), any()) }
        val cooled = compactor.compactIfNeeded(history(), provider, force = true)
        assertFalse(cooled.compacted)
        coVerify(exactly = 3) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun microCompactAloneSolvesOverflowWithoutCallingModel() = runTest {
        prepare(context = 4_000)
        val original = toolHeavyHistory()
        val before = CompactionText.estimateRequest("", emptyList(), original)
        assertTrue("precondition: the context must actually exceed the threshold", before > 2_678)
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertTrue(result.compacted)
        assertTrue(CompactionText.estimateRequest("", emptyList(), result.messages) < before)
        // Micro-compaction never calls the model, so there is no timeout, no failure, and thus no way to reach the "drop history on failed summary" path.
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }
    }

    @Test
    fun microCompactKeepsEveryUserAndAssistantMessageIntact() = runTest {
        prepare(context = 4_000)
        val original = toolHeavyHistory()
        val kept = compactor.compactIfNeeded(original, provider, force = true).messages
        assertEquals(original.size, kept.size)
        original.indices.forEach { index ->
            when (val message = original[index]) {
                is AgentMessage.UserMessage, is AgentMessage.AssistantMessage -> assertEquals(message, kept[index])
                is AgentMessage.ToolResultMessage -> {
                    val folded = kept[index] as AgentMessage.ToolResultMessage
                    assertEquals(message.id, folded.id)
                    // The result read by UI and persistence stays complete — the user sees the output in full.
                    assertEquals(message.result, folded.result)
                    // Only the projection fed to the model is folded.
                    assertTrue(folded.modelResult!!.length < message.result.length)
                }
            }
        }
    }

    @Test
    fun microCompactPreservesRecentToolResultsAndToolCallPairing() = runTest {
        prepare(context = 4_000)
        // One huge early output + one small latest output: only the early one should be folded.
        val original = listOf(
            AgentMessage.UserMessage(id = "goal", content = "run"),
            AgentMessage.AssistantMessage(id = "call0", content = "", toolCalls = listOf(ToolCall("c0", "shell", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "c0", toolName = "shell", result = "R".repeat(20_000)),
            AgentMessage.UserMessage(id = "next", content = "and again"),
            AgentMessage.AssistantMessage(id = "call1", content = "", toolCalls = listOf(ToolCall("c1", "shell", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "c1", toolName = "shell", result = "small output")
        )
        val kept = compactor.compactIfNeeded(original, provider, force = true).messages
        val early = kept[2] as AgentMessage.ToolResultMessage
        val recent = kept[5] as AgentMessage.ToolResultMessage
        assertNull("recent tool output within budget should not be folded", recent.modelResult)
        assertEquals("small output", recent.result)
        assertEquals("R".repeat(20_000), early.result)
        assertNotNull(early.modelResult)
        assertTrue(early.modelResult!!.length < 20_000)
        // tool-call / tool-result pairing is preserved intact.
        assertEquals(listOf("c0", "c1"), kept.filterIsInstance<AgentMessage.AssistantMessage>().flatMap { it.toolCalls }.map { it.id })
    }

    @Test
    fun microCompactStubStillNamesTheToolSoItCanBeRerun() = runTest {
        // Small window + several oversized outputs force progressively deeper folding. The most recent result is protected and stays high-fidelity,
        // while earlier ones get compressed harder — matching Claude Code's microcompact behavior.
        prepare(context = 4_000)
        val original = buildList {
            add(AgentMessage.UserMessage(id = "goal", content = "run"))
            repeat(16) { index ->
                add(AgentMessage.AssistantMessage(id = "call$index", content = "",
                    toolCalls = listOf(ToolCall("c$index", "shell", emptyMap()))))
                add(AgentMessage.ToolResultMessage(id = "c$index", toolName = "shell", result = "R".repeat(200_000)))
            }
        }
        val events = mutableListOf<AgentEvent>()
        val result = compactor.compactIfNeeded(original, provider, force = true, onEvent = { events.add(it) })
        val kept = result.messages
        assertTrue(result.compacted)
        // Micro-compaction never calls the model: no timeout, no failure, and thus no way to reach the "drop history on failed summary" path.
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
        coVerify(exactly = 0) { dao.commitCompaction(any(), any(), any(), any()) }

        val folded = kept.filterIsInstance<AgentMessage.ToolResultMessage>().mapNotNull { it.modelResult }
        assertTrue("some tool output should have been folded", folded.isNotEmpty())
        // Every tier carries the tool name and original length: the model can tell which call it was and has a reference for whether to rerun.
        assertTrue(folded.joinToString("\n"), folded.all { it.contains("shell") && it.contains("200000") })
        // Earlier results are compressed harder: the most recent result is protected and keeps higher fidelity than the earliest.
        val lengths = kept.filterIsInstance<AgentMessage.ToolResultMessage>().map { (it.modelResult ?: it.result).length }
        assertTrue("earliest should be compressed harder than latest: $lengths", lengths.first() < lengths.last())
        // Not a single original output is lost on the persistence side.
        assertTrue(kept.filterIsInstance<AgentMessage.ToolResultMessage>().all { it.result == "R".repeat(200_000) })
    }

    @Test
    fun missingPersistedAnchorFailsBeforeSummaryCall() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns emptyList()
        val original = history()
        assertSame(original, compactor.compactIfNeeded(original, provider, sessionId = "session", force = true).messages)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun commitUsesHeadIdsAndEarliestTailTimestamp() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(
            AgentMessageEntity(id = "old", sessionId = "session", role = "USER", content = "history", timestamp = 50),
            AgentMessageEntity(id = "answer", sessionId = "session", role = "ASSISTANT", content = "done", timestamp = 200),
            AgentMessageEntity(id = "goal", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        )
        val result = compactor.compactIfNeeded(history(), provider, sessionId = "session", force = true)
        assertTrue(result.compacted)
        coVerify(exactly = 1) {
            dao.commitCompaction("session", listOf("old"), match { it.map { row -> row.timestamp } == listOf(98L, 99L) }, any())
        }
        coVerify(exactly = 1) { persistence.invalidateHistory("session") }
    }

    @Test
    fun adjustedEmptyHeadFailsWithoutCallingModel() = runTest {
        prepare()
        val original = listOf(
            AgentMessage.AssistantMessage(id = "assistant", content = "", toolCalls = listOf(ToolCall("call", "read", emptyMap()))),
            AgentMessage.ToolResultMessage(id = "call", toolName = "read", result = "result")
        )
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        coVerify(exactly = 0) { provider.complete(any(), any(), any(), any()) }
    }

    @Test
    fun mainSystemBudgetIsIncludedInFinalValidation() = runTest {
        prepare()
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, force = true, systemPrompt = "汉".repeat(20_000))
        assertFalse(result.compacted)
        assertSame(original, result.messages)
    }

    @Test
    fun transactionFailureReturnsOriginal() = runTest {
        prepare()
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(
            AgentMessageEntity(id = "old", sessionId = "session", role = "USER", content = "history", timestamp = 50),
            AgentMessageEntity(id = "goal", sessionId = "session", role = "USER", content = "goal", timestamp = 100)
        )
        coEvery { dao.commitCompaction(any(), any(), any(), any()) } throws IllegalStateException("transaction failed")
        val original = history()
        val result = compactor.compactIfNeeded(original, provider, sessionId = "session", force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
        coVerify(exactly = 0) { persistence.invalidateHistory(any()) }
    }

    @Test
    fun cancellationPropagatesAndRecordsFailure() = runTest {
        prepare()
        coEvery { provider.complete(any(), any(), any(), any()) } throws CancellationException("cancelled")
        try {
            compactor.compactIfNeeded(history(), provider, force = true)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            coVerify(exactly = 1) { records.insert(match { it.status == "error" }) }
            coVerify { provider.maxOutputTokens = 8_000 }
        }
    }

    @Test
    fun oversizedTailFailsWithoutReplacingHistory() = runTest {
        prepare()
        val original = history() + AgentMessage.UserMessage(id = "huge-tail", content = "汉".repeat(30_000))
        val result = compactor.compactIfNeeded(original, provider, force = true)
        assertFalse(result.compacted)
        assertSame(original, result.messages)
    }
}
