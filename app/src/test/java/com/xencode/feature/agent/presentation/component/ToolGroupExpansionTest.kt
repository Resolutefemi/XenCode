package com.xencode.feature.agent.presentation.component

import com.xencode.feature.agent.presentation.AgentAttachment
import com.xencode.feature.agent.presentation.AgentUIMessage
import com.xencode.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expansion rules for consecutive tool-call groups + whole-turn task folding.
 *
 * Group expanded or not = **the manually persisted choice from the upper layer** ([AIAgentViewModel.toolExpansionOverrides], keyed by [toolGroupKey]):
 * with no manual record, always collapsed — tool calls default to collapsed uniformly.
 *
 * Whole-turn folding (outermost) only honors [AIAgentViewModel.turnExpansionOverrides]; with no record, "the last running turn expands by default,
 * the rest collapse by default", and finishing a task auto-collapses as activeTurnKey goes null.
 *
 * Structure: tool groups and process items hang off the turn-header item's [ChatRenderItem.turnProcess] (same Composable as the folding header,
 * animated as a whole when expanded); top-level items are only "user messages / turn headers / always-visible items and results".
 */
class ToolGroupExpansionTest {

    private fun user(id: String) =
        AgentUIMessage(id = id, role = MessageRole.USER, content = "hi")

    private fun tool(id: String) = AgentUIMessage(id = id, role = MessageRole.TOOL, content = "done")

    private fun assistant(id: String) =
        AgentUIMessage(id = id, role = MessageRole.ASSISTANT, content = "take a look")

    private val groupKey = "toolgroup:t1"

    private fun items(
        messages: List<AgentUIMessage>,
        overrides: Map<String, Boolean> = emptyMap(),
        turnOverrides: Map<String, Boolean> = emptyMap(),
        activeTurnKey: String? = null,
    ) = buildChatItems(messages, overrides, turnOverrides, activeTurnKey)

    /** The single turn-header item among the top-level items. */
    private fun List<ChatRenderItem>.header() = first { it.turnHeader != null }

    /** All tool-group items carried by turn headers (flattened in turn-header order). */
    private fun List<ChatRenderItem>.groupItems(): List<ChatRenderItem> =
        flatMap { it.turnProcess }.filter { it.toolGroup != null }

    // ---- Consecutive tool-call groups ----

    @Test
    fun expandedTurn_groupCollapsesByDefault() {
        // Even with the whole turn expanded (last turn running), consecutive tool groups stay collapsed by default
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = "turn:u0")
        val groups = items.groupItems()
        assertEquals(1, groups.size)
        assertFalse(groups.first().groupExpanded)
    }

    @Test
    fun singleTool_isNotGrouped() {
        // A single tool is not folded into a "1 tool call" group; it shows as a plain tool row
        val items = items(listOf(user("u0"), tool("t1")), activeTurnKey = "turn:u0")
        assertEquals(0, items.groupItems().size)
        assertTrue(items.header().turnProcess.any { it.key == "t1" })
    }

    @Test
    fun noGroupAutoExpands() {
        // Regression: "a group member still running" or "this is the last group of an in-progress turn" used to auto-expand the whole group.
        // Now everything defaults to collapsed; historical and latest groups are treated alike.
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t3"), tool("t4"),
        )
        val groups = items(messages, activeTurnKey = "turn:u0").groupItems()
        assertEquals(2, groups.size)
        assertTrue("all collapsed by default", groups.none { it.groupExpanded })
    }

    @Test
    fun manualExpand_survivesRebuild() {
        val messages = listOf(user("u0"), tool("t1"), tool("t2"))
        val expanded = items(messages, overrides = mapOf(groupKey to true), activeTurnKey = "turn:u0")
        assertTrue(expanded.groupItems().first().groupExpanded)
        // Rebuilding with the same overrides (exactly the path list-scroll recycling / page-switch-return recomposition takes): state must agree
        val rebuilt = items(messages, overrides = mapOf(groupKey to true), activeTurnKey = "turn:u0")
        assertTrue(rebuilt.groupItems().first().groupExpanded)
        assertEquals(expanded.size, rebuilt.size)
    }

    @Test
    fun manualCollapse_isRespected() {
        val items = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            overrides = mapOf(groupKey to false),
            activeTurnKey = "turn:u0",
        )
        assertFalse("manually collapsed before: stays collapsed", items.groupItems().first().groupExpanded)
    }

    @Test
    fun userMessageBreaksGrouping() {
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            user("u1"),
            tool("t3"), tool("t4"),
        )
        val groups = items(messages, turnOverrides = mapOf("turn:u0" to true, "turn:u1" to true)).groupItems()
        assertEquals(2, groups.size)
        assertEquals("toolgroup:t1", groups[0].key)
        assertEquals("toolgroup:t3", groups[1].key)
    }

    @Test
    fun toolWithAttachments_isNotGrouped() {
        // File rows produced by sendFile / generateImage are the result itself: they take no part in the "N tool calls" folding,
        // nor in whole-turn folding (always visible). Each is a first-level item and also splits the tool batches on both sides.
        val file = AgentAttachment(
            fileName = "report.pdf",
            containerPath = "~/workspace/report.pdf",
            localPath = "/tmp/report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024,
            isImage = false,
        )
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            tool("send").copy(toolName = "sendFile", attachments = listOf(file)),
            tool("t3"), tool("t4"),
        )
        val items = items(messages, activeTurnKey = "turn:u0")
        // send is an always-visible item: a standalone top-level item
        assertTrue("a tool with attachments is an always-visible top-level item", items.any { it.key == "send" })
        val groups = items.groupItems()
        assertEquals(listOf("toolgroup:t1", "toolgroup:t3"), groups.map { it.key })
        assertTrue("groups collapse by default", groups.none { it.groupExpanded })
        // send is not a member of any group
        assertFalse(groups.any { group -> group.toolGroup.orEmpty().any { it.id == "send" } })
    }

    // ---- Whole-turn task folding ----

    @Test
    fun activeTurn_expandsByDefault() {
        // Last turn and the agent is busy: the folding header shows "running" and expands by default, process items mount as usual
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = "turn:u0")
        val header = items.header().turnHeader!!
        assertTrue(header.running)
        assertTrue(header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    @Test
    fun activeTurn_keepsAssistantBeforeFollowingTools() {
        val items = items(
            listOf(user("u0"), assistant("a1"), tool("t1"), tool("t2")),
            activeTurnKey = "turn:u0",
        )
        assertEquals(listOf("a1", "toolgroup:t1"), items.header().turnProcess.map { it.key })
        assertFalse(items.any { it.key == "a1" })
    }

    @Test
    fun activeTurn_keepsSuccessiveAssistantMessagesInOrder() {
        val messages = listOf(user("u0"), assistant("a1"), tool("t1"), assistant("a2"))
        val running = items(messages, activeTurnKey = "turn:u0")
        assertEquals(listOf("a1", "t1", "a2"), running.header().turnProcess.map { it.key })
        assertFalse(running.any { it.key == "a2" })

        val finished = items(messages, turnOverrides = mapOf("turn:u0" to true))
        assertEquals(listOf("a1", "t1"), finished.header().turnProcess.map { it.key })
        assertEquals(1, finished.count { it.key == "a2" })
    }

    @Test
    fun finishedTurn_autoCollapses() {
        // Finished (activeTurnKey goes null): the header switches to "completed" and collapses by default; process data remains, collapsed by animation
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = null)
        val header = items.header().turnHeader!!
        assertFalse(header.running)
        assertFalse(header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    @Test
    fun manualTurnExpand_survivesFinish() {
        // Expanded manually by the user: finishing the task does not auto-collapse
        val items = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            turnOverrides = mapOf("turn:u0" to true),
            activeTurnKey = null,
        )
        val header = items.header().turnHeader!!
        assertTrue(header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    @Test
    fun plainTextTurn_hasNoHeader() {
        // A plain-text turn with no process (no thinking, no tools): degrades to the old behavior, no folding header
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "a direct answer"),
        )
        val items = items(messages, activeTurnKey = null)
        assertTrue(items.none { it.turnHeader != null })
        assertTrue(items.any { it.key == "a1" })
    }

    @Test
    fun resultReasoning_becomesProcessItem() {
        // The turn-end assistant's thinking is extracted as a process item; the result body block no longer re-renders it
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "final answer", reasoning = "think a bit"),
        )
        val items = items(messages, turnOverrides = mapOf("turn:u0" to true), activeTurnKey = null)
        assertTrue("reasoning becomes a turn-header process item", items.header().turnProcess.any { it.key == "a1#reasoning" })
        assertFalse("the result body block does not re-render reasoning", items.first { it.key == "a1" }.reasoningVisible)
    }
}
