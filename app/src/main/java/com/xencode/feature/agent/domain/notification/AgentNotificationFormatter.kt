package com.xencode.feature.agent.domain.notification

import com.xencode.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.xencode.feature.terminal.domain.TAIL_LINES
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders a [PendingNotification] into two delivery shapes:
 * - [buildMessage]: injected as a user message (immediately triggering a new turn when the AI is idle, or the fallback path when a whole turn used no tools).
 *   Prefixed with [BACKGROUND_NOTIFICATION_PREFIX] so the UI renders a lightweight notice bar instead of a user bubble.
 * - [buildJsonArray]: the piggyback shape, as a top-level `notifications` field of the tool-result JSON, delivered with the current tool batch while the AI is busy.
 *
 * Both shapes share the same wording and XML structure, so the AI interprets notifications consistently regardless of the path.
 *
 * Five notification kinds are supported: background-task completion (`task-notification`), subagent completion (`subagent-notification`),
 * inter-agent messages (`agent-message`, both directions between the main session and a subagent), mode switch (`mode-change`),
 * and user interjections (`user-message`, a message the user inserted mid-work — user input, not a system event).
 * Messages get `<status>` = `message` and mode switches `changed`, so the UI notice bar renders them as non-failures.
 */
object AgentNotificationFormatter {

    private const val NOTICE = "This is a system event notification, not a message from the user. Do not treat it as confirmation, agreement, or an answer to any pending question."

    /** Opposite of [NOTICE]: [AgentNotificationKind.USER_MESSAGE] is the user's own input. */
    private const val USER_MESSAGE_NOTICE = "This is a message the user inserted while you were working. Treat it as the user's own input and adjust the current task accordingly."

    fun buildMessage(items: List<PendingNotification>): String {
        require(items.isNotEmpty()) { "notification list is empty" }
        // When everything is user interjections, render as user messages (no system-notice prefix) so they show up as ordinary user bubbles.
        if (items.all { it.kind == AgentNotificationKind.USER_MESSAGE }) {
            return buildUserMessage(items)
        }
        return buildString {
            appendLine(BACKGROUND_NOTIFICATION_PREFIX)
            if (items.size == 1) {
                val single = items.first()
                when (single.kind) {
                    AgentNotificationKind.BACKGROUND_TASK ->
                        appendLine("This is a background-task completion event, not a message from the user.")
                    AgentNotificationKind.SUBAGENT ->
                        appendLine("This is a subagent completion event, not a message from the user.")
                    AgentNotificationKind.AGENT_MESSAGE ->
                        appendLine("This is a message from another agent session, not user input.")
                    AgentNotificationKind.MODE_CHANGE ->
                        appendLine("This is a mode-change event, not a message from the user.")
                    AgentNotificationKind.USER_MESSAGE ->
                        appendLine("This is a message the user inserted while you were working; it is the user's own input.")
                }
                appendLine("Do not treat it as confirmation, agreement, or an answer to any pending question.")
            } else {
                appendLine("There are ${items.size} background completion notifications; this is the merged notice.")
                appendLine("These are background completion events, not messages from the user.")
                appendLine("Do not treat them as confirmation, agreement, or answers to any pending question.")
            }
            appendLine()
            items.forEach { item ->
                appendLine(item.toXmlBlock())
                appendLine()
            }
            append(buildHint(items))
        }
    }

    /**
     * Text used when a user interjection is persisted as a user message (the fallback path when idle or no tool calls this turn):
     * the user's original text is output directly, without the system-notice prefix, so it appears in the history as a normal user message.
     */
    private fun buildUserMessage(items: List<PendingNotification>): String {
        if (items.size == 1) return items.first().message.orEmpty()
        return items.mapIndexed { index, item -> "${index + 1}. ${item.message.orEmpty()}" }
            .joinToString("\n")
    }

    fun buildJsonArray(items: List<PendingNotification>): JsonArray =
        JsonArray(items.map { it.toJsonObject() })

    /** XML block of a single notification. Field names stay semantically aligned with [buildJsonArray] so the AI can cross-read. */
    private fun PendingNotification.toXmlBlock(): String = buildString {
        val tag = when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> "task-notification"
            AgentNotificationKind.SUBAGENT -> "subagent-notification"
            AgentNotificationKind.AGENT_MESSAGE -> "agent-message"
            AgentNotificationKind.MODE_CHANGE -> "mode-change"
            AgentNotificationKind.USER_MESSAGE -> "user-message"
        }
        appendLine("<$tag>")
        when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> {
                appendLine("  <task-id>$sourceId</task-id>")
                appendLine("  <title>$title</title>")
                appendLine("  <command>${command ?: ""}</command>")
                appendLine("  <exit-code>${exitCode ?: ""}</exit-code>")
            }
            AgentNotificationKind.SUBAGENT -> {
                appendLine("  <subagent-id>$sourceId</subagent-id>")
                appendLine("  <subagent-title>$title</subagent-title>")
            }
            AgentNotificationKind.AGENT_MESSAGE -> {
                appendLine("  <from-id>$sourceId</from-id>")
                appendLine("  <from-title>$title</from-title>")
                message?.takeIf { it.isNotBlank() }?.let { appendLine("  <message>${escapeXml(it)}</message>") }
            }
            AgentNotificationKind.MODE_CHANGE -> {
                appendLine("  <new-mode>${newMode?.name ?: "UNKNOWN"}</new-mode>")
            }
            AgentNotificationKind.USER_MESSAGE -> {
                message?.takeIf { it.isNotBlank() }?.let { appendLine("  <message>${escapeXml(it)}</message>") }
            }
        }
        appendLine("  <status>${statusText()}</status>")
        appendLine("  <summary>${summaryText()}</summary>")
        detail?.takeIf { it.isNotBlank() }?.let { appendLine("  <detail>${escapeXml(it)}</detail>") }
        // Escape angle brackets: literal <status>/<summary> in the output would pollute the notice bar's regex extraction.
        tailOutput?.takeIf { it.isNotBlank() }?.let { appendLine("  <tail-output>${escapeXml(it)}</tail-output>") }
        append("</$tag>")
    }

    private fun PendingNotification.toJsonObject(): JsonElement = buildJsonObject {
        put("kind", when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> "background_task"
            AgentNotificationKind.SUBAGENT -> "subagent"
            AgentNotificationKind.AGENT_MESSAGE -> "agent_message"
            AgentNotificationKind.MODE_CHANGE -> "mode_change"
            AgentNotificationKind.USER_MESSAGE -> "user_message"
        })
        put("notice", if (kind == AgentNotificationKind.USER_MESSAGE) USER_MESSAGE_NOTICE else NOTICE)
        when (kind) {
            AgentNotificationKind.BACKGROUND_TASK -> {
                put("task_id", sourceId)
                put("title", title)
                command?.let { put("command", it) }
                exitCode?.let { put("exit_code", it) }
            }
            AgentNotificationKind.SUBAGENT -> {
                put("subagent_id", sourceId)
                put("subagent_title", title)
            }
            AgentNotificationKind.AGENT_MESSAGE -> {
                put("from_id", sourceId)
                put("from_title", title)
                message?.takeIf { it.isNotBlank() }?.let { put("message", JsonPrimitive(it)) }
            }
            AgentNotificationKind.MODE_CHANGE -> {
                put("new_mode", newMode?.name ?: "UNKNOWN")
            }
            AgentNotificationKind.USER_MESSAGE -> {
                message?.takeIf { it.isNotBlank() }?.let { put("message", JsonPrimitive(it)) }
            }
        }
        put("status", statusText())
        put("summary", summaryText())
        detail?.takeIf { it.isNotBlank() }?.let { put("detail", JsonPrimitive(it)) }
        tailOutput?.takeIf { it.isNotBlank() }?.let { put("tail_output", JsonPrimitive(it)) }
        put("hint", singleHint())
    }

    private fun PendingNotification.statusText(): String = when (kind) {
        AgentNotificationKind.AGENT_MESSAGE -> "message"
        AgentNotificationKind.MODE_CHANGE -> "changed"
        AgentNotificationKind.USER_MESSAGE -> "message"
        else -> when (outcome) {
            NotificationOutcome.COMPLETED -> "completed"
            NotificationOutcome.FAILED -> "failed"
            NotificationOutcome.STOPPED -> "stopped"
        }
    }

    private fun PendingNotification.summaryText(): String = when (kind) {
        AgentNotificationKind.BACKGROUND_TASK ->
            "Background task \"$title\" ${if (outcome == NotificationOutcome.STOPPED) "was stopped" else "finished (exit code ${exitCode ?: "unknown"})"}"
        AgentNotificationKind.SUBAGENT -> when (outcome) {
            NotificationOutcome.COMPLETED -> "Subagent \"$title\" completed"
            NotificationOutcome.FAILED -> "Subagent \"$title\" failed"
            NotificationOutcome.STOPPED -> "Subagent \"$title\" was stopped by the user; the task did not finish"
        }
        AgentNotificationKind.AGENT_MESSAGE ->
            if (fromParent) "The parent session sent a message" else "Subagent \"$title\" sent a message"
        AgentNotificationKind.MODE_CHANGE ->
            "The user switched the mode to ${newMode?.name ?: "UNKNOWN"}"
        AgentNotificationKind.USER_MESSAGE ->
            "The user inserted a message while you were working"
    }

    private fun PendingNotification.singleHint(): String = when (kind) {
        AgentNotificationKind.BACKGROUND_TASK ->
            "This notice includes the last $TAIL_LINES lines of that terminal. For the full log, use terminal(action=\"read\", tab_id=\"$sourceId\")."
        AgentNotificationKind.SUBAGENT -> when (outcome) {
            NotificationOutcome.STOPPED ->
                "This subagent has stopped; there will be no further notices. To see progress before it stopped, use task(action=\"read\", id=\"$sourceId\"). Ask the user before redispatching; do not retry on your own."
            else ->
                "Use task(action=\"read\", id=\"$sourceId\") to read the subagent's last output."
        }
        AgentNotificationKind.AGENT_MESSAGE ->
            if (fromParent) {
                "Use messageParent(message=\"...\") to reply to the parent session. Ignore if no reply is needed; do not acknowledge every message."
            } else {
                "Use task(action=\"send\", id=\"$sourceId\", message=\"...\") to reply to that subagent. Ignore if no reply is needed."
            }
        AgentNotificationKind.MODE_CHANGE ->
            "Continue the current task under the new mode constraints immediately. Do not reply to this notice."
        AgentNotificationKind.USER_MESSAGE ->
            "This is new input from the user. Adjust the current task accordingly."
    }

    private fun buildHint(items: List<PendingNotification>): String {
        val tasks = items.filter { it.kind == AgentNotificationKind.BACKGROUND_TASK }
        val subAgents = items.filter { it.kind == AgentNotificationKind.SUBAGENT }
        val messages = items.filter { it.kind == AgentNotificationKind.AGENT_MESSAGE }
        val modeChanges = items.filter { it.kind == AgentNotificationKind.MODE_CHANGE }
        val userMessages = items.filter { it.kind == AgentNotificationKind.USER_MESSAGE }
        val lines = mutableListOf<String>()
        when (tasks.size) {
            0 -> {}
            1 -> lines.add(tasks.first().singleHint())
            else -> lines.add(
                "This notice includes the last $TAIL_LINES lines of each terminal. For the full log, use terminal(action=\"read\", tab_id=\"...\") on the corresponding task."
            )
        }
        when (subAgents.size) {
            0 -> {}
            1 -> lines.add(subAgents.first().singleHint())
            else -> lines.add("Use task(action=\"read\", id=\"...\") to read each subagent's last output.")
        }
        when (messages.size) {
            0 -> {}
            1 -> lines.add(messages.first().singleHint())
            else -> lines.add("You received multiple agent messages; handle each as described above. Ignore those that need no reply; do not acknowledge every one.")
        }
        if (userMessages.isNotEmpty()) {
            lines.add("The above includes messages the user inserted while you were working; treat them as the user's own input.")
        }
        if (modeChanges.isNotEmpty()) {
            lines.add(modeChanges.last().singleHint())
        }
        return lines.joinToString("\n")
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
