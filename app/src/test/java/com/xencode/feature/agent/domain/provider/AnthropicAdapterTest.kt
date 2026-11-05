package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.data.remote.anthropic.AnthropicApi
import com.xencode.feature.agent.data.remote.anthropic.AnthropicContentBlock
import com.xencode.feature.agent.data.remote.anthropic.AnthropicMessage
import com.xencode.feature.agent.data.remote.anthropic.AnthropicMessageRequest
import com.xencode.feature.agent.data.remote.anthropic.AnthropicMessageResponse
import com.xencode.feature.agent.data.remote.anthropic.AnthropicStopDetails
import com.xencode.feature.agent.data.remote.anthropic.AnthropicUsage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call

class AnthropicAdapterTest {

    /** Captures the actually-sent request body and returns a configurable response. */
    private class FakeApi(var response: AnthropicMessageResponse) : AnthropicApi {
        var lastRequest: AnthropicMessageRequest? = null

        override suspend fun createMessage(
            url: String,
            apiKey: String,
            version: String,
            extraHeaders: Map<String, String>,
            request: AnthropicMessageRequest
        ): AnthropicMessageResponse {
            lastRequest = request
            return response
        }

        override fun streamMessage(
            url: String,
            apiKey: String,
            version: String,
            extraHeaders: Map<String, String>,
            request: AnthropicMessageRequest
        ): Call<ResponseBody> = throw UnsupportedOperationException("this test only covers the non-streaming path")
    }

    private fun response(
        content: List<AnthropicContentBlock> = listOf(AnthropicContentBlock(type = "text", text = "ok")),
        stopReason: String? = "end_turn",
        stopDetails: AnthropicStopDetails? = null,
        usage: AnthropicUsage = AnthropicUsage(input_tokens = 10, output_tokens = 5)
    ) = AnthropicMessageResponse(
        id = "msg_1",
        type = "message",
        role = "assistant",
        content = content,
        model = "claude-3-5-sonnet-20241022",
        stop_reason = stopReason,
        stop_sequence = null,
        stop_details = stopDetails,
        usage = usage
    )

    private fun adapter(api: FakeApi, maxOutput: Int? = null): AnthropicAdapter =
        AnthropicAdapter(api).apply {
            apiKey = "k"
            maxOutputTokens = maxOutput
        }

    private fun blocksOf(request: AnthropicMessageRequest?, role: String): List<AnthropicContentBlock> {
        val message = request?.messages?.first { it.role == role } ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return message.content as? List<AnthropicContentBlock> ?: emptyList()
    }

    private fun toolCall(id: String) = ToolCall(id = id, name = "readFile", arguments = JsonObject(emptyMap()))

    @Test
    fun max_tokens_uses_model_metadata_output_limit() = runTest {
        val api = FakeApi(response())
        adapter(api, maxOutput = 64000).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        assertEquals(64000, api.lastRequest?.max_tokens)
    }

    @Test
    fun max_tokens_falls_back_when_metadata_missing() = runTest {
        val api = FakeApi(response())
        adapter(api, maxOutput = null).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        assertEquals(16384, api.lastRequest?.max_tokens)
    }

    @Test
    fun max_tokens_leaves_room_for_content_above_thinking_budget() = runTest {
        val api = FakeApi(response())
        // When the metadata cap is smaller than the thinking budget it cannot be used directly: max_tokens must exceed budget_tokens, otherwise the server returns 400.
        adapter(api, maxOutput = 2048).complete(
            "sys",
            listOf(AgentMessage.UserMessage(content = "hi")),
            reasoningEffort = "high"
        )

        assertEquals(8192, api.lastRequest?.thinking?.budget_tokens)
        assertEquals(8192 + 4096, api.lastRequest?.max_tokens)
    }

    @Test
    fun thinking_and_redacted_blocks_are_snapshotted_in_order() = runTest {
        val api = FakeApi(
            response(
                content = listOf(
                    AnthropicContentBlock(type = "thinking", thinking = "Think it over", signature = "sig-a"),
                    AnthropicContentBlock(type = "redacted_thinking", data = "opaque-blob"),
                    AnthropicContentBlock(type = "thinking", thinking = " once more", signature = "sig-b"),
                    AnthropicContentBlock(type = "text", text = "the answer")
                )
            )
        )
        val result = adapter(api).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        assertEquals("the answer", result.content)
        // The thinking text is still concatenated for UI display, but the replay snapshot must keep the three original blocks and their order.
        assertEquals("Think it over once more", result.reasoning)
        val snapshot = result.thinkingBlocksJson
        assertTrue(snapshot != null && snapshot.contains("opaque-blob"))
        assertEquals(
            listOf("thinking", "redacted_thinking", "thinking"),
            Regex("\"type\":\"(\\w+)\"").findAll(snapshot!!).map { it.groupValues[1] }.toList()
        )
    }

    @Test
    fun stop_details_and_cache_creation_are_surfaced() = runTest {
        val api = FakeApi(
            response(
                content = emptyList(),
                stopReason = "refusal",
                stopDetails = AnthropicStopDetails(type = "refusal", explanation = "violates the usage policy"),
                usage = AnthropicUsage(
                    input_tokens = 10,
                    output_tokens = 0,
                    cache_read_input_tokens = 7,
                    cache_creation_input_tokens = 3
                )
            )
        )
        val result = adapter(api).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        // On refusal the body is empty; only stop_details carries a displayable reason.
        assertEquals("violates the usage policy", result.stopDetail)
        assertTrue(result.isAborted)
        assertEquals(7, result.cachedInputTokens)
        assertEquals(3, result.cacheCreationTokens)
    }

    @Test
    fun assistant_history_replays_thinking_snapshot_verbatim() = runTest {
        val api = FakeApi(response())
        val snapshot = """[{"type":"thinking","thinking":"last turn's thinking","signature":"sig-a"},""" +
            """{"type":"redacted_thinking","data":"opaque-blob"}]"""
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(
                    content = "last turn's answer",
                    reasoning = "last turn's thinking",
                    signature = "sig-a",
                    thinkingBlocksJson = snapshot
                )
            )
        )

        val blocks = blocksOf(api.lastRequest, "assistant")
        assertEquals(listOf("thinking", "redacted_thinking", "text"), blocks.map { it.type })
        assertEquals("opaque-blob", blocks[1].data)
        assertEquals("sig-a", blocks[0].signature)
    }

    @Test
    fun assistant_history_falls_back_to_signature_block_without_snapshot() = runTest {
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                // Old data / restored backups have only the signature, no native snapshot.
                AgentMessage.AssistantMessage(
                    content = "last turn's answer",
                    reasoning = "last turn's thinking",
                    signature = "sig-a"
                )
            )
        )

        val blocks = blocksOf(api.lastRequest, "assistant")
        assertEquals(listOf("thinking", "text"), blocks.map { it.type })
        assertEquals("last turn's thinking", blocks[0].thinking)
        assertNull(blocks[0].data)
    }

    @Test
    fun assistant_history_sends_no_thinking_block_when_nothing_stored() = runTest {
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(content = "last turn's answer")
            )
        )

        val blocks = blocksOf(api.lastRequest, "assistant")
        assertEquals(listOf("text"), blocks.map { it.type })
    }

    @Test
    fun parallel_tool_results_go_into_one_user_message() = runTest {
        val api = FakeApi(response())
        adapter(api).complete(
            "sys",
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(
                    content = "read two files in parallel",
                    toolCalls = listOf(toolCall("c1"), toolCall("c2"))
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "a"),
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "b")
            )
        )

        // When two tool_results are split into two user messages, gateways that don't merge consecutive user turns report
        // "tool_use ids were found without tool_result blocks immediately after".
        val messages = api.lastRequest!!.messages
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.role })
        @Suppress("UNCHECKED_CAST")
        val resultBlocks = messages.last().content as List<AnthropicContentBlock>
        assertEquals(listOf("tool_result", "tool_result"), resultBlocks.map { it.type })
        assertEquals(listOf("c1", "c2"), resultBlocks.map { it.tool_use_id })
    }

    @Test
    fun user_message_keeps_cache_breakpoint_on_last_text_block() = runTest {
        val api = FakeApi(response())
        adapter(api).complete("sys", listOf(AgentMessage.UserMessage(content = "hi")))

        val user: AnthropicMessage = api.lastRequest!!.messages.last { it.role == "user" }
        @Suppress("UNCHECKED_CAST")
        val blocks = user.content as List<AnthropicContentBlock>
        assertEquals("ephemeral", blocks.last().cache_control?.get("type"))
    }
}
