package com.xencode.feature.agent.presentation.component

import android.content.ClipData
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xencode.R
import com.xencode.core.theme.Brand
import com.xencode.core.theme.Spacing
import com.xencode.core.theme.semanticColors
import com.xencode.core.ui.ChevronRotationStyle
import com.xencode.core.ui.ContentWidth
import com.xencode.core.ui.ExpandableChevronIcon
import com.xencode.feature.agent.presentation.AgentUIMessage
import com.xencode.feature.agent.presentation.hasVisibleContent
import com.xencode.feature.agent.presentation.MessageRole
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Database
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.RotateCcw
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Tool card entry duration (ms) and rise offset: dips down slightly then fades into place; draw-layer only, layout untouched. */
private const val MESSAGE_ENTRY_ANIM_MS = 260
private val MESSAGE_ENTRY_RISE = 10.dp

/** Result of splitting one turn: turn start + all assistant messages in the turn (n steps per turn) + the closing one. */
private data class AgentTurn(
    val startMillis: Long,
    val assistantMessages: List<AgentUIMessage>,
    val endMessage: AgentUIMessage
)

/**
 * Token totals within one turn: sum of input/output/cache hits over **every step** of the turn (each LLM call).
 * Attached to the same closing assistant message as [computeTaskDurations]; see [computeTurnUsage].
 */
internal data class TurnUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0
)

/**
 * Splits messages into turns at "user message → up to the next user message".
 *
 * Turn-end check: the first message after it is a user message, or it is the list's last item and the turn has finished
 * ([lastTurnFinished], derived from whether the agent is idle). The still-generating trailing turn is excluded — duration and usage only hold once the turn wraps up.
 *
 * Anchors/summaries inserted by context compaction land inside a turn (compaction happens before the request); if they
 * took part in the split, the turn start would land at the compaction moment, so strip them out first.
 */
private fun splitTurns(messages: List<AgentUIMessage>, lastTurnFinished: Boolean): List<AgentTurn> {
    val turnMessages = messages.filter {
        !it.isCompactionMarker && !it.isContextSummary && !it.isCompactionFailure
    }
    if (turnMessages.isEmpty()) return emptyList()
    val turns = mutableListOf<AgentTurn>()
    var turnStart: Long? = null
    var assistants = mutableListOf<AgentUIMessage>()
    turnMessages.forEachIndexed { index, message ->
        when (message.role) {
            MessageRole.USER -> {
                turnStart = message.timestamp
                assistants = mutableListOf()
            }
            MessageRole.ASSISTANT -> {
                val start = turnStart ?: return@forEachIndexed
                assistants += message
                val isTurnEnd = if (index == turnMessages.lastIndex) {
                    lastTurnFinished
                } else {
                    turnMessages[index + 1].role == MessageRole.USER
                }
                if (isTurnEnd) {
                    turns += AgentTurn(start, assistants.toList(), message)
                    assistants = mutableListOf()
                }
            }
            MessageRole.TOOL -> Unit
        }
    }
    return turns
}

/**
 * Total duration (ms) per turn: the closing assistant message's persisted timestamp − the turn's user message send time, i.e.
 * the wall-clock time from the user hitting send to the AI finishing the turn (including tool execution and waits for user approval). Returns "message id → duration";
 * only the closing assistant message of a turn has an entry.
 */
internal fun computeTaskDurations(
    messages: List<AgentUIMessage>,
    lastTurnFinished: Boolean
): Map<String, Long> = splitTurns(messages, lastTurnFinished)
    .filter { it.endMessage.timestamp > it.startMillis }
    .associate { it.endMessage.id to (it.endMessage.timestamp - it.startMillis) }

/**
 * Token totals per turn: input/output/cache hits summed over **all steps** of the turn; returns "message id → [TurnUsage]",
 * likewise only the closing assistant message of a turn has an entry.
 *
 * The intent is "what the whole turn cost", not "what this step cost": a turn may call the model n times (tool loop),
 * per-step display would shatter one task's cost into fragments, and mid-turn numbers mean nothing to the user.
 */
internal fun computeTurnUsage(
    messages: List<AgentUIMessage>,
    lastTurnFinished: Boolean
): Map<String, TurnUsage> = splitTurns(messages, lastTurnFinished).associate { turn ->
    turn.endMessage.id to TurnUsage(
        inputTokens = turn.assistantMessages.sumOf { it.inputTokens },
        outputTokens = turn.assistantMessages.sumOf { it.outputTokens },
        cachedInputTokens = turn.assistantMessages.sumOf { it.cachedInputTokens }
    )
}

/** Task duration formatting: under 1 minute shows `12s`, under 1 hour shows `2:05`, longer shows `1:02:05`. */
internal fun formatTaskDuration(millis: Long): String {
    val totalSeconds = ((millis + 500) / 1000).coerceAtLeast(1)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val paddedSeconds = seconds.toString().padStart(2, '0')
    return when {
        hours > 0 -> "$hours:${minutes.toString().padStart(2, '0')}:$paddedSeconds"
        minutes > 0 -> "$minutes:$paddedSeconds"
        else -> "${seconds}s"
    }
}

/**
 * Cache hit rate of a single message: cached input / total input (inputTokens includes the cache-hit portion), same basis as the settings page Token stats.
 * Returns null when there is no input stat or no cache hit this time (no placeholder, so "channel reports no cache data" is not misread as a 0% hit rate).
 */
internal fun formatCacheHitRate(inputTokens: Int, cachedInputTokens: Int): String? {
    if (inputTokens <= 0 || cachedInputTokens <= 0) return null
    val rate = (cachedInputTokens * 100.0 / inputTokens).coerceAtMost(100.0)
    return "${rate.roundToInt()}%"
}

@Composable
internal fun AgentMessageItem(
    message: AgentUIMessage,
    /** Whether this is the newest message of the whole session (only it gets "copy / more"). User messages are exempt: each one
     *  permanently carries this button row so users can copy or rewind their own words anytime; assistant messages only get the
     *  newest one, avoiding rows of duplicate buttons dangling under old replies and fragmenting the chat log. Timestamp, usage and duration are informational, not governed by this. */
    showActions: Boolean = false,
    liveOutput: String? = null,
    markdownCache: MarkdownRenderCache? = null,
    onRewindClick: ((String) -> Unit)? = null,
    onMoreClick: ((AgentUIMessage) -> Unit)? = null,
    onToolToggle: (() -> Unit)? = null,
    /** Persisted override for the tool row's expanded state (null = never toggled manually, default by content type); see [ToolMessageBody]. */
    toolExpandedOverride: Boolean? = null,
    /** Reports the new state when the tool row is expanded/collapsed manually; persisted by the layer above. */
    onToolExpandedChange: ((Boolean) -> Unit)? = null,
    /** Outer padding for this item's content: when a tool-call group is expanded, [AIChatPanel] passes in an indent to distinguish hierarchy. */
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** Total duration (ms) of this turn: non-null only on the closing assistant message; see [computeTaskDurations]. */
    taskDurationMs: Long? = null,
    /** Token totals of this turn (summed over n steps in 1 turn): non-null only on the closing assistant message; see [computeTurnUsage]. */
    turnUsage: TurnUsage? = null,
    /** Entry animation delay (ms) for new messages: null means history messages show immediately; non-null fades in after the delay on first composition. */
    entryDelayMs: Long? = null,
    /** Chunked rendering of long messages: when non-null, the body MarkdownContent renders only this slice.
     *  Chunks join seamlessly (first chunk carries reasoning, last carries the action row and bottom corners); the copy button still copies the whole message.content. */
    contentSlice: String? = null,
    /** Whether this is the first chunk (renders the reasoning block and top corners); always true for non-chunked messages. */
    isChunkHeader: Boolean = true,
    /** Whether to render this message's own reasoning block. Defaults to true; pass false when whole-turn collapsing pulls reasoning out as a standalone process item, to avoid duplication. */
    reasoningVisible: Boolean = true,
    /** Reasoning duration (ms): actual thinking time recorded by the layer above. */
    reasoningDurationMs: Long? = null,
    /** Whether this is the last chunk (renders the action row, bottom corners, and spacing to the next list item); always true for non-chunked messages. */
    isChunkFooter: Boolean = true,
) {
    if (message.isCompactionMarker) {
        // Internal compaction anchors no longer render a divider: the summary card already gives compaction feedback, so this would duplicate it.
        return
    }

    if (message.isContextSummary) {
        CompactionSummaryCard(message, markdownCache)
        return
    }

    if (message.isCompactionFailure) {
        CompactionFailureCard(message)
        return
    }

    if (message.isBackgroundNotification) {
        BackgroundNotificationBar(message)
        return
    }

    val hasReasoning = message.role == MessageRole.ASSISTANT && !message.reasoning.isNullOrEmpty()
    val hasContent = message.content.hasVisibleContent()
    val hasAttachments = message.attachments.isNotEmpty()
    // Images emitted directly by the model are persisted as attachments; image-only messages have no body or reasoning yet still need displaying (no early return).
    if (message.role == MessageRole.ASSISTANT && !hasContent && !hasReasoning && !hasAttachments) return

    val isUser = message.role == MessageRole.USER
    // Chunked messages: one paragraph gap between chunks keeps the body visually continuous (no outline seams under the DSH flat document flow).
    val chunked = contentSlice != null
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // The user bubble grows with its text, capped at the AI bubble width (message column width - list side padding).
    // On large screens the message column is already width-limited and centered, so the bubble cap narrows accordingly; full screen width no longer applies.
    val maxUserBubbleWidth = remember(screenWidthDp) {
        minOf(screenWidthDp.dp, ContentWidth.readable) - Spacing.lg * 2
    }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()

    // Tool card entry: plays only for messages newly appended during this browsing session (entryDelayMs non-null);
    // history, returning from page switches, and recycled item re-mounts all display directly (who enters is decided by MessageEntryScheduler).
    // Entry only changes alpha and translationY (effective in the draw phase); card height is final from the insertion frame:
    // with AnimatedVisibility, not-yet-entered cards take no space at all, so each entry jumps the list height once,
    // which stacked with the bottom-following scrollToItem becomes a run of jitters — exactly what this animation is meant to remove.
    // Now the list height changes only once when a message is inserted, and the animation never touches layout afterwards.
    var entered by rememberSaveable(message.id) { mutableStateOf(entryDelayMs == null) }
    LaunchedEffect(message.id) {
        if (entered) return@LaunchedEffect
        val delayMs = entryDelayMs ?: return@LaunchedEffect
        if (delayMs > 0) delay(delayMs)
        entered = true
    }
    val entryProgress by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = MESSAGE_ENTRY_ANIM_MS, easing = LinearOutSlowInEasing),
        label = "tool-entry"
    )

    // Over-long assistant messages are split by AIChatPanel into multiple bounded items (chunks); no more height-capped internal scrolling here;
    // each chunk acts as one segment of a normal message: body rendered per chunk, reasoning only in the first, action row only in the last.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Hierarchy indent for group members (see the contentPadding comment): applied at the outermost layer, so the card's own hairline and
            // the "command / result" panels indent along with it — no group-header-flush-with-member-rows look.
            .padding(contentPadding)
            // No more uniform spacedBy on the LazyColumn: the last chunk (or a non-chunked message) carries its own spacing to the next item,
            // and adjacent chunks join seamlessly with zero gap, so a long reply still reads as one continuous block.
            // Under the flat document flow there is no bubble border fallback between bodies; compact spacing keeps turns distinct.
            .padding(bottom = if (isChunkFooter) Spacing.sm else 0.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (hasReasoning && isChunkHeader && reasoningVisible) {
            // Reasoning is collapsed by default: the collapsed row takes a single line (the first line of reasoning); tap to read the full text
            ReasoningBubble(
                text = message.reasoning.orEmpty(),
                cache = markdownCache,
                durationMs = reasoningDurationMs
            )
        }
        if (hasContent || hasAttachments || message.role != MessageRole.ASSISTANT) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                // Assistant messages align left, user messages align right
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
            ) {
                if (hasContent || message.role == MessageRole.TOOL) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.role == MessageRole.TOOL) {
                            // The tool row carries its own 1px hairline and status icon; this spot only handles the entry animation (draw layer only, layout untouched)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        alpha = entryProgress
                                        translationY = (1f - entryProgress) * MESSAGE_ENTRY_RISE.toPx()
                                    }
                            ) {
                                ToolMessageBody(
                                    message = message,
                                    liveOutput = liveOutput,
                                    expandedOverride = toolExpandedOverride,
                                    onExpandedChange = onToolExpandedChange,
                                    onToggle = onToolToggle
                                )
                            }
                        } else if (isUser) {
                            // User messages: right-aligned light pill + dark text (no longer a solid theme-color block with inverted text).
                            Surface(
                                shape = ChatStyle.bubbleShape,
                                color = chatUserBubbleColor(),
                                modifier = Modifier.widthIn(max = maxUserBubbleWidth)
                            ) {
                                SelectionContainer {
                                    CompositionLocalProvider(
                                        LocalTextSelectionColors provides TextSelectionColors(
                                            handleColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            backgroundColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.24f),
                                        )
                                    ) {
                                        Text(
                                            text = message.content,
                                            color = chatUserBubbleTextColor(),
                                            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
                                        )
                                    }
                                }
                            }
                        } else {
                            // Assistant body: no container, laid directly on the page background (document flow). A single paragraph gap between chunks
                            // keeps the whole message reading as one continuous body of text.
                            SelectionContainer {
                                CompositionLocalProvider(
                                    LocalTextSelectionColors provides TextSelectionColors(
                                        handleColor = MaterialTheme.colorScheme.primary,
                                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                    )
                                ) {
                                    MarkdownContent(
                                        text = contentSlice ?: message.content,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth(),
                                        cache = markdownCache,
                                    )
                                }
                            }
                        }
                    }
                }
                if ((isUser || message.role == MessageRole.ASSISTANT) && hasAttachments) {
                    MessageAttachmentList(attachments = message.attachments)
                }
                // Meta row under the bubble (not shown for tool messages): user messages permanently carry "time + copy/rewind/more",
                // assistant messages carry it only on the session's newest one, avoiding a row of buttons dangling under every reply and fragmenting the chat log.
                // The layout is fixed as "stats (usage/cache/duration) → copy → more options": information first, action entries tucked at the end of the row.
                //
                // Usage and duration are attached as **whole-turn totals** (summed over all calls of the n steps) to the closing assistant message:
                // per-step display would shatter a task's cost into fragments, and mid-turn numbers mean nothing to the user.
                val tokenStats = turnUsage
                    ?.takeIf { it.inputTokens > 0 || it.outputTokens > 0 }
                    ?.let {
                        val inStr = formatTokenCount(it.inputTokens.toLong())
                        val outStr = formatTokenCount(it.outputTokens.toLong())
                        "↑$inStr ↓$outStr"
                    }
                val cacheHitRate = turnUsage?.let {
                    formatCacheHitRate(it.inputTokens, it.cachedInputTokens)
                }
                val durationText = taskDurationMs?.let { formatTaskDuration(it) }
                val hasMeta = isUser || tokenStats != null || cacheHitRate != null || durationText != null
                val actionsVisible = isUser || showActions
                if ((hasContent || hasAttachments) && message.role != MessageRole.TOOL && isChunkFooter &&
                    (actionsVisible || hasMeta)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                        // Only add spacing after something has been emitted; otherwise this row's first element gets spuriously indented
                        var emitted = false
                        // User messages: the timestamp comes before the copy button (matching "17:55 ⧉" under the bubble in the reference design)
                        if (isUser) {
                            ChatMetaText(text = formatClockTime(message.timestamp))
                            emitted = true
                        }
                        val copyButton: @Composable () -> Unit = {
                            MessageActionIconButton(
                                icon = if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                                contentDescription = if (copied) stringResource(R.string.chat_copied) else stringResource(R.string.chat_copy),
                                tint = iconTint,
                                onClick = {
                                    copyScope.launch {
                                        clipboard.setClipEntry(
                                            ClipEntry(ClipData.newPlainText("message", message.content))
                                        )
                                        copied = true
                                    }
                                }
                            )
                        }
                        // User messages: copy follows the timestamp immediately, rewind comes after (original order preserved)
                        if (isUser && actionsVisible) {
                            if (emitted) Spacer(Modifier.width(Spacing.xs))
                            if (hasContent) copyButton()
                            if (onRewindClick != null) {
                                MessageActionIconButton(
                                    icon = FeatherIcons.RotateCcw,
                                    contentDescription = stringResource(R.string.checkpoint_rewind_title),
                                    tint = iconTint,
                                    onClick = { onRewindClick(message.id) }
                                )
                            }
                            emitted = true
                        }
                        if (tokenStats != null) {
                            if (emitted) Spacer(Modifier.width(Spacing.sm))
                            ChatMetaText(text = tokenStats)
                            emitted = true
                        }
                        if (cacheHitRate != null) {
                            if (emitted) Spacer(Modifier.width(Spacing.sm))
                            Icon(
                                FeatherIcons.Database,
                                contentDescription = stringResource(R.string.chat_cache_hit_rate, cacheHitRate),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            ChatMetaText(text = cacheHitRate)
                            emitted = true
                        }
                        if (durationText != null) {
                            if (emitted) Spacer(Modifier.width(Spacing.sm))
                            Icon(
                                FeatherIcons.Clock,
                                contentDescription = stringResource(R.string.chat_task_duration, durationText),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            ChatMetaText(text = durationText)
                            emitted = true
                        }
                        // Assistant messages: the copy button sits to the left of "more options" (after the stats)
                        if (!isUser && actionsVisible && hasContent) {
                            if (emitted) Spacer(Modifier.width(Spacing.xs))
                            copyButton()
                            emitted = true
                        }
                        // "More options" goes **last** in this row: on assistant messages it follows the copy button (information first,
                        // then action entries); user messages have no stats, so it naturally follows the rewind button with the same spacing as the button group.
                        if (actionsVisible && onMoreClick != null) {
                            if (emitted) Spacer(Modifier.width(Spacing.xs))
                            MessageActionIconButton(
                                icon = FeatherIcons.MoreHorizontal,
                                contentDescription = stringResource(R.string.chat_more_options),
                                tint = iconTint,
                                onClick = { onMoreClick(message) }
                            )
                        }
                    }
                    // Restore the icon 1.5s after a successful copy
                    if (copied) {
                        LaunchedEffect(copied) {
                            delay(1500)
                            copied = false
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whole-turn collapse header (outermost layer): one "running / completed in Xs + chevron" row; a single tap expands/collapses the whole turn.
 *
 * Collapsed uses a right chevron (ChevronRight), expanded a down chevron (ChevronDown) — matching the reference design;
 * while running ([running]) it only shows "running", and shows the turn duration once finished. Styled to match [ToolCallGroupHeader].
 */
@Composable
internal fun TurnCollapseHeader(
    running: Boolean,
    durationMs: Long?,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight)
            .clip(ChatStyle.panelShape)
            .clickable(
                onClickLabel = stringResource(
                    if (expanded) R.string.common_collapse_action else R.string.common_expand
                ),
                onClick = onToggle
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (running) {
                stringResource(R.string.chat_turn_running)
            } else {
                stringResource(R.string.chat_turn_completed, durationMs?.let { formatTaskDuration(it) } ?: "")
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(Spacing.xs))
        ExpandableChevronIcon(
            expanded = expanded,
            style = ChevronRotationStyle.RIGHT_DOWN,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            size = 18.dp
        )
    }
}

@Composable
private fun MessageActionIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(24.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(14.dp))
    }
}

/**
 * Lightweight notice bar for background-task completion notifications: not shown as a normal user bubble, just a compact strip
 * telling the user which background command finished and whether it succeeded. Extracts <status>/<summary> fields from the notification text.
 */
@Composable
private fun BackgroundNotificationBar(message: AgentUIMessage) {
    val content = message.content
    val statuses = Regex("<status>(.*?)</status>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim()?.lowercase() }.filterNotNull().toList()
    val summaries = Regex("<summary>(.*?)</summary>")
        .findAll(content).map { it.groupValues.getOrNull(1)?.trim() }.filterNotNull().toList()
    val dotColor = when {
        // A status of "message" means an inter-agent message (not a failure), so use the primary color; any other non-completed status counts as a failure.
        statuses.any { it != "completed" && it != "message" } -> MaterialTheme.colorScheme.error
        statuses.any { it == "message" } -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.semanticColors.success
    }
    val label = when {
        summaries.size <= 1 -> summaries.firstOrNull() ?: stringResource(R.string.chat_bg_command_done)
        else -> {
            // The raw summary is generated by the domain layer (the same text is also fed to the AI); don't slice task names out by Chinese markers here —
            // rewording the copy or switching the UI to English would break that parsing. For multiple items, just report the count and failures.
            val failedCount = statuses.count { it != "completed" }
            if (failedCount > 0) {
                stringResource(R.string.chat_bg_commands_partial_failed, summaries.size, failedCount)
            } else {
                stringResource(R.string.chat_bg_commands_done, summaries.size)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ChatHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChatStyle.toolRowMinHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Context compaction success row: collapsed by default to "dot + context compressed + chevron"; tap to expand the full summary.
 * Flat row + muted panel, isomorphic to the tool row; only the dot color distinguishes the event type.
 */
@Composable
private fun CompactionSummaryCard(message: AgentUIMessage, markdownCache: MarkdownRenderCache?) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ChatHairline()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChatStyle.panelShape)
                .background(chatMutedSurfaceColor())
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_context_compressed),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                ExpandableChevronIcon(
                    expanded = expanded,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 16.dp
                )
            }
            if (expanded && message.content.hasVisibleContent()) {
                Spacer(Modifier.height(Spacing.xs))
                MarkdownContent(
                    text = message.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                    cache = markdownCache
                )
            }
        }
    }
}

/**
 * Context compaction failure row: collapsed by default to "dot + compaction failed + first line of the reason + chevron"; tap to expand the full reason.
 * Flat row isomorphic to the tool row, distinguished by an error-colored dot.
 */
@Composable
private fun CompactionFailureCard(message: AgentUIMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    val reason = message.content.ifBlank { stringResource(R.string.chat_compaction_failed) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        ChatHairline()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ChatStyle.panelShape)
                .background(chatMutedSurfaceColor())
                .clickable { expanded = !expanded }
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_compaction_failed),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                if (!expanded) {
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = reason.replace("\n", " ").trim(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                ExpandableChevronIcon(
                    expanded = expanded,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 16.dp
                )
            }
            if (expanded && reason.isNotBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                ChatMonoPanel {
                    Text(
                        text = reason,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
            }
        }
    }
}
