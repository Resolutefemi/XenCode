package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.data.remote.gemini.GeminiApi
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.tool.ToolCall
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject as KxJsonObject
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call

class GeminiAdapterTest {

    /** Captures the sent request body and returns a configurable response JSON. */
    private class FakeApi(var responseJson: String) : GeminiApi {
        var lastRequest: Map<*, *>? = null
        var lastUrl: String? = null

        override suspend fun generateContent(
            url: String,
            apiKey: String,
            extraHeaders: Map<String, String>,
            request: Any
        ): JsonObject {
            lastUrl = url
            lastRequest = request as Map<*, *>
            return JsonParser.parseString(responseJson).asJsonObject
        }

        override fun streamGenerateContent(
            url: String,
            apiKey: String,
            extraHeaders: Map<String, String>,
            request: Any
        ): Call<ResponseBody> = throw UnsupportedOperationException("this test only covers the non-streaming path")

        override suspend fun createInteraction(
            url: String,
            apiKey: String,
            extraHeaders: Map<String, String>,
            request: Any
        ): JsonObject {
            lastUrl = url
            lastRequest = request as Map<*, *>
            return JsonParser.parseString(responseJson).asJsonObject
        }

        override fun streamInteraction(
            url: String,
            apiKey: String,
            extraHeaders: Map<String, String>,
            request: Any
        ): Call<ResponseBody> = throw UnsupportedOperationException("this test only covers the non-streaming path")
    }

    private fun textResponse(
        finishReason: String = "STOP",
        candidateTokens: Int = 5,
        thoughtTokens: Int = 0
    ) = """
        {
          "candidates": [{
            "finishReason": "$finishReason",
            "content": {"role": "model", "parts": [{"text": "ok"}]}
          }],
          "usageMetadata": {
            "promptTokenCount": 10,
            "candidatesTokenCount": $candidateTokens,
            "thoughtsTokenCount": $thoughtTokens
          }
        }
    """.trimIndent()

    private fun adapter(api: FakeApi, maxOutput: Int? = null): GeminiAdapter =
        GeminiAdapter(api).apply {
            apiKey = "k"
            maxOutputTokens = maxOutput
        }

    private fun user(text: String) = AgentMessage.UserMessage(content = text)

    @Suppress("UNCHECKED_CAST")
    private fun contents(request: Map<*, *>?): List<Map<*, *>> =
        request?.get("contents") as? List<Map<*, *>> ?: emptyList()

    @Suppress("UNCHECKED_CAST")
    private fun generationConfig(request: Map<*, *>?): Map<*, *> =
        request?.get("generationConfig") as? Map<*, *> ?: emptyMap<Any, Any>()

    @Test
    fun max_output_tokens_and_thinking_config_coexist_in_generation_config() = runTest {
        val api = FakeApi(textResponse())
        // The two used to write generationConfig overwriting each other, leaving only whichever landed last.
        adapter(api, maxOutput = 64000).complete("sys", listOf(user("hi")), reasoningEffort = "high")

        val config = generationConfig(api.lastRequest)
        assertEquals(64000, config["maxOutputTokens"])
        assertEquals(mapOf("thinkingBudget" to 8192, "includeThoughts" to true), config["thinkingConfig"])
    }

    @Test
    fun max_output_tokens_absent_when_metadata_missing() = runTest {
        val api = FakeApi(textResponse())
        adapter(api, maxOutput = null).complete("sys", listOf(user("hi")))

        assertFalse(generationConfig(api.lastRequest).containsKey("maxOutputTokens"))
    }

    @Test
    fun output_tokens_include_thought_tokens() = runTest {
        val api = FakeApi(textResponse(candidateTokens = 20, thoughtTokens = 30))
        val result = adapter(api).complete("sys", listOf(user("hi")))

        // Thinking is billed at output rates; taking only candidatesTokenCount undercounts.
        assertEquals(50, result.outputTokens)
        assertEquals(10, result.inputTokens)
    }

    @Test
    fun max_tokens_finish_reason_marks_response_truncated() = runTest {
        val api = FakeApi(textResponse(finishReason = "MAX_TOKENS"))
        val result = adapter(api).complete("sys", listOf(user("hi")))

        assertTrue(result.isTruncated)
        assertFalse(result.isAborted)
    }

    @Test
    fun safety_finish_reason_marks_response_aborted() = runTest {
        val api = FakeApi(textResponse(finishReason = "SAFETY"))
        val result = adapter(api).complete("sys", listOf(user("hi")))

        assertTrue(result.isAborted)
    }

    @Test
    fun function_call_id_is_used_as_tool_call_id() = runTest {
        val api = FakeApi(
            """
            {
              "candidates": [{
                "finishReason": "STOP",
                "content": {"role": "model", "parts": [
                  {"functionCall": {"id": "call-1", "name": "readFile", "args": {"path": "a.txt"}}},
                  {"functionCall": {"id": "call-2", "name": "readFile", "args": {"path": "b.txt"}}}
                ]}
              }]
            }
            """.trimIndent()
        )
        val result = adapter(api).complete("sys", listOf(user("hi")))

        // When the function name doubles as the id, parallel calls to the same-named tool overwrite each other's results.
        assertEquals(listOf("call-1", "call-2"), result.toolCalls.map { it.id })
        assertEquals(listOf("readFile", "readFile"), result.toolCalls.map { it.name })
    }

    @Test
    fun function_call_id_falls_back_to_name() = runTest {
        val api = FakeApi(
            """
            {
              "candidates": [{
                "finishReason": "STOP",
                "content": {"role": "model", "parts": [
                  {"functionCall": {"name": "readFile", "args": {}}}
                ]}
              }]
            }
            """.trimIndent()
        )
        val result = adapter(api).complete("sys", listOf(user("hi")))

        assertEquals(listOf("readFile"), result.toolCalls.map { it.id })
    }

    @Test
    fun parts_with_signature_are_snapshotted_and_plain_text_is_not() = runTest {
        val withSignature = FakeApi(
            """
            {
              "candidates": [{
                "finishReason": "STOP",
                "content": {"role": "model", "parts": [
                  {"text": "the answer", "thoughtSignature": "sig-a"}
                ]}
              }]
            }
            """.trimIndent()
        )
        val signed = adapter(withSignature).complete("sys", listOf(user("hi")))
        assertTrue(signed.thinkingBlocksJson!!.contains("sig-a"))

        val plain = FakeApi(textResponse())
        val unsigned = adapter(plain).complete("sys", listOf(user("hi")))
        // Plain-text turns don't need verbatim replay; storing a snapshot would only duplicate the history body.
        assertNull(unsigned.thinkingBlocksJson)
    }

    @Test
    fun assistant_history_replays_parts_snapshot_verbatim() = runTest {
        val api = FakeApi(textResponse())
        val snapshot = """[{"text":"last turn's answer","thoughtSignature":"sig-a"},""" +
            """{"functionCall":{"id":"call-1","name":"readFile","args":{"path":"a.txt"}}}]"""
        adapter(api).complete(
            "sys",
            listOf(
                user("hi"),
                AgentMessage.AssistantMessage(
                    content = "last turn's answer",
                    toolCalls = listOf(ToolCall(id = "call-1", name = "readFile", arguments = KxJsonObject(emptyMap()))),
                    thinkingBlocksJson = snapshot
                ),
                AgentMessage.ToolResultMessage(id = "call-1", toolName = "readFile", result = "body")
            )
        )

        val model = contents(api.lastRequest).first { it["role"] == "model" }
        val parts = model["parts"] as com.google.gson.JsonArray
        assertEquals("sig-a", parts[0].asJsonObject.get("thoughtSignature").asString)
        assertEquals("call-1", parts[1].asJsonObject.getAsJsonObject("functionCall").get("id").asString)
    }

    @Test
    fun assistant_history_rebuilds_parts_without_snapshot() = runTest {
        val api = FakeApi(textResponse())
        adapter(api).complete(
            "sys",
            listOf(
                user("hi"),
                AgentMessage.AssistantMessage(content = "last turn's answer")
            )
        )

        val model = contents(api.lastRequest).first { it["role"] == "model" }
        @Suppress("UNCHECKED_CAST")
        val parts = model["parts"] as List<Map<*, *>>
        assertEquals("last turn's answer", parts.single()["text"])
    }

    @Test
    fun function_response_carries_name_and_call_id() = runTest {
        val api = FakeApi(textResponse())
        adapter(api).complete(
            "sys",
            listOf(
                user("hi"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(ToolCall(id = "call-1", name = "readFile", arguments = KxJsonObject(emptyMap())))
                ),
                AgentMessage.ToolResultMessage(id = "call-1", toolName = "readFile", result = "body")
            )
        )

        val toolTurn = contents(api.lastRequest).last()
        @Suppress("UNCHECKED_CAST")
        val parts = toolTurn["parts"] as List<Map<*, *>>
        val functionResponse = parts.single()["functionResponse"] as Map<*, *>
        assertEquals("readFile", functionResponse["name"])
        assertEquals("call-1", functionResponse["id"])
    }

    @Test
    fun legacy_function_response_omits_id_when_it_equals_name() = runTest {
        val api = FakeApi(textResponse())
        adapter(api).complete(
            "sys",
            listOf(
                user("hi"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(ToolCall(id = "readFile", name = "readFile", arguments = KxJsonObject(emptyMap())))
                ),
                // In old data the tool result's id is the function name; in that case no id should be sent.
                AgentMessage.ToolResultMessage(id = "readFile", toolName = "readFile", result = "body")
            )
        )

        val toolTurn = contents(api.lastRequest).last()
        @Suppress("UNCHECKED_CAST")
        val parts = toolTurn["parts"] as List<Map<*, *>>
        val functionResponse = parts.single()["functionResponse"] as Map<*, *>
        assertFalse(functionResponse.containsKey("id"))
    }

    @Test
    fun parallel_tool_results_share_one_user_turn() = runTest {
        val api = FakeApi(textResponse())
        adapter(api).complete(
            "sys",
            listOf(
                user("hi"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(
                        ToolCall(id = "call-1", name = "readFile", arguments = KxJsonObject(emptyMap())),
                        ToolCall(id = "call-2", name = "readFile", arguments = KxJsonObject(emptyMap()))
                    )
                ),
                AgentMessage.ToolResultMessage(id = "call-1", toolName = "readFile", result = "a"),
                AgentMessage.ToolResultMessage(id = "call-2", toolName = "readFile", result = "b")
            )
        )

        // Splitting two functionResponses into two turns makes the gateway report missing tool results (HTTP 400).
        val toolTurns = contents(api.lastRequest).filter { it["role"] == "user" }.drop(1)
        assertEquals(1, toolTurns.size)
        @Suppress("UNCHECKED_CAST")
        val parts = toolTurns.single()["parts"] as List<Map<*, *>>
        assertEquals(
            listOf("call-1", "call-2"),
            parts.map { (it["functionResponse"] as Map<*, *>)["id"] }
        )
    }

    @Test
    fun user_text_after_tool_results_starts_new_turn() = runTest {
        val api = FakeApi(textResponse())
        adapter(api).complete(
            "sys",
            listOf(
                user("hi"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(ToolCall(id = "call-1", name = "readFile", arguments = KxJsonObject(emptyMap())))
                ),
                AgentMessage.ToolResultMessage(id = "call-1", toolName = "readFile", result = "a"),
                user("continue"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(ToolCall(id = "call-2", name = "readFile", arguments = KxJsonObject(emptyMap())))
                ),
                AgentMessage.ToolResultMessage(id = "call-2", toolName = "readFile", result = "b")
            )
        )

        val userTurns = contents(api.lastRequest).filter { it["role"] == "user" }
        assertEquals(4, userTurns.size)
        @Suppress("UNCHECKED_CAST")
        val lastParts = userTurns.last()["parts"] as List<Map<*, *>>
        assertEquals("call-2", (lastParts.single()["functionResponse"] as Map<*, *>)["id"])
    }

    // ── Interactions API (useResponseApi = true)─────────────────────────────

    private fun interactionsAdapter(api: FakeApi, maxOutput: Int? = null): GeminiAdapter =
        adapter(api, maxOutput).apply {
            useResponseApi = true
            baseUrl = "https://generativelanguage.googleapis.com/"
        }

    private fun interactionResponse(status: String = "completed") = """
        {
          "id": "int_1",
          "status": "$status",
          "steps": [{"type": "model_output", "content": [{"type": "text", "text": "ok"}]}],
          "usage": {
            "total_input_tokens": 10,
            "total_output_tokens": 20,
            "total_thought_tokens": 30,
            "total_cached_tokens": 4
          }
        }
    """.trimIndent()

    @Test
    fun interactions_posts_to_the_shared_endpoint_with_model_in_body() = runTest {
        val api = FakeApi(interactionResponse())
        interactionsAdapter(api).complete("sys", listOf(user("hi")))

        // The model name is in the request body; the endpoint no longer carries `models/{model}:generateContent`
        assertEquals("https://generativelanguage.googleapis.com/v1beta/interactions", api.lastUrl)
        assertEquals("gemini-1.5-flash", api.lastRequest?.get("model"))
    }

    @Test
    fun interactions_is_stateless_with_string_system_instruction() = runTest {
        val api = FakeApi(interactionResponse())
        interactionsAdapter(api).complete("You are an assistant", listOf(user("hi")))

        // Local messages are the single source of truth; server-side state would conflict with context compaction/regeneration
        assertEquals(false, api.lastRequest?.get("store"))
        assertFalse(api.lastRequest!!.containsKey("previous_interaction_id"))
        // system_instruction is a top-level string, no longer a {role, parts} object
        assertEquals("You are an assistant", api.lastRequest?.get("system_instruction"))
    }

    @Test
    fun interactions_generation_config_uses_thinking_level_not_budget() = runTest {
        val api = FakeApi(interactionResponse())
        interactionsAdapter(api, maxOutput = 64000).complete("sys", listOf(user("hi")), reasoningEffort = "max")

        @Suppress("UNCHECKED_CAST")
        val config = api.lastRequest?.get("generation_config") as Map<*, *>
        assertEquals(64000, config["max_output_tokens"])
        // thinkingBudget does not exist in Interactions; xhigh/max normalize to high
        assertEquals("high", config["thinking_level"])
        assertFalse(config.containsKey("thinkingConfig"))
        // Without explicitly requesting summaries there is no displayable thinking text
        assertEquals("auto", config["thinking_summaries"])
        // Interactions' GenerationConfig has no temperature field
        assertFalse(config.containsKey("temperature"))
    }

    @Test
    fun interactions_omits_thinking_fields_for_non_thinking_models() = runTest {
        val api = FakeApi(interactionResponse())
        // Non-thinking models (gemma / image / music) get no reasoning effort from upper layers, so these two fields are omitted together
        interactionsAdapter(api, maxOutput = 8192).complete("sys", listOf(user("hi")))

        @Suppress("UNCHECKED_CAST")
        val config = api.lastRequest?.get("generation_config") as Map<*, *>
        assertFalse(config.containsKey("thinking_level"))
        assertFalse(config.containsKey("thinking_summaries"))
        assertEquals(8192, config["max_output_tokens"])
    }

    @Test
    fun interactions_output_tokens_include_thought_tokens() = runTest {
        val api = FakeApi(interactionResponse())
        val result = interactionsAdapter(api).complete("sys", listOf(user("hi")))

        assertEquals(10, result.inputTokens)
        // total_output_tokens excludes thinking; the sum of both is the real output volume
        assertEquals(50, result.outputTokens)
        assertEquals(4, result.cachedInputTokens)
        assertEquals("ok", result.content)
    }

    @Test
    fun interactions_incomplete_status_triggers_continuation() = runTest {
        val api = FakeApi(interactionResponse(status = "incomplete"))
        val result = interactionsAdapter(api).complete("sys", listOf(user("hi")))

        // Hitting the output limit is now signaled by status=incomplete instead of finishReason=MAX_TOKENS
        assertTrue(result.isTruncated)
    }

    @Test
    fun interactions_full_url_is_used_verbatim() = runTest {
        val api = FakeApi(interactionResponse())
        interactionsAdapter(api).apply {
            useFullUrl = true
            baseUrl = "https://gw.example.com/gemini/interactions"
        }.complete("sys", listOf(user("hi")))

        assertEquals("https://gw.example.com/gemini/interactions", api.lastUrl)
    }
}
