package com.xencode.feature.agent.domain.provider

import com.xencode.feature.settings.domain.model.DEFAULT_KEY_SWITCH_STATUS_CODES
import retrofit2.HttpException

/**
 * Decides whether a failed LLM call is attributable to the API key currently in use, i.e. whether automatic multi-key switching should trigger.
 *
 * Only authentication/permission, billing/quota, and rate-limit failures count: HTTP status codes default to 401/402/403/429 (overridable per provider,
 * see [AIProviderConfig.keySwitchStatusCodes]) plus in-stream quota/authentication error codes. 5xx, 408, timeouts,
 * and DNS/connection failures are server-side or link problems where switching keys does not help and must not trigger a switch.
 *
 * [enrichWithHttpErrorBody] wraps [HttpException] in an IllegalStateException with the original attached as the
 * cause, so walk the cause chain for the status code and fall back to message-text matching only when it is absent.
 */
fun Throwable.isKeySwitchFailure(statusCodes: Set<Int> = DEFAULT_KEY_SWITCH_STATUS_CODES): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is HttpException -> if (current.code() in statusCodes) return true
            is StreamApiException -> {
                if (current.code?.lowercase() in KEY_FAILURE_STREAM_CODES) return true
            }
        }
        current = current.cause
        depth++
    }
    val text = message?.lowercase() ?: return false
    // The HTTP status-code text fallback is likewise constrained by [statusCodes], otherwise a custom code set could not actually narrow the trigger
    if (statusCodes.any { text.contains("http $it") }) return true
    return KEY_FAILURE_MESSAGES.any { text.contains(it) }
}

private const val MAX_CAUSE_DEPTH = 5

private val KEY_FAILURE_STREAM_CODES = setOf(
    "insufficient_quota",
    "quota_exceeded",
    "usage_limit_reached",
    "usage_not_included",
    "rate_limit_exceeded",
    "rate_limit_error",
    "invalid_api_key",
    "authentication_error",
    "permission_error",
    "permission_denied"
)

private val KEY_FAILURE_MESSAGES = listOf(
    "invalid api key",
    "invalid_api_key",
    "incorrect api key",
    "insufficient_quota",
    "insufficient balance",
    "quota exceeded",
    "authentication_error",
    "permission_denied"
)
