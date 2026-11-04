package com.aicode.feature.agent.domain.tool.instabox

import com.aicode.core.net.AppProxy
import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.repository.InstaboxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class InstaboxHttpResult(
    val code: Int,
    val body: String,
    val bytes: ByteArray? = null
)

class InstaboxApiException(
    val code: Int,
    val errorCode: String?,
    override val message: String
) : Exception(message)

@Singleton
class InstaboxClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .build()
    }

    private val longClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(620, TimeUnit.SECONDS)
            .build()
    }

    fun baseUrl(config: InstaboxConfig): String =
        config.apiUrl.trim().ifBlank { InstaboxConfig.DEFAULT_API_URL }.trimEnd('/')

    suspend fun request(
        config: InstaboxConfig,
        method: String,
        path: String,
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap(),
        idempotent: Boolean = false,
        confirmDelete: String? = null,
        rawBody: ByteArray? = null,
        accept: String = "application/json",
        binary: Boolean = false,
        longTimeout: Boolean = false
    ): InstaboxHttpResult = withContext(Dispatchers.IO) {
        val urlBuilder = "${baseUrl(config)}$path".toHttpUrl().newBuilder()
        query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("X-API-Key", config.apiKey)
            .header("Accept", accept)
            .header("User-Agent", "aicode/1.0")
        if (idempotent) builder.header("Idempotency-Key", UUID.randomUUID().toString())
        if (confirmDelete != null) builder.header("X-Confirm-Delete", confirmDelete)
        val requestBody = when {
            rawBody != null -> rawBody.toRequestBody("application/octet-stream".toMediaType())
            body != null || method == "POST" || method == "PUT" || method == "PATCH" ->
                (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
            else -> null
        }
        builder.method(method, requestBody)
        val http = if (longTimeout) longClient else client
        http.newCall(builder.build()).execute().use { resp ->
            if (binary) {
                InstaboxHttpResult(resp.code, "", resp.body?.bytes())
            } else {
                InstaboxHttpResult(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    suspend fun requestJson(
        config: InstaboxConfig,
        method: String,
        path: String,
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap(),
        idempotent: Boolean = false,
        confirmDelete: String? = null,
        longTimeout: Boolean = false
    ): JsonElement {
        val result = request(
            config = config,
            method = method,
            path = path,
            body = body,
            query = query,
            idempotent = idempotent,
            confirmDelete = confirmDelete,
            longTimeout = longTimeout
        )
        if (result.code !in 200..299) throw apiError(result)
        if (result.body.isBlank()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(result.body) }
            .getOrElse { JsonObject(mapOf("raw" to JsonPrimitive(result.body))) }
    }

    suspend fun waitForOperation(config: InstaboxConfig, operationId: String, attempts: Int = 24): JsonObject {
        repeat(attempts) {
            val el = requestJson(config, "GET", "/v1/operations/$operationId", query = mapOf("wait" to "25s"))
            val obj = el.jsonObject
            when (obj["state"]?.jsonPrimitive?.contentOrNull) {
                "succeeded" -> return obj
                "failed" -> {
                    val err = obj["error"]?.jsonObject
                    throw InstaboxApiException(
                        500,
                        err?.get("code")?.jsonPrimitive?.contentOrNull,
                        err?.get("message")?.jsonPrimitive?.contentOrNull ?: "InstaBox operation failed"
                    )
                }
            }
            delay(500)
        }
        throw InstaboxApiException(504, "timeout", "InstaBox operation timed out")
    }

    suspend fun ensureRunning(config: InstaboxConfig, machineId: String): JsonObject {
        val info = requestJson(config, "GET", "/v1/machines/$machineId").jsonObject
        val state = info["state"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (state == "running") return info
        val path = when (state) {
            "suspended", "suspending" -> "/v1/machines/$machineId/resume"
            "stopped", "stopping", "error" -> "/v1/machines/$machineId/start"
            else -> null
        }
        if (path != null) {
            val op = requestJson(config, "POST", path, idempotent = true).jsonObject
            op["id"]?.jsonPrimitive?.contentOrNull?.let { waitForOperation(config, it) }
        }
        return requestJson(config, "GET", "/v1/machines/$machineId").jsonObject
    }

    suspend fun testConnection(config: InstaboxConfig): Result<String> = runCatching {
        if (config.apiKey.isBlank()) error("API key is empty")
        val el = requestJson(config, "GET", "/v1/machines", query = mapOf("limit" to "5"))
        val count = (el.jsonObject["machines"] as? JsonArray)?.size ?: 0
        "Connected. $count machine(s) visible."
    }

    fun apiError(result: InstaboxHttpResult): InstaboxApiException {
        val parsed = runCatching { json.parseToJsonElement(result.body).jsonObject }.getOrNull()
        val err = parsed?.get("error")?.jsonObject ?: parsed
        val message = err?.get("message")?.jsonPrimitive?.contentOrNull
            ?: parsed?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "InstaBox HTTP ${result.code}"
        val code = err?.get("code")?.jsonPrimitive?.contentOrNull
        FileLogger.w(TAG, "InstaBox HTTP ${result.code} $code: $message")
        return InstaboxApiException(result.code, code, message)
    }

    fun summarizeMachine(obj: JsonObject): JsonObject = buildJsonObject {
        put("id", obj["id"] ?: JsonPrimitive(""))
        put("name", obj["name"] ?: JsonPrimitive(""))
        put("state", obj["state"] ?: JsonPrimitive(""))
        obj["template"]?.jsonObject?.get("id")?.let { put("template", it) }
        obj["spec"]?.jsonObject?.let { spec ->
            put("vcpus", spec["vcpus"] ?: JsonPrimitive(0))
            put("memMiB", spec["memMiB"] ?: JsonPrimitive(0))
        }
        obj["autoSuspendAfterSeconds"]?.let { put("autoSuspendAfterSeconds", it) }
        obj["metadata"]?.let { put("metadata", it) }
    }

    companion object {
        private const val TAG = "InstaboxClient"
        fun jsonObj(vararg pairs: Pair<String, JsonElement>) = buildJsonObject {
            pairs.forEach { (k, v) -> put(k, v) }
        }
    }
}
