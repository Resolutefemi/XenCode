package com.xencode.feature.agent.domain.notification

import com.xencode.feature.agent.domain.model.AgentMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification piggyback injector: valid JSON results get a top-level `notifications` field injected and stay parseable;
 * non-JSON results fall back to text appending so no events are lost.
 */
class AgentEventInjectorTest {

    private val injector = DefaultAgentEventInjector()

    private val modeChange = PendingNotification(
        kind = AgentNotificationKind.MODE_CHANGE,
        sourceId = "user",
        title = "PLAN",
        outcome = NotificationOutcome.COMPLETED,
        newMode = AgentMode.PLAN
    )

    @Test
    fun inject_validJson_addsNotificationsFieldAndKeepsOriginal() {
        val raw = """{"success":true,"data":{"message":"ok"}}"""

        val injected = injector.inject(raw, listOf(modeChange))
        val obj = Json.parseToJsonElement(injected).jsonObject

        assertEquals("ok", (obj["data"] as JsonObject)["message"]!!.jsonPrimitive.content)
        val notifications = obj["notifications"] as JsonArray
        assertEquals(1, notifications.size)
        assertEquals("mode_change", (notifications[0] as JsonObject)["kind"]!!.jsonPrimitive.content)
    }

    @Test
    fun inject_nonJson_fallsBackToTextAppend() {
        val raw = "tool output is plain text"

        val injected = injector.inject(raw, listOf(modeChange))

        assertTrue(injected.startsWith(raw))
        assertTrue(injected.contains("<mode-change>"))
        assertTrue(injected.contains("<new-mode>PLAN</new-mode>"))
    }

    @Test
    fun inject_emptyEvents_returnsRaw() {
        val raw = """{"success":true}"""

        assertEquals(raw, injector.inject(raw, emptyList()))
    }
}