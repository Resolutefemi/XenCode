package com.xencode.feature.agent.presentation.component

import android.content.ClipData
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xencode.R
import com.xencode.core.theme.Spacing
import com.xencode.core.ui.ImageViewerHost
import com.xencode.core.ui.LocalImageViewer
import com.xencode.core.ui.readableContentMaxWidth
import com.xencode.core.ui.rememberImageViewerState
import com.xencode.core.ui.rememberViewerDecodeSpec
import com.xencode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.xencode.feature.agent.presentation.AgentUIMessage
import com.xencode.feature.agent.presentation.AgentUIState
import com.xencode.feature.agent.presentation.AIAgentViewModel
import com.xencode.feature.agent.presentation.MessageRole
import com.xencode.feature.agent.presentation.QueuedRequest
import com.xencode.feature.agent.presentation.hasVisibleContent
import com.xencode.feature.onboarding.domain.OnboardingStep
import com.xencode.feature.onboarding.presentation.onboardingTarget
import com.xencode.feature.settings.data.repository.TodoDisplayPosition
import com.xencode.core.ui.glassBackdrop
import com.xencode.core.ui.rememberChatGlassLayer
import com.xencode.core.ui.rememberGlassLuminance
import com.xencode.feature.settings.presentation.SettingsViewModel
import com.xencode.feature.settings.domain.model.DashboardContext
import com.xencode.feature.settings.domain.model.ProviderDashboardState
import com.xencode.feature.settings.domain.model.modelMetadataKey
import com.xencode.feature.workspace.domain.WorkspacePathMapper
import com.xencode.feature.workspace.presentation.WorkspaceViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowDown
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


/**
 * The three states of the streaming tail, used for [when] branch dispatch.
 *
 * Earlier a [androidx.compose.animation.Crossfade] provided the fade, but Crossfade caches the content
 * sub-composition keyed by targetState — during streaming targetState never changes, so growing text never re-invokes content,
 * leaving [StreamingBubble] without later text, stuck on the first sentence. Hence the enum + direct [when] dispatch.
 */
private enum class TailKind { THINKING, STREAMING, COMPACTING, RETRYING, KEY_SWITCHED, NONE }

/** Gap between the floating layer (banner/panel/input bar) and the last message. */
private val FLOATING_LAYER_GAP_DP = 8.dp

/** Tolerance (px) for content-bottom-driven calibration: calibrate downward only when the last content bottom exceeds the safe area by more than this, avoiding sub-pixel jitter. */
private const val AUTO_SCROLL_TOLERANCE_PX = 2

/** How long (ms) to keep calibrating frame by frame after the AI finishes: async md parsing can still change height, so don't stop the moment work ends. */
private const val CALIBRATE_TAIL_MS = 1_200L

/** Diameter (dp) of the scroll-to-bottom button. */
private const val SCROLL_TO_BOTTOM_BTN_SIZE = 34

/** Entry stagger interval (ms) for consecutive new messages, and its total cap: inserting many cards at once must not make the last one wait seconds. */
private const val MESSAGE_ENTRY_STAGGER_MS = 90L
private const val MESSAGE_ENTRY_MAX_STAGGER_MS = 360L

/** How long (ms) to wait before showing the loading hint when messages are not ready: local DB reads are fast, and showing it immediately just flickers. */
private const val MESSAGES_LOADING_HINT_DELAY_MS = 220L

/**
 * Chunked rendering of long messages:
 *
 * If an overlong assistant body (a long document restatement) is stuffed into a single LazyColumn item, once it stretches several screens deep the gesture hit area
 * falls outside the list's visible region, and horizontal swipes/long-press copy/taps on deep tables all stop working (the root cause of broken deep interactions in the original version).
 * A single-window scheme with capped height + internal scrolling would instead create a "disconnected, standalone block" break between the window and the list world.
 *
 * The chunking idea: split into multiple list items of "bounded height", each an ordinary bubble (reasoning only in the first chunk,
 * action row only in the last chunk, adjacent chunks seamlessly joined with zero spacing), all on the list's single scroll axis — the world is fully connected top to bottom, and deep
 * interactions (swipe/copy/tap on 8+ row tables) all recover because every item's height is bounded.
 *
 * If the body length does not exceed the threshold, no chunking — identical to an ordinary message.
 *
 * internal (rather than private) so the use cases of [buildChatItems] can assert rendered items; see ToolGroupExpansionTest.
 */
internal data class ChatRenderItem(
    val message: AgentUIMessage,
    val key: String,
    val contentType: String,
    val slice: String? = null,
    val isChunkHeader: Boolean = true,
    val isChunkFooter: Boolean = true,
    /**
     * Non-null marks this as a "consecutive tool calls" group header item. Members are still independent items (generated only when the group expands),
     * so expanded or not, every item's height stays bounded — the precondition for deep interactions (table swipes, long-press copy) not breaking.
     */
    val toolGroup: List<AgentUIMessage>? = null,
    /** Whether the group is currently expanded (user overrides already applied). */
    val groupExpanded: Boolean = true,
    /** Non-null = whole-turn collapse header item ("Running / Finished Xs"). */
    val turnHeader: TurnHeaderState? = null,
    /** Process items carried by the turn header (reasoning / tool groups / intermediate body text): expand smoothly with the collapse header when the turn expands. */
    val turnProcess: List<ChatRenderItem> = emptyList(),
    /** Whether to render the message's own reasoning block; false for result chunks whose reasoning was extracted into a process item. */
    val reasoningVisible: Boolean = true,
)

/** contentType of the tool-call group header item. */
private const val TOOL_GROUP_CONTENT_TYPE = "tool-group"

/** contentType of the whole-turn collapse header item. */
private const val TURN_HEADER_CONTENT_TYPE = "turn-header"

/** contentType of the "turn-end assistant reasoning" extracted as an independent process item when the turn expands. */
private const val REASONING_CONTENT_TYPE = "reasoning"

/** Turn-leading user message id → persistence key of the whole-turn collapse (same convention as ViewModel.turnExpansionOverrides). */
internal fun turnKeyOf(firstMessageId: String): String = "turn:$firstMessageId"

/**
 * Render state of the whole-turn task collapse header item. Non-null means this is a "Running / Finished" header row.
 *
 * [endMessageId] is the turn-end assistant message id, used to look up this turn's duration (see [computeTaskDurations], same convention).
 */
internal data class TurnHeaderState(
    val key: String,
    val endMessageId: String?,
    /** Whether this turn is still running (last turn and the agent is busy). */
    val running: Boolean,
    /** Expansion state (user overrides already applied). */
    val expanded: Boolean,
)

/**
 * Left indent of member rows when a group is expanded: the group header and member rows originally align exactly at the leading edge (same 16dp icon slot + same spacing),
 * hiding the subordination; indenting member rows one level makes the hierarchy obvious — header as parent, members as children.
 * Fixed at 16dp (not [Spacing.lg]: under compact density that is only 14dp, and the indent would vary with screen form factor).
 */
private val ToolGroupMemberPadding = PaddingValues(start = 16.dp)

/**
 * Whether this message participates in "consecutive tool calls" grouping.
 *
 * Context compaction failure/summary and background-notification TOOL messages each have dedicated render branches (see the early returns in [AgentMessageItem]);
 * mixed into a group they would be treated as ordinary tool rows, so they are excluded; ordinary messages (user/assistant) naturally break a group.
 *
 * **Tools with attachments (`sendFile` / `generateImage`) are not grouped either**: the file rows they produce are the result itself,
 * and folding them into an "N tool calls" group would collapse them by default, hiding the delivered files; kept as top-level items they stay visible.
 */
private fun AgentUIMessage.isGroupableTool(): Boolean =
    role == MessageRole.TOOL && !isCompactionFailure && !isContextSummary &&
        !isCompactionMarker && !isBackgroundNotification && attachments.isEmpty()

/** Group identity: the first message id in the group, keeping item keys stable while a batch of tool calls is appended (no rebuilds, no viewport jumps). */
private fun toolGroupKey(first: AgentUIMessage): String = "toolgroup:${first.id}"

/**
 * Whether this assistant message renders the "meta row below the bubble" — i.e. whether it can host the "Copy / More" button row.
 *
 * Compaction markers, context summaries, compaction failures, and background-notification bars each have dedicated render branches in [AgentMessageItem] that
 * return early and never produce this row; reasoning-only assistant messages without body text are the same (no body to copy). Hanging the "latest message" buttons
 * on those would leave the whole session without a single button.
 */
private fun AgentUIMessage.rendersActionRow(): Boolean =
    role == MessageRole.ASSISTANT &&
        !isCompactionMarker && !isContextSummary && !isCompactionFailure && !isBackgroundNotification &&
        (content.hasVisibleContent() || attachments.isNotEmpty())

/**
 * Items a single message (not a tool group) occupies in one render pass:
 * overlong assistant bodies split into multiple bounded chunks; all other messages are 1:1.
 */
private fun messageRenderItems(message: AgentUIMessage): List<ChatRenderItem> {
    val canSplit = message.role == MessageRole.ASSISTANT &&
        !message.isCompactionMarker &&
        !message.isContextSummary &&
        !message.isCompactionFailure &&
        !message.isBackgroundNotification &&
        message.content.length > CHUNK_SPLIT_THRESHOLD_CHARS
    if (!canSplit) {
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        )
    }
    val slices = splitLongContent(message.content)
    if (slices.size <= 1) {
        // Only one chunk produced (e.g. an overlong single paragraph with no blank lines): same as an ordinary message.
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name)
        )
    }
    return slices.mapIndexed { idx, slice ->
        ChatRenderItem(
            message = message,
            key = "${message.id}#chunk$idx",
            contentType = "assistant-chunk",
            slice = slice,
            isChunkHeader = idx == 0,
            isChunkFooter = idx == slices.lastIndex,
        )
    }
}

/**
 * Message list → LazyColumn item list.
 *
 * Three jobs: **whole-turn task collapse**, long-text chunking, **consecutive tool call grouping**.
 *
 * First split into turns at "user message → up to the next user message" (see [splitChatTurns]): each turn yields, in order, "user message →
 * turn header (carrying process items, only when the turn has process content or is running) → persistent items and the result". Process items hang inside the turn header item and
 * expand smoothly with the collapse header when the turn expands; persistent items such as compaction summaries/background notifications/tools with attachments do not fold.
 * Result = the last ordinary assistant message in the turn with visible body text (its reasoning is extracted as a process item).
 *
 * Turn header expansion: without a manual record, "last turn and agent busy" defaults to expanded, the rest default to collapsed ([activeTurnKey]);
 * once the user has tapped in [turnOverrides], their choice wins and is not overridden by the automatic collapse on task completion.
 *
 * Tool call groups stay **collapsed by default** and honor only manual choices in [groupOverrides].
 */
internal fun buildChatItems(
    messages: List<AgentUIMessage>,
    groupOverrides: Map<String, Boolean>,
    turnOverrides: Map<String, Boolean> = emptyMap(),
    activeTurnKey: String? = null,
): List<ChatRenderItem> {
    val items = ArrayList<ChatRenderItem>(messages.size)
    val (leading, turns) = splitChatTurns(messages)
    // Scattered messages before the first turn (assistant/tool output appearing at the start of a session, rare) are not folded; shown as-is.
    leading.forEach { items += messageRenderItems(it) }
    for (turn in turns) {
        items += messageRenderItems(turn.userMessage)
        val endAssistant = turn.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && !it.isPersistentInTurn()
        }
        val running = turn.key == activeTurnKey
        val resultId = if (running) null else turn.messages.lastOrNull { it.isResultCandidate() }?.id
        // Process items hang inside the turn header item and expand with it (so the expand animation is one overall height change); persistent items and the result are separate items.
        val process = buildTurnProcessItems(turn.messages, resultId, groupOverrides)
        val expanded = turnOverrides[turn.key] ?: running
        if (process.isNotEmpty() || running) {
            items += ChatRenderItem(
                message = turn.userMessage,
                key = "${turn.key}#header",
                contentType = TURN_HEADER_CONTENT_TYPE,
                turnHeader = TurnHeaderState(
                    key = turn.key,
                    endMessageId = endAssistant?.id,
                    running = running,
                    expanded = expanded,
                ),
                turnProcess = process,
            )
        }
        items += buildTurnPersistentItems(turn.messages, resultId)
    }
    return items
}

/** One task turn: the leading user message + all messages after it (assistant / tools / compaction cards etc.). */
private class ChatTurn(
    val key: String,
    val userMessage: AgentUIMessage,
    val messages: List<AgentUIMessage>,
)

/**
 * Splits into turns at "user messages". Returns (scattered messages before the first turn, list of turns).
 *
 * Background notifications may carry the USER role, but they are system-injected notice bars rather than user input, so they do not start a turn (they belong to the current turn's content).
 */
private fun splitChatTurns(messages: List<AgentUIMessage>): Pair<List<AgentUIMessage>, List<ChatTurn>> {
    val leading = ArrayList<AgentUIMessage>()
    val turns = ArrayList<ChatTurn>()
    var user: AgentUIMessage? = null
    var body: MutableList<AgentUIMessage>? = null
    for (message in messages) {
        if (message.role == MessageRole.USER && !message.isBackgroundNotification) {
            user?.let { turns += ChatTurn(turnKeyOf(it.id), it, body?.toList().orEmpty()) }
            user = message
            body = ArrayList()
        } else {
            if (body == null) leading += message else body.add(message)
        }
    }
    user?.let { turns += ChatTurn(turnKeyOf(it.id), it, body?.toList().orEmpty()) }
    return leading to turns
}

/** Whether this message is "persistent" in the whole-turn collapse — not part of process folding; shown whether the turn is expanded or not. */
private fun AgentUIMessage.isPersistentInTurn(): Boolean =
    isCompactionMarker || isContextSummary || isCompactionFailure || isBackgroundNotification ||
        (role == MessageRole.TOOL && attachments.isNotEmpty())

/** Whether it can be a turn's "result": the last ordinary assistant message with visible body text. */
private fun AgentUIMessage.isResultCandidate(): Boolean =
    role == MessageRole.ASSISTANT && !isPersistentInTurn() && content.hasVisibleContent()

/**
 * A turn's "process" items (in original order): reasoning, consecutive tool call groups, intermediate assistant body text. They hang inside the turn header item and expand with it.
 *
 * The result message's reasoning belongs to process (extracted as one [REASONING_CONTENT_TYPE] item); persistent items such as compaction summaries/background notifications/tools with attachments
 * do not enter process and are produced separately by [buildTurnPersistentItems].
 */
private fun buildTurnProcessItems(
    messages: List<AgentUIMessage>,
    resultId: String?,
    groupOverrides: Map<String, Boolean>,
): List<ChatRenderItem> {
    val out = ArrayList<ChatRenderItem>(messages.size)
    var i = 0
    while (i < messages.size) {
        val message = messages[i]
        if (message.isGroupableTool()) {
            var j = i
            while (j < messages.size && messages[j].isGroupableTool()) j++
            val members = messages.subList(i, j).toList()
            // A single tool is not folded into a "1 tool call" group; shown directly as an ordinary tool row.
            if (members.size == 1) {
                out += messageRenderItems(members.first())
            } else {
                val key = toolGroupKey(members.first())
                out += ChatRenderItem(
                    message = members.first(),
                    key = key,
                    contentType = TOOL_GROUP_CONTENT_TYPE,
                    toolGroup = members,
                    groupExpanded = groupOverrides[key] == true,
                )
            }
            i = j
            continue
        }
        when {
            message.isPersistentInTurn() -> Unit
            message.id == resultId -> {
                if (!message.reasoning.isNullOrEmpty()) {
                    out += ChatRenderItem(
                        message = message,
                        key = "${message.id}#reasoning",
                        contentType = REASONING_CONTENT_TYPE,
                    )
                }
            }
            else -> out += messageRenderItems(message)
        }
        i++
    }
    return out
}

/** A turn's "persistent" items (in original order): the result body (chunked, reasoning not re-rendered) plus compaction cards/notifications/tools with attachments. */
private fun buildTurnPersistentItems(
    messages: List<AgentUIMessage>,
    resultId: String?,
): List<ChatRenderItem> {
    val out = ArrayList<ChatRenderItem>()
    for (message in messages) {
        if (message.id == resultId) {
            messageRenderItems(message).forEach { out += it.copy(reasoningVisible = false) }
        } else if (message.isPersistentInTurn()) {
            out += messageRenderItems(message)
        }
    }
    return out
}

/** Assistant bodies longer than this (characters) are split into multiple bounded chunks. */
private const val CHUNK_SPLIT_THRESHOLD_CHARS = 2_000

/** Target character budget per chunk: the body is packed by markdown blocks; a single block over budget (e.g. a huge table)
 *  is hard-cut by lines as a fallback, keeping every chunk's height bounded (≈0.5-0.7 screen). */
private const val CHUNK_BUDGET_CHARS = 1_200

/**
 * Long-body chunking: recognizes three kinds of markdown blocks line by line — code fences (whole segment), tables (consecutive | lines,
 * whole table kept intact), and ordinary paragraphs (separated by blank lines) — then greedily packs them into chunks by character budget.
 * An over-budget block is split by lines into several blocks; better to break a table than let an item grow unbounded.
 */
internal fun splitLongContent(text: String): List<String> {
    val lines = text.lines()
    val rawBlocks = ArrayList<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith(fence)) {
                    sb.append('\n').append(lines[j]); j++
                }
                if (j < lines.size) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            line.isBlank() -> i++
            trimmed.startsWith("|") -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && lines[j].trimStart().startsWith("|")) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            else -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() &&
                    !lines[j].trimStart().startsWith("```") &&
                    !lines[j].trimStart().startsWith("~~~") &&
                    !lines[j].trimStart().startsWith("|")
                ) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
        }
    }
    if (rawBlocks.isEmpty()) return listOf(text)

    // An over-budget single block (e.g. a giant table/paragraph) is cut by lines into budget-sized pieces as a bounded fallback.
    // Code blocks (``` / ~~~) are indivisible syntax units and must stay whole — never shred them by lines,
    // or the fence closing breaks, later fragments degrade into ordinary body text losing highlighting and copy, and HTML gets wrongly scrubbed by the preprocessor.
    val blocks = ArrayList<String>()
    for (block in rawBlocks) {
        val isCodeBlock = block.trimStart().let { it.startsWith("```") || it.startsWith("~~~") }
        if (block.length <= CHUNK_BUDGET_CHARS || isCodeBlock) {
            blocks.add(block)
        } else {
            val piece = StringBuilder()
            var weight = 0
            for (ln in block.lines()) {
                if (weight > 0 && weight + 1 + ln.length > CHUNK_BUDGET_CHARS) {
                    blocks.add(piece.toString()); piece.setLength(0); weight = 0
                }
                piece.append(ln).append('\n'); weight += ln.length + 1
            }
            if (piece.isNotBlank()) blocks.add(piece.toString())
        }
    }

    val chunks = ArrayList<String>()
    val cur = StringBuilder()
    var weight = 0
    for (block in blocks) {
        val w = block.length + 2
        if (weight > 0 && weight + w > CHUNK_BUDGET_CHARS) {
            chunks.add(cur.toString()); cur.setLength(0); weight = 0
        }
        // Blocks must be separated by a blank line: dropping it glues markdown semantics together (a paragraph + a following --- parses as a setext heading, inflating the paragraph to heading size).
        if (weight > 0) cur.append('\n')
        cur.append(block).append('\n'); weight += w
    }
    if (cur.isNotBlank()) chunks.add(cur.toString())
    return chunks
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatPanel(
    viewModel: AIAgentViewModel,
    onNavigateToTerminal: () -> Unit = {},
    onNavigateToGit: () -> Unit = {},
    onNavigateToBrowser: () -> Unit = {},
    settingsViewModel: SettingsViewModel? = null,
    workspaceViewModel: WorkspaceViewModel? = null,
    onOpenDrawer: () -> Unit,
    showMenuButton: Boolean = true,
    /** When the large-screen right pane currently shows Terminal / Git, the matching top-bar icon is highlighted. */
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    browserActive: Boolean = false,
    currentFile: String? = null,
    selectedCode: String? = null,
    onboardingStep: OnboardingStep? = null,
    onSelectModelInOnboarding: (() -> Unit)? = null,
    onDismissModelSheetInOnboarding: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val messages = messagesState.messages
    val pendingScroll by viewModel.pendingScrollMessage.collectAsStateWithLifecycle()

    val currentSessionId by viewModel.currentSessionId.collectAsStateWithLifecycle()
    // Tool card entry scheduling: only TOOL messages newly appended at the tail during this browsing session get staggered fade-ins.
    // The scheduler is rebuilt on session switch, so the new session's existing messages get no entry animation.
    val entryScheduler = remember(currentSessionId) { MessageEntryScheduler() }
    val messageEntryDelays = remember(messages, entryScheduler) { entryScheduler.schedule(messages) }
    val currentSessionState by viewModel.currentSessionState.collectAsStateWithLifecycle()
    val currentSession = currentSessionState
    val sessionTitle = currentSession?.title?.takeIf { it.isNotBlank() }
        ?.let { com.xencode.feature.agent.domain.session.SessionUseCase.displayTitle(it) }
        ?: stringResource(R.string.chat_new_session_btn)
    val sessionInputTokens = currentSession?.totalInputTokens ?: 0
    val sessionOutputTokens = currentSession?.totalOutputTokens ?: 0
    val sessionLastInputTokens = currentSession?.lastInputTokens ?: 0
    val contextUsages by viewModel.contextUsages.collectAsStateWithLifecycle()
    val messagesReady = messagesState.loaded && messagesState.sessionId == currentSessionId
    val runningTool by viewModel.runningTool.collectAsStateWithLifecycle()
    val isCompacting by viewModel.isCompacting.collectAsStateWithLifecycle()
    val retryState by viewModel.retryState.collectAsStateWithLifecycle()
    val keySwitchState by viewModel.keySwitchState.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val streamingReasoning by viewModel.streamingReasoning.collectAsStateWithLifecycle()
    val reasoningTiming by viewModel.reasoningTiming.collectAsStateWithLifecycle()
    val preparingTool by viewModel.preparingTool.collectAsStateWithLifecycle()
    // Status text while waiting for the model or a tool to run:
    // 1. If a tool is currently running (runningTool) or the model is preparing one (preparingTool — name arrived, args still streaming),
    //    say exactly what is happening ("Reading file", "Editing file", etc.; see toolRunningLabelRes);
    // 2. Otherwise (e.g. waiting for the model's first token, a pure-reasoning gap) fall back to "Thinking".
    // derivedStateOf isolation: runningTool is a List updated on every streaming chunk; reading it directly during composition
    // would recompose all of AIChatPanel (including ChatHeader/LazyColumn parameter area) on every line the tool emits;
    // wrapped, recomposition fires only when the "current tool name" actually changes (usually once or twice per tool call).
    val activeToolName by remember {
        derivedStateOf { runningTool.lastOrNull()?.toolName?.takeIf { it.isNotBlank() } ?: preparingTool }
    }
    val thinkingLabel = activeToolName?.let { stringResource(toolRunningLabelRes(it)) }
        ?: stringResource(R.string.chat_status_thinking)
    val pendingPermission by viewModel.pendingToolPermission.collectAsStateWithLifecycle()
    val pendingPermissionSessionTitle by viewModel.pendingToolPermissionSessionTitle.collectAsStateWithLifecycle()
    val pendingQuestion by viewModel.pendingUserQuestion.collectAsStateWithLifecycle()
    // Collect early: the todo overlay below the title bar must collapse in response to the plan approval dialog (previously collected inside the floating layer, too late)
    val planApproval by viewModel.pendingPlanApproval.collectAsStateWithLifecycle()
    val currentTodoItems by viewModel.currentSessionTodoItems.collectAsStateWithLifecycle()
    // Task todo display position: below the title bar / above the message bar (default, pre-existing behavior) / off
    val todoDisplayPosition = settingsViewModel?.todoDisplayPosition?.collectAsStateWithLifecycle()?.value
        ?: TodoDisplayPosition.ABOVE_INPUT_BAR
    val todoBelowTitleBar = todoDisplayPosition == TodoDisplayPosition.BELOW_TITLE_BAR
    val todoAboveInputBar = todoDisplayPosition == TodoDisplayPosition.ABOVE_INPUT_BAR
    // Whether the below-title-bar todo overlay is actually visible: decides how much extra height the message list reserves for it on the first screen
    val todoOverlayVisible = todoBelowTitleBar && currentTodoItems.isNotEmpty()
    val queuedRequests by viewModel.queuedRequests.collectAsStateWithLifecycle()
    val targetRewindMessageId by viewModel.targetRewindMessageId.collectAsStateWithLifecycle()
    val providers = (settingsViewModel?.providers?.collectAsStateWithLifecycle()?.value ?: emptyList()).filter { it.isEnabled }
    val modelMetadata = settingsViewModel?.modelMetadata?.collectAsStateWithLifecycle()?.value.orEmpty()
    val sessionProviderModel by viewModel.currentSessionProviderModel.collectAsStateWithLifecycle()
    val defaultProviderId = settingsViewModel?.defaultModelProviderId?.collectAsStateWithLifecycle()?.value ?: ""
    val defaultModelName = settingsViewModel?.defaultModel?.collectAsStateWithLifecycle()?.value ?: ""
    // Fallback when no session model is bound: the new-session default model (remembered when chosen in an empty home session); null if unset (UI shows the default icon / leaves the top-bar model name blank).
    val defaultFallbackProvider = providers
        .find { it.id == defaultProviderId }
        ?.takeIf { it.hasUsableApiKey }
        ?.let { if (defaultModelName.isNotBlank() && defaultModelName in it.models) it.copy(selectedModel = defaultModelName) else it }
    val activeProvider = run {
        val (boundProviderId, boundModel) = sessionProviderModel
        if (!boundProviderId.isNullOrBlank()) {
            // Keep consistent with workflow.resolveProviderConfig: a bound provider must be enabled with an apiKey filled in, otherwise fall back to the default model
            providers.find { it.id == boundProviderId }
                ?.takeIf { it.hasUsableApiKey }
                // The binding is void when the bound model has been removed from the list; fall back to the default model (consistent with workflow.resolveProviderConfig)
                ?.takeIf { boundModel.isNullOrBlank() || boundModel in it.models }
                ?.let {
                    if (!boundModel.isNullOrBlank()) it.copy(selectedModel = boundModel) else it
                } ?: defaultFallbackProvider
        } else {
            defaultFallbackProvider
        }
    }
    val currentWorkspace = workspaceViewModel?.current?.collectAsStateWithLifecycle()?.value
    val projectRoot = currentWorkspace?.path ?: ""
    val currentMode by viewModel.currentSessionMode.collectAsStateWithLifecycle()
    val slashCommands by viewModel.slashCommands.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf("") }
    val inputDraft by viewModel.inputDraft.collectAsStateWithLifecycle()
    LaunchedEffect(inputDraft) {
        if (inputText != inputDraft) inputText = inputDraft
    }
    // Rescan skills when "/" opens the command menu, reflecting skills added/removed/changed on disk.
    LaunchedEffect(inputText) {
        if (inputText == "/") viewModel.refreshSlashCommands()
    }
    var pendingAttachments by remember { mutableStateOf<List<PendingUploadAttachment>>(emptyList()) }
    var uploadingCount by remember { mutableStateOf(0) }
    var messageForMenu by remember { mutableStateOf<AgentUIMessage?>(null) }
    var editingMessage by remember { mutableStateOf<AgentUIMessage?>(null) }
    var queuedEditing by remember { mutableStateOf<QueuedRequest?>(null) }
    val listState = rememberLazyListState()
    // Loading hint while messages are not ready: local DB reads usually take tens of milliseconds, and showing it instantly just flickers; only hint after a short wait.
    var showMessagesLoading by remember(currentSessionId) { mutableStateOf(false) }
    LaunchedEffect(currentSessionId, messagesReady) {
        if (messagesReady) {
            showMessagesLoading = false
            return@LaunchedEffect
        }
        delay(MESSAGES_LOADING_HINT_DELAY_MS)
        showMessagesLoading = true
    }
    // Bottom-pinned scroll reserve: a fallback value (roughly input bar + gap) before the first measurement, then the measured floating layer height,
    // so in any banner/panel/input-bar configuration the last message stops above the floating layer unobstructed.
    val inputBarBottomReserveDp = 156.dp
    var floatingLayerHeightPx by remember { mutableStateOf(0) }
    val inputBarReservePx = with(LocalDensity.current) {
        (if (floatingLayerHeightPx > 0) floatingLayerHeightPx + FLOATING_LAYER_GAP_DP.toPx()
        else inputBarBottomReserveDp.toPx()).toInt()
    }
    val markdownCache = remember { MarkdownRenderCache() }
    // Manual expansion state of tool calls (group headers / single tool rows): kept in the ViewModel so it survives page switches and viewport recycling.
    // key = group key (`toolgroup:<first message id in the group>`) or a single message id.
    val toolExpansionOverrides = viewModel.toolExpansionOverrides
    // Snapshot: derivedStateOf is lazy — recompositions of AIChatPanel due to other state do not redo the toMap allocation,
    // and a new snapshot is produced only when the map's contents actually change (it participates in equality as a remember key for chatItems below).
    val toolGroupOverrideSnapshot by remember { derivedStateOf { toolExpansionOverrides.toMap() } }
    // Manual expansion state of whole-turn collapses, same kind of snapshot.
    val turnExpansionOverrides = viewModel.turnExpansionOverrides
    val turnOverrideSnapshot by remember { derivedStateOf { turnExpansionOverrides.toMap() } }
    // Expansion state of reasoning cards, same kind of snapshot.
    val reasoningExpansionOverrides = viewModel.reasoningExpansionOverrides
    val reasoningOverrideSnapshot by remember { derivedStateOf { reasoningExpansionOverrides.toMap() } }
    // Set of currently running tool ids: only the set's contents join the remember key, avoiding whole-list rebuilds from character-by-character live output.
    val runningToolIds by remember { derivedStateOf { runningTool.mapTo(HashSet()) { it.messageId } } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    val isBusy = agentState is AgentUIState.Loading || agentState is AgentUIState.Streaming
    // Chunking + tool grouping: overlong assistant messages expand into multiple bounded items (single scroll axis, continuous look),
    // and consecutive tool calls fold into one "N tool calls" group; chatItems order is the LazyColumn item order.
    // Hoisted here (rather than inside the LazyColumn branch) because isFarFromBottom's check of "does the layout match the current messages"
    // needs it: grouping makes item count ≠ message count + 1, so message count can no longer serve as the expected value.
    // Last turn and agent busy = the turn currently running: its collapse header defaults to expanded (showing "Running") and auto-collapses when done.
    val activeTurnKey = remember(messages, isBusy) {
        if (!isBusy) {
            null
        } else {
            messages.lastOrNull { it.role == MessageRole.USER && !it.isBackgroundNotification }
                ?.let { turnKeyOf(it.id) }
        }
    }
    val chatItems = remember(messages, toolGroupOverrideSnapshot, turnOverrideSnapshot, activeTurnKey) {
        buildChatItems(
            messages = messages,
            groupOverrides = toolGroupOverrideSnapshot,
            turnOverrides = turnOverrideSnapshot,
            activeTurnKey = activeTurnKey,
        )
    }
    // Total duration of each task turn (user sends → this turn's AI finishes) and token totals, both attached only below the turn-end assistant bubble
    val taskDurations = remember(messages, isBusy) {
        computeTaskDurations(messages, lastTurnFinished = !isBusy)
    }
    val turnUsages = remember(messages, isBusy) {
        computeTurnUsage(messages, lastTurnFinished = !isBusy)
    }
    val reasoningDurations = viewModel.reasoningDurations
    // Plan A: the final reply of every properly finished turn (including all historical turns) gets an action row (copy/more),
    // fully fixing the bug where relying on in-memory sets made historical messages lose their action buttons after an app restart.
    // Only the latest turn still generating (isBusy == true) defers attaching, so buttons don't jump around mid-generation.
    val actionableMessageIds = remember(messages, isBusy) {
        val (leading, turns) = splitChatTurns(messages)
        val ids = mutableSetOf<String>()
        leading.filter { it.rendersActionRow() }.forEach { ids += it.id }
        for (i in turns.indices) {
            val turn = turns[i]
            val isLastTurn = i == turns.lastIndex
            val turnFinished = !isLastTurn || !isBusy
            if (turnFinished) {
                val resultMsg = turn.messages.lastOrNull { it.isResultCandidate() && it.rendersActionRow() }
                    ?: turn.messages.lastOrNull { it.rendersActionRow() }
                if (resultMsg != null) {
                    ids += resultMsg.id
                }
            }
        }
        ids
    }
    val activeModel = activeProvider?.effectiveModel.orEmpty()
    val activeModelMetadata = activeProvider?.let { modelMetadata[modelMetadataKey(it.id, activeModel)] }
    val canUploadFiles = projectRoot.isNotBlank() && activeModelMetadata?.supportsTools == true
    val canUploadImages = projectRoot.isNotBlank()
    val reasoningEffort by viewModel.currentSessionReasoningEffort.collectAsStateWithLifecycle()

    val providerDashboards by (settingsViewModel?.providerDashboards?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyMap()) })
    val currentDashboardState = activeProvider?.let { providerDashboards[it.id] } ?: ProviderDashboardState.Idle

    // Collapse panels when the keyboard shows, so the input bar isn't squeezed
    val imeBottomPx = WindowInsets.ime.getBottom(LocalDensity.current)
    val imeVisible = imeBottomPx > 0

    // Balance/todo panel expansion state: when expanded, stacked panels collapse in step, so the input bar isn't pushed up twice
    var dashboardExpanded by rememberSaveable { mutableStateOf(false) }
    var todoExpanded by rememberSaveable { mutableStateOf(false) }
    // ProviderDashboardBar renders only for providers with a dashboard script (see ChatInputBar). Without that bar
    // dashboardExpanded can be stuck at true: the panel once expanded, then was unmounted by a provider switch/script removal, the LaunchedEffect
    // reporting chain broke and cannot reset it; using it directly as forceCollapse would pin the permission/question/plan panels permanently collapsed.
    // So combine visibility: stack panels collapse only while the panel is currently visible and expanded.
    val dashboardBarVisible = activeProvider?.dashboardScriptPath?.isNotBlank() == true
    val dashboardCollapseActive = (dashboardExpanded && dashboardBarVisible) || (todoExpanded && currentTodoItems.isNotEmpty())

    fun buildDashboardContext(
        lastInput: Int = 0,
        lastOutput: Int = 0,
        lastCached: Int = 0,
        refreshReason: String = ""
    ): DashboardContext {
        val curSessionId = currentSessionId.orEmpty()
        val agentStateStr = when (agentState) {
            is AgentUIState.Idle -> "idle"
            is AgentUIState.Loading -> "loading"
            is AgentUIState.Streaming -> "streaming"
            is AgentUIState.Result -> "result"
            is AgentUIState.Error -> "error"
        }
        // When no latest tokens are passed explicitly (session switch/task done/manual refresh, etc.), fall back to the last assistant message carrying tokens:
        // otherwise the panel's "latest input/output/cache" would show 0. Refreshes triggered by llm events still use the live values from the event (more timely).
        val lastTokenMsg = messages.asReversed().firstOrNull {
            it.role == MessageRole.ASSISTANT && (it.inputTokens > 0 || it.outputTokens > 0)
        }
        val effLastInput = if (lastInput > 0) lastInput else (lastTokenMsg?.inputTokens ?: 0)
        val effLastOutput = if (lastOutput > 0) lastOutput else (lastTokenMsg?.outputTokens ?: 0)
        val effLastCached = if (lastCached > 0) lastCached else (lastTokenMsg?.cachedInputTokens ?: 0)
        return DashboardContext(
            model = activeModel,
            // Dashboard scripts run inside the container, so the workspace path must be the container view (~/workspace); the real host path does not exist inside the container
            workspacePath = WorkspacePathMapper.CONTAINER_ROOT,
            workspaceName = if (projectRoot.isNotBlank()) File(projectRoot).name else "",
            sessionId = curSessionId,
            lastInputTokens = effLastInput,
            lastOutputTokens = effLastOutput,
            lastCachedTokens = effLastCached,
            totalInputTokens = currentSession?.totalInputTokens ?: 0,
            totalOutputTokens = currentSession?.totalOutputTokens ?: 0,
            modelContextTokens = activeModelMetadata?.contextTokens ?: 0,
            modelMaxInputTokens = activeModelMetadata?.inputTokens ?: 0,
            modelMaxOutputTokens = activeModelMetadata?.outputTokens ?: 0,
            modelInputCostUsdPerM = activeModelMetadata?.inputCostUsdPerM ?: 0.0,
            modelOutputCostUsdPerM = activeModelMetadata?.outputCostUsdPerM ?: 0.0,
            modelCacheReadCostUsdPerM = activeModelMetadata?.cacheReadCostUsdPerM ?: 0.0,
            modelSupportsTools = activeModelMetadata?.supportsTools ?: false,
            modelSupportsVision = activeModelMetadata?.supportsVision ?: false,
            modelSupportsReasoning = activeModelMetadata?.supportsReasoning ?: false,
            messageCount = messages.size,
            agentState = agentStateStr,
            sessionMode = currentMode.name.lowercase(),
            reasoningEffort = reasoningEffort.apiValue,
            refreshReason = refreshReason
        )
    }

    // Fetch the dashboard once on first entry, provider switch, script path switch, or session switch.
    // On session switch, wait until currentSession / messages have both landed on the new session (sessionReady) before refreshing:
    // otherwise buildDashboardContext reads the old session snapshot from the switch instant and the panel shows stale data or zero tokens.
    val sessionReady = currentSession?.id == currentSessionId && messagesReady
    LaunchedEffect(activeProvider?.id, activeProvider?.dashboardScriptPath, currentSessionId, sessionReady) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (provider.dashboardScriptPath.isBlank()) return@LaunchedEffect
        if (!sessionReady) return@LaunchedEffect
        val context = buildDashboardContext(refreshReason = "session")
        settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
    }

    // Refresh the dashboard immediately with the latest tokens and context every time a single LLM request returns.
    // Note: LaunchedEffect(Unit) starts only once; the collect closure captures the variable snapshot from the first composition,
    // so rememberUpdatedState is required for the latest values — otherwise session token totals/message counts stay frozen at old values.
    val latestBuildDashboardContext by rememberUpdatedState(
        { lastInput: Int, lastOutput: Int, lastCached: Int, refreshReason: String ->
            buildDashboardContext(lastInput, lastOutput, lastCached, refreshReason)
        }
    )
    val latestActiveProvider by rememberUpdatedState(activeProvider)
    LaunchedEffect(Unit) {
        viewModel.llmCallEvents.collect { callEvent ->
            val provider = latestActiveProvider ?: return@collect
            if (provider.dashboardScriptPath.isBlank()) return@collect
            val context = latestBuildDashboardContext(
                callEvent.inputTokens,
                callEvent.outputTokens,
                callEvent.cachedTokens,
                "llm"
            )
            settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
        }
    }

    // Refresh the dashboard after each AI task turn finishes: the script gets the final agentState (result/idle/error),
    // otherwise the panel state stays stuck at "generating". busy→not-busy happens once per turn (setting Idle after Result is a
    // not-busy→not-busy transition and does not re-trigger), so the refresh fired here lands exactly on the final state.
    var lastAgentStateForPanel by remember { mutableStateOf<AgentUIState?>(null) }
    LaunchedEffect(agentState) {
        val prev = lastAgentStateForPanel
        lastAgentStateForPanel = agentState
        val wasBusy = prev is AgentUIState.Loading || prev is AgentUIState.Streaming
        val nowDone = agentState !is AgentUIState.Loading && agentState !is AgentUIState.Streaming
        if (wasBusy && nowDone) {
            val provider = latestActiveProvider ?: return@LaunchedEffect
            if (provider.dashboardScriptPath.isBlank()) return@LaunchedEffect
            val context = latestBuildDashboardContext(0, 0, 0, "done")
            settingsViewModel?.refreshProviderDashboard(provider, context = context, force = true)
        }
    }

    LaunchedEffect(activeProvider?.type, activeModel) {
        val provider = activeProvider ?: return@LaunchedEffect
        if (activeModel.isNotBlank()) {
            settingsViewModel?.resolveModelMetadata(provider.id, provider.type, listOf(activeModel))
        }
    }

    // The home model selection sheet reuses ModelSelectionSheet, whose capability tags depend on full model metadata;
    // resolving only the active model would leave never-selected models without tags (the settings page already loads everything; the home page fills the gap).
    // providers load asynchronously and are empty at first composition, so resolve once non-empty (later provider changes also trigger this; cheap thanks to caching).
    LaunchedEffect(providers) {
        if (providers.isNotEmpty()) {
            settingsViewModel?.loadAllModelMetadata()
        }
    }

    fun removePendingAttachment(index: Int) {
        pendingAttachments = pendingAttachments.filterIndexed { i, _ -> i != index }
    }

    fun handlePickedAttachments(uris: List<Uri>, images: Boolean) {
        if (uris.isEmpty()) return
        if (projectRoot.isBlank()) {
            Toast.makeText(context, emptyWorkspaceMessage(context), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasAttachmentSlots(pendingAttachments.size)) {
            Toast.makeText(context, maxAttachmentMessage(context, MAX_PENDING_ATTACHMENTS), Toast.LENGTH_SHORT).show()
            return
        }
        val selected = selectedAttachments(uris, pendingAttachments.size)
        scope.launch {
            var successCount = 0
            val failures = mutableListOf<String>()
            uploadingCount = selected.size
            try {
                selected.forEach { uri ->
                    runCatching {
                        copyUriToWorkspace(context, uri, viewModel.fileAccess, includeImageData = images)
                    }.onSuccess { uploaded ->
                        pendingAttachments = pendingAttachments + uploaded.toPendingAttachment()
                        successCount += 1
                    }.onFailure { error ->
                        failures += (error.message ?: uploadFallbackError(context))
                    }
                }
            } finally {
                uploadingCount = 0
            }
            // Result toast: all failed shows the first error; files truncated by the cap or failed uploads use the partial wording; full success uses the success wording.
            val skipped = uris.size - selected.size
            when {
                successCount == 0 && failures.isNotEmpty() ->
                    Toast.makeText(context, failures.first(), Toast.LENGTH_LONG).show()
                skipped > 0 || failures.isNotEmpty() ->
                    Toast.makeText(context, partialUploadMessage(context, successCount), Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(context, uploadSuccessMessage(context, successCount), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        handlePickedAttachments(uris, images = false)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        handlePickedAttachments(uris, images = true)
    }

    // Take photo: output to a cache temp file (FileProvider-authorized uri); after shooting it is handled as an image attachment.
    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraPhotoUri
        cameraPhotoUri = null
        if (success && uri != null) {
            handlePickedAttachments(listOf(uri), images = true)
        }
    }
    fun takePhoto() {
        val photoFile = File(context.cacheDir, "camera_${System.currentTimeMillis()}.jpg")
        val uri = runCatching {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
        }.getOrNull()
        if (uri == null) {
            Toast.makeText(context, context.getString(R.string.chat_camera_file_failed), Toast.LENGTH_SHORT).show()
            return
        }
        cameraPhotoUri = uri
        takePictureLauncher.launch(uri)
    }

    // The state computation for streaming text/reasoning buffer retention, persisted-in-place detection (isAssistantSettled), typewriter
    // advancement and tail rendering is sunk entirely into the LazyColumn tail item (key="__active__") for execution:
    // streamingText / streamingReasoning take a new value on every output chunk; if read during this function's composition,
    // every token the model emits recomposes all of AIChatPanel (including ChatHeader, the LazyColumn parameter area, and every itemContent
    // registration/comparison) — the main amplifier of dropped frames during streaming. Sunk, invalidation is confined to the tail
    // item's single itemContent; typewriter progress is backed by rememberSaveable inside it, so scrolling out and back resumes typing.
    // (The snapshotFlow { Triple(streamingText?.length, ...) } in auto-scroll following does not subscribe to the composition scope;
    // it is a safe read and stays where it is.)

    // Auto-scroll following
    // Both states must be saveable: opening the editor / terminal / Git / settings in a narrow window is a fullscreen route that disposes the
    // whole chat composition. With plain remember, on return positionedSession resets to null (treated as a session switch, re-pinned to bottom) and
    // followBottom resets to true (the calibration loop drags the restored position back to the bottom), losing the browsed history position.
    var positionedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var followBottom by rememberSaveable { mutableStateOf(true) }
    var userScrollGesture by remember { mutableStateOf(false) }
    var pointerDown by remember { mutableStateOf(false) }
    var nestedFling by remember { mutableStateOf(false) }
    val userScrollConnection = remember(listState) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userScrollGesture = true
                    followBottom = false
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                nestedFling = true
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                nestedFling = false
                return Velocity.Zero
            }
        }
    }

    // The key must include inputBarReservePx: the closure captures the value at creation, and a keyless remember would freeze
    // the check at the first frame's fallback reserve (156dp), still using the stale safe area after panel expansion raises the floating layer.
    val isAtBottom by remember(inputBarReservePx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            // When the list is not yet measured (first frame, or the LazyColumn unmounted while messages load), layoutInfo is empty and
            // canScrollForward is always false. "At bottom" must not be judged then — the first frame back from another page would
            // restore following and drag the just-restored scroll position of listState back to the bottom.
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf true
            val lastIndex = layout.totalItemsCount - 1
            // At bottom = the last content bottom stops near the safe area (the floating layer's top edge), not the viewport bottom:
            // stricter, so a user who drags away a little is not judged "at bottom", re-enables following, and gets instantly yanked back.
            val safeBottom = layout.viewportEndOffset - inputBarReservePx
            lastVisible.index >= lastIndex &&
                (lastVisible.offset + lastVisible.size) <= safeBottom + AUTO_SCROLL_TOLERANCE_PX
        }
    }

    LaunchedEffect(pointerDown, userScrollGesture, nestedFling, listState.isScrollInProgress) {
        if (!pointerDown && userScrollGesture && !nestedFling && !listState.isScrollInProgress) {
            delay(150)
            userScrollGesture = false
            followBottom = isAtBottom
        }
    }

    // Scroll-to-bottom button visibility threshold: "not at bottom" alone would count the frame or two of streaming growth (before the
    // calibration loop pulls the viewport back) as away-from-bottom, making the button flicker. Require more than half a viewport away; the button appears only when the user truly scrolled up into history.
    // messagesReady / messages.isEmpty() and totalItemsCount are two sides of the same coin: layoutInfo is the
    // product of the "last layout pass" and is not auto-cleared when the LazyColumn unmounts (empty-session WelcomeState, loading placeholder) —
    // after browsing history in an old session then switching to an empty one, the leftover layout would leave the button hovering over the new session. totalItemsCount also intercepts
    // the frame where "the new list has not yet been re-measured for the current messages" (before layout starts, layoutInfo is still the old session's).
    val isFarFromBottom by remember(inputBarReservePx, messagesReady, messages.size, chatItems.size) {
        derivedStateOf {
            if (!messagesReady || messages.isEmpty()) return@derivedStateOf false
            if (!listState.canScrollForward) return@derivedStateOf false
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
                ?: return@derivedStateOf false
            if (layout.totalItemsCount != chatItems.size + 1) return@derivedStateOf false
            if (lastVisible.index < layout.totalItemsCount - 1) return@derivedStateOf true
            val safeBottom = layout.viewportEndOffset - inputBarReservePx
            (lastVisible.offset + lastVisible.size) - safeBottom > layout.viewportEndOffset / 2
        }
    }

    // User starts dragging: stop following. On release, restore following if already at the bottom.
    // Extra: during streaming the content keeps growing, so after release the user may get "pushed" off the bottom again —
    // snapshotFlow { isAtBottom } monitors continuously and restores following whenever the bottom is reached,
    // satisfying "scroll to the bottom during streaming to resume following automatically".
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> followBottom = false
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    // Delay the restore-following decision after release: let inertial scrolling settle,
                    // so "released at the bottom but flung upward" is not immediately dragged back.
                    scope.launch {
                        delay(150)
                        if (!userScrollGesture) followBottom = isAtBottom
                    }
                }
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom && !userScrollGesture }.collect { atBottom ->
            if (atBottom) followBottom = true
        }
    }

    // Bottom-pinned positioning (sending a message, switching sessions): scroll straight to the anchor — scrollToItem's target offset places
    // the last item's bottom exactly above the floating layer (contentPadding reserves the reserve; scrolling there is the list's
    // maximum scroll, with no reliance on animation or gradual alignment).
    val snapToBottom: suspend () -> Unit = {
        val lastIndex = listState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) {
            // Scroll to the list's maximum scroll: scrollToItem's offset is clamped to maxScroll,
            // so the last item's bottom stops exactly at the contentPadding bottom (= the floating-layer top reserve); no manual item-height math needed.
            listState.scrollToItem(lastIndex, Int.MAX_VALUE)
        }
    }

    // Anchor-based standing calibration loop: the anchor = the last content bottom stopping exactly at the floating layer's (input bar's) top edge.
    // scrollToItem(last item, Int.MAX_VALUE) is clamped by LazyColumn to the maximum scroll
    // (guaranteed by the contentPadding bottom reserve), i.e. the last item's bottom stops at the floating layer's top edge —
    // mathematically true at any moment: with enough messages, the last message never falls below the input bar,
    // and it does not rely on the "last visible item == last item" height assumption (height jumps never mis-target either).
    // Check the last visible item every frame: when the last content is pushed down by growth (bottom past the safe area) or content is pushed below the viewport
    // (last visible item is not the last item, i.e. tracking lost), scroll back to the anchor; md async-parsing height jumps are also
    // detected on the next frame — no gap between signal and rendering.
    // Calibrate downward only: when content shrinks (streaming ends, collapsing) keep the current position, avoiding "rolling back" and sawtoothing.
    // reserve is passed via State: the lambda below is created once; directly capturing a local Int would keep using the first frame's fallback value.
    val reservePxState = rememberUpdatedState(inputBarReservePx)
    val busyState = rememberUpdatedState(isBusy)
    val calibrateToAnchor: suspend () -> Unit = remember(listState) {
        {
            // No room to scroll down (content shorter than a screen or already at the anchor): the last content is necessarily above the safe area, no calibration needed.
            if (followBottom && !userScrollGesture && listState.canScrollForward) {
                val layout = listState.layoutInfo
                val lastIndex = layout.totalItemsCount - 1
                if (lastIndex >= 0) {
                    val lastVisible = layout.visibleItemsInfo.lastOrNull()
                    val safeBottom = layout.viewportEndOffset - reservePxState.value
                    // The last item was pushed below the viewport (tracking lost) or the last content bottom crossed the safe area: scroll back to the anchor.
                    // When the user is browsing elsewhere, followBottom is already false and this path is not reached.
                    val lost = lastVisible == null || lastVisible.index < lastIndex
                    val pushedDown = lastVisible != null &&
                        lastVisible.offset + lastVisible.size > safeBottom + AUTO_SCROLL_TOLERANCE_PX
                    if (lost || pushedDown) listState.scrollToItem(lastIndex, Int.MAX_VALUE)
                }
            }
        }
    }

    // Calibrate frame by frame only while "following and the content may still be moving". It used to be an unconditional while(true): with followBottom
    // false it merely continued, frame callbacks still registered — waking the main thread every frame forever (burning power even when idle).
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow { followBottom && (busyState.value || listState.isScrollInProgress) }
            .collectLatest { active ->
                if (active) {
                    while (true) {
                        withFrameNanos { }
                        calibrateToAnchor()
                    }
                } else {
                    // The content may not be stable the moment work finishes (md async parsing often lags); keep covering a short tail before stopping.
                    val deadline = System.nanoTime() + CALIBRATE_TAIL_MS * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        withFrameNanos { }
                        calibrateToAnchor()
                    }
                }
            }
    }

    // Content-change signal bypass: calibrate immediately when text/reasoning/message count changes, without waiting for the next frame —
    // complementing the standing calibration loop by covering "no animation frame" gaps and eliminating tracking-loss windows.
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow {
            Triple(streamingText?.length, streamingReasoning?.length, messages.size)
        }.collect { calibrateToAnchor() }
    }

    val sendMessage: () -> Unit = {
        val text = inputText.trim()
        if (text.isNotEmpty() || pendingAttachments.isNotEmpty()) {
            val attachments = pendingAttachments
            val modelSupportsVision = activeModelMetadata?.supportsVision == true
            val promptAttachments = if (modelSupportsVision) attachments.filterNot { it.isImage } else attachments
            val modelRequest = appendAttachmentsToRequest(context, text, promptAttachments)
            val images = if (modelSupportsVision) attachments.toAgentImages() else emptyList()
            // Everything goes through the queue: enqueue while the AI is busy (auto-sends the next after this turn ends), send directly when idle.
            // Slash commands are dispatched inside the ViewModel (before the agent workflow), no need to distinguish here.
            viewModel.enqueueAgentRequest(
                request = text,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = images,
                inputAttachments = attachments.toAgentAttachments()
            )
            inputText = ""
            viewModel.clearInputDraft()
            pendingAttachments = emptyList()
            followBottom = true
            scope.launch {
                kotlinx.coroutines.delay(0)
                snapToBottom()
            }
        }
    }

    // Session switch / open search hit: locate the target message first, otherwise pin to the bottom and restore following.
    LaunchedEffect(currentSessionId, messagesReady, pendingScroll, chatItems) {
        if (!messagesReady) return@LaunchedEffect
        val pending = pendingScroll
        if (pending != null && pending.first == currentSessionId) {
            val index = chatItems.indexOfFirst { it.message.id == pending.second }
            if (index >= 0) {
                // Wait one frame for the LazyColumn to recompose for the new session, then jump straight to the target message.
                withFrameNanos { }
                followBottom = false
                listState.scrollToItem(index)
                positionedSession = currentSessionId
                viewModel.consumePendingScroll()
            } else if (!messagesState.hasMore) {
                // All messages loaded but still not found (message may have been deleted): give up to avoid being stuck in the pending-locate state.
                viewModel.consumePendingScroll()
            }
            return@LaunchedEffect
        }
        if (positionedSession != currentSessionId) {
            // Wait one frame for the LazyColumn to recompose for the new session, then scroll to the anchor (maxScroll).
            withFrameNanos { }
            snapToBottom()
            positionedSession = currentSessionId
            followBottom = true
        }
    }

    // After expanding/collapsing an item (tool card, tool group header), **do no active scrolling at all**: expand in place, collapse in place,
    // viewport perfectly still; only pause bottom-pinned following (restored automatically by the isAtBottom monitor when the user scrolls back to the bottom).
    //
    // This used to reposition the viewport so "the item's bottom clears the safe area", plus a fallback of "scrollToItem when the item is entirely invisible";
    // for a card taller than a screen the former's computed target got clamped to 0 and the latter simply pushed the item to the viewport top — both paths made
    // the tapped row jump wholesale to the top (sporadic, depending on which frame the layout had settled on at the tap). That jump is far harder to accept than
    // "the expanded bottom being slightly covered by the input bar", so the whole repositioning logic was removed.
    val onToolItemToggled: () -> Unit = { followBottom = false }

    // Load more history when nearing the top. Never use firstVisibleItemIndex as an effect key read during composition:
    // during a fling the index changes every time it crosses an item, recomposing the whole big AIChatPanel function and with it every visible
    // item — the main amplifier of fast-scroll jank (and in turn fling-compensation jumps and stutter). Instead, observe
    // the index with snapshotFlow inside a coroutine; the composition never reads it.
    val latestMessagesState = rememberUpdatedState(messagesState)
    LaunchedEffect(listState, messagesReady) {
        if (!messagesReady) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                val ms = latestMessagesState.value
                if (index <= 3 && ms.hasMore && !ms.isLoadingMore) {
                    viewModel.loadMoreMessages()
                }
            }
    }

    val executionMode = settingsViewModel?.executionMode?.collectAsStateWithLifecycle()?.value
    val connectionState = settingsViewModel?.connectionState?.collectAsStateWithLifecycle()?.value
    val isRemote = executionMode == com.xencode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH

    val markdownImageTransformer = remember(viewModel.fileAccess) {
        MarkdownImageTransformer(viewModel.fileAccess)
    }
    val imageViewerState = rememberImageViewerState()
    val viewerDecodeSpec = rememberViewerDecodeSpec()
    val chatImageLoad = remember(viewModel.fileAccess, viewerDecodeSpec) {
        chatImageLoader(viewModel.fileAccess, viewerDecodeSpec.maxEdge, viewerDecodeSpec.maxPixels)
    }

    // File cards open with the system app: re-fetching/copying must run on the IO thread (remote mode downloads), hence the coroutine injected here.
    val attachmentOpener = remember(viewModel.fileAccess, context) {
        AttachmentOpener { attachment ->
            scope.launch { openSentAttachment(context, attachment, viewModel.fileAccess) }
        }
    }

    // Renders one item. The expanded content of turn headers / tool groups and the collapsed header expand as a whole with AnimatedVisibility
    // inside the same Composable (instead of per-list-item placement animations): the height opens smoothly, the content moves along — feels like one body.
    @Composable
    fun RenderChatItem(item: ChatRenderItem) {
        val header = item.turnHeader
        val group = item.toolGroup
        when {
            header != null -> {
                TurnCollapseHeader(
                    running = header.running,
                    durationMs = header.endMessageId?.let { taskDurations[it] },
                    expanded = header.expanded,
                    onToggle = {
                        viewModel.setTurnExpanded(header.key, !header.expanded)
                        onToolItemToggled()
                    }
                )
                AnimatedVisibility(
                    visible = header.expanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        item.turnProcess.forEach { child -> RenderChatItem(child) }
                    }
                }
            }
            item.contentType == REASONING_CONTENT_TYPE -> {
                // The assistant's reasoning is extracted as a process item: expansion state is persisted by the host and survives scrolling out of the viewport or page switches.
                ReasoningBubble(
                    text = item.message.reasoning.orEmpty(),
                    cache = markdownCache,
                    durationMs = reasoningDurations[item.message.id],
                    expandedOverride = reasoningOverrideSnapshot[item.message.id],
                    onExpandedChange = { viewModel.setReasoningExpanded(item.message.id, it) }
                )
            }
            group != null -> {
                ToolCallGroupHeader(
                    count = group.size,
                    running = group.any { it.id in runningToolIds || it.isToolRunning(null) },
                    expanded = item.groupExpanded,
                    onToggle = {
                        viewModel.setToolExpanded(item.key, !item.groupExpanded)
                        onToolItemToggled()
                    }
                )
                AnimatedVisibility(
                    visible = item.groupExpanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column {
                        group.forEach { member ->
                            // derivedStateOf isolates by id: live tool output swaps in a new List on every chunk;
                            // reading it directly invalidates and reruns every visible item's composition. Wrapped, recomputation affects only
                            // the single item whose text actually changed (the tool row currently streaming).
                            val live by remember(member.id) {
                                derivedStateOf { runningTool.firstOrNull { it.messageId == member.id }?.text }
                            }
                            AgentMessageItem(
                                message = member,
                                showActions = member.id in actionableMessageIds,
                                liveOutput = live,
                                markdownCache = markdownCache,
                                onRewindClick = { viewModel.openRewindMenu(it) },
                                onMoreClick = { messageForMenu = it },
                                toolExpandedOverride = toolGroupOverrideSnapshot[member.id],
                                onToolExpandedChange = { isExpanded -> viewModel.setToolExpanded(member.id, isExpanded) },
                                onToolToggle = onToolItemToggled,
                                // When a group is expanded, member rows indent one level to show hierarchy versus the group header
                                contentPadding = ToolGroupMemberPadding,
                                entryDelayMs = messageEntryDelays[member.id]
                            )
                        }
                    }
                }
            }
            else -> {
                val message = item.message
                // Same as above: isolate the live-output subscription by id, so runningTool's per-chunk updates
                // don't recompose every visible message item (the source of recomposition storms while streaming).
                val live by remember(message.id) {
                    derivedStateOf { runningTool.firstOrNull { it.messageId == message.id }?.text }
                }
                AgentMessageItem(
                    message = message,
                    showActions = message.id in actionableMessageIds,
                    liveOutput = live,
                    markdownCache = markdownCache,
                    contentSlice = item.slice,
                    isChunkHeader = item.isChunkHeader,
                    isChunkFooter = item.isChunkFooter,
                    reasoningVisible = item.reasoningVisible,
                    onRewindClick = { viewModel.openRewindMenu(it) },
                    onMoreClick = { messageForMenu = it },
                    toolExpandedOverride = toolGroupOverrideSnapshot[message.id],
                    onToolExpandedChange = { isExpanded -> viewModel.setToolExpanded(message.id, isExpanded) },
                    onToolToggle = onToolItemToggled,
                    reasoningDurationMs = reasoningDurations[message.id],
                    taskDurationMs = taskDurations[message.id],
                    turnUsage = turnUsages[message.id],
                    entryDelayMs = messageEntryDelays[message.id]
                )
            }
        }
    }

    // Liquid Glass: this is the page's only backdrop layer, shared by the title bar / input area / todo overlay,
    // avoiding multiple fullscreen captures dragging down GPU and memory.
    val glassLayer = rememberChatGlassLayer()
    val glassLuminance = rememberGlassLuminance(glassLayer)
    // The floating title bar no longer takes layout height; the message list's contentPadding reserves it instead:
    // status bar height + title bar content height (remote sessions add one more connection-status row).
    val density = LocalDensity.current
    val topReserveDp = with(density) {
        WindowInsets.statusBars.getTop(density).toDp()
    } + CHAT_HEADER_HEIGHT_DP.dp + (if (isRemote && connectionState != null) CHAT_HEADER_CONNECTION_ROW_DP.dp else 0.dp)

    CompositionLocalProvider(
        LocalMarkdownImageTransformer provides markdownImageTransformer,
        LocalImageViewer provides imageViewerState,
        LocalAttachmentOpener provides attachmentOpener
    ) {
        Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
        // Large screens cap and center the body column: filling the whole tablet width makes text lines too long and bubbles span the screen — exhausting to read.
        // In a narrow window readableContentMaxWidth() returns Dp.Unspecified and widthIn imposes no constraint.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = readableContentMaxWidth())
                .fillMaxSize()
        ) {
            // Content layer: the message list extends to the screen bottom with the input bar floating above it; while scrolling, cards can slide in behind the input bar.
            // The single backdrop sample: the glass of the title bar / input area / todo overlay all reads this layer,
            // avoiding multiple simultaneous fullscreen backdrop captures.
            Column(modifier = Modifier.fillMaxSize().glassBackdrop(glassLayer)) {
            Box(modifier = Modifier.weight(1f)) {
                if (!messagesReady) {
                    // Show a connection-status placeholder while the remote connection is not ready, avoiding a blank flash or stale workspace records
                    if (isRemote && connectionState != null && connectionState != com.xencode.feature.agent.domain.container.ConnectionState.CONNECTED) {
                        RemoteConnectingPlaceholder(state = connectionState)
                    } else if (showMessagesLoading) {
                        // Placeholder for slower local DB reads: this used to draw nothing, and switching sessions flashed blank first.
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else if (messages.isEmpty()) {
                    WelcomeState(
                        bottomReserve = with(LocalDensity.current) { inputBarReservePx.toDp() },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .nestedScroll(userScrollConnection)
                            .pointerInput(listState) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    pointerDown = true
                                    try {
                                        do {
                                            val event = awaitPointerEvent(PointerEventPass.Final)
                                        } while (event.changes.any { it.pressed })
                                    } finally {
                                        pointerDown = false
                                    }
                                }
                            },
                        contentPadding = PaddingValues(
                            start = Spacing.lg,
                            end = Spacing.lg,
                            // On the first screen, let the first message sit below the floating title bar (and the below-title-bar todo overlay);
                            // after scrolling, content passes behind the glass
                            top = topReserveDp + Spacing.md +
                                (if (todoOverlayVisible) CHAT_TODO_BAR_RESERVE_DP.dp else 0.dp),
                            bottom = with(LocalDensity.current) { inputBarReservePx.toDp() }
                        )
                    ) {
                        itemsIndexed(chatItems, key = { _, item -> item.key }, contentType = { _, item -> item.contentType }) { _, item ->
                            RenderChatItem(item)
                        }
                        // Tail item: the reasoning bubble and status tail are merged into this one permanently mounted item; neither is added/removed by state.
                        // If reasoning or streaming start/stop made totalItemsCount jump up/down, LazyColumn would
                        // clamp firstVisibleItemIndex downward → the viewport jumps up (root cause of old symptom 2). With the item count constant at 1,
                        // the anchor is never clamped: render the reasoning bubble when showReasoning (with its own folding), otherwise nothing;
                        // when tailKind is NONE the tail is an empty Box (0 height). After the stream ends and is persisted, the follow effect pins the new message to the bottom.
                        //
                        // Streaming state (streamingText / streamingReasoning / retained buffers / persisted-in-place detection /
                        // typewriter advancement / tailKind) is all computed and subscribed inside this itemContent: these values change with every output
                        // chunk; if read in the AIChatPanel function body or the LazyListScope content, every token the
                        // model emits would recompose the whole panel (the main amplifier of dropped frames during streaming); converged here, invalidation
                        // covers only this item and every other item skips.
                        item(key = "__active__", contentType = "tail") {
                            // Buffer retention: during the window after streaming ends but before the database finishes dispatching (messages still ends with
                            // USER/TOOL messages), keep holding this turn's full text and reasoning to prop up the bottom bubble, completely eliminating
                            // "text nearly finished then the whole block suddenly evaporates (blank flash-back)" or "the reasoning block vanishes then pops back";
                            // clear them the moment messages formally accepts this turn's assistant message at the end.
                            // rememberSaveable: retained survives this item being disposed after scrolling out of the viewport and is still there when scrolled back.
                            var retainedStreamingText by rememberSaveable { mutableStateOf<String?>(null) }
                            var retainedStreamingReasoning by rememberSaveable { mutableStateOf<String?>(null) }
                            LaunchedEffect(currentSessionId) {
                                retainedStreamingText = null
                                retainedStreamingReasoning = null
                            }
                            val lastMsg = messages.lastOrNull()
                            // Whether this turn's assistant message is formally in place in the message list:
                            // only when the trailing message is ASSISTANT and the body and reasoning prefixes match does it mean this turn's output has been persisted into the messages list
                            val isAssistantSettled = lastMsg?.role == MessageRole.ASSISTANT && run {
                                val currentText = streamingText ?: retainedStreamingText
                                val currentReasoning = streamingReasoning ?: retainedStreamingReasoning
                                val textSettled = if (currentText.isNullOrBlank()) {
                                    true
                                } else {
                                    val prefix = currentText.trimStart().take(20)
                                    prefix.isEmpty() || lastMsg.content.trimStart().startsWith(prefix)
                                }
                                val reasoningSettled = if (currentReasoning.isNullOrBlank()) {
                                    true
                                } else {
                                    val prefix = currentReasoning.trimStart().take(20)
                                    prefix.isEmpty() || (lastMsg.reasoning?.trimStart()?.startsWith(prefix) == true)
                                }
                                textSettled && reasoningSettled
                            }
                            LaunchedEffect(streamingText, isAssistantSettled) {
                                val st = streamingText
                                if (st != null && st.hasVisibleContent()) {
                                    retainedStreamingText = st
                                } else if (isAssistantSettled) {
                                    retainedStreamingText = null
                                }
                            }
                            LaunchedEffect(streamingReasoning, isAssistantSettled) {
                                val sr = streamingReasoning
                                if (sr != null && sr.hasVisibleContent()) {
                                    retainedStreamingReasoning = sr
                                } else if (isAssistantSettled) {
                                    retainedStreamingReasoning = null
                                }
                            }
                            val displayStreamingText = if (isAssistantSettled) null else (streamingText ?: retainedStreamingText)
                            val showStreaming = displayStreamingText?.hasVisibleContent() == true

                            val displayStreamingReasoning = if (isAssistantSettled) null else (streamingReasoning ?: retainedStreamingReasoning)
                            val reasoning = displayStreamingReasoning
                            val showReasoning = reasoning?.hasVisibleContent() == true

                            // Typewriter render progress: the progress is backed by the rememberSaveable inside
                            // rememberTypewriterStreamingText, so scrolling out of the viewport and back resumes typing. Hold the whole object:
                            // the settled flag tells MarkdownContent "still typing" → parse throttling + no cache writes.
                            val typewriter = rememberTypewriterStreamingText(
                                text = displayStreamingText ?: "",
                                active = streamingText != null,
                                sessionKey = currentSessionId
                            )
                            val typewriterRenderText = typewriter.text
                            // The reasoning goes through the typewriter too: it shares the same speed-adaptive logic as the reply text.
                            // Body output starting (streamingText non-null) marks the end of reasoning: the reasoning typewriter wraps up immediately,
                            // so half-typed reasoning and already-started body don't both keep typing slowly on top of each other.
                            val typewriterReasoningText = rememberTypewriterStreamingText(
                                text = displayStreamingReasoning ?: "",
                                active = streamingReasoning != null && streamingText == null,
                                sessionKey = currentSessionId
                            ).text

                            // Busy indicator: shown when there is no body/reasoning streaming, no compaction, and no permission dialog or question pending.
                            // While a tool is running or the model is preparing one, thinkingLabel dynamically renders the matching scenario (e.g. "Reading file");
                            // with neither streaming nor a concrete tool, fall back to "Thinking".
                            val showThinking = !showReasoning && !showStreaming && !isCompacting && isBusy && pendingPermission == null && pendingQuestion == null
                            val showRetrying = retryState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                            val showKeySwitched = keySwitchState != null && isBusy && !isCompacting && !showStreaming && !showReasoning
                            val tailKind = when {
                                showStreaming -> TailKind.STREAMING
                                isCompacting -> TailKind.COMPACTING
                                showKeySwitched -> TailKind.KEY_SWITCHED
                                showRetrying -> TailKind.RETRYING
                                showThinking -> TailKind.THINKING
                                else -> TailKind.NONE
                            }
                            Column {
                                if (showReasoning) {
                                    // Streaming live: collapsed by default, the collapsed row scrolls with the line being written; tap to see the full text
                                    val reasoningLive = reasoningTiming != null && reasoningTiming?.second == null &&
                                        !showStreaming && preparingTool == null
                                    ReasoningBubble(
                                        text = typewriterReasoningText,
                                        cache = markdownCache,
                                        showTimer = reasoningLive,
                                        durationMs = reasoningTiming?.let { (start, end) -> end?.minus(start) },
                                        timerStartMs = reasoningTiming?.first,
                                        preRendered = true,
                                        sessionKey = currentSessionId,
                                        live = reasoningLive,
                                    )
                                }
                                when (tailKind) {
                                    TailKind.THINKING -> ThinkingBubble(label = thinkingLabel)
                                    TailKind.STREAMING -> StreamingBubble(
                                        text = typewriterRenderText,
                                        cache = markdownCache,
                                        // Typewriter not caught up = streaming: Markdown parsing is increment-throttled and intermediate snapshots stay out of the LRU
                                        streaming = !typewriter.settled
                                    )
                                    TailKind.COMPACTING -> CompactionProgressBubble()
                                    TailKind.RETRYING -> {
                                        val rs = retryState
                                        if (rs != null) RetryingBubble(rs.attempt, rs.maxRetries, rs.error) else Box(Modifier)
                                    }
                                    TailKind.KEY_SWITCHED -> {
                                        val ks = keySwitchState
                                        if (ks != null) KeySwitchedBubble(ks.newIndex, ks.total) else Box(Modifier)
                                    }
                                    TailKind.NONE -> Box(Modifier)
                                }
                            }
                        }
                    }
                }
            }
            } // end of content layer

            // Task todos (display position = below the title bar): a "floating glass layer" like the title bar, same width and horizontal margins,
            // hugging right under the title bar. Default is a one-line summary (x/y done + in-progress tasks); expanded, it overlays the messages,
            // no longer taking layout height in the message flow (the old version was embedded in the content layer: it couldn't stretch the glass and its width didn't align with the title bar).
            if (todoBelowTitleBar && currentTodoItems.isNotEmpty()) {
                TodoDashboardBar(
                    items = currentTodoItems,
                    sessionId = currentSessionId.orEmpty(),
                    // Mutual-exclusion collapse only collapses "the others": collapse the todo when a permission/question/plan panel appears or the keyboard comes up.
                    // Never include our own todoExpanded in forceCollapse — that would create an
                    // "expand → report → force-collapse → reset → expand" dead loop (symptoms: can't expand, frantic flicker when returning to the page).
                    forceCollapse = pendingPermission != null || pendingQuestion != null ||
                        planApproval != null || imeVisible,
                    onExpandedChange = { todoExpanded = it },
                    glassBackdrop = glassLayer,
                    glassLuminance = { glassLuminance.luminance },
                    glassContentColor = glassLuminance.contentColor,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .widthIn(max = readableContentMaxWidth())
                        .padding(start = Spacing.xs, end = Spacing.xs, top = topReserveDp)
                )
            }

            // Floating glass title bar: no longer the Scaffold's topBar (that would permanently deduct a block of height),
            // it overlays the message list instead; the list only uses contentPadding to reserve first-screen space, and content passes behind the glass while scrolling.
            ChatHeader(
                sessionTitle = sessionTitle,
                modelName = activeProvider?.effectiveModel,
                inputTokens = sessionInputTokens,
                outputTokens = sessionOutputTokens,
                onOpenDrawer = {
                    keyboardController?.hide()
                    onOpenDrawer()
                },
                onNewChat = { viewModel.newSession() },
                onNavigateToTerminal = onNavigateToTerminal,
                onNavigateToGit = onNavigateToGit,
                onNavigateToBrowser = onNavigateToBrowser,
                currentMode = currentMode,
                onToggleMode = { viewModel.setSessionMode(it) },
                connectionState = connectionState?.takeIf { isRemote },
                showMenuButton = showMenuButton,
                terminalActive = terminalActive,
                gitActive = gitActive,
                browserActive = browserActive,
                glassBackdrop = glassLayer,
                glassLuminance = { glassLuminance.luminance },
                glassContentColor = glassLuminance.contentColor,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = readableContentMaxWidth())
            )

            // Floating layer: error bubble / panels / input bar (the mask is inside ChatInputBar and follows the keyboard up)
            val floatingPanelAlpha by animateFloatAsState(
                targetValue = if (listState.isScrollInProgress) 0.4f else 1f,
                animationSpec = tween(200),
                label = "floating-panel-alpha"
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    // Use the measured floating layer height as the scroll reserve so no banner/panel/input-bar configuration ever covers the last message
                    .onGloballyPositioned { if (it.size.height > 0) floatingLayerHeightPx = it.size.height }
            ) {
            Box(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = floatingPanelAlpha }) {
                StatusBanner(state = agentState)
            }

            // During the exit animation uploadingCount is already zero; reading it directly would fade out an "Uploading 0 files" —
            // like StatusBanner, hold the exit with the last non-null remembered value.
            val lastUploadingCount = rememberLastNonNull(uploadingCount.takeIf { it > 0 })
            AnimatedVisibility(
                visible = uploadingCount > 0,
                modifier = Modifier.graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                lastUploadingCount?.let { UploadingBanner(count = it) }
            }

            val questionForPanel = rememberLastNonNull(pendingQuestion)
            AnimatedVisibility(
                visible = pendingQuestion != null,
                modifier = Modifier.graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                questionForPanel?.let { question ->
                    AskUserQuestionPanel(
                        question = question,
                        onConfirm = { answer -> viewModel.resolveUserQuestion(question.id, answer) },
                        onSkip = { viewModel.resolveUserQuestion(question.id, UserQuestionAnswer(emptyList())) },
                        forceCollapse = dashboardCollapseActive
                    )
                }
            }

            val planForPanel = rememberLastNonNull(planApproval)
            AnimatedVisibility(
                visible = planApproval != null,
                modifier = Modifier.graphicsLayer { alpha = floatingPanelAlpha },
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                planForPanel?.let { state ->
                    PlanApprovalPanel(
                        state = state,
                        onApprove = { viewModel.approvePlanAndBuild() },
                        onRefine = { viewModel.refinePlan() },
                        forceCollapse = dashboardCollapseActive
                    )
                }
            }

            ChatInputBar(
                value = inputText,
                onValueChange = { inputText = it; viewModel.updateInputDraft(it) },
                onSend = sendMessage,
                enterToSend = settingsViewModel?.enterToSend?.collectAsStateWithLifecycle()?.value ?: false,
                onStop = { viewModel.stopAgent() },
                isBusy = isBusy,
                workspaceViewModel = workspaceViewModel,
                hasRunningSessions = { viewModel.hasRunningSessionsInCurrentWorkspace() },
                onSwitchWorkspaceConfirmed = { viewModel.stopAllAndCloseTerminal() },
                activeProvider = activeProvider,
                providers = providers,
                modelMetadata = modelMetadata,
                onSelectModel = { p, m ->
                    viewModel.setSessionProviderModel(p, m)
                },
                currentMode = currentMode,
                onToggleMode = { viewModel.setSessionMode(it) },
                reasoningEffort = reasoningEffort,
                onReasoningEffortChange = { viewModel.setSessionReasoningEffort(it) },
                pendingAttachments = pendingAttachments,
                onRemoveAttachment = ::removePendingAttachment,
                canUploadFiles = canUploadFiles,
                canUploadImages = canUploadImages,
                onUploadFile = { filePicker.launch(arrayOf("*/*")) },
                onUploadImage = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onTakePhoto = ::takePhoto,
                slashCommands = slashCommands,
                queuedRequests = queuedRequests,
                onRemoveQueued = { viewModel.removeQueuedRequest(it) },
                onMoveQueued = { from, to -> viewModel.moveQueuedRequest(from, to) },
                onEditQueued = { queuedEditing = it },
                onInterjectQueued = { viewModel.interjectQueuedRequest(it) },
                dashboardState = currentDashboardState,
                // Only with "above the message bar" are todos rendered by the input bar; below the title bar they render separately above; when off, not at all.
                todoItems = if (todoAboveInputBar) currentTodoItems else emptyList(),
                glassBackdrop = glassLayer,
                glassLuminance = { glassLuminance.luminance },
                sessionId = currentSessionId.orEmpty(),
                onTodoExpandedChange = { todoExpanded = it },
                forceCollapseDashboard = pendingPermission != null || pendingQuestion != null || planApproval != null || imeVisible,
                onDashboardExpandedChange = { dashboardExpanded = it },
                onRefreshDashboard = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "manual")
                        settingsViewModel?.refreshProviderDashboard(it, context = context, force = true)
                    }
                },
                onRefreshDashboardByButton = {
                    activeProvider?.let {
                        val context = buildDashboardContext(refreshReason = "button")
                        settingsViewModel?.refreshProviderDashboard(it, context = context, force = true)
                    }
                },
                tokenEstimated = contextUsages[currentSessionId]?.estimated == true,
                tokenProgress = run {
                    val contextLimit = activeModelMetadata?.let {
                        com.xencode.feature.settings.domain.model.ModelContextPolicy.effectiveInputBudget(it)
                    } ?: 0
                    val usage = contextUsages[currentSessionId]?.takeIf { sessionLastInputTokens > 0 || isBusy }
                    if (contextLimit > 0) {
                        (usage?.inputTokens ?: sessionLastInputTokens).toFloat() / contextLimit
                    } else 0f
                },
                isScrolling = listState.isScrollInProgress,
                forceOpenModelSheet = onboardingStep == OnboardingStep.SIMULATE_CHOOSE_MODEL,
                onSelectModelInOnboarding = onSelectModelInOnboarding,
                onModelSheetDismiss = onDismissModelSheetInOnboarding,
                modifier = Modifier
                    .fillMaxWidth()
                    .onboardingTarget(OnboardingStep.SEND_MESSAGE)
            )
            } // end of floating layer

            // Floating permission dialog: independent of the bottom bar's layout flow, floating above the input bar, so it never inflates the bottom bar and pushes the message list up.
            // It dims translucently while the user scrolls the message list, keeping the reading view clear.
            val permissionForPanel = rememberLastNonNull(pendingPermission)
            var permissionPanelHeightPx by remember { mutableStateOf(0) }
            AnimatedVisibility(
                visible = pendingPermission != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = with(LocalDensity.current) { floatingLayerHeightPx.toDp() } + Spacing.xs)
                    .onGloballyPositioned { permissionPanelHeightPx = if (pendingPermission != null) it.size.height else 0 },
                enter = fadeIn(tween(220)) +
                        slideInVertically(tween(220)) { it / 3 } +
                        scaleIn(initialScale = 0.96f, animationSpec = tween(220)),
                exit = fadeOut(tween(160)) +
                       slideOutVertically(tween(160)) { it / 3 } +
                       scaleOut(targetScale = 0.96f, animationSpec = tween(160))
            ) {
                permissionForPanel?.let { request ->
                    ToolPermissionPanel(
                        request = request,
                        onChoice = { choice -> viewModel.resolveToolPermission(request.id, choice) },
                        sessionTitle = pendingPermissionSessionTitle,
                        forceCollapse = dashboardCollapseActive,
                        isScrolling = listState.isScrollInProgress
                    )
                }
            }

            // Scroll-to-bottom button: floats above the input bar's top-right corner (clearing the bottom bar and any floating permission dialog); shown when
            // more than half a screen from the bottom, regardless of scroll direction — stopping after paging up through history is exactly when one-tap return is needed most; it fades out with the input bar while scrolling.
            val permissionOffsetPx = with(LocalDensity.current) {
                if (pendingPermission != null) permissionPanelHeightPx + Spacing.xs.toPx() else 0f
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = isFarFromBottom,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = Spacing.lg)
                    .padding(bottom = with(LocalDensity.current) { (floatingLayerHeightPx + permissionOffsetPx + FLOATING_LAYER_GAP_DP.toPx()).toDp() })
                    .graphicsLayer { alpha = if (listState.isScrollInProgress) 0.4f else 1f },
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                ScrollToBottomButton(
                    onClick = {
                        followBottom = true
                        scope.launch { snapToBottom() }
                    }
                )
            }

            targetRewindMessageId?.let { targetId ->
                val targetMsg = messages.find { it.id == targetId }
                RewindOptionsBottomSheet(
                    promptSnippet = targetMsg?.content ?: "",
                    onOptionSelected = { option ->
                        viewModel.executeRewindOption(targetId, option) { text, attachments ->
                            inputText = text
                            pendingAttachments = attachments.map { it.toPendingAttachment() }
                        }
                    },
                    onDismissRequest = { viewModel.dismissRewindMenu() }
                )
            }

            messageForMenu?.let { message ->
                val clipboard = LocalClipboard.current
                val copyScope = rememberCoroutineScope()
                MessageActionsBottomSheet(
                    message = message,
                    onDismiss = { messageForMenu = null },
                    onEditClick = { editingMessage = message },
                    onCopyClick = {
                        copyScope.launch {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.content)))
                        }
                    },
                    onDeleteClick = { viewModel.deleteMessage(message.id) }
                )
            }

            editingMessage?.let { message ->
                EditMessageDialog(
                    initialText = message.content,
                    onDismiss = { editingMessage = null },
                    onConfirm = { newContent ->
                        viewModel.updateMessageContent(message.id, newContent)
                        editingMessage = null
                    }
                )
            }

            queuedEditing?.let { req ->
                EditMessageDialog(
                    initialText = req.request,
                    onDismiss = { queuedEditing = null },
                    onConfirm = { newContent ->
                        viewModel.updateQueuedRequest(req.id, newContent)
                        queuedEditing = null
                    }
                )
            }
        }
        }
    }

        // A Dialog is a separate window that takes no space in the parent layout; placed after the Scaffold it covers the whole screen —
        // under the tablet two-pane layout it won't cover only the chat column, nor be covered by the global background watermark MainActivity draws on top.
        ImageViewerHost(state = imageViewerState, load = chatImageLoad)
    }
}

/**
 * Remembers the last non-null value so [AnimatedVisibility]'s exit animation keeps rendering the old content.
 *
 * Held in a plain object rather than [androidx.compose.runtime.MutableState]: all we need is to keep the previous value across recompositions,
 * not to trigger recomposition itself (a source change already recomposes the read point); entering the snapshot system would only cause one extra invalid recomposition.
 */
@Composable
internal fun <T : Any> rememberLastNonNull(value: T?): T? {
    val holder = remember { LastNonNullHolder<T>() }
    if (value != null) holder.value = value
    return holder.value
}

private class LastNonNullHolder<T : Any>(var value: T? = null)

/**
 * Scheduler for tool card entry animations: decides which messages play an entry and how long each is staggered.
 *
 * The criterion is "newly appended at the tail during this scheduler's lifetime", not "timestamp within the last N seconds" —
 * the latter would re-classify the batch still inside the time window as new on returning from another page, replaying the animation.
 * Messages already in the list on first call are all recorded as existing; history paged in from above is older than the max seen timestamp and
 * likewise gets no entry. On session switch the whole scheduler is rebuilt, so the new session's existing messages don't animate either.
 */
private class MessageEntryScheduler {
    private val seen = mutableSetOf<String>()
    private val delays = mutableMapOf<String, Long>()
    private var initialized = false
    private var maxSeenTimestamp = Long.MIN_VALUE

    /** Returns "message id → entry delay (ms)"; messages not in the map display directly. */
    fun schedule(messages: List<AgentUIMessage>): Map<String, Long> {
        if (!initialized) {
            initialized = true
            messages.forEach { seen += it.id }
            maxSeenTimestamp = messages.maxOfOrNull { it.timestamp } ?: Long.MIN_VALUE
            return emptyMap()
        }
        var consecutive = 0
        for (message in messages) {
            if (message.id in seen) {
                consecutive = 0
                continue
            }
            seen += message.id
            val appendedAtTail = message.timestamp >= maxSeenTimestamp
            maxSeenTimestamp = maxOf(maxSeenTimestamp, message.timestamp)
            if (appendedAtTail && message.role == MessageRole.TOOL) {
                delays[message.id] = (consecutive * MESSAGE_ENTRY_STAGGER_MS)
                    .coerceAtMost(MESSAGE_ENTRY_MAX_STAGGER_MS)
                consecutive++
            } else {
                consecutive = 0
            }
        }
        return delays.toMap()
    }
}

/** Scroll-to-bottom button: circular, floating above the input bar's top-right corner, shown when not at the bottom. */
@Composable
private fun ScrollToBottomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
        modifier = modifier
            .size(SCROLL_TO_BOTTOM_BTN_SIZE.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = FeatherIcons.ArrowDown,
                contentDescription = stringResource(R.string.common_scroll_to_bottom),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

