package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.data.remote.gemini.InteractionStep
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ToolCall
import com.xencode.feature.agent.domain.tool.ToolParameter
import com.xencode.feature.agent.domain.tool.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interactions request body construction ([buildInteractionsTools] / [buildInteractionsInput]).
 * Coverage: tool declarations no longer wrap in `functionDeclarations`, history is a flat step timeline,
 * model-produced steps are replayed verbatim (a thought's signature cannot be rebuilt), and tool call/result pairing is cleaned up.
 */
class GeminiInteractionsPayloadTest {

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

    /** Step elements may be Maps (rebuilt) or Gson JsonObjects (snapshot replayed verbatim); normalize to JsonObject before asserting. */
    private fun steps(messages: List<AgentMessage>): List<JsonObject> =
        buildInteractionsInput(messages).map { Gson().toJsonTree(it).asJsonObject }

    private fun json(raw: String): JsonObject =
        JsonParser.parseString(raw.replace('\'', '"')).asJsonObject

    private fun JsonObject.type(): String = get("type").asString

    @Test
    fun tool_definition_is_flat_without_function_declarations() {
        val defs = buildInteractionsTools(listOf(FakeTool("readFile")))
        assertEquals(1, defs?.size)
        val def = Gson().toJsonTree(defs!!.first()).asJsonObject
        assertEquals("function", def.get("type").asString)
        assertEquals("readFile", def.get("name").asString)
        assertTrue(def.has("description"))
        assertTrue(def.has("parameters"))
        // The generateContent-style [{functionDeclarations: [...]}] wrapper layer does not exist in Interactions
        assertFalse(def.has("functionDeclarations"))
    }

    @Test
    fun no_tools_means_no_tools_field() {
        assertNull(buildInteractionsTools(emptyList()))
    }

    @Test
    fun user_message_becomes_user_input_step() {
        val result = steps(listOf(AgentMessage.UserMessage(content = "hi")))
        assertEquals(1, result.size)
        assertEquals(InteractionStep.USER_INPUT, result[0].type())
        val content = result[0].getAsJsonArray("content")
        assertEquals(1, content.size())
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertEquals("hi", content[0].asJsonObject.get("text").asString)
    }

    @Test
    fun user_images_become_image_content_blocks() {
        val message = AgentMessage.UserMessage(
            content = "look at the image",
            images = listOf(AgentImage(mimeType = "image/png", base64Data = "AAA"))
        )
        val content = steps(listOf(message))[0].getAsJsonArray("content")
        assertEquals(2, content.size())
        val image = content[1].asJsonObject
        assertEquals("image", image.get("type").asString)
        // generateContent wraps with inline_data; Interactions flattens to mime_type / data
        assertEquals("image/png", image.get("mime_type").asString)
        assertEquals("AAA", image.get("data").asString)
        assertFalse(image.has("inline_data"))
    }

    @Test
    fun assistant_text_only_turn_is_rebuilt_as_model_output() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(content = "hello")
            )
        )
        assertEquals(2, result.size)
        assertEquals(InteractionStep.MODEL_OUTPUT, result[1].type())
        assertEquals("hello", result[1].getAsJsonArray("content")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun tool_roundtrip_emits_function_call_then_function_result() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "read the file"),
                AgentMessage.AssistantMessage(content = "", toolCalls = listOf(call("c1", "readFile", "a.kt"))),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "content")
            )
        )
        assertEquals(3, result.size)
        assertEquals(InteractionStep.FUNCTION_CALL, result[1].type())
        assertEquals("c1", result[1].get("id").asString)
        assertEquals("readFile", result[1].get("name").asString)
        assertEquals(InteractionStep.FUNCTION_RESULT, result[2].type())
        // Results are paired with calls by call_id against the call's id
        assertEquals("c1", result[2].get("call_id").asString)
        assertEquals("content", result[2].getAsJsonObject("result").get("result").asString)
    }

    @Test
    fun rebuilt_arguments_are_real_json_not_kotlinx_internals() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "read the file"),
                AgentMessage.AssistantMessage(content = "", toolCalls = listOf(call("c1", "readFile", "a.kt"))),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "content")
            )
        )
        val args = result[1].getAsJsonObject("arguments")
        // Handing kotlinx's JsonObject straight to Gson would reflect out {"body":...,"isString":true}
        assertEquals("a.kt", args.get("path").asString)
        assertFalse(args.has("body"))
    }

    @Test
    fun call_without_result_is_dropped_together_with_the_call() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "read the file"),
                AgentMessage.AssistantMessage(content = "ok", toolCalls = listOf(call("c1", "readFile", "a.kt")))
            )
        )
        // When the user refuses execution the call has no result; keeping the call would be rejected by the server for a missing function_result
        assertEquals(2, result.size)
        assertEquals(InteractionStep.MODEL_OUTPUT, result[1].type())
    }

    @Test
    fun orphan_tool_result_is_dropped() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.ToolResultMessage(id = "gone", toolName = "readFile", result = "content")
            )
        )
        assertEquals(1, result.size)
        assertEquals(InteractionStep.USER_INPUT, result[0].type())
    }

    @Test
    fun out_of_order_result_is_attached_to_its_call() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "two tools"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(call("c1", "ask", "x"), call("c2", "readFile", "a.kt"))
                ),
                // While askUserQuestion is blocked, c2's result lands first
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "content"),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "ask", result = "answer")
            )
        )
        // The two calls are written back-to-back first, then the two results together (matching the official stateless example's order)
        assertEquals(
            listOf(
                InteractionStep.USER_INPUT,
                InteractionStep.FUNCTION_CALL,
                InteractionStep.FUNCTION_CALL,
                InteractionStep.FUNCTION_RESULT,
                InteractionStep.FUNCTION_RESULT
            ),
            result.map { it.type() }
        )
        assertEquals("c1", result[3].get("call_id").asString)
        assertEquals("c2", result[4].get("call_id").asString)
    }

    @Test
    fun snapshot_is_replayed_verbatim_keeping_thought_signature() {
        val snapshot = """
            [{'type':'thought','signature':'sig-1'},
             {'type':'function_call','id':'c1','name':'readFile','arguments':{'path':'a.kt'}}]
        """.trimIndent().replace('\'', '"')
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "read the file"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(call("c1", "readFile", "a.kt")),
                    thinkingBlocksJson = snapshot
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "content")
            )
        )
        assertEquals(4, result.size)
        // The signature cannot be rebuilt and must be replayed verbatim, otherwise the previous turn's reasoning cannot be continued
        assertEquals(InteractionStep.THOUGHT, result[1].type())
        assertEquals("sig-1", result[1].get("signature").asString)
        assertEquals(InteractionStep.FUNCTION_CALL, result[2].type())
        assertEquals(InteractionStep.FUNCTION_RESULT, result[3].type())
    }

    @Test
    fun snapshot_is_abandoned_when_a_call_lost_its_result() {
        val snapshot = """
            [{'type':'thought','signature':'sig-1'},
             {'type':'function_call','id':'c1','name':'ask','arguments':{}},
             {'type':'function_call','id':'c2','name':'readFile','arguments':{'path':'a.kt'}}]
        """.trimIndent().replace('\'', '"')
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "two tools"),
                AgentMessage.AssistantMessage(
                    content = "ok",
                    toolCalls = listOf(call("c1", "ask", "x"), call("c2", "readFile", "a.kt")),
                    thinkingBlocksJson = snapshot
                ),
                // c1 was refused by the user, no result
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "content")
            )
        )
        // The snapshot's signature no longer matches the actually-sent call sequence, so it falls back to rebuilding from the body
        assertEquals(
            listOf(
                InteractionStep.USER_INPUT,
                InteractionStep.MODEL_OUTPUT,
                InteractionStep.FUNCTION_CALL,
                InteractionStep.FUNCTION_RESULT
            ),
            result.map { it.type() }
        )
        assertEquals("c2", result[2].get("id").asString)
    }

    @Test
    fun snapshot_drops_echoed_input_steps() {
        // The server may echo back the user_input / function_result we sent; keeping them would duplicate them next turn
        val steps = listOf(
            json("{'type':'user_input','content':[{'type':'text','text':'hi'}]}"),
            json("{'type':'thought','signature':'sig-1'}"),
            json("{'type':'function_result','call_id':'c1','result':[]}"),
            json("{'type':'model_output','content':[{'type':'text','text':'hello'}]}")
        )
        val snapshot = JsonParser.parseString(snapshotInteractionSteps(steps)!!).asJsonArray
        assertEquals(2, snapshot.size())
        assertEquals(InteractionStep.THOUGHT, snapshot[0].asJsonObject.type())
        assertEquals(InteractionStep.MODEL_OUTPUT, snapshot[1].asJsonObject.type())
    }

    @Test
    fun pure_text_turn_needs_no_snapshot() {
        val steps = listOf(json("{'type':'model_output','content':[{'type':'text','text':'hello'}]}"))
        // Pure text can be rebuilt from the body; persisting a snapshot would only double-write the body
        assertNull(snapshotInteractionSteps(steps))
    }

    @Test
    fun tool_result_is_emitted_as_plain_result_object_without_images() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "take a screenshot"),
                AgentMessage.AssistantMessage(content = "", toolCalls = listOf(call("c1", "shot", "x"))),
                AgentMessage.ToolResultMessage(
                    id = "c1",
                    toolName = "shot",
                    result = "screenshot taken",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "BBB"))
                )
            )
        )
        // result is sent back as an object (compatible with relay gateways that reject array-shaped results when converting to generateContent);
        // images produced by tools are not sent back — they were already shown to the user, and re-entering the context would only bloat tokens.
        val resultObj = result[2].getAsJsonObject("result")
        assertEquals("screenshot taken", resultObj.get("result").asString)
        assertFalse(resultObj.has("images"))
    }

    @Test
    fun assistant_generated_images_are_rebuilt_into_model_output_blocks() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "draw a cat"),
                AgentMessage.AssistantMessage(
                    content = "done drawing",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "CCC"))
                )
            )
        )
        assertEquals(InteractionStep.MODEL_OUTPUT, result[1].type())
        val content = result[1].getAsJsonArray("content")
        assertEquals(2, content.size())
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertEquals("image", content[1].asJsonObject.get("type").asString)
        // Interactions' image block flattens mime_type / data (unlike generateContent's inline_data)
        assertEquals("image/png", content[1].asJsonObject.get("mime_type").asString)
        assertEquals("CCC", content[1].asJsonObject.get("data").asString)
    }

    @Test
    fun assistant_image_turn_abandons_snapshot_to_keep_base64_out_of_db() {
        // Replaying an image snapshot verbatim would carry huge base64 (~10MB for a 4K image) and blow up the DB row,
        // so turns with images abandon the snapshot and rebuild from body + images (the lost signature has bounded impact).
        val snapshot = """
            [{'type':'thought','signature':'sig-1'}]
        """.trimIndent().replace('\'', '"')
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "draw a cat"),
                AgentMessage.AssistantMessage(
                    content = "done drawing",
                    thinkingBlocksJson = snapshot,
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "CCC"))
                )
            )
        )
        assertEquals(InteractionStep.MODEL_OUTPUT, result[1].type())
        val content = result[1].getAsJsonArray("content")
        assertEquals(2, content.size())
        assertEquals("image", content[1].asJsonObject.get("type").asString)
        assertEquals("CCC", content[1].asJsonObject.get("data").asString)
    }

    @Test
    fun assistant_pure_image_turn_emits_model_output_with_image_only() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "draw a cat"),
                AgentMessage.AssistantMessage(
                    content = "",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "CCC"))
                )
            )
        )
        assertEquals(InteractionStep.MODEL_OUTPUT, result[1].type())
        val content = result[1].getAsJsonArray("content")
        assertEquals(1, content.size())
        assertEquals("image", content[0].asJsonObject.get("type").asString)
    }
}
