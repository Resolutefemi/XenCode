package com.xencode.feature.agent.domain.workflow

import com.xencode.feature.agent.domain.model.AgentContext
import com.xencode.feature.agent.domain.model.AgentMode
import com.xencode.feature.agent.domain.provider.RetryErrorInfo
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.flow.Flow

/**
 * Step events pushed to the UI during Agent execution.
 * Each event corresponds to one chat entry (or an update to one); the ViewModel persists and renders them.
 */
sealed class AgentEvent {
    /** A text reply produced by the model (possibly alongside tool calls). [reasoning] is this turn's thinking process, persisted for history display; empty string means none. */
    data class AssistantText(
        val content: String,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoning: String = "",
        /** Encrypted signature of Anthropic extended thinking, persisted with reasoning for tool-loop passback. Empty string for other providers. */
        val signature: String = "",
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val cachedInputTokens: Int = 0,
        /** Verbatim snapshot of Anthropic thinking / redacted_thinking content blocks (JSON array text), persisted with reasoning for passback verbatim in later turns. */
        val thinkingBlocksJson: String = "",
        /** File cards constructed after persisting model-emitted images (Gemini image models), stored with the message for the UI to render. */
        val attachments: List<com.xencode.feature.agent.presentation.AgentAttachment> = emptyList(),
        val messageId: String = java.util.UUID.randomUUID().toString(),
        val persisted: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    ) : AgentEvent()

    /** Text streamed token by token during the run ([accumulated] is the full text accumulated this turn, for live UI rendering; not persisted). */
    data class AssistantDelta(val accumulated: String) : AgentEvent()

    /** Thinking process streamed token by token during the run ([accumulated] is the full thinking accumulated this turn, for live UI rendering; not persisted). */
    data class ReasoningDelta(val accumulated: String) : AgentEvent()

    /** The model decided to call a tool (before execution). */
    data class ToolCallStarted(val id: String, val toolName: String, val argsPreview: String) : AgentEvent()

    /**
     * The model has just started producing a tool call and the tool name is known (arguments are still streaming in).
     *
     * For long-argument tools (writing a whole file, long commands), argument streaming can last several seconds during which there is neither body text nor thinking deltas,
     * so the UI can only show a generic "Thinking". This event lets the UI make the status concrete sooner; not persisted, not part of execution decisions.
     */
    data class ToolCallPreparing(val toolName: String) : AgentEvent()

    /** Live accumulated output while a tool runs (produced only by streaming tools, for live UI rendering; not persisted). */
    data class ToolCallProgress(val id: String, val toolName: String, val accumulated: String) : AgentEvent()

    /** The tool's finished result (success, failure, or rejected by the user). */
    data class ToolCallFinished(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        val argsPreview: String? = null,
        /** Display-only tools such as sendFile: file-card metadata attached to the result, persisted for UI rendering, not fed back into the model context. */
        val attachments: List<com.xencode.feature.agent.presentation.AgentAttachment> = emptyList(),
        val persisted: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    ) : AgentEvent()

    /** A network request is retrying (auto retry triggered by a pre-first-byte failure). For live UI display only, not persisted. [error] is a summary of the error that triggered the retry. */
    data class Retrying(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo) : AgentEvent()

    /** Multi-key auto-switch: the current key is unusable, so this request is being resent with key [newIndex] of [total]. For live UI display only, not persisted. */
    data class KeySwitched(val newIndex: Int, val total: Int) : AgentEvent()

    /** Context compaction in progress. For live UI display only, not persisted. */
    data class CompactionStarted(val estimatedTokens: Int) : AgentEvent()

    /** The context compaction flow has ended (success or failure). For live UI display only, not persisted. */
    object CompactionFinished : AgentEvent()

    data class ContextUsage(val inputTokens: Int, val inputBudget: Int, val estimated: Boolean) : AgentEvent()

    /** Context compaction failed (e.g. the compaction model is unavailable). Carries the failure reason, shown by the UI as an expandable failure card; not persisted. */
    data class CompactionFailed(val reason: String) : AgentEvent()

    /**
     * The whole flow terminated due to an error (e.g. the streaming request was cut off by the network, or the iteration cap was reached). Unlike [Completed]: carries an error, and the UI should show an error, not success.
     * [reasonCode] is the server-provided type code (e.g. Anthropic's refusal / model_context_window_exceeded),
     * which the UI can map to a localized message; when null, show [error] directly.
     */
    data class Failed(val error: String, val reasonCode: String? = null) : AgentEvent()

    /** The whole flow has ended. */
    object Completed : AgentEvent()

    /** The mode has switched (triggered by the AI calling planMode); the UI shows the plan-review panel etc. accordingly. */
    data class ModeChanged(val newMode: AgentMode, val reason: String) : AgentEvent()
}

interface AgentWorkflow {
    /**
     * Runs the Agent loop and pushes progress step by step as an event flow:
     * model reply → (possible) tool calls → tool results → reply again …… until the model calls no more tools.
     */
    fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent>

    /**
     * Manually triggers context compaction for the given session (invoked by the /compress command).
     * Reuses the auto-compaction logic: resolves the currently active provider and runs ContextCompactor over the message history.
     * The compaction result is persisted inside ContextCompactor, and compaction progress events are pushed via the [onEvent] callback.
     * @return Whether compaction was actually triggered (false when history is under the threshold, with no changes made).
     */
    suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit = {}): Boolean

    /**
     * Generates a title for a newly created session (called asynchronously after the first user message).
     * Follows the current chat model by default; uses the dedicated title-summary model if configured and available.
     * Returns null when generation fails or no title is produced (the caller keeps the temporary title).
     */
    suspend fun generateTitle(sessionId: String, request: String): String?

    /**
     * Generates a Conventional Commits-compliant commit message from a Git diff.
     * Uses the globally active AI provider and default model by default.
     * @param diff The change diff text (`git diff --cached` or the working-tree diff)
     * @return The suggested commit message, or null on failure.
     */
    suspend fun generateCommitMessage(diff: String): String?
}
