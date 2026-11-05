package com.xencode.core.tools

import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.ProviderConfig

/**
 * Multi-turn agent session with a local tool loop.
 *
 * The caller keeps one [AgentSession] per conversation. [send] streams the model
 * reply through [onDelta]; when the model requests tools they are executed locally
 * (via [LocalTools]) and the loop continues automatically, up to [maxToolRounds]
 * rounds per user turn.
 */
class AgentSession(
    private val client: ChatClient,
    private val config: ProviderConfig,
    private val tools: LocalTools,
    private val systemPrompt: String,
    private val maxToolRounds: Int = 10
) {
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
        val specs = LocalTools.TOOL_SCHEMAS.map {
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
                return null
            }
            if (pendingCalls.isEmpty()) {
                val finalText = text.toString().trim()
                if (finalText.isEmpty()) {
                    lastFailure = "empty model response"
                    return null
                }
                history.add(ChatClient.ChatMessage(role = "assistant", content = finalText))
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
                return null
            }
            for (call in pendingCalls) {
                onToolCall(call.name, call.argumentsJson)
                val result = tools.execute(call.name, call.argumentsJson)
                history.add(ChatClient.ChatMessage(role = "tool", content = result, toolCallId = call.id))
            }
        }
    }

    /** Last failure message, for display after a null return. */
    var lastFailure: String? = null
        private set

    fun historySnapshot(): List<ChatClient.ChatMessage> = history.toList()

    fun clear() = history.clear()
}
