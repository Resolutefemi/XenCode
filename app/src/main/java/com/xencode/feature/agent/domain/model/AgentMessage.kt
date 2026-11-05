package com.xencode.feature.agent.domain.model

import kotlinx.serialization.Serializable
import com.xencode.feature.agent.domain.subagent.AgentDefinition
import com.xencode.feature.agent.domain.tool.ToolCall

@Serializable
sealed class AgentMessage {
    @Serializable
    data class UserMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String,
        val images: List<AgentImage> = emptyList()
    ) : AgentMessage()

    @Serializable
    data class AssistantMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        /** The model's reasoning for this turn (OpenAI/DeepSeek reasoning_content). Must be sent back verbatim when replaying context, or DeepSeek thinking mode returns a 400. */
        val reasoning: String = "",
        /** Anthropic extended thinking's encrypted signature. Sent back verbatim together with [reasoning] (required in the tool loop), otherwise 400. Empty string for other providers. */
        val signature: String = "",
        /** Verbatim snapshot of Anthropic thinking / redacted_thinking content blocks (JSON array text); must be replayed verbatim and in order. Empty string for other providers. */
        val thinkingBlocksJson: String = "",
        /**
         * Images generated directly by the model this turn (Gemini image models). In memory, base64Data may be empty and path points at the container file;
         * on replay the base64 is rebuilt from path to feed the model. Persistence stores only the attachment path, not base64 (see [MessagePersistenceUseCase]).
         */
        val images: List<AgentImage> = emptyList(),
        val inputTokens: Int = 0
    ) : AgentMessage()

    @Serializable
    data class ToolResultMessage(
        val id: String = java.util.UUID.randomUUID().toString(),
        val toolName: String,
        val result: String,
        val images: List<AgentImage> = emptyList(),
        /** Compact result text fed to the model only; falls back to [result] when null. UI and persistence still use result. */
        val modelResult: String? = null,
        val messageId: String = "tool_$id"
    ) : AgentMessage()
}

@Serializable
data class AgentImage(
    val mimeType: String,
    val base64Data: String,
    val path: String = ""
)

const val CONTEXT_COMPACTION_MARKER = "What did we do so far?"
// Legacy Chinese marker persisted by earlier app versions on compacted context summaries.
// Kept verbatim (do NOT translate): it is matched against historical data to strip the old prefix.
const val CONTEXT_SUMMARY_LEGACY_PREFIX = "【系统提示：早期的对话已被压缩，以下是之前的核心状态摘要】"

val AgentMessage.id: String
    get() = when (this) {
        is AgentMessage.UserMessage -> id
        is AgentMessage.AssistantMessage -> id
        is AgentMessage.ToolResultMessage -> messageId
    }

data class AgentContext(
    val currentFile: String?,
    val selectedCode: String?,
    val projectRoot: String,
    val language: String?,
    val history: List<AgentMessage> = emptyList(),
    val inputImages: List<AgentImage> = emptyList(),
    /** Current session id: routes this turn's AI requests/responses into that session's log file ([com.xencode.core.util.AILogger]). */
    val sessionId: String? = null,
    val inputMessageId: String = java.util.UUID.randomUUID().toString(),
    val lastInputTokens: Int = 0,
    val mode: AgentMode = AgentMode.BUILD,
    /** Mode before entering PLAN (BUILD/ASK): restored when leaving PLAN; null is treated as BUILD. */
    val modeBeforePlan: AgentMode? = null,
    /** Reasoning effort ("low"/"medium"/"high"), passed on every LLM request to providers that support it. */
    val reasoningEffort: String? = null,
    /**
     * Custom subagent definition bound to this session; non-null means this is a subagent session run by that definition,
     * with the system prompt and tool set assembled from its config (see [com.xencode.feature.agent.domain.prompt.SystemPromptProvider]).
     */
    val agentDefinition: AgentDefinition? = null
)
