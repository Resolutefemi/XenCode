package com.xencode.feature.agent.presentation

import androidx.compose.runtime.Immutable
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.provider.RetryErrorInfo
import com.xencode.feature.workspace.domain.FileEntry
import kotlinx.serialization.Serializable

/**
 * A visible node of the sidebar file tree (flattened per expansion state, carries [depth] for UI indentation).
 * Expanded in place from the workspace root: only the nodes present in [FileBrowseState.Success] are currently visible.
 */
@Immutable
data class FileTreeNode(
    val entry: FileEntry,
    /** Absolute container path (e.g. `~/workspace/app/src`); the workspace root when [isRoot]. */
    val path: String,
    /** Indentation level: 0 for the root, 1 for its direct children, and so on. */
    val depth: Int,
    /** Workspace root node: cannot be renamed or deleted; items can only be created under it. */
    val isRoot: Boolean,
    /** Whether the directory is expanded; always false for non-directories. */
    val isExpanded: Boolean,
    /** Failed to read this directory's children on expand (e.g. insufficient permissions); a root read failure goes through [FileBrowseState.Error]. */
    val hasError: Boolean = false,
    /** Matched by the workspace root .gitignore: dimmed in orange in the UI (VSCode-like). */
    val ignored: Boolean = false
)

/** File browse clipboard: holds only one item at a time, pending paste after copy or cut. */
@Immutable
data class BrowseClipboard(
    /** Path of the directory containing the source entry. */
    val sourcePath: String,
    /** Display name of the source entry (for UI hints). */
    val sourceName: String,
    /** Whether it is a cut (source is deleted after a successful paste). */
    val isCut: Boolean
)

/** Final result status of a single-turn workflow (used by [AgentUIState.Result]). */
enum class WorkflowStatus {
    SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELLED
}

sealed class AgentUIState {
    object Idle : AgentUIState()
    object Loading : AgentUIState()
    object Streaming : AgentUIState()
    data class Result(val status: WorkflowStatus) : AgentUIState()
    data class Error(val message: String) : AgentUIState()
}

/** File tree read state for the sidebar "Files" tab. Remote mode goes through SFTP/exec, where reads can fail or be slow, hence the three states. */
sealed interface FileBrowseState {
    data object Loading : FileBrowseState
    data class Success(val nodes: List<FileTreeNode>) : FileBrowseState
    data class Error(val detail: String?) : FileBrowseState
}

/** One chat history search hit: session title + message snippet, shown in a sidebar result row. */
@Immutable
data class ChatSearchHit(
    val sessionId: String,
    val sessionTitle: String,
    val messageId: String,
    /** Body snippet centered on the matched term (line-break whitespace collapsed, ellipses added at the ends as needed). */
    val snippet: String,
    val timestamp: Long
)

/** Sidebar chat history search state: current keyword and the flattened hit results (newest first). */
@Immutable
data class ChatSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val hits: List<ChatSearchHit> = emptyList()
)

/**
 * Completion event of a single LLM request (includes tokens consumed by that call).
 */
@Immutable
data class LlmCallEvent(
    val sessionId: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val cachedTokens: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Network request retry state (only used for the live "Retrying (N/M)..." hint in the UI).
 * [error] is a summary of the error that triggered the retry; the UI uses it to show the specific reason (e.g. 429/500/network down).
 * Decoupled from [AgentUIState]: retry is a sub-state of Streaming and does not change the top-level agent state machine.
 */
@Immutable
data class RetryState(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo? = null)

/**
 * Multi-key auto-switch state (only used for the live "Switched to key N of M" hint in the UI).
 * Exists only briefly between the switch and the resend; cleared once content starts flowing again or the turn ends.
 */
@Immutable
data class KeySwitchState(val newIndex: Int, val total: Int)

@Immutable
data class AgentUIMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<AgentAttachment> = emptyList(),
    // TOOL messages only: used for rendering, not part of context replay.
    val toolName: String? = null,
    // TOOL messages only: arguments passed in this call (JSON text), used to render the "executed command".
    val toolArgs: String? = null,
    val isError: Boolean = false,
    // ASSISTANT messages only: the model's reasoning for this turn, rendered as a collapsible "reasoning" bubble; null if absent.
    val reasoning: String? = null,
    // Internal context compaction anchor: not shown as a user bubble, rendered as a compaction divider.
    val isCompactionMarker: Boolean = false,
    // Summary message generated by context compaction: rendered as an expandable compaction summary card, colored to distinguish it from tool calls.
    val isContextSummary: Boolean = false,
    // Context compaction failure record (TOOL message): rendered as an expandable failure card showing the failure reason.
    val isCompactionFailure: Boolean = false,
    // Background task completion notification: part of the model context but not shown as a normal user bubble, rendered as a lightweight notice bar.
    val isBackgroundNotification: Boolean = false,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    // ASSISTANT messages only: tokens in this call's input that hit the server cache; the cache hit rate below the bubble is computed from it.
    val cachedInputTokens: Int = 0
)

@Immutable
@Serializable
data class AgentAttachment(
    val fileName: String,
    val containerPath: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val isImage: Boolean
)

enum class MessageRole {
    USER, ASSISTANT, TOOL
}

/**
 * Fixed prefix for background task completion notifications. It is both
 * the leading text [com.xencode.feature.agent.domain.notification.AgentNotificationFormatter] uses when building the notification message,
 * and what the UI layer uses to recognize this kind of message (not rendered as a normal user bubble). Changes here must be synced on both sides.
 */
const val BACKGROUND_NOTIFICATION_PREFIX = "[system notification - not user input]"

/** Pre-translation prefix persisted in old DB rows; kept for recognition only. */
const val LEGACY_BACKGROUND_NOTIFICATION_PREFIX = "[系统通知 - 非用户输入]"

/**
 * The toolName of the TOOL message recording a context compaction failure. It is both the marker persisted to the DB and what the UI uses to recognize failure cards;
 * the message has no paired toolCallId, so [MessagePersistenceUseCase.buildHistory] drops it during replay and it never enters the model context.
 */
const val COMPACTION_FAILURE_TOOL_NAME = "Context compaction"

/** Pre-translation toolName persisted in old DB rows; kept for recognition only. */
const val LEGACY_COMPACTION_FAILURE_TOOL_NAME = "上下文压缩"

/**
 * Result snapshot of one session message query.
 * - [sessionId]: the session these messages belong to; null means no session has been resolved yet (cold start).
 * - [messages]: filtered list of messages ready to render.
 * - [loaded]: whether this session's data has been read from the database, to distinguish "loading" from "empty session".
 */
@Immutable
data class ChatMessagesState(
    val sessionId: String?,
    val messages: List<AgentUIMessage>,
    val loaded: Boolean,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false
)

/**
 * Code points that lay down "no ink": not whitespace and not format (Cf)/control (Cc), yet rendered zero-width or purely blank.
 * The Hangul filler family is the key case — its Unicode category is Lo (other letter), so **any scheme that judges by category
 * misses them**; they must be listed explicitly. This is the real reason the previous "filter only Cf/Cc" fix failed: some models emit
 * fillers such as U+3164 on pure tool-call turns, leaking an empty bubble on every tool call.
 */
internal val BLANK_GLYPH_CODE_POINTS: Set<Char> = setOf(
    0x115F, 0x1160, 0x3164, 0xFFA0, // Hangul fillers (Lo, zero-width, missed by category checks)
    0x2800,                         // Braille pattern blank (So, purely blank dots)
    0x034F,                         // Combining grapheme joiner CGJ
    0x17B4, 0x17B5,                 // Khmer inherent vowels (zero-width)
    0x2060, 0xFEFF,                 // Word joiner / BOM (Cf, redundant fallback)
    0x200B, 0x200C, 0x200D,         // Zero-width space / ZWNJ / ZWJ (Cf, redundant fallback)
).mapTo(HashSet()) { it.toChar() }

/**
 * Whether the text contains "visible (inked)" content. The check = at least one character that is neither whitespace nor in an invisible category (Cf/Cc/surrogate),
 * nor in the [BLANK_GLYPH_CODE_POINTS] denylist. Much stricter than [CharSequence.isBlank]:
 * the latter only treats whitespace as empty, letting zero-width/filler characters leak blank bubbles.
 * This is the single gate deciding "whether to render an assistant bubble"; persistence normalization and every render/filter layer share it, so changing this one place applies across the whole chain.
 * Note: surrogate pairs (emoji etc.) and ordinary visible characters are kept; the zero-width joiner ZWJ only counts as empty for the "is the whole text empty" question,
 * and is never stripped from displayed text, so emoji ligature sequences are unaffected.
 */
fun CharSequence.hasVisibleContent(): Boolean {
    var i = 0
    while (i < length) {
        val ch = get(i)
        if (!ch.isWhitespace() && ch.category != CharCategory.FORMAT &&
            ch.category != CharCategory.CONTROL && ch !in BLANK_GLYPH_CODE_POINTS
        ) {
            // Surrogates (the supplementary planes where emoji live) count as visible only as a valid paired high/low surrogate; lone or broken surrogates still count as empty
            if (ch.category != CharCategory.SURROGATE) return true
            if (Character.isHighSurrogate(ch) && i + 1 < length && Character.isLowSurrogate(get(i + 1))) return true
        }
        i++
    }
    return false
}

/**
 * Live accumulated output of a running tool. Memory-only, never persisted: used to overlay the tool message bubble in real time with
 * the command's stdout line by line; cleared once the command ends, and the final full result is persisted through the normal path.
 */
@Immutable
data class RunningToolOutput(val messageId: String, val text: String, val toolName: String = "", val toolArgs: String = "")

data class QueuedRequest(
    val id: String,
    val request: String,
    val modelRequest: String = request,
    val currentFile: String?,
    val selectedCode: String?,
    val projectRoot: String,
    val inputImages: List<AgentImage> = emptyList(),
    val inputAttachments: List<AgentAttachment> = emptyList(),
    val isAutoTrigger: Boolean = false
)
