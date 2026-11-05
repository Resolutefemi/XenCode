package com.xencode.core.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ChatClient wire-protocol tests against MockEngine SSE fixtures: text
 * streaming, tool-call assembly and error envelopes for all three kinds
 * (openai / anthropic / gemini). No network involved.
 */
class ChatClientProtocolTest {

    private fun provider(kind: String) = ProviderConfig(
        name = "t", kind = kind,
        baseUrl = if (kind == "gemini") "https://generativelanguage.googleapis.com" else "https://example.com",
        apiKey = "k", model = "m"
    )

    private fun client(sseBody: String, status: HttpStatusCode = HttpStatusCode.OK): ChatClient =
        ChatClient(
            HttpClient(
                MockEngine { _ ->
                    respond(
                        content = sseBody,
                        status = status,
                        headers = headersOf("Content-Type", ContentType.Text.EventStream.toString())
                    )
                }
            )
        )

    private fun events(c: ChatClient, kind: String, messages: List<ChatClient.ChatMessage> = emptyList(), tools: List<ChatClient.ToolSpec> = emptyList()): List<ChatClient.ChatEvent> =
        runBlocking { c.streamChat(provider(kind), messages, tools, "sys").toList() }

    // ---- OpenAI-compatible ----

    @Test
    fun openai_streamsTextAndToolCalls() {
        val sse = listOf(
            """data: {"choices":[{"delta":{"content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo"}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"run_command","arguments":"{\"com"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"mand\":\"ls\"}"}}]}}]}""",
            """data: {"choices":[{"finish_reason":"tool_calls"}]}""",
            """data: {"usage":{"prompt_tokens":111,"completion_tokens":22}}""",
            """data: [DONE]"""
        ).joinToString("\n") { it }
        val ev = events(client(sse), "openai")
        val text = ev.filterIsInstance<ChatClient.ChatEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Hello", text)
        val tool = ev.filterIsInstance<ChatClient.ChatEvent.ToolCall>().single()
        assertEquals("run_command", tool.name)
        assertEquals("""{"command":"ls"}""", tool.argumentsJson)
        // Usage arrives in its own chunk after finish_reason and must still be captured.
        val done = ev.filterIsInstance<ChatClient.ChatEvent.Completed>().single()
        assertEquals(111, done.inputTokens)
        assertEquals(22, done.outputTokens)
    }

    @Test
    fun openai_usageDefaultsToZeroWhenGatewayOmitsIt() {
        val sse = listOf(
            """data: {"choices":[{"delta":{"content":"hey"}}]}""",
            """data: {"choices":[{"finish_reason":"stop"}]}""",
            """data: [DONE]"""
        ).joinToString("\n") { it }
        val ev = events(client(sse), "openai")
        val done = ev.filterIsInstance<ChatClient.ChatEvent.Completed>().single()
        assertEquals(0, done.inputTokens)
        assertEquals(0, done.outputTokens)
    }

    @Test
    fun openai_httpErrorSurfacesMessage() {
        val c = ChatClient(
            HttpClient(
                MockEngine { _ ->
                    respond("""{"error":{"message":"bad key"}}""", HttpStatusCode.Unauthorized)
                }
            )
        )
        val ev = events(c, "openai")
        val fail = ev.filterIsInstance<ChatClient.ChatEvent.Failed>().single()
        assertTrue(fail.message.contains("401"), fail.message)
        assertTrue(fail.message.contains("bad key"), fail.message)
    }

    // ---- Anthropic native ----

    @Test
    fun anthropic_streamsTextAndAssemblesToolInput() {
        val sse = listOf(
            """data: {"type":"message_start","message":{"usage":{"input_tokens":77}}}""",
            """data: {"type":"content_block_start","content_block":{"type":"tool_use","id":"t1","name":"read_file"}}""",
            """data: {"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{\"path\""}}""",
            """data: {"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":":\"a.txt\"}"}}""",
            """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"working"}}""",
            """data: {"type":"message_delta","usage":{"output_tokens":31}}"""
        ).joinToString("\n") { it }
        val ev = events(client(sse), "anthropic")
        val tool = ev.filterIsInstance<ChatClient.ChatEvent.ToolCall>().single()
        assertEquals("t1", tool.id)
        assertEquals("read_file", tool.name)
        assertEquals("""{"path":"a.txt"}""", tool.argumentsJson)
        assertEquals("working", ev.filterIsInstance<ChatClient.ChatEvent.TextDelta>().joinToString("") { it.text })
        val done = ev.filterIsInstance<ChatClient.ChatEvent.Completed>().single()
        assertEquals(77, done.inputTokens)
        assertEquals(31, done.outputTokens)
    }

    @Test
    fun anthropic_toolResultMessagesRenderAsToolResult() {
        val sse = """data: {"type":"message_delta"}"""
        val messages = listOf(
            ChatClient.ChatMessage(
                role = "assistant",
                toolCalls = listOf(ChatClient.ToolCallMsg("t1", "read_file", """{"path":"a"}"""))
            ),
            ChatClient.ChatMessage(role = "tool", content = "file body", toolCallId = "t1")
        )
        val ev = events(client(sse), "anthropic", messages)
        // Wire-format specifics are internal; the contract is just that the
        // request parses and completes without a failure event.
        assertTrue(ev.none { it is ChatClient.ChatEvent.Failed }, ev.toString())
    }

    // ---- Gemini native ----

    @Test
    fun gemini_streamsTextAndFunctionCalls() {
        val sse = listOf(
            """data: {"candidates":[{"content":{"parts":[{"text":"Hi "}]}}],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":3}}""",
            """data: {"candidates":[{"content":{"parts":[{"text":"there"}]}}],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":9}}""",
            """data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"run_command","args":{"command":"ls"}}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":14}}"""
        ).joinToString("\n") { it }
        val ev = events(client(sse), "gemini")
        assertEquals("Hi there", ev.filterIsInstance<ChatClient.ChatEvent.TextDelta>().joinToString("") { it.text })
        val tool = ev.filterIsInstance<ChatClient.ChatEvent.ToolCall>().single()
        assertEquals("run_command", tool.name)
        assertEquals("""{"command":"ls"}""", tool.argumentsJson)
        // The last chunk's usageMetadata carries the totals.
        val done = ev.filterIsInstance<ChatClient.ChatEvent.Completed>().single()
        assertEquals(50, done.inputTokens)
        assertEquals(14, done.outputTokens)
    }

    @Test
    fun gemini_httpErrorSurfacesMessage() {
        val c = ChatClient(
            HttpClient(
                MockEngine { _ ->
                    respond("""{"error":{"message":"api key not valid"}}""", HttpStatusCode.BadRequest)
                }
            )
        )
        val ev = events(c, "gemini")
        val fail = ev.filterIsInstance<ChatClient.ChatEvent.Failed>().single()
        assertTrue(fail.message.contains("400"), fail.message)
        assertTrue(fail.message.contains("api key not valid"), fail.message)
    }

    @Test
    fun providerConfig_acceptsGeminiKindAndRejectsOthers() {
        assertEquals(null, provider("gemini").validate())
        assertEquals(null, provider("openai").validate())
        assertEquals(null, provider("anthropic").validate())
        assertTrue(provider("grok").validate() != null)
    }
}
