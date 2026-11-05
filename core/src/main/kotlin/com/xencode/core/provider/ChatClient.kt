package com.xencode.core.provider

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The transport contract consumed by [com.xencode.core.tools.AgentSession] and
 * the goal runner. [ChatClient] is the production implementation; tests and the
 * parallel goal runner inject fakes that implement the same surface.
 */
interface ChatTransport {
    fun streamChat(
        config: ProviderConfig,
        messages: List<ChatClient.ChatMessage>,
        tools: List<ChatClient.ToolSpec> = emptyList(),
        systemPrompt: String? = null
    ): Flow<ChatClient.ChatEvent>
}

/**
 * Streaming chat client for the PC line (CLI / desktop).
 *
 * One API surface, three wire protocols:
 * - kind = "openai"    -> /v1/chat/completions SSE (OpenAI, DeepSeek, Groq, OpenRouter, Ollama, Z.AI, ...)
 * - kind = "anthropic" -> native /v1/messages SSE
 * - kind = "gemini"    -> native :streamGenerateContent?alt=sse (Google AI Studio)
 *
 * Emits [ChatEvent]s. Tool calls are accumulated across deltas and delivered once,
 * fully assembled, before [ChatEvent.Completed]. Non-SSE error envelopes (some
 * gateways answer HTTP 200 with a JSON error body) are surfaced as [ChatEvent.Failed].
 */
class ChatClient(private val http: HttpClient = HttpClient(CIO)) : ChatTransport {

    // ---- Message / tool models (wire-agnostic) ----

    data class ToolCallMsg(val id: String, val name: String, val argumentsJson: String)

    data class ChatMessage(
        val role: String,
        val content: String? = null,
        val toolCalls: List<ToolCallMsg>? = null,
        val toolCallId: String? = null
    )

    data class ToolSpec(val name: String, val description: String, val parametersJson: String)

    sealed class ChatEvent {
        data class TextDelta(val text: String) : ChatEvent()
        data class ToolCall(val id: String, val name: String, val argumentsJson: String) : ChatEvent()
        object Completed : ChatEvent()
        data class Failed(val message: String) : ChatEvent()
    }

    private val gson = Gson()

    override fun streamChat(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        systemPrompt: String?
    ): Flow<ChatEvent> = flow {
        val emitter: suspend (ChatEvent) -> Unit = { e -> emit(e) }
        try {
            when (config.kind) {
                "anthropic" -> anthropic(config, messages, tools, systemPrompt, emitter)
                "gemini" -> gemini(config, messages, tools, systemPrompt, emitter)
                else -> openai(config, messages, tools, systemPrompt, emitter)
            }
        } catch (e: ClientRequestException) {
            val body = runCatching { e.response.body<String>() }.getOrDefault("")
            emitter(ChatEvent.Failed("HTTP ${e.response.status.value}: ${body.take(400)}"))
        } catch (e: Exception) {
            emitter(ChatEvent.Failed("${e.javaClass.simpleName}: ${e.message ?: "no message"}"))
        }
    }

    // ------------------------------------------------------------------
    // OpenAI-compatible
    // ------------------------------------------------------------------

    private suspend fun openai(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        systemPrompt: String?,
        emit: suspend (ChatEvent) -> Unit
    ): Unit = http.preparePost(url(config, "/chat/completions")) {
        contentType(ContentType.Application.Json)
        authHeaders(config, "openai")
        setBody(gson.toJson(openaiBody(config, messages, tools, systemPrompt)))
    }.execute { resp ->
        val channel = resp.bodyAsChannel()
        if (!resp.status.isSuccess()) {
            val raw = StringBuilder()
            while (!channel.isClosedForRead && raw.length < 400) {
                (channel.readUTF8Line() ?: break).let { raw.appendLine(it) }
            }
            emit(ChatEvent.Failed("HTTP ${resp.status.value}: ${raw.toString().trim().take(400)}"))
            return@execute
        }
        // index -> (id, name, args); OpenAI fragments tool calls by index.
        val toolIds = HashMap<Int, String>()
        val toolNames = HashMap<Int, String>()
        val toolArgs = HashMap<Int, StringBuilder>()
        var sawDataLine = false
        var sawFinish = false
        var anyText = false
        val rawBody = StringBuilder()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            sawDataLine = true
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") { sawFinish = true; break }
            if (rawBody.length < 400) rawBody.append(payload)
            val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
            val choice = obj.getAsJsonArray("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val delta = choice.getAsJsonObject("delta") ?: continue
            delta.get("content")?.takeIf { !it.isJsonNull }?.let {
                val text = it.asString
                if (text.isNotEmpty()) { anyText = true; emit(ChatEvent.TextDelta(text)) }
            }
            delta.getAsJsonArray("tool_calls")?.forEach { tcEl ->
                val tc = tcEl.asJsonObject
                val idx = tc.get("index")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                tc.get("id")?.takeIf { !it.isJsonNull }?.let { toolIds[idx] = it.asString }
                tc.getAsJsonObject("function")?.let { fn ->
                    fn.get("name")?.takeIf { !it.isJsonNull }?.let { n -> toolNames[idx] = n.asString }
                    fn.get("arguments")?.takeIf { !it.isJsonNull }?.let { a ->
                        toolArgs.getOrPut(idx) { StringBuilder() }.append(a.asString)
                    }
                }
            }
            (choice.get("finish_reason")?.takeIf { !it.isJsonNull })?.let { finish ->
                sawFinish = true
                if (finish.asString == "tool_calls" || finish.asString == "stop") {
                    flushTools(toolIds, toolNames, toolArgs, emit)
                    emit(ChatEvent.Completed)
                    return@execute
                }
            }
        }
        if (!sawDataLine) {
            // HTTP 200 but the body is not SSE — many gateways return a JSON error envelope here.
            emit(ChatEvent.Failed("non-SSE response: ${rawBody.toString().trim().ifBlank { "(empty body)" }}".take(400)))
            return@execute
        }
        if (!sawFinish) {
            emit(ChatEvent.Failed("stream ended without finish_reason"))
            return@execute
        }
        // Some gateways end with [DONE] but never sent finish_reason=stop: accept if we saw anything.
        flushTools(toolIds, toolNames, toolArgs, emit)
        if (!anyText && toolNames.isEmpty()) {
            emit(ChatEvent.Failed("empty model response"))
            return@execute
        }
        emit(ChatEvent.Completed)
    }

    private fun openaiBody(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        systemPrompt: String?
    ): Map<String, Any?> {
        val msgs = buildList {
            if (systemPrompt != null) add(mapOf("role" to "system", "content" to systemPrompt))
            for (m in messages) {
                when {
                    m.toolCallId != null -> add(
                        mapOf("role" to "tool", "tool_call_id" to m.toolCallId, "content" to (m.content ?: ""))
                    )
                    m.toolCalls != null -> add(
                        mapOf(
                            "role" to "assistant",
                            "content" to (m.content ?: ""),
                            "tool_calls" to m.toolCalls.map {
                                mapOf(
                                    "id" to it.id,
                                    "type" to "function",
                                    "function" to mapOf("name" to it.name, "arguments" to it.argumentsJson)
                                )
                            }
                        )
                    )
                    else -> add(mapOf("role" to m.role, "content" to (m.content ?: "")))
                }
            }
        }
        val body = linkedMapOf<String, Any?>(
            "model" to config.model,
            "messages" to msgs,
            "stream" to true
        )
        config.temperature?.let { body["temperature"] = it }
        config.maxTokens?.let { body["max_tokens"] = it }
        if (tools.isNotEmpty()) {
            body["tools"] = tools.map {
                mapOf(
                    "type" to "function",
                    "function" to mapOf(
                        "name" to it.name,
                        "description" to it.description,
                        "parameters" to JsonParser.parseString(it.parametersJson)
                    )
                )
            }
        }
        return body
    }

    private suspend fun flushTools(
        toolIds: Map<Int, String>,
        toolNames: Map<Int, String>,
        toolArgs: Map<Int, StringBuilder>,
        emit: suspend (ChatEvent) -> Unit
    ) {
        for (idx in toolNames.keys.sorted()) {
            val name = toolNames[idx] ?: continue
            val id = toolIds[idx] ?: "call_$idx"
            val args = toolArgs[idx]?.toString() ?: "{}"
            emit(ChatEvent.ToolCall(id, name, args))
        }
    }

    // ------------------------------------------------------------------
    // Gemini native (Google Generative Language API)
    // ------------------------------------------------------------------

    private suspend fun gemini(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        systemPrompt: String?,
        emit: suspend (ChatEvent) -> Unit
    ): Unit = http.preparePost(geminiUrl(config)) {
        contentType(ContentType.Application.Json)
        authHeaders(config, "gemini")
        setBody(gson.toJson(geminiBody(config, messages, tools, systemPrompt)))
    }.execute { resp ->
        val channel = resp.bodyAsChannel()
        if (!resp.status.isSuccess()) {
            val raw = StringBuilder()
            while (!channel.isClosedForRead && raw.length < 400) {
                (channel.readUTF8Line() ?: break).let { raw.appendLine(it) }
            }
            emit(ChatEvent.Failed("HTTP ${resp.status.value}: ${raw.toString().trim().take(400)}"))
            return@execute
        }
        // name -> assembled arguments JSON. Gemini delivers each functionCall part
        // complete in one chunk, so no cross-chunk accumulation is needed.
        val pendingCalls = LinkedHashMap<String, String>()
        var sawDataLine = false
        var sawFinish = false
        var anyText = false
        val rawBody = StringBuilder()
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            sawDataLine = true
            val payload = line.removePrefix("data:").trim()
            if (rawBody.length < 400) rawBody.append(payload)
            val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
            val candidate = obj.getAsJsonArray("candidates")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            candidate.getAsJsonObject("content")?.getAsJsonArray("parts")?.forEach { partEl ->
                val part = partEl.asJsonObject
                part.get("text")?.takeIf { !it.isJsonNull }?.let {
                    val text = it.asString
                    if (text.isNotEmpty()) { anyText = true; emit(ChatEvent.TextDelta(text)) }
                }
                part.getAsJsonObject("functionCall")?.let { fc ->
                    val name = fc.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return@let
                    val args = fc.get("args")?.takeIf { !it.isJsonNull } ?: JsonObject()
                    pendingCalls[name] = gson.toJson(args)
                }
            }
            val finish = candidate.get("finishReason")?.takeIf { !it.isJsonNull }?.asString
            if (finish != null) {
                sawFinish = true
                pendingCalls.forEach { (name, args) ->
                    // Gemini pairs calls by name, not by id: reuse the name as id.
                    emit(ChatEvent.ToolCall(name, name, args))
                }
                emit(ChatEvent.Completed)
                return@execute
            }
        }
        if (!sawDataLine) {
            emit(ChatEvent.Failed("non-SSE response: ${rawBody.toString().trim().ifBlank { "(empty body)" }}".take(400)))
            return@execute
        }
        if (!sawFinish) {
            emit(ChatEvent.Failed("stream ended without finishReason"))
            return@execute
        }
        emit(ChatEvent.Completed)
    }

    private fun geminiUrl(config: ProviderConfig): String {
        val base = config.baseUrl.trimEnd('/')
        val root = if (base.endsWith("/v1beta")) base else "$base/v1beta"
        return "$root/models/${config.model}:streamGenerateContent?alt=sse"
    }

    private fun geminiBody(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        systemPrompt: String?
    ): Map<String, Any?> {
        // Gemini pairs tool calls with results by NAME, while our wire-agnostic
        // history carries the call id; map ids back to names via the preceding
        // assistant tool_calls message.
        val idToName = HashMap<String, String>()
        val contents = buildList {
            for (m in messages) {
                when {
                    m.toolCallId != null -> {
                        val name = idToName[m.toolCallId] ?: m.toolCallId
                        val responseObj = runCatching {
                            JsonParser.parseString(m.content ?: "").takeIf { it.isJsonObject }?.asJsonObject
                        }.getOrNull() ?: JsonObject().also { el ->
                            el.addProperty("result", m.content ?: "")
                        }
                        add(
                            mapOf(
                                "role" to "user",
                                "parts" to listOf(mapOf("functionResponse" to mapOf("name" to name, "response" to responseObj)))
                            )
                        )
                    }
                    m.toolCalls != null -> add(
                        mapOf(
                            "role" to "model",
                            "parts" to buildList {
                                if (!m.content.isNullOrBlank()) add(mapOf("text" to m.content))
                                for (tc in m.toolCalls) {
                                    idToName[tc.id] = tc.name
                                    val parsed = runCatching { JsonParser.parseString(tc.argumentsJson) }
                                        .getOrDefault(JsonObject())
                                    add(mapOf("functionCall" to mapOf("name" to tc.name, "args" to parsed)))
                                }
                            }
                        )
                    )
                    else -> add(
                        mapOf(
                            "role" to (if (m.role == "assistant") "model" else "user"),
                            "parts" to listOf(mapOf("text" to (m.content ?: "")))
                        )
                    )
                }
            }
        }
        val body = linkedMapOf<String, Any?>("contents" to contents)
        systemPrompt?.let {
            body["systemInstruction"] = mapOf("parts" to listOf(mapOf("text" to it)))
        }
        val genConfig = linkedMapOf<String, Any?>()
        config.temperature?.let { genConfig["temperature"] = it }
        config.maxTokens?.let { genConfig["maxOutputTokens"] = it }
        if (genConfig.isNotEmpty()) body["generationConfig"] = genConfig
        if (tools.isNotEmpty()) {
            body["tools"] = listOf(
                mapOf(
                    "functionDeclarations" to tools.map {
                        mapOf(
                            "name" to it.name,
                            "description" to it.description,
                            "parameters" to JsonParser.parseString(it.parametersJson)
                        )
                    }
                )
            )
        }
        return body
    }

    // ------------------------------------------------------------------
    // Anthropic native
    // ------------------------------------------------------------------

    private suspend fun anthropic(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        systemPrompt: String?,
        emit: suspend (ChatEvent) -> Unit
    ): Unit = http.preparePost(url(config, "/messages")) {
        contentType(ContentType.Application.Json)
        authHeaders(config, "anthropic")
        setBody(gson.toJson(anthropicBody(config, messages, tools, systemPrompt)))
    }.execute { resp ->
        val channel = resp.bodyAsChannel()
        if (!resp.status.isSuccess()) {
            val raw = StringBuilder()
            while (!channel.isClosedForRead && raw.length < 400) {
                (channel.readUTF8Line() ?: break).let { raw.appendLine(it) }
            }
            emit(ChatEvent.Failed("HTTP ${resp.status.value}: ${raw.toString().trim().take(400)}"))
            return@execute
        }
        val toolNames = LinkedHashMap<String, String>() // tool_use id -> name (insertion-ordered)
        val toolArgs = HashMap<String, StringBuilder>()
        var currentToolId: String? = null
        var sawEvent = false
        var anyText = false
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            if (!line.startsWith("data:")) continue
            sawEvent = true
            val payload = line.removePrefix("data:").trim()
            val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: continue
            when (obj.get("type")?.takeIf { !it.isJsonNull }?.asString) {
                "content_block_start" -> {
                    val block = obj.getAsJsonObject("content_block")
                    if (block.get("type")?.asString == "tool_use") {
                        val id = block.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@execute
                        currentToolId = id
                        toolNames[id] = block.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "tool"
                        toolArgs[id] = StringBuilder()
                    } else {
                        currentToolId = null
                    }
                }
                "content_block_delta" -> {
                    val block = obj.getAsJsonObject("delta")
                    when (block.get("type")?.takeIf { !it.isJsonNull }?.asString) {
                        "text_delta" -> {
                            val t = block.get("text")?.asString ?: ""
                            if (t.isNotEmpty()) { anyText = true; emit(ChatEvent.TextDelta(t)) }
                        }
                        "input_json_delta" -> {
                            val partial = block.get("partial_json")?.takeIf { !it.isJsonNull }?.asString
                            if (partial != null) currentToolId?.let { toolArgs[it]?.append(partial) }
                        }
                    }
                }
                "message_delta" -> {
                    for (id in toolNames.keys) {
                        emit(ChatEvent.ToolCall(id, toolNames[id] ?: "tool", toolArgs[id]?.toString() ?: "{}"))
                    }
                    emit(ChatEvent.Completed)
                    return@execute
                }
                "error" -> {
                    emit(ChatEvent.Failed(obj.getAsJsonObject("error")?.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "stream error"))
                    return@execute
                }
            }
        }
        if (!sawEvent) {
            emit(ChatEvent.Failed("non-SSE response: (empty body)"))
            return@execute
        }
        emit(ChatEvent.Completed)
    }

    private fun anthropicBody(
        config: ProviderConfig,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        systemPrompt: String?
    ): Map<String, Any?> {
        val msgs = buildList {
            for (m in messages) {
                when {
                    m.toolCallId != null -> add(
                        // Anthropic: tool results arrive as a user turn with a tool_result block.
                        mapOf(
                            "role" to "user",
                            "content" to listOf(
                                mapOf(
                                    "type" to "tool_result",
                                    "tool_use_id" to m.toolCallId,
                                    "content" to (m.content ?: "")
                                )
                            )
                        )
                    )
                    m.toolCalls != null -> add(
                        mapOf(
                            "role" to "assistant",
                            "content" to buildList {
                                if (!m.content.isNullOrBlank()) add(mapOf("type" to "text", "text" to m.content))
                                for (tc in m.toolCalls) {
                                    val parsed = runCatching { JsonParser.parseString(tc.argumentsJson) }
                                        .getOrDefault(JsonObject())
                                    add(mapOf("type" to "tool_use", "id" to tc.id, "name" to tc.name, "input" to parsed))
                                }
                            }
                        )
                    )
                    else -> add(
                        mapOf(
                            "role" to m.role,
                            "content" to listOf(mapOf("type" to "text", "text" to (m.content ?: "")))
                        )
                    )
                }
            }
        }
        val body = linkedMapOf<String, Any?>(
            "model" to config.model,
            "max_tokens" to (config.maxTokens ?: 4096),
            "stream" to true,
            "messages" to msgs
        )
        systemPrompt?.let { body["system"] = it }
        config.temperature?.let { body["temperature"] = it }
        if (tools.isNotEmpty()) {
            body["tools"] = tools.map {
                mapOf(
                    "name" to it.name,
                    "description" to it.description,
                    "input_schema" to JsonParser.parseString(it.parametersJson)
                )
            }
        }
        return body
    }

    // ------------------------------------------------------------------

    private fun url(config: ProviderConfig, path: String): String {
        val base = config.baseUrl.trimEnd('/')
        // Allow both "https://host" and "https://host/v1" styles.
        return if (base.endsWith("/v1")) base + path else "$base/v1$path"
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authHeaders(config: ProviderConfig, kind: String) {
        headers {
            config.extraHeaders.forEach { (k, v) -> set(k, v) }
            when (kind) {
                "anthropic" -> {
                    set("x-api-key", config.apiKey)
                    set("anthropic-version", "2023-06-01")
                }
                "gemini" -> set("x-goog-api-key", config.apiKey)
                else -> set("Authorization", "Bearer ${config.apiKey}")
            }
        }
    }
}
