package com.xencode.feature.agent.domain.provider

import retrofit2.HttpException

/**
 * Converts an upstream HTTP error into an exception carrying the **response body**, so users can pinpoint the real cause of a 4xx/5xx.
 *
 * Retrofit's [HttpException] default message is only a status-line description like `"HTTP 400 Bad Request"`;
 * the specific reason the server wrote in the body (e.g. `model not found`, `invalid api key`, `Invalid value for tools`)
 * is never read out, leaving the user with a useless status code. This reads the errorBody and appends it to the message,
 * keeping the original exception as the cause.
 *
 * Note: errorBody can be read only once and is consumed by this call.
 */
fun Throwable.enrichWithHttpErrorBody(): Throwable {
    if (this !is HttpException) return this
    val raw = this.response()?.errorBody()?.string()?.trim()
    val body = runCatching {
        if (raw.isNullOrBlank()) return@runCatching ""
        // Common shapes: {"error":{"message":"...","type":"..."}} or {"message":"..."}; extract readable text where possible.
        val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
        obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject?.get("message")?.asString
            ?: obj.get("message")?.asString
            ?: raw
    }.getOrNull().orEmpty()
    val detail = if (body.isBlank()) raw?.take(500).orEmpty() else body
    val composed = if (detail.isBlank()) {
        this.message() ?: "HTTP ${this.code()}"
    } else {
        "HTTP ${this.code()}: $detail"
    }
    return IllegalStateException(composed, this)
}
