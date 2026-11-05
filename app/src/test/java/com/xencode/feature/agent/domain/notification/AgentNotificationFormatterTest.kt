package com.xencode.feature.agent.domain.notification

import com.xencode.feature.agent.domain.model.AgentMode
import com.xencode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Formats of the two notification delivery shapes: the fallback user message text (the UI banner extracts it via the <status>/<summary> regex,
 * so changing the format breaks the banner) and the `notifications` JSON array used for piggybacking.
 */
class AgentNotificationFormatterTest {

    private val backgroundTask = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = "term-2",
        title = "gradle build",
        outcome = NotificationOutcome.FAILED,
        command = "./gradlew assemble",
        exitCode = 1,
        tailOutput = "error: <unresolved> & failed"
    )

    private val subAgent = PendingNotification(
        kind = AgentNotificationKind.SUBAGENT,
        sourceId = "sub-1",
        title = "research the cache implementation",
        outcome = NotificationOutcome.COMPLETED
    )

    private val stoppedSubAgent = PendingNotification(
        kind = AgentNotificationKind.SUBAGENT,
        sourceId = "sub-2",
        title = "refactor the cache layer",
        outcome = NotificationOutcome.STOPPED,
        detail = "The user stopped this subagent from the UI; the task did not finish."
    )

    private val modeChange = PendingNotification(
        kind = AgentNotificationKind.MODE_CHANGE,
        sourceId = "user",
        title = "ASK",
        outcome = NotificationOutcome.COMPLETED,
        newMode = AgentMode.ASK
    )

    private val userMessage = PendingNotification(
        kind = AgentNotificationKind.USER_MESSAGE,
        sourceId = "session-1",
        title = "",
        outcome = NotificationOutcome.COMPLETED,
        message = "switch to <option B> & mind compatibility"
    )

    @Test
    fun message_singleBackgroundTask_hasFenceAndFields() {
        val text = AgentNotificationFormatter.buildMessage(listOf(backgroundTask))

        assertTrue(text.startsWith(BACKGROUND_NOTIFICATION_PREFIX))
        assertTrue(text.contains("<task-notification>"))
        assertTrue(text.contains("<task-id>term-2</task-id>"))
        assertTrue(text.contains("<exit-code>1</exit-code>"))
        assertTrue(text.contains("<status>failed</status>"))
        assertTrue(text.contains("<summary>Background task \"gradle build\" finished (exit code 1)</summary>"))
        assertTrue(text.contains("terminal(action=\"read\", tab_id=\"term-2\")"))
    }

    /** Bare angle brackets in tail output would be mismatched by the UI's <status>/<summary> regex, so they must be escaped. */
    @Test
    fun message_escapesTailOutput() {
        val text = AgentNotificationFormatter.buildMessage(listOf(backgroundTask))

        assertTrue(text.contains("&lt;unresolved&gt;"))
        assertTrue(text.contains("&amp;"))
        assertFalse(text.contains("<unresolved>"))
    }

    @Test
    fun message_subAgentUsesSubagentTags() {
        val text = AgentNotificationFormatter.buildMessage(listOf(subAgent))

        assertTrue(text.contains("<subagent-notification>"))
        assertTrue(text.contains("<subagent-id>sub-1</subagent-id>"))
        assertTrue(text.contains("<summary>Subagent \"research the cache implementation\" completed</summary>"))
        assertTrue(text.contains("task(action=\"read\", id=\"sub-1\")"))
    }

    @Test
    fun message_mergesMixedKinds() {
        val text = AgentNotificationFormatter.buildMessage(listOf(backgroundTask, subAgent))

        assertTrue(text.contains("There are 2 background completion notifications"))
        assertTrue(text.contains("<task-notification>"))
        assertTrue(text.contains("<subagent-notification>"))
        // When both kinds are present, both hint sentences must be given so the AI knows how to retrieve each result
        assertTrue(text.contains("tab_id=\"term-2\""))
        assertTrue(text.contains("id=\"sub-1\""))
    }

    @Test
    fun jsonArray_carriesStatusSummaryAndHint() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(backgroundTask, subAgent))

        assertEquals(2, array.size)
        val first = array[0] as JsonObject
        assertEquals("background_task", (first["kind"] as JsonPrimitive).content)
        assertEquals("term-2", (first["task_id"] as JsonPrimitive).content)
        assertEquals("failed", (first["status"] as JsonPrimitive).content)
        assertTrue((first["summary"] as JsonPrimitive).content.contains("gradle build"))
        assertTrue((first["hint"] as JsonPrimitive).content.contains("terminal(action=\"read\""))
        // The notice is the fence explanation: even seeing just one item, the AI knows it is not a user message
        assertTrue((first["notice"] as JsonPrimitive).content.contains("not a message from the user"))

        val second = array[1] as JsonObject
        assertEquals("subagent", (second["kind"] as JsonPrimitive).content)
        assertEquals("sub-1", (second["subagent_id"] as JsonPrimitive).content)
        assertEquals("completed", (second["status"] as JsonPrimitive).content)
    }

    /** The JSON shape relies on kotlinx for escaping; tail_output must stay verbatim with no XML escaping. */
    @Test
    fun jsonArray_keepsTailOutputRaw() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(backgroundTask))
        val obj = array[0] as JsonObject

        assertEquals("error: <unresolved> & failed", (obj["tail_output"] as JsonPrimitive).content)
    }

    /**
     * A manual stop by the user must not be reported as failed: that would make the AI treat a deliberate interruption as a fault to investigate or immediately retry.
     * The detail carries the stop reason so the parent agent is not left knowing only that it "didn't finish".
     */
    @Test
    fun message_stoppedSubAgentCarriesReasonAndNoRetryHint() {
        val text = AgentNotificationFormatter.buildMessage(listOf(stoppedSubAgent))

        assertTrue(text.contains("<status>stopped</status>"))
        assertTrue(text.contains("<summary>Subagent \"refactor the cache layer\" was stopped by the user; the task did not finish</summary>"))
        assertTrue(text.contains("<detail>The user stopped this subagent from the UI; the task did not finish.</detail>"))
        assertTrue(text.contains("do not retry on your own"))
    }

    @Test
    fun jsonArray_stoppedSubAgentCarriesDetail() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(stoppedSubAgent))
        val obj = array[0] as JsonObject

        assertEquals("stopped", (obj["status"] as JsonPrimitive).content)
        assertTrue((obj["detail"] as JsonPrimitive).content.contains("stopped this subagent"))
    }

    /** Normal completion carries no detail, to avoid wasting context. */
    @Test
    fun jsonArray_completedSubAgentHasNoDetail() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(subAgent))
        val obj = array[0] as JsonObject

        assertEquals(null, obj["detail"])
    }

    /** Mode-change notification: XML uses <mode-change> and status is "changed" (the UI renders it as non-failure accordingly). */
    @Test
    fun message_modeChangeUsesModeChangeTag() {
        val text = AgentNotificationFormatter.buildMessage(listOf(modeChange))

        assertTrue(text.contains("<mode-change>"))
        assertTrue(text.contains("<new-mode>ASK</new-mode>"))
        assertTrue(text.contains("<status>changed</status>"))
        assertTrue(text.contains("<summary>The user switched the mode to ASK</summary>"))
    }

    @Test
    fun jsonArray_modeChangeCarriesNewMode() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(modeChange))
        val obj = array[0] as JsonObject

        assertEquals("mode_change", (obj["kind"] as JsonPrimitive).content)
        assertEquals("ASK", (obj["new_mode"] as JsonPrimitive).content)
        assertEquals("changed", (obj["status"] as JsonPrimitive).content)
        assertTrue((obj["hint"] as JsonPrimitive).content.contains("new mode"))
    }

    /**
     * A user interjection stored as a user message is emitted verbatim, without the system notification prefix — otherwise the UI would render it as
     * a system banner and misclassify the completion-notification body too.
     */
    @Test
    fun message_userMessageIsPlainUserText() {
        val text = AgentNotificationFormatter.buildMessage(listOf(userMessage))

        assertEquals(userMessage.message, text)
        assertFalse(text.startsWith(BACKGROUND_NOTIFICATION_PREFIX))
        assertFalse(text.contains("not a message from the user"))
    }

    /** The piggybacked JSON shape: kind=user_message, with a notice clarifying it is the user's own input, not a system event. */
    @Test
    fun jsonArray_userMessageCarriesUserNotice() {
        val array = AgentNotificationFormatter.buildJsonArray(listOf(userMessage))
        val obj = array[0] as JsonObject

        assertEquals("user_message", (obj["kind"] as JsonPrimitive).content)
        assertEquals("message", (obj["status"] as JsonPrimitive).content)
        assertEquals(userMessage.message, (obj["message"] as JsonPrimitive).content)
        assertTrue((obj["notice"] as JsonPrimitive).content.contains("user"))
        assertFalse((obj["notice"] as JsonPrimitive).content.contains("not a message from the user"))
    }
}
