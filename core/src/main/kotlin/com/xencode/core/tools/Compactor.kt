package com.xencode.core.tools

import com.xencode.core.provider.ChatClient

/**
 * Deterministic context compaction for long histories.
 *
 * When a history grows past [maxChars], the middle is replaced by a digest of
 * the dropped exchange (user asks + assistant replies, truncated), keeping the
 * first user message and the last [keepLast] messages verbatim. `/compact` uses
 * the same machinery with an aggressive threshold.
 */
object Compactor {

    data class Plan(
        val kept: List<ChatClient.ChatMessage>,
        val summaryMessage: ChatClient.ChatMessage?,
        val savedChars: Int,
        val droppedCount: Int
    )

    fun charsOf(history: List<ChatClient.ChatMessage>): Int =
        history.sumOf { (it.content?.length ?: 0) + (it.toolCalls?.sumOf { c -> c.argumentsJson.length + c.name.length } ?: 0) }

    /**
     * @param maxChars compact only when the history exceeds this
     * @param keepLast how many trailing messages survive verbatim
     */
    fun plan(history: List<ChatClient.ChatMessage>, maxChars: Int = 24_000, keepLast: Int = 6): Plan {
        if (history.size <= keepLast + 1 || charsOf(history) <= maxChars) {
            return Plan(history, null, 0, 0)
        }
        val firstUser = 0.coerceAtMost(history.size - 1)
        val cutStart = firstUser + 1
        val cutEnd = history.size - keepLast
        if (cutEnd <= cutStart) return Plan(history, null, 0, 0)

        val dropped = history.subList(cutStart, cutEnd)
        val digest = StringBuilder()
        digest.appendLine("[Context compacted by XenCode — ${dropped.size} messages summarized]")
        for (m in dropped) {
            when {
                m.toolCallId != null -> digest.appendLine("- tool result: ${(m.content ?: "").take(80)}")
                m.toolCalls != null -> digest.appendLine("- tool calls: ${m.toolCalls.joinToString { it.name }}")
                m.role == "user" -> digest.appendLine("- user asked: ${(m.content ?: "").take(120)}")
                m.role == "assistant" -> digest.appendLine("- agent said: ${(m.content ?: "").take(120)}")
            }
        }
        val summary = ChatClient.ChatMessage(
            role = "user",
            content = digest.toString().trim()
        )
        val kept = listOf(history[firstUser], summary) + history.subList(cutEnd, history.size)
        return Plan(kept, summary, charsOf(history) - charsOf(kept), dropped.size)
    }
}
