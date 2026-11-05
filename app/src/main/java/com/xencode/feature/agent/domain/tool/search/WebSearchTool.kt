package com.xencode.feature.agent.domain.tool.search

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ParameterType
import com.xencode.feature.agent.domain.tool.ToolParameter
import com.xencode.feature.agent.domain.tool.ToolCapability
import com.xencode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class WebSearchTool @Inject constructor() : AgentTool() {

    private companion object {
        const val TAG = "WebSearchTool"
        // Uses the same public Parallel AI MCP endpoint as opencode
        const val PARALLEL_MCP_URL = "https://search.parallel.ai/mcp"

        // Goes through the global/provider proxy chain (AppProxy's global ProxySelector + per-host dispatched auth),
        // consistent with conversations and other HTTP requests; lazy ensures the client is first built after applyGlobal.
        val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .proxyAuthenticator(com.xencode.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }

    override val name = "websearch"
    override val description = "Search the internet for up-to-date information. Use when the task needs current or time-sensitive facts."
    override val capabilities = setOf(ToolCapability.NETWORK_READ)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "Search query. Use a precise, searchable phrase.",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull 
            ?: return ToolResult.Error("Missing required parameter: query")

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = JsonObject(
                    mapOf(
                        "jsonrpc" to JsonPrimitive("2.0"),
                        "id" to JsonPrimitive(1),
                        "method" to JsonPrimitive("tools/call"),
                        "params" to JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("web_search"),
                                "arguments" to JsonObject(
                                    mapOf(
                                        "objective" to JsonPrimitive(query),
                                        "search_queries" to JsonArray(listOf(JsonPrimitive(query))),
                                        "session_id" to JsonPrimitive("xencode-android")
                                    )
                                )
                            )
                        )
                    )
                ).toString()

                FileLogger.i(TAG, "WebSearch request: $query")

                val request = Request.Builder()
                    .url(PARALLEL_MCP_URL)
                    .header("Accept", "application/json, text/event-stream")
                    .header("User-Agent", "xencode/1.0")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val responseCode = resp.code
                    if (responseCode !in 200..299) {
                        val errorStr = resp.body?.string()
                        FileLogger.e(TAG, "WebSearch failed: HTTP $responseCode, $errorStr")
                        return@withContext ToolResult.Error("Web search failed (HTTP $responseCode)")
                    }

                    // Try reading plain JSON directly, or parse the Event-Stream (SSE) format
                    val responseBody = resp.body?.string().orEmpty()

                    // Parse the MCP response body
                    val rawResultText = parseMcpResponse(responseBody)

                    if (rawResultText.isNullOrBlank()) {
                        ToolResult.Success(kotlinx.serialization.json.JsonPrimitive("No search results for \"$query\". Try a different query."))
                    } else {
                        ToolResult.Success(parseSearchResultPayload(rawResultText) ?: JsonPrimitive(rawResultText))
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "WebSearch exception", e)
                ToolResult.Error("Search failed: ${e.message}")
            }
        }
    }

    /**
     * Parses either plain JSON or SSE (Server-Sent Events) format.
     */
    private fun parseMcpResponse(body: String): String? {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        
        for (line in lines) {
            val jsonStr = if (line.startsWith("data: ")) {
                line.substring(6)
            } else if (line.startsWith("{")) {
                line
            } else {
                continue
            }
            
            try {
                val json = Json.parseToJsonElement(jsonStr).jsonObject
                if (json.containsKey("result")) {
                    val contentArr = json["result"]?.jsonObject?.get("content")?.jsonArray
                    if (!contentArr.isNullOrEmpty()) {
                        return contentArr[0].jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors for partial lines
            }
        }
        return null
    }

    private fun parseSearchResultPayload(raw: String): JsonElement? {
        return runCatching {
            val element = Json.parseToJsonElement(raw.trim())
            val obj = element as? JsonObject ?: return@runCatching null
            if (obj["results"] is JsonArray) element else null
        }.getOrNull()
    }
}
