package com.xencode.feature.agent.domain.notification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Piggybacks pending system events into a tool result. Extracted as an interface so features reuse the same injection point and semantics,
 * without each implementing the "stuff events into the raw result" details.
 */
interface AgentEventInjector {
    /**
     * @param rawToolResult The raw tool result (usually transport JSON text)
     * @param events Pending system events
     * @return Result text that downstream can still parse after injection
     */
    fun inject(rawToolResult: String, events: List<PendingNotification>): String
}

/**
 * Default implementation: prefers injecting events as a top-level `notifications` field of the transport JSON, leaving valid JSON intact,
 * so the UI's formatToolResult (which reads data/message only) and other structured parsers are unaffected.
 * When raw is no longer valid JSON (e.g. plain-text mode-switch notices were appended), it degrades to text appending.
 */
class DefaultAgentEventInjector : AgentEventInjector {

    override fun inject(rawToolResult: String, events: List<PendingNotification>): String {
        if (events.isEmpty()) return rawToolResult
        val obj = runCatching { Json.parseToJsonElement(rawToolResult).jsonObject }.getOrNull()
            ?: return rawToolResult + "\n\n" + AgentNotificationFormatter.buildMessage(events)
        return JsonObject(obj + ("notifications" to AgentNotificationFormatter.buildJsonArray(events))).toString()
    }
}