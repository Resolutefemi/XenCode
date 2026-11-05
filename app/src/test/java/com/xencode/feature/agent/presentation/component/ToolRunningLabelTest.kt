package com.xencode.feature.agent.presentation.component

import com.xencode.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping tool names → running-status labels.
 *
 * This table decides whether the chat's "what it's doing" line reads like a human wrote it: keys must match ToolRegistry registration names (case-insensitive),
 * and unclassified ones (dynamic MCP tools, custom tools, missing tool names) must fall through to the generic fallback — never guess the wrong scenario.
 */
class ToolRunningLabelTest {

    @Test
    fun fileEditingTools_mapToEditingFile() {
        assertEquals(R.string.chat_status_editing_file, toolRunningLabelRes("editFile"))
        assertEquals(R.string.chat_status_editing_file, toolRunningLabelRes("writeFile"))
    }

    @Test
    fun fileReadingTools_mapToReadingFile() {
        listOf("readFile", "list", "sendFile", "viewImage").forEach { name ->
            assertEquals(name, R.string.chat_status_reading_file, toolRunningLabelRes(name))
        }
    }

    @Test
    fun networkTools_mapToSearchingWeb() {
        listOf("search", "websearch", "webFetch").forEach { name ->
            assertEquals(name, R.string.chat_status_searching_web, toolRunningLabelRes(name))
        }
    }

    @Test
    fun shellTools_mapToRunningCommand() {
        assertEquals(R.string.chat_status_running_command, toolRunningLabelRes("bash"))
        assertEquals(R.string.chat_status_running_command, toolRunningLabelRes("terminal"))
    }

    @Test
    fun miscTools_mapToTheirOwnScenario() {
        assertEquals(R.string.chat_status_generating_image, toolRunningLabelRes("generateImage"))
        assertEquals(R.string.chat_status_updating_todo, toolRunningLabelRes("todo"))
        assertEquals(R.string.chat_status_starting_subagent, toolRunningLabelRes("task"))
        assertEquals(R.string.chat_status_reading_memory, toolRunningLabelRes("memory"))
    }

    @Test
    fun unknownOrMissingTool_fallsBackToGenericLabel() {
        // Dynamic MCP tools, custom tools, missing tool names: all show "calling tool", never guessing a specific scenario
        listOf("mcp__filesystem__read", "custom_tool", "", null).forEach { name ->
            assertEquals("$name", R.string.chat_status_calling_tool, toolRunningLabelRes(name))
        }
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertEquals(toolRunningLabelRes("editfile"), toolRunningLabelRes("EditFile"))
        assertEquals(toolRunningLabelRes("bash"), toolRunningLabelRes("BASH"))
    }
}
