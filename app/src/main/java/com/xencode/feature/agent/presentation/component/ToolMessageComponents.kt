package com.xencode.feature.agent.presentation.component

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.xencode.core.theme.Brand
import com.xencode.core.theme.Radius
import com.xencode.core.theme.Spacing
import com.xencode.core.theme.semanticColors
import com.xencode.core.ui.ImageSource
import com.xencode.core.ui.ImageViewerRequest
import com.xencode.core.ui.LocalImageViewer
import com.xencode.feature.agent.domain.session.SessionUseCase
import com.xencode.feature.agent.presentation.AgentUIMessage
import com.xencode.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Database
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Search
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.xencode.R

internal val DiffAddBg: Color
    @Composable get() = MaterialTheme.semanticColors.diffAddBg

internal val DiffAddText: Color
    @Composable get() = MaterialTheme.semanticColors.diffAdd

internal val DiffRemoveBg: Color
    @Composable get() = MaterialTheme.semanticColors.diffRemoveBg

internal val DiffRemoveText: Color
    @Composable get() = MaterialTheme.semanticColors.diffRemove

internal const val DIFF_COLLAPSE_THRESHOLD = 20
internal const val TOOL_SECTION_LINE_LIMIT = 20

/** Character cap for rendering static (non-live) tool output: the 240dp viewport cap already makes over-long output unreadable, yet
 *  Text measures layout over the full text (O number of chars) — a card with 50KB of output can take 10ms+ for a single layout pass as it scrolls into view,
 *  a direct source of composition spikes/dropped frames while flinging through chat history. Over-cap content is truncated for display; the full content lives in the raw record. */
private const val TOOL_SECTION_CHAR_LIMIT = 16_000

/** Max height of the tool "instruction / result" expanded window: beyond it, scroll inside the window instead of growing unbounded. */
private val ToolSectionMaxHeight = 240.dp

/** Height of the bottom fade mask in the tool window (shown only while more content can scroll down). */
private val ToolSectionFadeHeight = 24.dp

/**
 * Tool message (DSH flat row): one row of "tool icon + tool name + path + add/remove stats + chevron" with a
 * 1px hairline above it as a separator, no outlined card; tap to expand the "instruction / result", or for editFile / writeFile
 * expand into a white outlined diff card (header path + copy, footer add/remove stats).
 *
 * Status is no longer color-only: the icon pulses while running, a failure additionally hangs a warning icon, and screen readers get separate status semantics.
 * A non-null [liveOutput] enters "live output" mode: shows output accumulating line by line.
 *
 * The expanded state is controlled via [expandedOverride] + [onExpandedChange] (persisted by the host, see
 * [com.xencode.feature.agent.presentation.AIAgentViewModel.toolExpansionOverrides]):
 * it used to live here in `remember(message.id)`, which snapped back to the default once the item scrolled out of view and was recycled by the LazyColumn, or a fullscreen route
 * disposed the whole chat composition. null means the user hasn't toggled it manually — then it's **collapsed by default**,
 * all tools (diff cards and todo cards included) treated alike; details are one tap away.
 */
@Composable
internal fun ToolMessageBody(
    message: AgentUIMessage,
    liveOutput: String? = null,
    expandedOverride: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onToggle: (() -> Unit)? = null
) {
    val streaming = liveOutput != null
    val running = message.isToolRunning(liveOutput)
    val edit = if (!running && !message.isError &&
        (message.toolName == "editFile" || message.toolName == "writeFile")
    ) {
        remember(message.id, message.content) { parseEditDiff(message.content) }
    } else null

    val resultText = if (!running) {
        remember(message.id, message.content) { formatToolResult(message.content) }
    } else null
    val argHint = remember(message.toolArgs) { toolArgHint(message.toolArgs) }
    val argsFull = remember(message.toolArgs) { formatToolArgs(message.toolArgs) }

    val todoData = if (message.toolName == "todo" && !running && !message.isError) {
        remember(message.id, message.content) { parseTodoResult(message.content) }
    } else null
    val webSearchData = if (message.toolName == "websearch" && !running && !message.isError) {
        remember(message.id, message.content) { parseWebSearchResult(message.content) }
    } else null
    // Background task/sub-agent completion notifications: piggybacked on this tool result for the AI, and always shown to the user too.
    val notifications = if (!running) {
        remember(message.id, message.content) { parseToolNotifications(message.content) }
    } else emptyList()
    // renderLayout result: a slim preview row at the bottom of the expanded card opens the PNG full screen.
    val renderData = if (message.toolName == "renderLayout" && !running && !message.isError) {
        remember(message.id, message.content) { parseRenderLayoutResult(message.content) }
    } else null

    // Collapsible/expandable even while running (e.g. collapse a spammy bash run to just the header row), regardless of current output; with no output the collapsed state has no content but stays clickable, consistent with the chevron
    val hasLiveOutput = !liveOutput.isNullOrBlank()
    val expandable = streaming || (!running && (edit != null || !resultText.isNullOrBlank() || !argsFull.isNullOrBlank()
            || (todoData != null && todoData.items.isNotEmpty()) || webSearchData != null || renderData != null))
    // Tool calls are collapsed by default (diff cards and todo cards included): the user taps to see details;
    // once toggled manually (expandedOverride non-null), the user's choice wins. The expanded state is kept by the host,
    // so scrolling out of view or switching pages and back no longer loses it.
    val effectiveExpanded = expandedOverride == true

    val toolLabel = message.toolName ?: stringResource(R.string.common_tool)
    // File-related tools: take the path from the structured diff or the tool args, displayed uniformly as "tool name + path + file name"
    val filePath = if (edit != null) {
        edit.path
    } else {
        remember(message.toolArgs) { extractFilePathArg(message.toolArgs) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChatStyle.toolRowMinHeight)
                .then(
                    if (expandable) Modifier.clickable {
                        onExpandedChange?.invoke(!effectiveExpanded)
                        onToggle?.invoke()
                    } else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolStatusIcon(running = running, isError = message.isError, toolName = message.toolName)
            Spacer(Modifier.width(Spacing.sm))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!filePath.isNullOrBlank()) {
                    Text(
                        text = toolLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    // Path segment (omittable) + file name segment (always complete, top priority): monospace, matching the code card header
                    val monoLabel = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                    val pathDir = filePath.substringBeforeLast('/')
                    if (pathDir.isNotEmpty()) {
                        Text(
                            text = pathDir + "/",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = monoLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Text(
                        text = filePath.substringAfterLast('/'),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = monoLabel,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = toolLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!argHint.isNullOrBlank()) {
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = argHint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
            if (running) {
                // The running one: the row's tail uses the unified ripple scene label to say "what it's doing". The tool name arrives
                // before execution starts (AgentEvent.ToolCallStarted), so there's no need to settle for a generic "running";
                // it's visible even collapsed — no need to expand to know it's working.
                Spacer(Modifier.width(Spacing.sm))
                RippleText(text = stringResource(toolRunningLabelRes(message.toolName)))
            }
            if (edit != null) {
                DiffStat(added = edit.added, removed = edit.removed)
                Spacer(Modifier.width(Spacing.sm))
            }
            if (todoData != null && todoData.total > 0) {
                Text(
                    text = "${todoData.completed}/${todoData.total}",
                    color = if (todoData.completed == todoData.total) DiffAddText
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            if (expandable) {
                ExpandableChevronIcon(
                    expanded = effectiveExpanded,
                    contentDescription = if (effectiveExpanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 18.dp
                )
            }
        }
        if (streaming) {
            // Expanded state mirrors the persisted card: first "instruction" (tool args), then "result" (tail of the live output);
            // collapsed keeps only the header row. "Still running" is conveyed by the ripple scene label at the header row's tail, not repeated here.
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column {
                    if (!argsFull.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_instruction), content = argsFull)
                    }
                    if (hasLiveOutput) {
                        val truncated = remember(liveOutput) { liveOutput.takeLastLines(TOOL_SECTION_LINE_LIMIT) }
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_result), content = truncated, live = true)
                    }
                }
            }
        } else {
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.pointerInput(message.id) {
                        detectDoubleTapToCollapse {
                            onExpandedChange?.invoke(false)
                            onToggle?.invoke()
                        }
                    }
                ) {
                if (todoData != null && todoData.items.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    TodoCard(items = todoData.items)
                } else if (webSearchData != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    WebSearchResultCard(result = webSearchData)
                } else if (edit != null) {
                    // Diff card: header carries the path and "copy", footer carries add/remove stats (that white outlined DSH card)
                    Spacer(Modifier.height(Spacing.xs))
                    ChatCodeCard(
                        title = edit.path.ifBlank { null },
                        copyText = edit.hunks.joinToString("\n") { it.diff },
                        footer = stringResource(
                            R.string.tool_changed_files_summary,
                            edit.added,
                            edit.removed,
                            if (edit.path.isBlank()) 0 else 1
                        )
                    ) {
                        Column {
                            edit.hunks.forEach { h -> DiffView(diff = h.diff, startLine = h.startLine) }
                            Spacer(Modifier.height(Spacing.xs))
                        }
                    }
                } else {
                    if (!argsFull.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_instruction), content = argsFull)
                    }
                    if (!resultText.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_result), content = resultText)
                    }
                    renderData?.let { data ->
                        Spacer(Modifier.height(Spacing.xs))
                        RenderLayoutResultRow(data)
                    }
                }
                }
            }
        }
        if (notifications.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            notifications.forEach { ToolNotificationRow(it) }
        }
        // File list: after the tool finishes, always shown at the bottom of the message, one file per row, tap to open with a system app.
        if (!running && message.attachments.isNotEmpty()) {
            val opener = LocalAttachmentOpener.current
            MessageAttachmentList(
                attachments = message.attachments,
                onClick = { opener.open(it) }
            )
        }
    }
}

internal data class ToolNotificationInfo(val summary: String, val succeeded: Boolean, val isMessage: Boolean = false)

/**
 * Extracts piggybacked notifications (background task/sub-agent completions) from the top-level `notifications` field of a tool result's transport JSON.
 * The field is injected by [com.xencode.feature.agent.domain.workflow.StatefulAgentWorkflow] while the AI is busy.
 */
internal fun parseToolNotifications(raw: String): List<ToolNotificationInfo> {
    val array = parseToolTransport(raw.withoutToolStatusPrefix())?.get("notifications") as? JsonArray
        ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val summary = (obj["summary"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
        ToolNotificationInfo(
            summary = summary,
            succeeded = status == "completed",
            isMessage = status == "message"
        )
    }
}

/** Piggybacked notification strip at the bottom of a tool card: status dot + summary. */
@Composable
private fun ToolNotificationRow(info: ToolNotificationInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when {
                        info.isMessage -> MaterialTheme.colorScheme.primary
                        info.succeeded -> MaterialTheme.semanticColors.success
                        else -> MaterialTheme.colorScheme.error
                    }
                )
        )
        Text(
            text = info.summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal data class RenderLayoutResultInfo(
    val name: String,
    val containerPath: String,
    val status: String
)

/**
 * Extracts render preview PNG metadata from a renderLayout transport JSON result. Returns
 * null for records without a valid render payload (older sessions, failed renders): the
 * tool card then keeps its plain shape without a preview row.
 */
internal fun parseRenderLayoutResult(raw: String): RenderLayoutResultInfo? {
    val json = parseToolTransport(raw.withoutToolStatusPrefix()) ?: return null
    val render = json["render"] as? JsonObject ?: return null
    val name = (render["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return null
    val containerPath = (render["container_path"] as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() } ?: return null
    val status = (json["status"] as? JsonPrimitive)?.contentOrNull
    return RenderLayoutResultInfo(
        name = name,
        containerPath = containerPath,
        status = if (status == "success") "success" else "approximate"
    )
}

/**
 * Slim preview row at the bottom of an expanded renderLayout card: small rounded icon chip
 * + file name + hint, deliberately flatter than the sendFile attachment rows. Tapping feeds
 * the rendered PNG to the built-in full-screen image viewer (same gesture as image
 * attachments), so the user sees exactly what the AI rendered.
 */
@Composable
private fun RenderLayoutResultRow(data: RenderLayoutResultInfo) {
    val viewer = LocalImageViewer.current
    Surface(
        shape = RoundedCornerShape(Radius.sm),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                viewer.show(
                    ImageViewerRequest(
                        sources = listOf(ImageSource.ContainerPath(data.containerPath)),
                        title = data.name
                    )
                )
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(Radius.xs))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FeatherIcons.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = data.name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        if (data.status != "success") {
                            append(stringResource(R.string.tool_render_approximate))
                            append(" · ")
                        }
                        append(stringResource(R.string.tool_render_row_hint))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = Brand.IconGray,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private fun String.takeLastLines(maxLines: Int): String {
    if (maxLines <= 0 || isEmpty()) return ""
    var seen = 0
    for (i in lastIndex downTo 0) {
        if (this[i] == '\n' && ++seen == maxLines) {
            return substring(i + 1)
        }
    }
    return this
}

/**
 * Double-tap-to-collapse detection: detectTapGestures' awaitFirstDown defaults to requireUnconsumed=true,
 * but the expanded content wrapped in SelectionContainer / clickable consumes the down, so the first tap gets ignored and
 * the double tap never fires. Here the first tap doesn't require unconsumed; it only checks for "two quick taps in a row".
 */
private suspend fun PointerInputScope.detectDoubleTapToCollapse(onDoubleTap: () -> Unit) {
    val viewConfig = viewConfiguration
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        // If a noticeable movement happens between the two taps (the user is swiping), give up on the double-tap detection
        if (!awaitTapOrSwipe(firstDown.id, firstDown.position, viewConfig.touchSlop)) return@awaitEachGesture
        val secondDown = withTimeoutOrNull(viewConfig.doubleTapTimeoutMillis) {
            awaitFirstDown(requireUnconsumed = false)
        } ?: return@awaitEachGesture
        if (secondDown.uptimeMillis - firstDown.uptimeMillis < viewConfig.doubleTapMinTimeMillis) {
            return@awaitEachGesture
        }
        if ((secondDown.position - firstDown.position).getDistance() > viewConfig.touchSlop) {
            return@awaitEachGesture
        }
        if (!awaitTapOrSwipe(secondDown.id, secondDown.position, viewConfig.touchSlop)) return@awaitEachGesture
        onDoubleTap()
    }
}

/** Waits for the given pointer to lift; returns false if movement exceeds slop (a swipe) meanwhile. */
private suspend fun AwaitPointerEventScope.awaitTapOrSwipe(
    pointerId: PointerId,
    downPosition: Offset,
    slop: Float
): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
        if ((change.position - downPosition).getDistance() > slop) return false
        if (!change.pressed) return true
    }
}

/**
 * Tool status icon: picks a glyph by tool type (file / terminal / search / task…), colored by state —
 * pulsing while running, the whole icon turning red on failure, back to neutral on success (a screen full of green steals attention).
 *
 * Failures no longer hang an extra warning icon: the leading icon slot keeps a single glyph, with state carried by color and screen-reader semantics alone,
 * avoiding two icons on one row and the leading position jumping with success/failure. Color-blind users rely on [statusLabel] (screen reader) to tell outcomes apart.
 */
@Composable
internal fun ToolStatusIcon(running: Boolean, isError: Boolean, toolName: String?) {
    val tint = when {
        isError -> DiffRemoveText
        running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val pulseAlpha = if (running) {
        val transition = rememberInfiniteTransition(label = "tool-status-pulse")
        transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(animation = tween(650), repeatMode = RepeatMode.Reverse),
            label = "tool-status-pulse-alpha"
        ).value
    } else {
        1f
    }
    val statusLabel = stringResource(
        when {
            running -> R.string.chat_tool_running
            isError -> R.string.chat_tool_failed
            else -> R.string.chat_tool_succeeded
        }
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = toolIcon(toolName),
            contentDescription = statusLabel,
            tint = tint,
            modifier = Modifier
                // Same cell width as the reasoning row: only then do both rows' icon centers align with the left edge of the following text
                .size(ChatStyle.rowIconSize)
                .graphicsLayer { alpha = pulseAlpha }
        )
    }
}

/** Tool name to glyph mapping: keys match the names registered in [com.xencode.feature.agent.domain.tool.ToolRegistry] (case-insensitive comparison). */
private fun toolIcon(toolName: String?): ImageVector = when (toolName?.lowercase()) {
    "editfile", "writefile", "generateimage" -> FeatherIcons.Edit3
    "readfile", "list", "sendfile", "viewimage" -> FeatherIcons.FileText
    "search", "websearch", "webfetch", "browser" -> FeatherIcons.Search
    "bash", "terminal" -> FeatherIcons.Terminal
    "todo" -> FeatherIcons.Check
    "task" -> FeatherIcons.Cpu
    "memory" -> FeatherIcons.Database
    else -> FeatherIcons.Tool
}

/**
 * Scene labels for tools in execution: bucket tool names into an at-a-glance "what it's doing" for the unified busy indicator
 * ([AgentBusyIndicator] / [RippleText]).
 *
 * Keys match [toolIcon] (tool registry names, case-insensitive). Bucketing follows the "user's view", not the "implementation view":
 * reading a file and listing a directory both count as "reading files"; web search and fetching a page both count as "searching the web". The tool name arrives
 * before execution starts (`AgentEvent.ToolCallStarted`), so the label tracks the actual action instead of stalling at "thinking".
 * MCP dynamically registered tools, custom tools and other unbucketed ones all fall back to [R.string.chat_status_calling_tool].
 */
@StringRes
internal fun toolRunningLabelRes(toolName: String?): Int = when (toolName?.lowercase()) {
    "editfile", "writefile" -> R.string.chat_status_editing_file
    "readfile", "list", "sendfile", "viewimage" -> R.string.chat_status_reading_file
    "search", "websearch", "webfetch", "browser" -> R.string.chat_status_searching_web
    "bash", "terminal" -> R.string.chat_status_running_command
    "generateimage" -> R.string.chat_status_generating_image
    "todo" -> R.string.chat_status_updating_todo
    "task" -> R.string.chat_status_starting_subagent
    "memory" -> R.string.chat_status_reading_memory
    "renderlayout" -> R.string.chat_status_rendering_layout
    else -> R.string.chat_status_calling_tool
}

/**
 * Whether the tool is still executing: mid live output, or the result is still a pending placeholder.
 * [AIChatPanel] reuses this when deciding whether a tool-call group must stay expanded, avoiding drift between two checks.
 */
internal fun AgentUIMessage.isToolRunning(liveOutput: String?): Boolean =
    liveOutput != null ||
        content.startsWith(SessionUseCase.PENDING_TOOL_MARKER) ||
        content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)

/**
 * "N tool calls" group header: DSH folds consecutive tool calls within one turn into a single row, **collapsed by default**;
 * one tap expands/collapses manually (it never pops open on its own while running).
 * Layout mirrors the reasoning row: tool icon on the left (hammer/construction) + call count in the middle + collapse chevron.
 *
 * When [running] is true, the "N tool calls" label itself gets the ripple highlight, indicating this batch of calls is still going.
 * No extra bouncing dots, and no scene label repeated here (things like "editing a file" describe **which specific tool**,
 * given per tool row by its running state once expanded; see [ToolMessageBody]).
 */
@Composable
internal fun ToolCallGroupHeader(
    count: Int,
    running: Boolean,
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
                // Accessibility: screen readers can announce whether tapping this row expands or collapses (the expanded state itself is conveyed by the count text)
                onClickLabel = stringResource(
                    if (expanded) R.string.common_collapse_action else R.string.common_expand
                ),
                onClick = onToggle
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Construction,
            contentDescription = null,
            tint = Brand.IconGray,
            modifier = Modifier.size(ChatStyle.rowIconSize)
        )
        Spacer(Modifier.width(Spacing.sm))
        // The weight sits on the outer spacer while the ripple draws at the text's own size: the band sweeps only the "N tool calls" words,
        // not slowly crawling across the row's blank space (see Modifier.rippleHighlight).
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            RippleText(
                text = stringResource(R.string.chat_tool_calls_count, count),
                highlight = if (running) Color.White else null
            )
        }
        ExpandableChevronIcon(
            expanded = expanded,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            size = 18.dp
        )
    }
}

/** A small titled content block in the expanded area (e.g. "instruction", "result"): muted monospace panel, scrolling inside the window beyond the height cap. */
@Composable
internal fun ToolSection(label: String, content: String, live: Boolean = false) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(2.dp))

    val scrollState = rememberScrollState()
    var followLive by remember { mutableStateOf(true) }
    var displayedContent by remember { mutableStateOf(content) }
    LaunchedEffect(scrollState, live) {
        if (!live) return@LaunchedEffect
        scrollState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followLive = false
        }
    }
    LaunchedEffect(scrollState, live) {
        if (!live) return@LaunchedEffect
        snapshotFlow { scrollState.isScrollInProgress to scrollState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (!scrolling && !canScrollForward) followLive = true
            }
    }
    LaunchedEffect(content, live, followLive, scrollState.isScrollInProgress) {
        if (!live || (followLive && !scrollState.isScrollInProgress)) displayedContent = content
    }
    val fadeColor = MaterialTheme.colorScheme.background
    // Static over-long output is truncated (see TOOL_SECTION_CHAR_LIMIT); live is already controlled by the caller's takeLastLines.
    val renderContent = remember(content, live) {
        if (!live && content.length > TOOL_SECTION_CHAR_LIMIT) {
            content.take(TOOL_SECTION_CHAR_LIMIT) + "\n…(output too long, truncated)"
        } else {
            content
        }
    }
    Box {
        ChatMonoPanel(
            modifier = Modifier
                .heightIn(max = ToolSectionMaxHeight)
                .nestedScroll(rememberBoundNestedScrollConnection(scrollState))
                .verticalScroll(scrollState)
        ) {
            SelectionContainer {
                Text(
                    text = if (live) displayedContent else renderContent,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
        // Bottom fade: only cover a layer while more content can scroll down, hinting "more content below"
        if (scrollState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(ToolSectionFadeHeight)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor)))
            )
        }
    }
}

/** Add/remove stat pill: green "+N" and red "−M". */
@Composable
internal fun DiffStat(added: Int, removed: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) {
            Text(
                text = "+$added",
                color = DiffAddText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (added > 0 && removed > 0) Spacer(Modifier.width(Spacing.xs))
        if (removed > 0) {
            Text(
                text = "−$removed",
                color = DiffRemoveText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Colored line-level diff view
 */
@Composable
internal fun DiffView(diff: String, startLine: Int) {
    val lines = remember(diff) { diff.split("\n") }
    val collapsible = lines.size > DIFF_COLLAPSE_THRESHOLD
    var expanded by remember(diff) { mutableStateOf(false) }
    val visibleLines = if (collapsible && !expanded) lines.take(DIFF_COLLAPSE_THRESHOLD) else lines

    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace
    )
    val removeCount = lines.count { it.startsWith("-") }
    val addCount = lines.count { it.startsWith("+") }
    val maxLineNo = startLine + lines.size - removeCount - addCount + maxOf(removeCount, addCount)
    val gutterChars = maxOf(2, maxLineNo.toString().length)
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Column(modifier = Modifier.fillMaxWidth()) {
        // Horizontal scroll container outside, text selection inside: the scroll gesture wins,
        // avoiding SelectionContainer occasionally stealing left/right swipes (once selection mode activates, drags are consumed by the selected word).
        // Background and corners come from the outer ChatCodeCard; this handles only row rendering and horizontal scrolling.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Column {
                    var oldLineNo = startLine
                    var newLineNo = startLine
                    visibleLines.forEach { line ->
                        val marker = line.firstOrNull()
                        val (bg, fg) = when (marker) {
                            '+' -> DiffAddBg to DiffAddText
                            '-' -> DiffRemoveBg to DiffRemoveText
                            else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val lineNo = when (marker) {
                            '-' -> oldLineNo++
                            '+' -> newLineNo++
                            else -> { val n = newLineNo; oldLineNo++; newLineNo++; n }
                        }
                        val gutter = lineNo.toString().padStart(gutterChars)
                        val styled = buildAnnotatedString {
                            withStyle(SpanStyle(color = gutterColor)) {
                                append(gutter)
                                append("  ")
                            }
                            withStyle(SpanStyle(color = fg)) {
                                append(line.ifEmpty { " " })
                            }
                        }
                        Text(
                            text = styled,
                            style = mono,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = Spacing.sm, vertical = 1.dp)
                        )
                    }
                }
            }
        }
        if (collapsible) {
            DiffExpandToggle(
                expanded = expanded,
                hiddenCount = lines.size - DIFF_COLLAPSE_THRESHOLD,
                onToggle = { expanded = !expanded }
            )
        }
    }
}

/** Footer toggle for long diffs */
@Composable
internal fun DiffExpandToggle(expanded: Boolean, hiddenCount: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        ExpandableChevronIcon(
            expanded = expanded,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            size = 16.dp
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.tool_expand_remaining, hiddenCount),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** Diff hunk for a single editFile edit. */
internal data class EditHunk(val startLine: Int, val diff: String)

/** Structured diff parsed from an editFile result */
internal data class EditDiff(
    val path: String,
    val added: Int,
    val removed: Int,
    val hunks: List<EditHunk>
)

/**
 * Parse the structured diff of editFile / writeFile from persisted TOOL content
 */
internal fun parseEditDiff(content: String): EditDiff? {
    val dataObj = extractToolDataObject(content)
    if (dataObj != null) {
        return parseEditDiffObject(dataObj)
    }

    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching {
        parseEditDiffObject(Json.parseToJsonElement(content.substring(start, end + 1)).jsonObject)
    }.getOrNull()
}

private fun parseEditDiffObject(obj: JsonObject): EditDiff? {
    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val added = obj["added_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val removed = obj["removed_lines"]?.jsonPrimitive?.intOrNull ?: 0

    val hunks = obj["hunks"]?.jsonArray?.mapNotNull { el ->
        val ho = el.jsonObject
        val d = ho["diff"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        EditHunk(startLine = ho["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d)
    } ?: run {
        val d = obj["diff"]?.jsonPrimitive?.contentOrNull ?: return null
        listOf(EditHunk(startLine = obj["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d))
    }
    if (hunks.isEmpty()) return null
    return EditDiff(path = path, added = added, removed = removed, hunks = hunks)
}

/**
 * Cleanse a persisted raw tool result into readable text
 */
internal fun formatToolResult(raw: String): String {
    val s = raw.withoutToolStatusPrefix()
    parseToolTransport(s)?.let { obj ->
        return when (obj["status"]?.jsonPrimitive?.contentOrNull) {
            "error" -> obj["message"]?.jsonPrimitive?.contentOrNull ?: s
            "success", "partial" -> formatToolData(obj["data"]) ?: s
            else -> s
        }
    }

    when {
        s.startsWith("Error(") -> {
            val msgIdx = s.indexOf("message=")
            if (msgIdx >= 0) {
                var body = s.substring(msgIdx + "message=".length)
                val codeIdx = body.lastIndexOf(", code=")
                body = if (codeIdx >= 0) body.substring(0, codeIdx) else body.removeSuffix(")")
                return body.trim()
            }
        }
        s.startsWith("Success(data=") -> {
            val inner = s.removePrefix("Success(data=").removeSuffix(")")
            return formatJsonData(inner) ?: inner.trim()
        }
        s.startsWith("Partial(data=") -> {
            var inner = s.removePrefix("Partial(data=")
            val msgIdx = inner.lastIndexOf(", message=")
            inner = if (msgIdx >= 0) inner.substring(0, msgIdx) else inner.removeSuffix(")")
            return formatJsonData(inner) ?: inner.trim()
        }
    }
    return s
}

private fun parseToolTransport(raw: String): JsonObject? {
    return runCatching {
        val obj = Json.parseToJsonElement(raw.trim()).jsonObject
        if (obj["status"] != null) obj else null
    }.getOrNull()
}

private fun extractToolDataObject(raw: String): JsonObject? {
    return (parseToolTransport(raw)?.get("data") as? JsonObject)
}

private fun formatToolData(data: JsonElement?): String? {
    return when (data) {
        is JsonPrimitive -> data.contentOrNull ?: data.toString()
        is JsonObject -> {
            val main = data["content"] ?: data["output"] ?: data["stdout"] ?: data["text"]
            val mainStr = (main as? JsonPrimitive)?.contentOrNull
            mainStr ?: data.entries.joinToString("\n") { (k, v) ->
                val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                "$k: $vv"
            }
        }
        null -> null
        else -> data.toString()
    }
}

internal fun String.withoutToolStatusPrefix(): String = trim()
    .removePrefix(SessionUseCase.LEGACY_STOPPED_TOOL_MARKER)
    .removePrefix(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
    .removePrefix(SessionUseCase.PENDING_TOOL_MARKER)
    .trim()

/**
 * Render the JsonElement text inside `data=` into a readable result
 */
internal fun formatJsonData(jsonStr: String): String? = runCatching {
    when (val el = Json.parseToJsonElement(jsonStr.trim())) {
        is JsonPrimitive -> formatToolData(el) ?: jsonStr.trim()
        is JsonObject -> formatToolData(el) ?: jsonStr.trim()
        else -> jsonStr.trim()
    }
}.getOrNull()

/** List the input args JSON as multi-line `key: value` */
internal fun formatToolArgs(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        if (obj.isEmpty()) return null
        obj.entries.joinToString("\n") { (k, v) ->
            val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            "$k: $vv"
        }
    }.getOrNull() ?: argsJson.trim()
}

/** Inline arg summary in the header row */
internal fun toolArgHint(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        val preferred = listOf("command", "cmd", "path", "file_path", "file", "query", "pattern", "url", "name")
        val v = preferred.firstNotNullOfOrNull { obj[it] } ?: obj.values.firstOrNull()
        val str = (v as? JsonPrimitive)?.contentOrNull ?: v?.toString()
        str?.replace("\n", " ")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/** Extract the file path from the tool args JSON (the path arg of readFile/editFile/writeFile). */
private fun extractFilePathArg(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        listOf("path", "file_path", "file").firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull
        }
    }.getOrNull()
}
