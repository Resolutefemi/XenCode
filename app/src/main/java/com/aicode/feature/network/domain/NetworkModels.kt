package com.aicode.feature.network.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

internal val compactJson = Json { ignoreUnknownKeys = true }
internal val prettyJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

const val DEFAULT_JSON_OBJECT = "{\n  \n}"
const val DEFAULT_AUTH_JSON = "{\n  \"type\": \"none\"\n}"
const val DEFAULT_TIMEOUT_SECONDS = 30
const val MIN_TIMEOUT_SECONDS = 1
const val MAX_TIMEOUT_SECONDS = 120
const val MAX_JSON_PRETTY_CHARS = 200_000
const val MAX_JSON_STRUCTURE_CHARS = 120_000
const val MAX_EDITOR_CHARS = 400_000
const val MAX_TREE_NODES = 1_500
const val MAX_TREE_DEPTH = 24

data class NetworkHeader(
    val name: String,
    val value: String
)

enum class HttpMethod {
    GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS
}

enum class NetworkPane {
    REQUEST, RESPONSE
}

enum class RequestTab {
    QUERY, HEADERS, BODY, AUTHORIZATION, SETTINGS
}

enum class ResponseTab {
    HEADERS, BODY, METRICS
}

enum class ResponseViewMode {
    TABLE, JSON
}

enum class JsonViewMode {
    JSON, TREE, TABLE
}

data class TableInputRow(
    val id: String = UUID.randomUUID().toString(),
    val key: String = "",
    val value: String = "",
    val enabled: Boolean = true
)

data class JsonTreeNode(
    val key: String,
    val preview: String,
    val children: List<JsonTreeNode> = emptyList()
)

data class NetworkClientSettings(
    val followRedirects: Boolean = true,
    val sslVerify: Boolean = true,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS
)

data class NetworkMetrics(
    val requestId: String = "",
    val method: String = "",
    val url: String = "",
    val statusCode: Int = 0,
    val statusText: String = "",
    val protocol: String = "",
    val remoteAddress: String = "",
    val durationMs: Long = 0,
    val dnsMs: Long = -1,
    val connectMs: Long = -1,
    val tlsMs: Long = -1,
    val requestBytes: Long = 0,
    val responseBytes: Long = 0,
    val contentType: String = ""
)

data class NetworkExchange(
    val headers: List<NetworkHeader> = emptyList(),
    val body: String = "",
    val metrics: NetworkMetrics = NetworkMetrics()
)

sealed interface NetworkSendState {
    data object Idle : NetworkSendState
    data object Sending : NetworkSendState
    data class Success(val exchange: NetworkExchange) : NetworkSendState
    data class Failure(val message: String, val exchange: NetworkExchange? = null) : NetworkSendState
}

@Serializable
data class NetworkHistoryEntry(
    val id: String,
    val createdAt: Long,
    val method: String,
    val url: String,
    val queryJson: String = DEFAULT_JSON_OBJECT,
    val headersJson: String = DEFAULT_JSON_OBJECT,
    val bodyJson: String = DEFAULT_JSON_OBJECT,
    val authJson: String = DEFAULT_AUTH_JSON,
    val followRedirects: Boolean = true,
    val sslVerify: Boolean = true,
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS
)

fun defaultViewMode(tab: ResponseTab): ResponseViewMode? = when (tab) {
    ResponseTab.HEADERS -> ResponseViewMode.TABLE
    ResponseTab.BODY -> ResponseViewMode.JSON
    ResponseTab.METRICS -> null
}

fun safeJsonViewMode(name: String?): JsonViewMode =
    JsonViewMode.entries.firstOrNull { it.name == name } ?: JsonViewMode.JSON

fun safeResponseViewMode(name: String?): ResponseViewMode =
    ResponseViewMode.entries.firstOrNull { it.name == name } ?: ResponseViewMode.JSON

fun normalizeRequestUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return trimmed
    val lower = trimmed.lowercase()
    return when {
        lower.startsWith("http://") -> "http://" + trimmed.substring("http://".length)
        lower.startsWith("https://") -> "https://" + trimmed.substring("https://".length)
        else -> "https://$trimmed"
    }
}

fun prettyPrintJson(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    if (trimmed.length > MAX_JSON_PRETTY_CHARS) return trimmed
    return try {
        val element = compactJson.parseToJsonElement(trimmed)
        prettyJson.encodeToString(JsonElement.serializer(), element)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        raw
    }
}

fun isBlankJson(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "{}" || trimmed == "[]") return true
    return try {
        when (val element = compactJson.parseToJsonElement(trimmed)) {
            is JsonObject -> element.isEmpty()
            is JsonArray -> element.isEmpty()
            else -> false
        }
    } catch (_: Exception) {
        false
    }
}

fun headersToJson(headers: List<NetworkHeader>): String {
    val map = linkedMapOf<String, JsonElement>()
    headers.forEach { header ->
        val value = JsonPrimitive(header.value)
        when (val existing = map[header.name]) {
            null -> map[header.name] = value
            is JsonArray -> map[header.name] = JsonArray(existing + value)
            else -> map[header.name] = JsonArray(listOf(existing, value))
        }
    }
    return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(map))
}

fun jsonToHeaders(raw: String): List<NetworkHeader> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "{}") return emptyList()
    return try {
        when (val element = compactJson.parseToJsonElement(trimmed)) {
            is JsonObject -> element.entries.flatMap { (key, value) ->
                when (value) {
                    is JsonArray -> value.map { NetworkHeader(key, jsonElementText(it)) }
                    else -> listOf(NetworkHeader(key, jsonElementText(value)))
                }
            }
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun mergeQueryIntoUrl(url: String, queryJson: String): String {
    val httpUrl = url.toHttpUrlOrNull() ?: return url
    val params = jsonToHeaders(queryJson).filter { it.name.isNotBlank() }
    if (params.isEmpty()) return url
    val builder = httpUrl.newBuilder()
    params.forEach { builder.addQueryParameter(it.name, it.value) }
    return builder.build().toString()
}

fun authHeaders(authJson: String): List<NetworkHeader> {
    val trimmed = authJson.trim()
    if (trimmed.isEmpty() || trimmed == "{}") return emptyList()
    return try {
        val obj = compactJson.parseToJsonElement(trimmed) as? JsonObject ?: return emptyList()
        val type = primitiveContent(obj["type"])?.lowercase().orEmpty()
        when (type) {
            "bearer" -> {
                val token = primitiveContent(obj["token"]) ?: primitiveContent(obj["value"]).orEmpty()
                if (token.isBlank()) emptyList()
                else listOf(NetworkHeader("Authorization", "Bearer $token"))
            }
            "basic" -> {
                val user = primitiveContent(obj["username"]) ?: primitiveContent(obj["user"]).orEmpty()
                val password = primitiveContent(obj["password"]).orEmpty()
                if (user.isBlank() && password.isBlank()) emptyList()
                else {
                    val token = Base64.getEncoder().encodeToString(
                        "$user:$password".toByteArray(Charsets.UTF_8)
                    )
                    listOf(NetworkHeader("Authorization", "Basic $token"))
                }
            }
            "apikey", "api_key", "api-key" -> {
                val name = primitiveContent(obj["key"])
                    ?: primitiveContent(obj["name"])
                    ?: "X-Api-Key"
                val value = primitiveContent(obj["value"]) ?: primitiveContent(obj["token"]).orEmpty()
                if (value.isBlank()) emptyList() else listOf(NetworkHeader(name, value))
            }
            else -> emptyList()
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun parseTimeoutSeconds(raw: String): Int =
    raw.trim().toIntOrNull()?.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)
        ?: DEFAULT_TIMEOUT_SECONDS

fun jsonToTreeNodes(raw: String): List<JsonTreeNode>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.length > MAX_JSON_STRUCTURE_CHARS) return null
    return try {
        val counter = intArrayOf(0)
        when (val element = compactJson.parseToJsonElement(trimmed)) {
            is JsonObject -> element.entries.map { (key, value) ->
                jsonElementToNode(key, value, 0, counter)
            }
            is JsonArray -> element.mapIndexed { index, value ->
                jsonElementToNode("[$index]", value, 0, counter)
            }
            else -> listOf(jsonElementToNode("value", element, 0, counter))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

fun bodyToTableRows(body: String): List<NetworkHeader> {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return emptyList()
    if (trimmed.length > MAX_JSON_STRUCTURE_CHARS) {
        return listOf(NetworkHeader("body", trimmed.take(8_000)))
    }
    return try {
        when (val element = compactJson.parseToJsonElement(trimmed)) {
            is JsonObject -> element.entries.take(400).map { (key, value) ->
                NetworkHeader(key, jsonElementText(value).take(4_000))
            }
            is JsonArray -> element.take(400).mapIndexed { index, value ->
                NetworkHeader("[$index]", jsonElementText(value).take(4_000))
            }
            else -> listOf(NetworkHeader("body", body.take(8_000)))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        listOf(NetworkHeader("body", body.take(8_000)))
    }
}

fun jsonToTableRows(raw: String): List<TableInputRow> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "{}" || trimmed == "[]") {
        return listOf(TableInputRow())
    }
    if (trimmed.length > MAX_JSON_STRUCTURE_CHARS) return listOf(TableInputRow())
    return try {
        when (val element = compactJson.parseToJsonElement(trimmed)) {
            is JsonObject -> {
                val rows = element.entries.flatMap { (key, value) ->
                    when (value) {
                        is JsonArray -> value.map { item ->
                            TableInputRow(key = key, value = jsonElementText(item), enabled = true)
                        }
                        else -> listOf(TableInputRow(key = key, value = jsonElementText(value), enabled = true))
                    }
                }
                rows.ifEmpty { listOf(TableInputRow()) }
            }
            else -> listOf(TableInputRow())
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        listOf(TableInputRow())
    }
}

fun tableRowsToJson(rows: List<TableInputRow>): String {
    val map = linkedMapOf<String, JsonElement>()
    rows.filter { it.enabled && it.key.isNotBlank() }.forEach { row ->
        val value = jsonPrimitiveFromText(row.value)
        when (val existing = map[row.key]) {
            null -> map[row.key] = value
            is JsonArray -> map[row.key] = JsonArray(existing + value)
            else -> map[row.key] = JsonArray(listOf(existing, value))
        }
    }
    return if (map.isEmpty()) {
        DEFAULT_JSON_OBJECT
    } else {
        prettyJson.encodeToString(JsonObject.serializer(), JsonObject(map))
    }
}

fun editorDisplayText(raw: String): String {
    if (raw.length <= MAX_EDITOR_CHARS) return raw
    return raw.take(MAX_EDITOR_CHARS)
}

fun methodHasBody(method: HttpMethod): Boolean =
    method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH

private fun jsonElementToNode(
    key: String,
    value: JsonElement,
    depth: Int,
    counter: IntArray
): JsonTreeNode {
    if (counter[0] >= MAX_TREE_NODES || depth >= MAX_TREE_DEPTH) {
        return JsonTreeNode(key = key, preview = "…")
    }
    counter[0]++
    return when (value) {
        is JsonObject -> JsonTreeNode(
            key = key,
            preview = if (value.isEmpty()) "{}" else "{${value.size}}",
            children = if (depth + 1 >= MAX_TREE_DEPTH) {
                emptyList()
            } else {
                value.entries.take(200).map { (childKey, child) ->
                    jsonElementToNode(childKey, child, depth + 1, counter)
                }
            }
        )
        is JsonArray -> JsonTreeNode(
            key = key,
            preview = if (value.isEmpty()) "[]" else "[${value.size}]",
            children = if (depth + 1 >= MAX_TREE_DEPTH) {
                emptyList()
            } else {
                value.take(200).mapIndexed { index, child ->
                    jsonElementToNode("[$index]", child, depth + 1, counter)
                }
            }
        )
        JsonNull -> JsonTreeNode(key = key, preview = "null")
        is JsonPrimitive -> JsonTreeNode(key = key, preview = jsonPrimitivePreview(value))
    }
}

private fun jsonPrimitivePreview(value: JsonPrimitive): String = when {
    value.isString -> "\"${value.content}\""
    else -> value.content
}

private fun jsonElementText(value: JsonElement): String = when (value) {
    is JsonPrimitive -> value.content
    JsonNull -> "null"
    else -> prettyJson.encodeToString(JsonElement.serializer(), value)
}

private fun primitiveContent(element: JsonElement?): String? =
    (element as? JsonPrimitive)?.contentOrNull ?: (element as? JsonPrimitive)?.content

private fun jsonPrimitiveFromText(raw: String): JsonElement {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return JsonPrimitive("")
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        runCatching { return compactJson.parseToJsonElement(trimmed) }
    }
    val asLong = trimmed.toLongOrNull()
    val asDouble = trimmed.toDoubleOrNull()
    return when {
        trimmed.equals("null", ignoreCase = true) -> JsonNull
        trimmed.equals("true", ignoreCase = true) -> JsonPrimitive(true)
        trimmed.equals("false", ignoreCase = true) -> JsonPrimitive(false)
        asLong != null && trimmed == asLong.toString() -> JsonPrimitive(asLong)
        asDouble != null && !trimmed.startsWith("0") && trimmed.any { it == '.' } -> JsonPrimitive(asDouble)
        else -> JsonPrimitive(raw)
    }
}
