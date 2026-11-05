package com.xencode.core.tools

import com.xencode.core.provider.ChatTransport
import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.ProviderConfig

/**
 * Multi-turn agent session with a local tool loop.
 *
 * The caller keeps one [AgentSession] per conversation. [send] streams the model
 * reply through [onDelta]; when the model requests tools they are executed via
 * the [ToolExecutor] (local tools + MCP, permission-gated upstream) and the loop
 * continues automatically, up to [maxToolRounds] rounds per user turn.
 *
 * [listener] receives lifecycle callbacks (tool call/result, turn completion) so
 * the CLI/desktop front ends can render swimlanes and emit sync events without
 * subclassing.
 */
class AgentSession(
    private val client: ChatTransport,
    private val config: ProviderConfig,
    private val tools: ToolExecutor,
    private val systemPrompt: String,
    private val maxToolRounds: Int = 10,
    private val listener: Listener? = null
) {
    /** Front-end hooks; all optional. */
    interface Listener {
        suspend fun onToolCall(name: String, argsJson: String) {}
        suspend fun onToolResult(name: String, argsJson: String, result: String) {}
        suspend fun onTurnCompleted(reply: String?) {}
    }

    private val history = mutableListOf<ChatClient.ChatMessage>()

    /**
     * @param onDelta        streamed assistant text (already de-duplicated fragments)
     * @param onToolCall     invoked before local execution (for UI display)
     * @return the final assistant text of this turn, or null on failure.
     */
    suspend fun send(
        userText: String,
        onDelta: suspend (String) -> Unit = {},
        onToolCall: suspend (name: String, argsJson: String) -> Unit = { _, _ -> }
    ): String? {
        history.add(ChatClient.ChatMessage(role = "user", content = userText))
        return runLoop(onDelta, onToolCall)
    }

    /** Continues from a pre-seeded history (session resume); does not append a user turn. */
    suspend fun runFromHistory(onDelta: suspend (String) -> Unit = {}, onToolCall: suspend (name: String, argsJson: String) -> Unit = { _, _ -> }): String? =
        runLoop(onDelta, onToolCall)

    private suspend fun runLoop(
        onDelta: suspend (String) -> Unit,
        onToolCall: suspend (name: String, argsJson: String) -> Unit
    ): String? {
        val specs = tools.schemas().map {
            ChatClient.ToolSpec(it.name, it.description, it.parametersJson)
        }
        var rounds = 0
        while (true) {
            val text = StringBuilder()
            val pendingCalls = mutableListOf<ChatClient.ToolCallMsg>()
            var failure: String? = null
            client.streamChat(config, history, specs, systemPrompt).collect { event ->
                when (event) {
                    is ChatClient.ChatEvent.TextDelta -> {
                        text.append(event.text)
                        onDelta(event.text)
                    }
                    is ChatClient.ChatEvent.ToolCall ->
                        pendingCalls.add(ChatClient.ToolCallMsg(event.id, event.name, event.argumentsJson))
                    ChatClient.ChatEvent.Completed -> { /* handled after collect */ }
                    is ChatClient.ChatEvent.Failed -> failure = event.message
                }
            }
            if (failure != null) {
                // Nothing was appended to the history for this round; the user message
                // stays so a retry can continue the conversation.
                lastFailure = failure
                listener?.onTurnCompleted(null)
                return null
            }
            if (pendingCalls.isEmpty()) {
                val finalText = text.toString().trim()
                if (finalText.isEmpty()) {
                    lastFailure = "empty model response"
                    listener?.onTurnCompleted(null)
                    return null
                }
                history.add(ChatClient.ChatMessage(role = "assistant", content = finalText))
                listener?.onTurnCompleted(finalText)
                return finalText
            }
            // Tool round: record the assistant's tool_use message, then each result.
            history.add(
                ChatClient.ChatMessage(
                    role = "assistant",
                    content = text.toString().trim().ifBlank { null },
                    toolCalls = pendingCalls
                )
            )
            if (++rounds > maxToolRounds) {
                lastFailure = "tool round limit ($maxToolRounds) exceeded"
                listener?.onTurnCompleted(null)
                return null
            }
            for (call in pendingCalls) {
                onToolCall(call.name, call.argumentsJson)
                listener?.onToolCall(call.name, call.argumentsJson)
                val result = tools.execute(call.name, call.argumentsJson)
                listener?.onToolResult(call.name, call.argumentsJson, result)
                history.add(ChatClient.ChatMessage(role = "tool", content = result, toolCallId = call.id))
            }
        }
    }

    /** Last failure message, for display after a null return. */
    var lastFailure: String? = null
        private set

    fun historySnapshot(): List<ChatClient.ChatMessage> = history.toList()

    /** Replaces the in-memory history (session resume, compaction). */
    fun restoreHistory(messages: List<ChatClient.ChatMessage>) {
        history.clear()
        history.addAll(messages)
    }

    /** Applies deterministic compaction; returns the saved characters. */
    fun compactHistory(maxChars: Int = 24_000, keepLast: Int = 6): Int {
        val plan = Compactor.plan(historySnapshot(), maxChars, keepLast)
        if (plan.savedChars <= 0) return 0
        restoreHistory(plan.kept)
        return plan.savedChars
    }

    fun clear() = history.clear()
}
