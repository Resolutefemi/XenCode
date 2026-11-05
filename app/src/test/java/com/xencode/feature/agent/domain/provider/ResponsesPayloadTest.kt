package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.data.remote.openai.ResponsesItem
import com.xencode.feature.agent.data.remote.openai.ResponsesPart
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ToolCall
import com.xencode.feature.agent.domain.tool.ToolParameter
import com.xencode.feature.agent.domain.tool.ToolResult
import com.google.gson.Gson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Responses API request body construction ([buildResponsesTools] / [buildResponsesInput]).
 * The coverage points are the two shapes that once caused 400s: nesting tool definitions in `function`, and keeping the Chat Completions style
 * assistant.tool_calls + role:"tool" messages for tool round-trips.
 */
class ResponsesPayloadTest {

    private class FakeTool(
        override val name: String,
        override val description: String = "desc",
        override val parameters: Map<String, ToolParameter> = emptyMap()
    ) : AgentTool() {
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Success(JsonPrimitive("ok"))
    }

    private fun call(id: String, name: String, path: String) =
        ToolCall(id = id, name = name, arguments = mapOf("path" to JsonPrimitive(path)))

    @Suppress("UNCHECKED_CAST")
    private fun parts(value: Any?): List<Map<String, Any>> = value as List<Map<String, Any>>

    @Test
    fun tool_definition_is_flat_without_function_wrapper() {
        val defs = buildResponsesTools(listOf(FakeTool("readFile")))
        assertEquals(1, defs?.size)
        val json = Gson().toJsonTree(defs!!.first()).asJsonObject
        assertEquals("function", json.get("type").asString)
        assertEquals("readFile", json.get("name").asString)
        assertTrue(json.has("description"))
        assertTrue(json.has("parameters"))
        // In the official schema strict carries no optional marker and must appear; tool schemas are not guaranteed strict subsets, so it is pinned to false
        assertFalse(json.get("strict").asBoolean)
        // The nested shape is rejected by the server with "tools[0]: missing field `name`"
        assertFalse(json.has("function"))
    }

    @Test
    fun no_tools_means_no_tools_field() {
        assertNull(buildResponsesTools(emptyList()))
    }

    @Test
    fun blank_system_prompt_adds_no_item() {
        val items = buildResponsesInput("", "system", emptyList())
        assertTrue(items.isEmpty())
    }

    @Test
    fun tool_round_trip_becomes_function_call_and_output_items() {
        val items = buildResponsesInput(
            systemPrompt = "you are helpful",
            systemRole = "system",
            messages = listOf(
                AgentMessage.UserMessage(id = "u1", content = "read a.txt"),
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "ok",
                    toolCalls = listOf(call("call_1", "readFile", "a.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "call_1", toolName = "readFile", result = "file body")
            )
        )

        assertEquals(5, items.size)
        assertEquals("system", items[0]["role"])
        assertEquals("you are helpful", items[0]["content"])
        assertEquals("user", items[1]["role"])
        assertEquals("read a.txt", items[1]["content"])

        assertEquals("assistant", items[2]["role"])
        val assistantParts = parts(items[2]["content"])
        assertEquals(ResponsesPart.OUTPUT_TEXT, assistantParts[0]["type"])
        assertEquals("ok", assistantParts[0]["text"])

        assertEquals(ResponsesItem.FUNCTION_CALL, items[3]["type"])
        assertEquals("call_1", items[3]["call_id"])
        assertEquals("readFile", items[3]["name"])
        assertEquals("""{"path":"a.txt"}""", items[3]["arguments"])

        assertEquals(ResponsesItem.FUNCTION_CALL_OUTPUT, items[4]["type"])
        assertEquals("call_1", items[4]["call_id"])
        assertEquals("file body", items[4]["output"])
    }

    @Test
    fun assistant_without_text_emits_only_function_call() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(id = "a1", content = "", toolCalls = listOf(call("c1", "listFiles", "."))),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "listFiles", result = "a.txt")
            )
        )
        assertEquals(2, items.size)
        assertEquals(ResponsesItem.FUNCTION_CALL, items[0]["type"])
        assertEquals(ResponsesItem.FUNCTION_CALL_OUTPUT, items[1]["type"])
    }

    @Test
    fun orphan_tool_result_is_dropped() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.ToolResultMessage(id = "ghost", toolName = "readFile", result = "body"),
                AgentMessage.UserMessage(id = "u1", content = "hi")
            )
        )
        assertEquals(1, items.size)
        assertEquals("user", items[0]["role"])
    }

    @Test
    fun call_without_result_is_dropped_together_with_the_call() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "about to run two tools",
                    toolCalls = listOf(call("c1", "readFile", "a.txt"), call("c2", "readFile", "b.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "body a")
            )
        )
        // The assistant text + c1's call and result; c2, refused by the user, does not appear, avoiding an upstream missing-tool-response error
        assertEquals(3, items.size)
        assertEquals("c1", items[1]["call_id"])
        assertEquals("c1", items[2]["call_id"])
        assertFalse(items.any { it["call_id"] == "c2" })
    }

    @Test
    fun out_of_order_results_are_paired_by_call_id() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "",
                    toolCalls = listOf(call("c1", "readFile", "a.txt"), call("c2", "readFile", "b.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "body b"),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "body a")
            )
        )
        assertEquals(4, items.size)
        assertEquals(listOf("c1", "c2", "c1", "c2"), items.map { it["call_id"] })
        assertEquals("body a", items[2]["output"])
        assertEquals("body b", items[3]["output"])
    }

    @Test
    fun multiple_calls_in_one_turn_are_written_before_their_outputs() {
        // Alternating call/result makes the server group the second call onward into a new assistant turn (a
        // function_call_output sits in between); under DeepSeek's thinking mode those turns without reasoning 400 outright
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "read three files in parallel",
                    toolCalls = listOf(
                        call("c1", "readFile", "a.txt"),
                        call("c2", "readFile", "b.txt"),
                        call("c3", "readFile", "c.txt")
                    )
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "a"),
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "b"),
                AgentMessage.ToolResultMessage(id = "c3", toolName = "readFile", result = "c")
            )
        )
        assertEquals(
            listOf(
                "assistant", ResponsesItem.FUNCTION_CALL, ResponsesItem.FUNCTION_CALL, ResponsesItem.FUNCTION_CALL,
                ResponsesItem.FUNCTION_CALL_OUTPUT, ResponsesItem.FUNCTION_CALL_OUTPUT, ResponsesItem.FUNCTION_CALL_OUTPUT
            ),
            items.map { it["type"] ?: it["role"] }
        )
        assertEquals(listOf("c1", "c2", "c3"), items.drop(4).map { it["call_id"] })
    }

    @Test
    fun user_images_become_input_image_parts() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.UserMessage(
                    id = "u1",
                    content = "what is this",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "AAAA"))
                )
            )
        )
        val content = parts(items[0]["content"])
        assertEquals(ResponsesPart.INPUT_TEXT, content[0]["type"])
        assertEquals(ResponsesPart.INPUT_IMAGE, content[1]["type"])
        assertEquals("data:image/png;base64,AAAA", content[1]["image_url"])
    }

    @Test
    fun tool_result_images_become_input_image_parts_in_output() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(id = "a1", content = "", toolCalls = listOf(call("c1", "viewImage", "a.png"))),
                AgentMessage.ToolResultMessage(
                    id = "c1",
                    toolName = "viewImage",
                    result = "screenshot below",
                    images = listOf(AgentImage(mimeType = "image/jpeg", base64Data = "BBBB"))
                )
            )
        )
        val output = parts(items[1]["output"])
        assertEquals(ResponsesPart.INPUT_TEXT, output[0]["type"])
        assertEquals(ResponsesPart.INPUT_IMAGE, output[1]["type"])
        assertEquals("data:image/jpeg;base64,BBBB", output[1]["image_url"])
    }

    @Test
    fun developer_role_is_used_for_reasoning_models() {
        val items = buildResponsesInput("prompt", "developer", emptyList())
        assertEquals("developer", items[0]["role"])
    }

    @Test
    fun reasoning_is_sent_back_as_item_before_assistant_content() {
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "read the file first",
                    reasoning = "need to look at the file contents first",
                    toolCalls = listOf(call("c1", "readFile", "a.txt"))
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "body")
            ),
            includeReasoningItems = true
        )
        // The reasoning item must sit immediately before its assistant content so the server can merge them into the same turn
        assertEquals(4, items.size)
        assertEquals(ResponsesItem.REASONING, items[0]["type"])
        val reasoningParts = parts(items[0]["content"])
        assertEquals(ResponsesPart.REASONING_TEXT, reasoningParts[0]["type"])
        assertEquals("need to look at the file contents first", reasoningParts[0]["text"])
        assertEquals("assistant", items[1]["role"])
        assertEquals(ResponsesItem.FUNCTION_CALL, items[2]["type"])
    }

    @Test
    fun reasoning_is_not_sent_back_by_default() {
        // Official path: a reasoning item needs id / summary / encrypted_content; plaintext alone gets rejected, so it is not sent
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(id = "a1", content = "ok", reasoning = "think first")
            )
        )
        assertEquals(1, items.size)
        assertFalse(items.any { it["type"] == ResponsesItem.REASONING })
    }

    @Test
    fun empty_reasoning_gets_placeholder_item_when_included() {
        // DeepSeek thinking mode: with tools in the request every assistant turn needs reasoning; when no thinking was stored, empty text is sent
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(AgentMessage.AssistantMessage(id = "a1", content = "ok")),
            includeReasoningItems = true
        )
        assertEquals(2, items.size)
        assertEquals(ResponsesItem.REASONING, items[0]["type"])
        assertEquals("", parts(items[0]["content"])[0]["text"])
    }

    @Test
    fun assistant_with_nothing_to_send_gets_no_reasoning_item() {
        // No body, and the only call pruned for a missing result (user refused to run): the whole turn writes no item,
        // otherwise the reasoning would become an orphan item with no adjacent assistant message to merge into
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "",
                    reasoning = "planning to call a tool",
                    toolCalls = listOf(call("c1", "readFile", "a.txt"))
                )
            ),
            includeReasoningItems = true
        )
        assertTrue(items.isEmpty())
    }

    @Test
    fun encrypted_reasoning_snapshot_is_sent_back_when_valid() {
        // OpenAI official / general Responses spec: when a snapshot with valid encrypted_content is stored, send back a legal reasoning item
        val snapshot = """{"type":"reasoning","encrypted_content":"gAAAAABtest","summary":[{"type":"summary_text","text":"analysis finished"}]}"""
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "final reply",
                    thinkingBlocksJson = snapshot
                )
            )
        )
        assertEquals(2, items.size)
        assertEquals(ResponsesItem.REASONING, items[0]["type"])
        assertEquals("gAAAAABtest", items[0]["encrypted_content"])
        @Suppress("UNCHECKED_CAST")
        val summary = items[0]["summary"] as List<Map<String, Any>>
        assertEquals(1, summary.size)
        assertEquals("analysis finished", summary[0]["text"])
        assertEquals("assistant", items[1]["role"])
    }

    @Test
    fun reasoning_snapshot_without_encrypted_content_is_safely_ignored() {
        // If the snapshot lacks encrypted_content, it is defensively filtered out; never send back an incomplete item (prevents a 400)
        val invalidSnapshot = """{"type":"reasoning","encrypted_content":"","summary":[]}"""
        val items = buildResponsesInput(
            systemPrompt = "",
            systemRole = "system",
            messages = listOf(
                AgentMessage.AssistantMessage(
                    id = "a1",
                    content = "final reply",
                    thinkingBlocksJson = invalidSnapshot
                )
            )
        )
        assertEquals(1, items.size)
        assertEquals("assistant", items[0]["role"])
    }
}
