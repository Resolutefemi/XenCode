package com.xencode.feature.agent.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xencode.MainActivity
import com.xencode.R
import com.xencode.core.util.FileLogger
import com.xencode.core.util.GitIgnoreMatcher
import com.xencode.core.util.toUserMessage
import com.xencode.core.util.formatCostUsd
import com.xencode.feature.agent.data.local.dao.AgentMessageDao
import com.xencode.feature.agent.domain.checkpoint.CheckpointManager
import com.xencode.feature.agent.domain.device.RootNetworkGuard
import com.xencode.feature.agent.data.local.dao.CheckpointDao
import com.xencode.feature.agent.data.local.dao.ChatSessionDao
import com.xencode.feature.agent.data.local.dao.LlmCallRecordDao
import com.xencode.feature.agent.data.local.dao.TodoItemDao
import com.xencode.feature.agent.domain.model.TodoItem
import com.xencode.feature.agent.data.local.entity.ChatSessionEntity
import com.xencode.feature.agent.domain.container.ContainerInitState
import com.xencode.feature.agent.domain.container.LinuxContainerEngine
import com.xencode.feature.settings.domain.model.ProviderType
import com.xencode.feature.settings.domain.service.ModelCostCalculator
import com.xencode.feature.settings.domain.repository.AIProviderRepository
import com.xencode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.xencode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.xencode.feature.settings.data.repository.GeneralSettingsRepository
import com.xencode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.xencode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.xencode.feature.settings.data.repository.StartupSessionMode
import com.xencode.feature.agent.domain.model.AgentContext
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.model.AgentMode
import com.xencode.feature.agent.domain.model.ChatSession
import com.xencode.feature.agent.domain.model.ReasoningEffort
import com.xencode.feature.agent.domain.notification.AgentNotificationCenter
import com.xencode.feature.agent.domain.notification.AgentNotificationFormatter
import com.xencode.feature.agent.domain.notification.AgentNotificationKind
import com.xencode.feature.agent.domain.notification.NotificationOutcome
import com.xencode.feature.agent.domain.notification.PendingNotification
import com.xencode.feature.agent.domain.permission.PermissionChoice
import com.xencode.feature.agent.domain.mcp.McpManager
import com.xencode.feature.agent.domain.subagent.AgentDefinition
import com.xencode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.xencode.feature.agent.domain.subagent.SubAgentEvent
import com.xencode.feature.agent.domain.subagent.SubAgentEventBus
import com.xencode.core.watch.FileChangeBatch
import com.xencode.core.watch.FileChangeHub
import com.xencode.core.watch.asDirtySignal
import com.xencode.feature.agent.domain.subagent.SubAgentEventType
import com.xencode.feature.agent.domain.workflow.AgentWorkflow
import com.xencode.feature.terminal.domain.TabFinishedEvent
import com.xencode.feature.terminal.domain.TerminalKeepaliveService
import com.xencode.feature.terminal.domain.TerminalSessionManager
import com.xencode.feature.terminal.domain.takeTailLines
import com.xencode.feature.workspace.domain.DirectoryListingCache
import com.xencode.feature.workspace.domain.FileAccessProvider
import com.xencode.feature.workspace.domain.FileEntry
import com.xencode.feature.workspace.domain.UriPathResolver
import com.xencode.feature.workspace.domain.WorkspacePathMapper
import com.xencode.feature.workspace.domain.isValidFileEntryName
import com.xencode.feature.workspace.domain.planBrowseInvalidation
import com.xencode.feature.agent.domain.workflow.AgentEvent
import com.xencode.feature.agent.domain.tool.ToolPermissionManager
import com.xencode.feature.agent.domain.tool.ToolRegistry
import com.xencode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.xencode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.xencode.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.xencode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.xencode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.xencode.feature.agent.domain.session.SessionUseCase
import com.xencode.feature.agent.domain.session.MessagePersistenceUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.xencode.feature.backup.domain.BackupManager
import com.xencode.feature.agent.domain.command.SlashCommand
import com.xencode.feature.agent.domain.command.SlashCommandContext
import com.xencode.feature.agent.domain.command.SlashCommandRegistry
import com.xencode.feature.agent.domain.command.SlashCommandRegistry.ResolvedCommand
import com.xencode.feature.agent.domain.skill.Skill
import com.xencode.feature.agent.presentation.AgentAttachment
import com.xencode.feature.agent.presentation.component.RewindOption
import com.xencode.feature.agent.presentation.component.formatTokenCount
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val planApprovalManager: PlanApprovalManager,
    private val terminalSessionManager: TerminalSessionManager,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val checkpointManager: CheckpointManager,
    private val checkpointDao: CheckpointDao,
    private val backupManager: BackupManager,
    private val mcpManager: McpManager,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val keepaliveSettings: KeepaliveSettingsRepository,
    private val subAgentEventBus: SubAgentEventBus,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val todoItemDao: TodoItemDao,
    val fileAccess: FileAccessProvider,
    private val fileChangeHub: FileChangeHub,
    private val rootNetworkGuard: RootNetworkGuard,
    private val remoteEnvironmentSettings: com.xencode.feature.settings.data.repository.RemoteEnvironmentSettingsRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SlashCommandContext {

    private val sessionJobs = mutableMapOf<String, Job>()

    /**
     * CPU wake lock held while the agent runs: with the screen off the system would suspend the process, breaking streaming responses and stalling tool calls.
     * Not reference-counted (setReferenceCounted(false)); all sessions share one lock, released once when the last task ends.
     */
    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XenCode:AgentWorkflow")
            .apply { setReferenceCounted(false) }
    }

    /** Persistent keep-alive the user enabled manually in settings; must not be turned off when an agent task wraps up. */
    @Volatile
    private var userKeepaliveEnabled = false

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentSessionTodoItems: StateFlow<List<TodoItem>> = _currentSessionId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else todoItemDao.getBySession(id).map { entities -> entities.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()

    /**
     * Per-session "did the last turn finish normally": only a clean finish (completion marker received) attaches the "Copy / More"
     * buttons under the turn's final reply; manual stop / error / never started all skip them, so buttons never hang off partial output.
     */
    private val _completedSessions = MutableStateFlow<Set<String>>(emptySet())
    val completedSessions: StateFlow<Set<String>> = _completedSessions.asStateFlow()

    val agentState: StateFlow<AgentUIState> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(AgentUIState.Idle)
            else _agentStates.map { it[id] ?: AgentUIState.Idle }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentUIState.Idle)

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        _agentStates.value = _agentStates.value + (sessionId to state)
    }

    /**
     * Failure text: when the server provides a type code (e.g. refusal / context overflow), use the localized message
     * and append the server's specific reason (if any); without a type code, show the raw error text directly.
     */
    private fun describeFailure(event: AgentEvent.Failed): String {
        val localized = when (event.reasonCode) {
            // Gemini finishReason values are all uppercase; these are synonymous with Anthropic's refusal (content-policy blocking).
            "refusal", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                context.getString(R.string.agent_stop_refusal)
            "model_context_window_exceeded" -> context.getString(R.string.agent_stop_context_exceeded)
            "input_budget_exceeded" -> context.getString(R.string.agent_input_budget_exceeded)
            else -> null
        } ?: return event.error
        return if (event.error.isBlank()) localized else "$localized\n${event.error}"
    }

    private val _messageLimit = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val defaultLimit = 30

    /** Chat history search: hit-count cap, input debounce, snippet context width, pagination margin reserved for locating. */
    private val chatSearchLimit = 50
    private val chatSearchDebounceMs = 300L
    private val snippetContext = 40
    private val messageLimitMargin = 5

    /**
     * Per-session input drafts, persisted to disk keyed by session: drafts survive process restarts.
     * It used to be a single global draft; half-typed text in A would follow the user to B on switch.
     */
    private val draftPrefs = context.getSharedPreferences("agent_input_drafts", Context.MODE_PRIVATE)
    private val _inputDrafts = MutableStateFlow<Map<String, String>>(
        draftPrefs.all.mapNotNull { (k, v) ->
            (v as? String)?.takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
    )
    val inputDraft: StateFlow<String> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf("") else _inputDrafts.map { it[id].orEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun updateInputDraft(text: String) {
        val id = _currentSessionId.value ?: return
        val editor = draftPrefs.edit()
        if (text.isEmpty()) {
            _inputDrafts.value = _inputDrafts.value - id
            editor.remove(id)
        } else {
            _inputDrafts.value = _inputDrafts.value + (id to text)
            editor.putString(id, text)
        }
        editor.apply()
    }

    fun clearInputDraft() {
        val id = _currentSessionId.value ?: return
        _inputDrafts.value = _inputDrafts.value - id
        draftPrefs.edit().remove(id).apply()
    }

    /**
     * Manual expansion state of tool calls (group headers and single tool cards): key = group key (`toolgroup:<first message id in group>`)
     * or a single message id.
     *
     * Kept in the ViewModel rather than in composition: in narrow windows, settings / terminal / Git / editor are all full-screen routes, disposing the entire chat
     * composition, so the `remember`ed map and per-message.id remember state vanish together — expanded tools
     * collapse back to default the moment they leave view (scrolled out and recycled, or navigating away and back). Here they live in App-process memory,
     * isolated across sessions (keys are message ids, globally unique), never persisted.
     */
    val toolExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** Records one manual expand/collapse (the caller passes the inverse of the current visible state). */
    fun setToolExpanded(key: String, expanded: Boolean) {
        toolExpansionOverrides[key] = expanded
    }

    /**
     * Manual expansion state of whole-turn folding (the outer "Running / Completed" header): key = `turn:<turn's first message id>`.
     *
     * Same approach as [toolExpansionOverrides]: kept in App-process memory, never persisted. Without a record, the default is "last running turn expanded,
     * all others collapsed" (see AIChatPanel.buildChatItems' activeTurnKey); once the user taps manually,
     * their choice wins and the auto-collapse on task completion never overrides it.
     */
    val turnExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** Records one manual expand/collapse of a whole-turn fold. */
    fun setTurnExpanded(key: String, expanded: Boolean) {
        turnExpansionOverrides[key] = expanded
    }

    /**
     * Manual choice of reasoning expansion state: key = assistant message id. Same as [toolExpansionOverrides] (process memory, not persisted),
     * so thinking cards keep their expanded/collapsed state after being recycled out of view or navigating away and back.
     */
    val reasoningExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** Records one expand/collapse of a thinking card. */
    fun setReasoningExpanded(messageId: String, expanded: Boolean) {
        reasoningExpansionOverrides[messageId] = expanded
    }

    /** Reasoning duration (ms): key = assistant message id. Records the actual streaming-thinking duration for the persisted card to show seconds with one decimal. */
    val reasoningDurations = mutableStateMapOf<String, Long>()

    fun loadMoreMessages() {
        val sid = _currentSessionId.value ?: return
        val currentLimit = _messageLimit.value[sid] ?: defaultLimit
        _messageLimit.value = _messageLimit.value + (sid to (currentLimit + 30))
    }

    /** Live container initialization progress (extraction/deployment/package install), shown in a bubble at the bottom of the AI page. */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _currentWorkspace = MutableStateFlow<String>("")
    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        _currentWorkspace.value = path
        // Switched to a new workspace: container paths share names but host directories differ, so the directory-listing cache and gitignore rules are all void.
        dirListingCache.clear()
        gitignoreCache = null
        // Restore this workspace's last persisted expansion state (root only when there is no record).
        _expandedPaths.value = loadExpansion(path)
        // Search is scoped to the current workspace; old results are meaningless after switching, so clear them too.
        _chatSearchQuery.value = ""
    }

    val sessions: StateFlow<List<ChatSession>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyList())
            else chatSessionDao.getRootSessionsByWorkspace(path)
                .map { list -> list.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Subagents of all root sessions, grouped by parent session id, for inline expansion in sidebar session rows.
     * A full query instead of lazy per-row loading: read the table once, avoiding a subscription storm of one Flow per expanded row.
     */
    val subSessionsByParent: StateFlow<Map<String, List<ChatSession>>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyMap())
            else chatSessionDao.getAllSessionsByWorkspace(path).map { list ->
                list.filter { it.parentId != null }
                    .groupBy({ it.parentId!! }, { it.toDomain() })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── Global chat-history search (scoped to the current workspace) ──
    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    /** Message pending location (session id to message id); consumed and cleared once the chat page scrolls to it or locating is confirmed impossible. */
    private val _pendingScrollMessage = MutableStateFlow<Pair<String, String>?>(null)
    val pendingScrollMessage: StateFlow<Pair<String, String>?> = _pendingScrollMessage.asStateFlow()

    /**
     * Search state: queries after debounce when the workspace or keyword changes, mapping hits to snippet-bearing UI models.
     * Queries run on the IO dispatcher so LIKE full-table scans don't stall the main thread.
     */
    val chatSearchState: StateFlow<ChatSearchState> =
        combine(_currentWorkspace, _chatSearchQuery) { ws, q -> ws to q }
            .debounce(chatSearchDebounceMs)
            .flatMapLatest { (workspace, query) ->
                val keyword = query.trim()
                if (workspace.isBlank() || keyword.isEmpty()) {
                    flowOf(ChatSearchState(query = query))
                } else {
                    flow {
                        emit(ChatSearchState(query = query, loading = true))
                        val hits = withContext(Dispatchers.IO) {
                            agentMessageDao.searchInWorkspace(
                                workspace,
                                escapeLike(keyword),
                                chatSearchLimit
                            ).map { m ->
                                ChatSearchHit(
                                    sessionId = m.sessionId,
                                    sessionTitle = m.sessionTitle,
                                    messageId = m.messageId,
                                    snippet = buildSnippet(m.content, keyword),
                                    timestamp = m.timestamp
                                )
                            }
                        }
                        emit(ChatSearchState(query = query, hits = hits))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ChatSearchState())

    fun updateChatSearchQuery(query: String) {
        _chatSearchQuery.value = query
    }

    fun clearChatSearch() {
        _chatSearchQuery.value = ""
        _pendingScrollMessage.value = null
    }

    /**
     * Opens a search hit: switches to that session, raises its pagination limit to cover the target message, and registers the pending-locate message.
     * The actual scrolling is done by the chat page once messages are ready. The search term and results are kept, so reopening the sidebar still shows the result list.
     */
    fun openChatSearchHit(hit: ChatSearchHit) {
        selectSession(hit.sessionId)
        viewModelScope.launch {
            val timestamp = withContext(Dispatchers.IO) {
                agentMessageDao.getMessageById(hit.messageId)?.timestamp
            }
            if (timestamp != null) {
                val needed = withContext(Dispatchers.IO) {
                    agentMessageDao.countMessagesFromTimestamp(hit.sessionId, timestamp)
                } + messageLimitMargin
                val current = _messageLimit.value[hit.sessionId] ?: defaultLimit
                if (needed > current) {
                    _messageLimit.value = _messageLimit.value + (hit.sessionId to needed)
                }
            }
            _pendingScrollMessage.value = hit.sessionId to hit.messageId
        }
    }

    fun consumePendingScroll() {
        _pendingScrollMessage.value = null
    }

    /** Escapes LIKE wildcards to pair with ESCAPE '!' in the SQL (the escape character itself must be handled first). */
    private fun escapeLike(raw: String): String =
        raw.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    /** Extracts a snippet centered on the hit: collapses newline whitespace, adds ellipses at both ends as needed; falls back to the opening section on no hit. */
    private fun buildSnippet(content: String, keyword: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val index = flat.indexOf(keyword, ignoreCase = true)
        if (index < 0) return flat.take(snippetContext * 2)
        val start = (index - snippetContext).coerceAtLeast(0)
        val end = (index + keyword.length + snippetContext).coerceAtMost(flat.length)
        return buildString {
            if (start > 0) append('\u2026')
            append(flat, start, end)
            if (end < flat.length) append('\u2026')
        }
    }

    /** Expanded directory set of the sidebar "Files" tab (container paths). Includes the workspace root: the root is also collapsible, expanded by default; persisted per workspace. */
    private val _expandedPaths = MutableStateFlow(setOf(WorkspacePathMapper.CONTAINER_ROOT))

    /** File-tree expansion state persisted per workspace (kept across restarts); key is the workspace path, value the set of expanded container paths. */
    private val expansionPrefs = context.getSharedPreferences("file_tree_expansion", Context.MODE_PRIVATE)

    /** Loads a workspace's persisted expansion set; defaults to only the workspace root expanded when there is no record. */
    private fun loadExpansion(workspace: String): Set<String> =
        expansionPrefs.getStringSet(workspace, null)?.toSet()
            ?: setOf(WorkspacePathMapper.CONTAINER_ROOT)

    /** Persists the current workspace's expansion set (pass a fresh copy; SharedPreferences forbids reusing the stored instance). */
    private fun saveExpansion(workspace: String, paths: Set<String>) {
        if (workspace.isBlank()) return
        expansionPrefs.edit().putStringSet(workspace, HashSet(paths)).apply()
    }
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    /** Directory path currently expanding, waiting for the listing to return (listFiles can take seconds over remote SSH); the UI shows a spinner on that row. */
    private val _expandingPath = MutableStateFlow<String?>(null)
    val expandingPath: StateFlow<String?> = _expandingPath.asStateFlow()

    /** Entry paths currently undergoing write operations (create/rename/delete/copy/move); the UI shows spinners on the rows and the toolbar. */
    private val _fileOpPaths = MutableStateFlow<Set<String>>(emptySet())
    val fileOpPaths: StateFlow<Set<String>> = _fileOpPaths.asStateFlow()

    /** Manual refresh signal: remote mode has no inotify and relies on it; local mode uses it as a fallback. */
    private val _browseRefresh = MutableStateFlow(0)

    /**
     * Directory listing cache (container path → entry snapshot): makes "expand → collapse → expand again" and unaffected directories zero-IO.
     * Invalidation is entirely change-event driven (see [applyBrowseInvalidation]); cleared wholesale on workspace switch and manual refresh.
     */
    private val dirListingCache = DirectoryListingCache()

    /** Cache of the parsed root `.gitignore`; null means not cached. Invalidated by gitignoreTouched and cleared on workspace switch. */
    private var gitignoreCache: List<String>? = null

    /**
     * Flattened visible file tree. listFiles is blocking IO locally and a network call remotely, so it must run on the IO dispatcher.
     * Watches the workspace root and all expanded directories: any change (not just AI — terminal and other apps count too) or manual refresh rebuilds the tree.
     * Only expanded directories are watched, not the whole tree recursively, to avoid spawning hordes of inotify handles in large repos.
     *
     * Rebuilding is not re-reading: the tree is assembled from [dirListingCache] alone, with real listFiles only on misses; inotify's single-level
     * semantics (direct children) exactly cover every case of "an expanded directory's listing changed", so FileChangeHub's snapshot-polling
     * fallback is explicitly disabled here (fallbackPoll=false) — polling listFiles + per-entry stat on every expanded directory every 2 seconds is a
     * standing IO tax, and the drawer tree is insensitive to deep recursive changes anyway. In ROOT/SSH mode the host watcher is unavailable to begin with, with manual refresh as the fallback.
     */
    val browseState: StateFlow<FileBrowseState> = _expandedPaths
        .flatMapLatest { expanded ->
            val watched = expanded + WorkspacePathMapper.CONTAINER_ROOT
            val triggers = merge(
                watched.map {
                    fileChangeHub.watchWorkspace(it, fallbackPoll = false)
                        .onEach { batch -> applyBrowseInvalidation(batch) }
                        .asDirtySignal()
                }.merge().debounce(BROWSE_DEBOUNCE_MS),
                // drop(1) discards the current value replayed on StateFlow rebuild, otherwise a freshly expanded directory gets read once more
                _browseRefresh.drop(1).map { }
            )
            flow {
                // Before the first emission, stateIn's initial Loading holds the place; later expand/collapse/refresh never returns to Loading —
                // the StateFlow keeps the previous Success until the new tree is ready, avoiding flashing spinners and losing scroll position.
                emit(buildBrowseTree(expanded))
                triggers.collect { emit(buildBrowseTree(expanded)) }
            }.flowOn(Dispatchers.IO)
        }
        // New tree ready: clear the expanding-wait state (whether success or error, to avoid a stuck spinner).
        .onEach { _expandingPath.value = null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FileBrowseState.Loading)

    /**
     * Precisely invalidates [dirListingCache] and gitignore rules per change batch. Each batch executes immediately within the debounce window,
     * so the cache is already invalidated when the rebuild assembles (onEach runs before the downstream collect's rebuild, order-wise).
     */
    private fun applyBrowseInvalidation(batch: FileChangeBatch) {
        val plan = planBrowseInvalidation(batch, WorkspacePathMapper.CONTAINER_ROOT)
        if (plan.fullRefresh) {
            dirListingCache.clear()
            gitignoreCache = null
            return
        }
        plan.relistDirs.forEach { dirListingCache.invalidate(it) }
        plan.dropTrees.forEach { dirListingCache.invalidateTree(it) }
        if (plan.gitignoreTouched) gitignoreCache = null
    }

    /** Reads the workspace root and expands recursively per [expanded], producing the flat visible node list; a root read failure fails the whole thing. */
    private fun buildBrowseTree(expanded: Set<String>): FileBrowseState {
        val startedAt = System.currentTimeMillis()
        listingCounter.set(0)
        val root = WorkspacePathMapper.CONTAINER_ROOT
        val rootEntries = listDirCached(root) ?: run {
            FileLogger.w(TAG, "Failed to list directory: $root")
            return FileBrowseState.Error("Cannot read the workspace root directory")
        }
        val ignorePatterns = cachedRootGitignore(root)
        val rootExpanded = root in expanded
        val nodes = mutableListOf<FileTreeNode>()
        nodes += FileTreeNode(
            entry = FileEntry(name = "workspace", isDirectory = true, size = 0, lastModified = 0),
            path = root,
            depth = 0,
            isRoot = true,
            isExpanded = rootExpanded
        )
        if (rootExpanded) {
            appendBrowseChildren(root, rootEntries, depth = 1, expanded = expanded, ignorePatterns = ignorePatterns, relParts = emptyList(), out = nodes)
        }
        val listed = listingCounter.get()
        FileLogger.d(TAG, "File tree rebuild: cached dirs=${dirListingCache.size()} listed=$listed nodes=${nodes.size} took=${System.currentTimeMillis() - startedAt}ms")
        return FileBrowseState.Success(nodes)
    }

    /** Reset to 0 on every rebuild, used only for diagnostics (see [buildBrowseTree]); thread-isolated so concurrent rebuilds don't interfere. */
    private val listingCounter = ThreadLocal.withInitial { 0 }

    /**
     * Cached directory listing: a hit returns the snapshot directly; a miss does the real listFiles and backfills the cache.
     * A failed listing returns null and is **never cached** (the next rebuild naturally retries); the caller decides how to present the error.
     */
    private fun listDirCached(path: String): List<FileEntry>? {
        dirListingCache.get(path)?.let { return it }
        val entries = runCatching { fileAccess.listFiles(path) }.getOrNull() ?: return null
        dirListingCache.put(path, entries)
        listingCounter.set(listingCounter.get() + 1)
        return entries
    }

    /**
     * Reads the workspace root .gitignore (local or remote); drops blank lines/comments/negated lines, empty if unreadable. Root .gitignore only, no nesting.
     * The result is cached with invalidation: cleared when a change batch touches the root .gitignore ([applyBrowseInvalidation]), on workspace switch,
     * or on manual refresh; avoiding exists+readFile on every rebuild (two su forks in ROOT mode).
     */
    private fun cachedRootGitignore(root: String): List<String> {
        gitignoreCache?.let { return it }
        val patterns = runCatching {
            val path = "$root/.gitignore"
            if (!fileAccess.exists(path)) return emptyList()
            fileAccess.readFile(path).lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                .map { it.removeSuffix("/") }
                .toList()
        }.getOrDefault(emptyList())
        gitignoreCache = patterns
        return patterns
    }

    private fun appendBrowseChildren(
        parent: String,
        entries: List<FileEntry>,
        depth: Int,
        expanded: Set<String>,
        ignorePatterns: List<String>,
        relParts: List<String>,
        out: MutableList<FileTreeNode>
    ) {
        // Deduplicate by name: the filesystem's readdir may return the same entry twice on FUSE storage (external workspace/emulated storage),
        // and AOSP's ReaddirHelper does the same. Same-name entries would produce duplicate node paths and break LazyColumn keys.
        for (entry in entries.distinctBy { it.name }.sortedWith(BROWSE_ORDER)) {
            val path = "$parent/${entry.name}"
            val parts = relParts + entry.name
            val ignored = ignorePatterns.isNotEmpty() &&
                GitIgnoreMatcher.isIgnored(ignorePatterns, parts)
            val open = entry.isDirectory && path in expanded
            if (!open) {
                out += FileTreeNode(entry, path, depth, isRoot = false, isExpanded = false, ignored = ignored)
                continue
            }
            val children = listDirCached(path)
            out += FileTreeNode(entry, path, depth, isRoot = false, isExpanded = true, hasError = children == null, ignored = ignored)
            if (children != null) appendBrowseChildren(path, children, depth + 1, expanded, ignorePatterns, parts, out)
        }
    }

    /** Manual full refresh: clears the directory cache then triggers a rebuild, keeping the display consistent with disk (the only source of truth in remote mode). */
    fun refreshBrowse() {
        dirListingCache.clear()
        gitignoreCache = null
        _browseRefresh.value++
    }

    /** Triggers one rebuild without clearing the cache (for precise invalidation after write operations; the user-visible full refresh is [refreshBrowse]). */
    private fun rebuildBrowseAfterWrite() {
        _browseRefresh.value++
    }

    /** Expands/collapses a directory; collapsing removes all its descendants from the expansion set too, avoiding stale watchers and unintended deep expansion later. Changes are persisted per workspace.
     *  Expanding waits for the listing to return, marking [expandingPath] meanwhile so the UI shows a spinner, and ignores repeated taps on the same directory (otherwise it would fold back). */
    fun toggleExpand(path: String) {
        if (path == _expandingPath.value) return
        val current = _expandedPaths.value
        val updated = if (path in current) {
            current.filterNot { it == path || it.startsWith("$path/") }.toSet()
        } else {
            _expandingPath.value = path
            current + path
        }
        _expandedPaths.value = updated
        saveExpansion(_currentWorkspace.value, updated)
    }

    /**
     * Shared wrapper for file-browse write operations: runs on the IO dispatcher and proactively re-reads directories on success (remote mode has no inotify).
     * [busyPaths] are the entry paths involved in this operation; they join [fileOpPaths] during the operation so the UI shows a spinner.
     * [block] returning false means an invalid name or an existing duplicate; a thrown exception means IO failure — both report failure.
     */
    private fun mutateBrowse(
        busyPaths: Set<String> = emptySet(),
        onResult: (Boolean) -> Unit,
        block: () -> Boolean
    ) = viewModelScope.launch {
        if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value + busyPaths
        try {
            val success = withContext(Dispatchers.IO) {
                runCatching(block)
                    .onFailure { FileLogger.w(TAG, "File operation failed", it) }
                    .getOrDefault(false)
            }
            if (success) {
                // Precise invalidation: only the directories touched by this operation get re-read (especially important in ROOT/SSH with no inotify fallback),
                // the rest of the cache is kept as-is — a small operation no longer re-lists the whole tree.
                busyPaths.forEach { path ->
                    dirListingCache.invalidate(path.substringBeforeLast('/', ""))
                    dirListingCache.invalidateTree(path)
                }
                rebuildBrowseAfterWrite()
            }
            onResult(success)
        } finally {
            if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value - busyPaths
        }
    }

    /** Child path under [parent]; returns null for an invalid name. */
    private fun browseChildPath(parent: String, name: String): String? =
        if (isValidFileEntryName(name)) "$parent/${name.trim()}" else null

    /** Creates an empty file in [parent]. */
    fun createBrowseFile(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.writeFile(target, "", overwrite = false)
                true
            }
        }
    }

    /** Creates a folder in [parent]. */
    fun createBrowseFolder(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.mkdirs(target)
                fileAccess.isDirectory(target)
            }
        }
    }

    /** Renames an entry (same directory only, no cross-directory moves). */
    fun renameBrowseEntry(path: String, newName: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isEmpty() || !isValidFileEntryName(newName)) {
                false
            } else {
                fileAccess.rename(path, "$parent/${newName.trim()}")
                true
            }
        }

    /** Deletes an entry; directories are deleted recursively with their contents. */
    fun deleteBrowseEntry(path: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            fileAccess.deleteRecursively(path)
            true
        }

    /** Copy picked files into [parent]. Existing names get a numeric suffix. */
    fun importBrowseFiles(
        parent: String,
        uris: List<Uri>,
        resolverContext: Context,
        onResult: (Boolean) -> Unit
    ) {
        if (uris.isEmpty()) {
            onResult(false)
            return
        }
        mutateBrowse(busyPaths = setOf(parent), onResult = onResult) {
            var any = false
            for (uri in uris) {
                val name = uniqueBrowseName(
                    parent,
                    sanitizeBrowseEntryName(uriDisplayName(resolverContext, uri))
                )
                if (!isValidFileEntryName(name)) continue
                val copied = resolverContext.contentResolver.openInputStream(uri)?.use { input ->
                    fileAccess.writeStream("$parent/$name", input, overwrite = false)
                    true
                } ?: false
                if (copied) any = true
            }
            any
        }
    }

    /** Copy a device folder into [parent]. Existing names get a numeric suffix. */
    fun importBrowseFolder(
        parent: String,
        treeUri: Uri,
        resolverContext: Context,
        onResult: (Boolean) -> Unit
    ) {
        mutateBrowse(busyPaths = setOf(parent), onResult = onResult) {
            val sourcePath = UriPathResolver.toFilePath(resolverContext, treeUri) ?: return@mutateBrowse false
            val source = File(sourcePath)
            if (!source.isDirectory) return@mutateBrowse false
            val destName = uniqueBrowseName(
                parent,
                sanitizeBrowseEntryName(source.name.ifBlank { "folder" })
            )
            if (!isValidFileEntryName(destName)) return@mutateBrowse false
            copyLocalTreeIntoBrowse(source, "$parent/$destName")
            true
        }
    }

    private fun copyLocalTreeIntoBrowse(source: File, destPath: String) {
        if (source.isDirectory) {
            fileAccess.mkdirs(destPath)
            source.listFiles()?.forEach { child ->
                val childName = sanitizeBrowseEntryName(child.name)
                if (isValidFileEntryName(childName)) {
                    copyLocalTreeIntoBrowse(child, "$destPath/$childName")
                }
            }
        } else {
            source.inputStream().use { input ->
                fileAccess.writeStream(destPath, input, overwrite = false)
            }
        }
    }

    private fun uriDisplayName(resolverContext: Context, uri: Uri): String {
        val queried = resolverContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index).orEmpty() else ""
        }.orEmpty()
        if (queried.isNotBlank()) return queried
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun sanitizeBrowseEntryName(name: String): String {
        val cleaned = name
            .map { ch -> if (ch.code < 32 || ch in "\\/:*?\"<>|") '_' else ch }
            .joinToString("")
            .trim()
            .trim('.')
        return cleaned.ifBlank { "file" }.take(160)
    }

    private fun uniqueBrowseName(dir: String, fileName: String): String {
        if (!fileAccess.exists("$dir/$fileName")) return fileName
        val dotIndex = fileName.lastIndexOf('.')
        val stem = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val extension = if (dotIndex > 0) fileName.substring(dotIndex) else ""
        var index = 1
        while (fileAccess.exists("$dir/$stem-$index$extension")) {
            index += 1
        }
        return "$stem-$index$extension"
    }

    // region File copy / cut / paste

    /** File-browse clipboard: stashes the source entry after copy or cut, for pasting into other directories. */
    private val _browseClipboard = MutableStateFlow<BrowseClipboard?>(null)
    val browseClipboard: StateFlow<BrowseClipboard?> = _browseClipboard.asStateFlow()

    /** Copies an entry into the clipboard (the clipboard holds one item; the old one is simply overwritten). */
    fun copyBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = false)
    }

    /** Cuts an entry into the clipboard (source deleted after a successful paste, and pasting is allowed only once). */
    fun cutBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = true)
    }

    /** Clears the clipboard. */
    fun clearBrowseClipboard() {
        _browseClipboard.value = null
    }

    /** Paste conflict (a same-name item already exists at the target) awaiting user confirmation to overwrite. Holds the source path and target directory; call [pasteBrowseEntryOverwrite] once confirmed. */
    private val _pasteConflict = MutableStateFlow<Pair<String, String>?>(null)
    val pasteConflict: StateFlow<Pair<String, String>?> = _pasteConflict.asStateFlow()

    /** Pastes the clipboard content into [targetDir]. When a same-name item exists at the target, emits [pasteConflict] so the UI asks whether to overwrite
     *  and skips the paste; otherwise pastes directly. A successful paste clears the clipboard (copy or cut, single-use either way); a failure keeps it for retry. */
    fun pasteBrowseEntry(targetDir: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val clip = _browseClipboard.value ?: return@launch onResult(false)
        if (clip.sourcePath == targetDir) return@launch onResult(false)
        val name = clip.sourceName
        if (!isValidFileEntryName(name)) return@launch onResult(false)
        val target = "$targetDir/$name"
        if (target == clip.sourcePath || target.startsWith("${clip.sourcePath.trimEnd('/')}/")) {
            return@launch onResult(false)
        }
        if (withContext(Dispatchers.IO) { fileAccess.exists(target) }) {
            _pasteConflict.value = clip.sourcePath to target
            return@launch
        }
        performPaste(clip, target, overwrite = false, onResult)
    }

    /** Force-pastes after the user confirms overwriting (same-name items at the target are overwritten). */
    fun pasteBrowseEntryOverwrite() {
        val conflict = _pasteConflict.value ?: return
        val clip = _browseClipboard.value ?: return
        _pasteConflict.value = null
        performPaste(clip, conflict.second, overwrite = true) {}
    }

    /** Clears the paste-conflict pending state (when the user taps "Cancel"). */
    fun clearPasteConflict() {
        _pasteConflict.value = null
    }

    private fun performPaste(clip: BrowseClipboard, target: String, overwrite: Boolean, onResult: (Boolean) -> Unit) {
        val busyPaths = if (clip.isCut) setOf(clip.sourcePath, target) else setOf(target)
        mutateBrowse(busyPaths = busyPaths, onResult = { success ->
            // Copy or cut, a successful paste clears the clipboard to avoid duplicate pastes; a failure keeps it for retry.
            if (success) _browseClipboard.value = null
            onResult(success)
        }) {
            if (clip.isCut) {
                fileAccess.move(clip.sourcePath, target, overwrite)
                true
            } else {
                fileAccess.copy(clip.sourcePath, target, overwrite)
                true
            }
        }
    }

    // endregion

    /** Full info of the current session (root and sub sessions alike; null means no session resolved yet). */
    val currentSessionState: StateFlow<ChatSession?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else chatSessionDao.getByIdFlow(id).map { it?.toDomain() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentSessionMode: StateFlow<AgentMode> = currentSessionState.map { it?.mode ?: AgentMode.BUILD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentMode.BUILD)

    /** Current session's reasoning effort (default MEDIUM). */
    val currentSessionReasoningEffort: StateFlow<ReasoningEffort> =
        currentSessionState.map { it?.reasoningEffort ?: ReasoningEffort.MEDIUM }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ReasoningEffort.MEDIUM)

    /** Current session's bound providerId/model (null means unbound; falls back to the global active provider). */
    val currentSessionProviderModel: StateFlow<Pair<String?, String?>> =
        currentSessionState.map { s -> (s?.providerId ?: "") to (s?.model ?: "") }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Pair(null, null))

    /**
     * Current session's message state: switches to the matching history automatically on session change, carrying the owning session id and loaded flag,
     * so the UI can tell "switching/cold-start loading" from an "empty session" — avoiding a flash of Welcome or the previous session's messages before a sudden refresh.
     * Filters out empty assistant rows from "tool-call-only" turns (blank content, existing only for replay pairing; should not render as bubbles).
     */
    val messagesState: StateFlow<ChatMessagesState> = combine(
        _currentSessionId,
        _messageLimit
    ) { id, limitMap -> id to (limitMap[id] ?: defaultLimit) }
        .flatMapLatest { (id, limit) ->
            if (id == null) flowOf(ChatMessagesState(null, emptyList(), loaded = false))
            else agentMessageDao.getMessagesBySessionPaged(id, limit).map { list ->
                ChatMessagesState(
                    sessionId = id,
                    messages = list.asSequence()
                        .filterNot {
                            it.role == MessageRole.ASSISTANT.name &&
                                !it.content.hasVisibleContent() &&
                                it.reasoning.isNullOrEmpty()
                        }
                        .map { entity -> entity.toUIMessage() }
                        .toList(),
                    loaded = true,
                    hasMore = list.size >= limit,
                    isLoadingMore = false
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatMessagesState(null, emptyList(), loaded = false))


    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    val runningTool: StateFlow<List<RunningToolOutput>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _runningTools.map { it[id]?.values?.toList() ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Adds/updates a running tool (located by msgId; multiple tools in parallel supported). */
    private fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
        _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
    }

    /** Removes a running tool; clears the session entry once it has no running tools left. */
    private fun removeRunningTool(sessionId: String, msgId: String) {
        val sessionTools = _runningTools.value[sessionId] ?: return
        val updated = sessionTools - msgId
        _runningTools.value = if (updated.isEmpty()) {
            _runningTools.value - sessionId
        } else {
            _runningTools.value + (sessionId to updated)
        }
    }

    private val _preparingTools = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * The tool name the model is currently streaming out but hasn't started executing (e.g. `editFile`).
     *
     * For long-argument tools (writing a whole file, long commands), argument streaming can last several seconds with neither body text nor thinking deltas,
     * leaving the UI only a generic "Thinking". The upstream reports as soon as the tool name appears ([AgentEvent.ToolCallPreparing]),
     * letting the UI switch the status to the concrete scene ("Editing file"). Once the tool actually starts, [runningTool] takes over.
     */
    val preparingTool: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _preparingTools.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setPreparingTool(sessionId: String, toolName: String?) {
        val current = _preparingTools.value
        if (toolName == null) {
            if (current.containsKey(sessionId)) _preparingTools.value = current - sessionId
        } else if (current[sessionId] != toolName) {
            _preparingTools.value = current + (sessionId to toolName)
        }
    }

    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _contextUsages = MutableStateFlow<Map<String, AgentEvent.ContextUsage>>(emptyMap())
    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = _contextUsages.asStateFlow()

    private val _llmCallEvents = MutableSharedFlow<LlmCallEvent>(extraBufferCapacity = 16)
    /** Per-LLM-request return events (carrying per-call token stats). */
    val llmCallEvents: SharedFlow<LlmCallEvent> = _llmCallEvents.asSharedFlow()
    val isCompacting: StateFlow<Boolean> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else _compactingSessions.map { it[id] == true }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun setCompacting(sessionId: String, compacting: Boolean) {
        _compactingSessions.value = if (compacting) {
            _compactingSessions.value + (sessionId to true)
        } else {
            _compactingSessions.value - sessionId
        }
    }

    private val _streamingTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingText: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingTexts.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingText(sessionId: String, text: String?) {
        _streamingTexts.value = if (text == null) _streamingTexts.value - sessionId else _streamingTexts.value + (sessionId to text)
    }

    private val _reasoningTimings = MutableStateFlow<Map<String, Pair<Long, Long?>>>(emptyMap())
    val reasoningTiming: StateFlow<Pair<Long, Long?>?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else _reasoningTimings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _streamingReasonings = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingReasoning: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingReasonings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingReasoning(sessionId: String, text: String?) {
        if (text == null) _reasoningTimings.value = _reasoningTimings.value - sessionId
        _streamingReasonings.value = if (text == null) _streamingReasonings.value - sessionId else _streamingReasonings.value + (sessionId to text)
    }

    /** Retry state maintained per sessionId; set to null when streaming resumes or ends. */
    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryState: StateFlow<RetryState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _retryStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) _retryStates.value - sessionId else _retryStates.value + (sessionId to state)
    }

    /** Multi-key switch notice maintained per sessionId; set to null when content resumes or the turn ends. */
    private val _keySwitchStates = MutableStateFlow<Map<String, KeySwitchState?>>(emptyMap())
    val keySwitchState: StateFlow<KeySwitchState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _keySwitchStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setKeySwitchState(sessionId: String, state: KeySwitchState?) {
        _keySwitchStates.value = if (state == null) _keySwitchStates.value - sessionId else _keySwitchStates.value + (sessionId to state)
    }

    val pendingToolPermission = toolPermissionManager.pendingRequest

    /** Title of the session owning the currently shown permission dialog (labels the dialog's owner when sessions run in parallel). Falls back to an empty string if the session doesn't exist. */
    val pendingToolPermissionSessionTitle: StateFlow<String> = combine(
        toolPermissionManager.pendingRequest,
        sessions,
        subSessionsByParent
    ) { req, roots, subs ->
        val sid = req?.sessionId.orEmpty()
        if (sid.isBlank()) return@combine ""
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Session ids currently awaiting user authorization, used to light the orange indicator on sidebar session rows. */
    val awaitingPermissionSessionIds: StateFlow<Set<String>> = toolPermissionManager.awaitingSessionIds

    val pendingUserQuestion = askUserQuestionManager.pendingQuestion

    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    // Sessions currently running a slash command: also treated as busy while the command runs
    // (not registered in sessionJobs, otherwise self-checks inside /compress and the like would misjudge as running),
    // used by enqueueAgentRequest to enqueue new messages instead of running them in parallel.
    private val _runningCommandSessions = MutableStateFlow<Set<String>>(emptySet())
    val queuedRequests: StateFlow<List<QueuedRequest>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _queuedRequests.map { it[id] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingPlanApproval: StateFlow<PlanApprovalRequest?> = planApprovalManager.pendingApproval

    // Tool-call input parameters (argsPreview) stashed by persisted message id: after ToolCallStarted is persisted,
    // ToolCallFinished / user stop REPLACEs the row with the same id, so the parameters must be carried into those later writes.
    private val toolArgsByMsgId = mutableMapOf<String, String>()

    /** Whether there is a running, interruptible agent task. */
    val isRunning: Boolean get() {
        val sid = _currentSessionId.value ?: return false
        return sessionJobs[sid]?.isActive == true
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean {
        if (sessions.value.any { sessionJobs[it.id]?.isActive == true }) return true
        // Subagent sessions are not in sessions (root sessions only): skipping them would release the wake lock and
        // foreground service early when the parent's turn ends, and still-running subagents would be suspended by the system.
        return subSessionsByParent.value.values.any { subs ->
            subs.any { sessionJobs[it.id]?.isActive == true }
        }
    }

    /**
     * Task start: brings up the foreground keep-alive service (registering an active session) and acquires the CPU wake lock,
     * ensuring the process isn't suspended by the system and network I/O doesn't drop when backgrounded / screen-off.
     *
     * Uses startSession (ACTION_START_SESSION) instead of enablePersistent:
     * - startSession increments sessionCount so the foreground service knows active sessions are running;
     * - startSession launches via startForegroundService, correctly brought up on Android 8+ when resuming from the background;
     * - ensureForeground shows "N sessions running" based on sessionCount > 0 instead of a generic "keep-alive on".
     */
    private fun acquireKeepalive() {
        TerminalKeepaliveService.startSession(context)
        // With ROOT, also lifts the system-side background network restrictions (netpolicy/standby/appops);
        // silently skipped without ROOT. On the IO dispatcher: it forks su subprocesses internally and must not touch the main thread.
        viewModelScope.launch(Dispatchers.IO) { rootNetworkGuard.applyIfRooted() }
        if (wakeLock.isHeld) return
        runCatching { wakeLock.acquire(KEEPALIVE_TIMEOUT_MS) }
            .onFailure { FileLogger.e(TAG, "acquire wakeLock failed", it) }
    }

    /**
     * Task wrap-up: releases the wake lock; ends the active session count (sessionCount decremented).
     * The foreground service stops only when there are neither background terminal tasks nor a user-enabled keep-alive.
     */
    private fun releaseKeepalive() {
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
                .onFailure { FileLogger.e(TAG, "release wakeLock failed", it) }
        }
        TerminalKeepaliveService.stopSession(context)
        if (!userKeepaliveEnabled && !terminalSessionManager.hasBackgroundTabs()) {
            TerminalKeepaliveService.disablePersistent(context)
        }
    }

    /** Posts a clickable system notification when the Agent finishes while the app is backgrounded (title = task completed, body = the user's message). */
    private fun showAgentCompletedNotification(userRequest: String) {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AGENT_COMPLETE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_complete_notification_title))
            .setContentText(agentCompleteNotificationBody(userRequest))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(AGENT_COMPLETE_NOTIFICATION_ID, notification)
        }.onFailure { FileLogger.e(TAG, "Failed to send agent completion notification", it) }
    }

    /**
     * System notification body: plain user messages are shown as-is; turns triggered by background callbacks don't expose
     * the internal "[System notification - not user input]…" construction text, showing the task title instead.
     */
    private fun agentCompleteNotificationBody(userRequest: String): String {
        if (userRequest.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
            val titles = TASK_NOTIFICATION_TITLE_REGEX.findAll(userRequest)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()
            return when {
                titles.isEmpty() -> context.getString(R.string.agent_complete_notification_body)
                titles.size == 1 -> context.getString(
                    R.string.agent_complete_notification_background_body, titles.first()
                )
                else -> context.getString(
                    R.string.agent_complete_notification_background_multi_body, titles.size
                )
            }
        }
        return userRequest.ifBlank { context.getString(R.string.agent_complete_notification_body) }
    }

    private companion object {
        const val TAG = "AIAgentViewModel"
        const val AGENT_COMPLETE_CHANNEL = "agent_complete"
        const val AGENT_COMPLETE_NOTIFICATION_ID = 100
        /** wakeLock timeout safety net: build and dependency-install tools easily run over ten minutes, so allow a full 60; released proactively when the task ends normally. */
        const val KEEPALIVE_TIMEOUT_MS = 60 * 60 * 1000L
        /** Extracts the <title> content from background task notification text, for the system notification body. */
        val TASK_NOTIFICATION_TITLE_REGEX = Regex("<title>([^<]+)</title>")
        /** File-browse ordering: directories first, then case-insensitive by name within each kind. */
        val BROWSE_ORDER: Comparator<FileEntry> =
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        /** Writing files fires bursts of inotify events; merge them before re-reading directories. */
        const val BROWSE_DEBOUNCE_MS = 300L
    }

    init {
        // Cold-start cleanup: tools left "executing" when the previous process was killed are backfilled as "interrupted". Runs in the background without blocking first frame or session display.
        viewModelScope.launch(Dispatchers.IO) {
            sessionUseCase.initColdStartCleanup()
        }

        // Rescan skills on startup and workspace switch (project-level skills vary by workspace), refreshing the `/` command menu.
        viewModelScope.launch {
            _currentWorkspace.collect { slashCommandRegistry.refresh() }
        }

        viewModelScope.launch {
            _currentWorkspace.collectLatest { path ->
                if (path.isBlank()) return@collectLatest
                val recent = sessionUseCase.getMostRecentSessionOfWorkspace(path)
                // Resolve and set the current session immediately, so the first frame renders instantly and the sidebar shows the new session at once, eliminating any spinner stall.
                // "Open recent session" goes straight to the most recent one with history (by updatedAt, unaffected by pinning);
                // "New session" reuses an empty session that has never been messaged (avoiding an empty session piling up on every launch); otherwise creates a new one.
                val targetId = when {
                    recent == null -> createAndUpsertSession(path)
                    generalSettingsRepository.startupSessionMode() == StartupSessionMode.RECENT_SESSION -> recent.id
                    sessionUseCase.isSessionEmpty(recent.id) -> recent.id
                    else -> createAndUpsertSession(path)
                }
                _currentSessionId.value = targetId

                // Recycles surplus empty sessions asynchronously (keeping the current targetId), in the background, without stalling the main thread or first-frame rendering.
                if (recent != null) {
                    launch(Dispatchers.IO) {
                        sessionUseCase.recycleEmptySessions(path, keepId = targetId)
                    }
                }
            }
        }

        // Subscribes to background command completion events: commands with notify=true automatically inject a message and trigger a new AI turn when they finish.
        // Events arriving while the session is busy are cached and merged into one send after the turn ends (see [flushMergedNotifications]).
        viewModelScope.launch {
            terminalSessionManager.tabFinishedEvents.collect { event ->
                handleBackgroundCommandFinished(event)
            }
        }

        viewModelScope.launch {
            keepaliveSettings.enabledFlow.collect { userKeepaliveEnabled = it }
        }

        // Subscribes to subagent lifecycle events (analogous to terminal's notify=true async callbacks):
        // - SPAWNED: the task tool created a sub-session and messaged it for the user; start the AI workflow on the sub-session here;
        // - STOPPED: task(action="stop") requests stopping the subagent; cancels that session's AI task;
        // - COMPLETED/FAILED: emitted when the sub-session's workflow ends (see handleSubAgentFinished), injecting a parent-session notification.
        viewModelScope.launch {
            subAgentEventBus.events.collect { event ->
                when (event.type) {
                    SubAgentEventType.SPAWNED -> spawnSubAgentWorkflow(event)
                    SubAgentEventType.STOPPED -> stopAgentSession(event.subSessionId)
                    SubAgentEventType.COMPLETED, SubAgentEventType.FAILED -> {
                        enqueueSubAgentNotification(event)
                    }
                    SubAgentEventType.MESSAGE_FROM_PARENT -> deliverMessageToSubAgent(event)
                    SubAgentEventType.MESSAGE_FROM_SUB -> deliverMessageToParent(event)
                }
            }
        }
    }

    /**
     * Subagent created (the task tool created a sub-session): start the AI workflow on the sub-session.
     * The message is persisted uniformly by executeAgentRequestStream (same as a user-sent message),
     * and the title keeps the description passed to task (skipTitleUpdate).
     */
    private suspend fun spawnSubAgentWorkflow(event: SubAgentEvent) {
        val parentSession = sessionUseCase.getSessionById(event.parentSessionId)
        if (parentSession == null) {
            FileLogger.w(TAG, "Subagent parent session not found: ${event.parentSessionId}")
            return
        }
        // The sub-session may already have been deleted by the user; skip
        if (sessionUseCase.getSessionById(event.subSessionId) == null) {
            FileLogger.w(TAG, "Subagent session already deleted; skipping start: ${event.subSessionId}")
            return
        }
        // No duplicate start while the subagent is running (the session already has an active job)
        if (sessionJobs[event.subSessionId]?.isActive == true) return

        executeAgentRequestStream(
            request = event.detail,
            projectRoot = parentSession.workspacePath,
            targetSessionId = event.subSessionId,
            skipTitleUpdate = true
        )
    }

    /**
     * Subagent completion/failure notification: when the parent session is busy, enqueue first (delivered piggybacked on the next batch of tool results within the turn, see
     * [com.xencode.feature.agent.domain.workflow.StatefulAgentWorkflow]); when idle, immediately inject a system notification message to trigger a new turn.
     * The parent agent thus learns the subagent ended and can task(action="read") to fetch the result. Same mechanism as terminal background notifications.
     */
    private fun enqueueSubAgentNotification(event: SubAgentEvent) {
        viewModelScope.launch {
            val title = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "Subagent"
            notifyParentSubAgentFinished(
                parentSessionId = event.parentSessionId,
                subSessionId = event.subSessionId,
                title = title,
                outcome = if (event.type == SubAgentEventType.FAILED) {
                    NotificationOutcome.FAILED
                } else {
                    NotificationOutcome.COMPLETED
                },
                detail = event.detail.takeIf { it.isNotBlank() && event.type == SubAgentEventType.FAILED }
            )
        }
    }

    /**
     * Messages from the main session to a running/finished subagent: enqueued piggyback when the recipient is busy; triggers a new turn when idle.
     * Same dispatch logic as [notifyParentSubAgentFinished], in the opposite direction.
     */
    private suspend fun deliverMessageToSubAgent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.parentSessionId)?.title ?: "Parent session"
        deliverAgentMessage(
            recipientSessionId = event.subSessionId,
            senderSessionId = event.parentSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = true
        )
    }

    /** Message from a subagent to the main session: same as above, with the main session as recipient. */
    private suspend fun deliverMessageToParent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "Subagent"
        deliverAgentMessage(
            recipientSessionId = event.parentSessionId,
            senderSessionId = event.subSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = false
        )
    }

    /**
     * Delivers an inter-agent message: when the recipient is busy, into [AgentNotificationCenter] (piggybacked on tool results within the turn, or the end-of-turn fallback),
     * when idle, a notification message triggers its new turn. The message body arrives with the notification, so the recipient need not read it separately.
     */
    private suspend fun deliverAgentMessage(
        recipientSessionId: String,
        senderSessionId: String,
        senderTitle: String,
        message: String,
        fromParent: Boolean
    ) {
        if (message.isBlank()) return
        if (sessionUseCase.getSessionById(recipientSessionId) == null) return
        val item = PendingNotification(
            kind = AgentNotificationKind.AGENT_MESSAGE,
            sourceId = senderSessionId,
            title = senderTitle,
            outcome = NotificationOutcome.COMPLETED,
            message = message,
            fromParent = fromParent
        )
        deliverSystemEvent(recipientSessionId, item)
    }

    /**
     * Delivers a subagent-completion notification to the parent session: enqueued piggyback when busy; injected immediately to trigger a new turn when idle.
     * Failure and termination both carry a reason, otherwise the parent only knows "it didn't work" and must read once more for a clue.
     */
    private suspend fun notifyParentSubAgentFinished(
        parentSessionId: String,
        subSessionId: String,
        title: String,
        outcome: NotificationOutcome,
        detail: String?
    ) {
        val item = PendingNotification(
            kind = AgentNotificationKind.SUBAGENT,
            sourceId = subSessionId,
            title = title,
            outcome = outcome,
            detail = detail
        )
        deliverSystemEvent(parentSessionId, item)
    }

    /**
     * Callback after a background command (notify=true) finishes.
     *
     * - Session busy: enqueue into [agentNotificationCenter], delivered piggybacked on the next batch of tool results within the turn (the AI perceives it that very turn,
     *   saving the "wait for the turn to end, then start another" LLM round trip); [flushPendingNotifications] is the fallback when the turn has no more tool calls.
     * - Session idle: immediately trigger a new Agent turn with a system notification message.
     *
     * The fallback path uses a user message rather than an assistant(tool_call) + tool_result pair: the latter could land out of order against the original
     * terminal tool call's tool result (background callbacks fire asynchronously and may be persisted before the original terminal result), making messages
     * violate OpenAI's "assistant(tool_calls) → tool results immediately after" pairing constraint and returning 400 upstream. A user message needs no
     * pairing with any tool_call and inherently preserves order. The notification text carries fenced framing so the AI doesn't mistake it for a new user instruction or approval;
     * the AI then uses terminal(read) to fetch the full output.
     *
     * The notification is not persisted here; it goes through the normal enqueueAgentRequest flow with isAutoTrigger=false:
     * executeAgentRequestStream persists this user message uniformly, and the identical UserMessage appended by the workflow's InitRequest
     * is that very message, avoiding duplicate persistence or empty placeholder messages.
     */
    private fun handleBackgroundCommandFinished(event: TabFinishedEvent) {
        val sessionId = event.sourceSessionId ?: return
        val jobActive = sessionJobs[sessionId]?.isActive == true
        val currentSid = _currentSessionId.value
        FileLogger.d(TAG, "handleBgFinished: eventSid=$sessionId currentSid=$currentSid jobActive=$jobActive state=${_agentStates.value[sessionId]}")
        val item = event.toPendingNotification()
        deliverSystemEvent(sessionId, item)
    }

    /**
     * Uniformly delivers a system event to the given session: if busy, into [agentNotificationCenter], delivered piggybacked on tool results within the turn;
     * if idle, a system notification message triggers a new turn. Background task completion, subagent completion, inter-agent messages, and mode switches all share this dispatch,
     * avoiding repeated busy/idle checks everywhere.
     */
    private fun deliverSystemEvent(sessionId: String, item: PendingNotification) {
        if (sessionJobs[sessionId]?.isActive == true) {
            agentNotificationCenter.enqueue(sessionId, item)
            return
        }
        viewModelScope.launch {
            enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(listOf(item)),
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    private fun TabFinishedEvent.toPendingNotification() = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = tabId,
        title = title,
        outcome = if (exitCode == 0) NotificationOutcome.COMPLETED else NotificationOutcome.FAILED,
        command = command,
        exitCode = exitCode,
        tailOutput = tailOutput
    )

    /**
     * End-of-turn fallback delivery: merges notifications still left in the queue into one message and sends it.
     * Notifications already delivered piggybacked on tool results have been acked and removed by then; if the drain comes up empty, do nothing.
     */
    private fun flushPendingNotifications(sessionId: String) {
        val items = agentNotificationCenter.drain(sessionId)
        if (items.isEmpty()) return
        FileLogger.d(TAG, "flushPendingNotifications: sid=$sessionId items=${items.size} state=${_agentStates.value[sessionId]}")
        viewModelScope.launch {
            enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(items),
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    fun enqueueAgentRequest(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        isAutoTrigger: Boolean = false,
        targetSessionId: String? = null
    ) {
        val sid = targetSessionId ?: _currentSessionId.value
        val isCurrentRunning = sid != null &&
            (sessionJobs[sid]?.isActive == true || sid in _runningCommandSessions.value)
        if (isCurrentRunning) {
            val req = QueuedRequest(
                id = UUID.randomUUID().toString(),
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                isAutoTrigger = isAutoTrigger
            )
            val currentList = _queuedRequests.value[sid] ?: emptyList()
            _queuedRequests.value = _queuedRequests.value + (sid to (currentList + req))
        } else {
            executeAgentRequestStream(
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                targetSessionId = sid,
                isAutoTrigger = isAutoTrigger
            )
        }
    }

    /** Removes an entry from the current session's queue (queue-panel delete button). */
    fun removeQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
    }

    /** Reorders the current session's queue entries (queue-panel drag-and-drop sorting). */
    fun moveQueuedRequest(fromIndex: Int, toIndex: Int) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val reordered = queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        _queuedRequests.value = _queuedRequests.value + (sid to reordered)
    }

    /** Edits the text of a queue entry in the current session (queue-panel edit). */
    fun updateQueuedRequest(id: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.map { req ->
            if (req.id != id) req
            else req.copy(request = text, modelRequest = syncQueuedModelRequest(req, text))
        })
    }

    /**
     * Rebuilds modelRequest from the edited text: in sync with request when no attachments are injected; with attachment injection
     * (composed as `request + "\n\n" + attachment list`), only the text prefix is replaced, keeping the attachment part.
     */
    private fun syncQueuedModelRequest(req: QueuedRequest, newText: String): String {
        if (req.modelRequest == req.request) return newText
        val prefix = req.request.trimEnd()
        return if (req.modelRequest.startsWith(prefix)) {
            newText.trimEnd() + req.modelRequest.removePrefix(prefix)
        } else {
            newText
        }
    }

    /**
     * Immediately injects a queued message of the current session into the running turn: removed from the queue and converted to a user-interjection notification for
     * [deliverSystemEvent] — when busy, piggybacked into this batch's tool results so the AI perceives it in the current turn; when idle, sent
     * as the next turn's user message.
     */
    fun interjectQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        val req = queue.firstOrNull { it.id == id } ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
        deliverSystemEvent(
            sid,
            PendingNotification(
                kind = AgentNotificationKind.USER_MESSAGE,
                sourceId = sid,
                title = "",
                outcome = NotificationOutcome.COMPLETED,
                message = req.request
            )
        )
    }

    private fun processNextInQueue(sessionId: String) {
        // Don't consume when an active job already exists (possibly started before this wrap-up by notification merging/flush entries),
        // avoiding the queue being consumed by multiple wrap-up entries and two jobs running concurrently on one session.
        if (sessionJobs[sessionId]?.isActive == true) return
        val queue = _queuedRequests.value[sessionId] ?: return
        val next = queue.firstOrNull() ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.drop(1))
        executeAgentRequestStream(
            request = next.request,
            modelRequest = next.modelRequest,
            currentFile = next.currentFile,
            selectedCode = next.selectedCode,
            projectRoot = next.projectRoot,
            inputImages = next.inputImages,
            inputAttachments = next.inputAttachments,
            targetSessionId = sessionId,
            isAutoTrigger = next.isAutoTrigger
        )
    }

    /**
     * Executes a slash command: first persists the command text as a user message (entering the conversation context), then dispatches by type —
     * built-in commands run local actions, while skills trigger an agent turn with their body as this turn's instruction.
     * During execution the session is marked command-occupied (preventing parallel execution of new messages); afterwards the next queued entry follows.
     */
    private fun runResolvedCommand(resolved: ResolvedCommand, input: String, sessionId: String) {
        viewModelScope.launch {
            _runningCommandSessions.value = _runningCommandSessions.value + sessionId
            try {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                when (resolved) {
                    is ResolvedCommand.Action -> resolved.handler.execute(this@AIAgentViewModel, resolved.args)
                    is ResolvedCommand.SkillCommand -> runSkill(resolved.skill, resolved.args)
                }
            } finally {
                _runningCommandSessions.value = _runningCommandSessions.value - sessionId
                processNextInQueue(sessionId)
            }
        }
    }

    fun executeAgentRequestStream(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        targetSessionId: String? = null,
        isAutoTrigger: Boolean = false,
        /** Subagent and similar scenarios: the session title is already preset; skip title derivation/generation for the first message and keep the preset title. */
        skipTitleUpdate: Boolean = false
    ): Job = viewModelScope.launch {
        val sessionId = targetSessionId ?: ensureSession()
        if (sessionId.isBlank()) {
            FileLogger.w(TAG, "Workspace not ready; skipping request")
            return@launch
        }
        // Command routing: when a slash command hits (built-in command or skill), skip the agent workflow and execute the command directly
        // (the command text is already persisted as a user message, entering the conversation context). Not registered in sessionJobs,
        // so isRunning stays false and self-checks inside /compress and the like keep working.
        if (request.startsWith("/")) {
            slashCommandRegistry.resolve(request)?.let { command ->
                runResolvedCommand(command, request, sessionId)
                return@launch
            }
        }
        // Legacy-session compatibility: if the session has no persisted providerId/model binding, pin it when sending, so later default-model changes don't affect existing sessions
        val currentSession = sessionUseCase.getSessionById(sessionId)
        if (currentSession != null && (currentSession.providerId.isNullOrBlank() || currentSession.model.isNullOrBlank())) {
            val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
            val defaultModel = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
            if (defaultProviderId != null && defaultModel != null) {
                sessionUseCase.updateProviderModel(sessionId, defaultProviderId, defaultModel)
            }
        }

        coroutineContext[Job]?.let { sessionJobs[sessionId] = it }
        FileLogger.d(TAG, "stream start: sid=$sessionId prevState=${_agentStates.value[sessionId]} isAutoTrigger=$isAutoTrigger")
        setAgentState(sessionId, AgentUIState.Streaming)
        acquireKeepalive()

        try {
            var failed = false
            // History must be read before inserting this user message: the workflow adds(userRequest) itself, avoiding duplication.
            val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
            val isFirst = history.isEmpty()

            val userMsgId = UUID.randomUUID().toString()
            if (!isAutoTrigger) {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, request, id = userMsgId, attachments = inputAttachments)
                checkpointManager.createCheckpoint(sessionId, userMsgId, request)
                if (isFirst && !skipTitleUpdate) {
                    sessionUseCase.updateTitle(sessionId, sessionUseCase.deriveTitle(request))
                    // Asynchronously generate a better title with the LLM in the background to replace the temporary one; keep the temporary title on failure/none
                    viewModelScope.launch {
                        agentWorkflow.generateTitle(sessionId, request)?.let { sessionUseCase.updateTitle(sessionId, it) }
                    }
                }
            } else {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, modelRequest, id = userMsgId,
                    attachments = inputAttachments)
            }
            messagePersistenceUseCase.invalidateHistory(sessionId)
            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())

            val sessionEntity = sessionUseCase.getSessionById(sessionId)
            val sessionDomain = sessionEntity?.toDomain()
            val mode = sessionDomain?.mode ?: AgentMode.BUILD
            // A sub-session's subagentType stores the custom agent name; when its definition is found, both the prompt and the tool set are assembled from it.
            val agentDefinition = sessionEntity?.takeIf { it.parentId != null }
                ?.subagentType
                ?.let { agentDefinitionRepository.findIncludingDisabled(it) }

            val agentContext = AgentContext(
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                language = currentFile?.let { detectLanguage(it) },
                history = history,
                inputImages = inputImages,
                sessionId = sessionId,
                inputMessageId = userMsgId,
                lastInputTokens = sessionEntity?.lastInputTokens ?: 0,
                mode = mode,
                modeBeforePlan = sessionDomain?.modeBeforePlan,
                reasoningEffort = sessionDomain?.reasoningEffort?.apiValue,
                agentDefinition = agentDefinition
            )

            val allTools = toolRegistry.getAvailableTools()
            val isSub = sessionEntity?.parentId != null
            val instaboxReady = remoteEnvironmentSettings.getInstaboxConfig().isReady
            val tools = when {
                agentDefinition != null -> {
                    val allowed = agentDefinition.filterToolNames(allTools.map { it.name }).toSet()
                    allTools.filter { it.name in allowed }
                }
                isSub -> allTools.filterNot { it.name == AgentDefinition.NESTED_TOOL }
                else -> allTools.filterNot { it.name == AgentDefinition.PARENT_MESSAGE_TOOL }
            }.let { list ->
                if (instaboxReady) list else list.filterNot { it.name == "linux_machine" }
            }

            var currentReasoningStart: Long? = null
            var currentReasoningEnd: Long? = null
            fun finishReasoning() {
                val start = currentReasoningStart ?: return
                if (currentReasoningEnd == null) {
                    val end = System.currentTimeMillis()
                    currentReasoningEnd = end
                    _reasoningTimings.value = _reasoningTimings.value + (sessionId to (start to end))
                }
            }
            agentWorkflow.executeEvents(
                userRequest = modelRequest,
                context = agentContext,
                tools = tools
            ).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> {
                        if (event.accumulated.hasVisibleContent()) finishReasoning()
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setStreamingText(sessionId, event.accumulated)
                    }
                    is AgentEvent.ReasoningDelta -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        if (currentReasoningStart == null) {
                            currentReasoningStart = System.currentTimeMillis()
                            currentReasoningEnd = null
                        }
                        _reasoningTimings.value = _reasoningTimings.value +
                            (sessionId to (currentReasoningStart!! to currentReasoningEnd))
                        setStreamingReasoning(sessionId, event.accumulated)
                    }
                    is AgentEvent.ToolCallPreparing -> {
                        finishReasoning()
                        // The tool name arrives before the arguments: let the UI swap "Thinking" for the concrete scene ("Editing file").
                        // Cleared by ToolCallStarted once arguments finish streaming and the tool actually starts, replaced by the tool row.
                        setPreparingTool(sessionId, event.toolName)
                    }
                    is AgentEvent.Retrying -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, RetryState(event.attempt, event.maxRetries, event.error))
                        // A retry re-streams from scratch: clear the already-shown body/thinking bubbles,
                        // otherwise after reconnecting the thinking regenerates while the old body lingers (the workflow has already cleared its accumulators in sync).
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setKeySwitchState(sessionId, null)
                    }
                    is AgentEvent.KeySwitched -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setKeySwitchState(sessionId, KeySwitchState(event.newIndex, event.total))
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                    }
                    is AgentEvent.CompactionStarted -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setCompacting(sessionId, true)
                    }
                    AgentEvent.CompactionFinished -> {
                        setCompacting(sessionId, false)
                        _contextUsages.value = _contextUsages.value - sessionId
                    }
                    is AgentEvent.ContextUsage -> {
                        _contextUsages.value = _contextUsages.value + (sessionId to event)
                    }
                    is AgentEvent.CompactionFailed -> {
                        setCompacting(sessionId, false)
                        // Persisted as an unpaired TOOL message: the UI renders a failure card, buildHistory drops it on replay, and it never enters the model context.
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.reason,
                            toolName = COMPACTION_FAILURE_TOOL_NAME,
                            isError = true
                        )
                    }
                    is AgentEvent.AssistantText -> {
                        finishReasoning()
                        val reasoningDuration = currentReasoningStart?.let { start ->
                            currentReasoningEnd?.minus(start)
                        }
                        currentReasoningStart = null
                        currentReasoningEnd = null

                        // Streaming wrap-up: synchronously clear the streaming state before persisting and triggering the UI messages update,
                        // avoiding a timing gap where persisted messages emit first and the UI shows persisted messages and streaming bubbles side by side.
                        setStreamingReasoning(sessionId, null)
                        setStreamingText(sessionId, null)
                        if (event.toolCalls.isEmpty()) {
                            setPreparingTool(sessionId, null)
                        }

                        val normalized = if (event.content.hasVisibleContent()) event.content else ""
                        val reasoning = event.reasoning.takeIf { it.hasVisibleContent() }
                        val msgId = event.messageId
                        if (reasoningDuration != null && reasoningDuration > 0) {
                            reasoningDurations[msgId] = reasoningDuration
                        }
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.ASSISTANT,
                            normalized,
                            id = msgId,
                            toolCalls = event.toolCalls,
                            reasoning = reasoning,
                            signature = event.signature.ifEmpty { null },
                            thinkingBlocksJson = event.thinkingBlocksJson.ifEmpty { null },
                            attachments = event.attachments,
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                            cachedInputTokens = event.cachedInputTokens
                        )
                        if (event.inputTokens > 0 || event.outputTokens > 0) {
                            _llmCallEvents.tryEmit(LlmCallEvent(sessionId, event.inputTokens, event.outputTokens, event.cachedInputTokens))
                            // Synchronous DB write: before the tool loop's next CallLlm, lastInputTokens is re-read to decide compaction;
                            // an async write might read the stale pre-compaction large value and trigger compaction repeatedly.
                            runCatching {
                                chatSessionDao.addTokenUsage(sessionId, event.inputTokens, event.outputTokens)
                                if (event.inputTokens > 0) {
                                    chatSessionDao.updateLastInputTokens(sessionId, event.inputTokens)
                                }
                            }
                        }
                        event.persisted?.complete(Unit)
                    }
                    is AgentEvent.ToolCallStarted -> {
                        val msgId = "tool_${event.id}"
                        setStreamingText(sessionId, null)
                        setPreparingTool(sessionId, null)
                        toolArgsByMsgId[msgId] = event.argsPreview
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            "${SessionUseCase.PENDING_TOOL_MARKER} ${context.getString(R.string.agent_tool_executing, event.toolName)}",
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview,
                            isError = false
                        )
                        setRunningTool(sessionId, msgId, RunningToolOutput(msgId, "", event.toolName, event.argsPreview))
                    }
                    is AgentEvent.ToolCallProgress -> {
                        val msgId = "tool_${event.id}"
                        setRunningTool(sessionId, msgId, RunningToolOutput(
                            msgId,
                            event.accumulated,
                            event.toolName,
                            toolArgsByMsgId[msgId] ?: ""
                        ))
                    }
                    is AgentEvent.ToolCallFinished -> {
                        val msgId = "tool_${event.id}"
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.result,
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview ?: toolArgsByMsgId[msgId],
                            isError = event.isError,
                            attachments = event.attachments
                        )
                        toolArgsByMsgId.remove(msgId)
                        removeRunningTool(sessionId, msgId)
                        event.persisted?.complete(Unit)
                    }
                    is AgentEvent.Failed -> {
                        failed = true
                        setCompacting(sessionId, false)
                        setAgentState(sessionId, AgentUIState.Error(describeFailure(event)))
                        // Notify the parent session when a subagent session fails
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.FAILED,
                                        detail = event.error
                                    )
                                )
                            }
                        }
                    }
                    AgentEvent.Completed -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setCompacting(sessionId, false)
                        // Notify the parent session when a subagent session completes (async callback)
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.COMPLETED
                                    )
                                )
                            }
                        }
                        // Post the agent completion notification only when the app is not in the foreground (to avoid disturbing a user watching the conversation).
                        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                        if (!inForeground && agentSoundSettings.isEnabled()) {
                            showAgentCompletedNotification(modelRequest)
                        }
                    }
                    is AgentEvent.ModeChanged -> {
                        // Mode-switch event: PlanApprovalManager has already suspended at the workflow layer awaiting user approval;
                        // here we only update the streamingText display
                    }
                }
            }

            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
            // Only set the completion state if this job still holds the busy state: in concurrent scenarios the queue/notifications may have started a new job
            // and switched the state to Streaming, which an earlier-finishing job must not clobber into Result (the button would flip back to send prematurely).
            val finishedState = _agentStates.value[sessionId]
            if (!failed && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
                _completedSessions.value = _completedSessions.value + sessionId
            }
            setStreamingText(sessionId, null)

        } catch (e: CancellationException) {
            val cancelledState = _agentStates.value[sessionId]
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream cancelled: sid=$sessionId isOwnJob=$isOwnJob state=$cancelledState")
            if (isOwnJob &&
                (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)
            ) {
                setAgentState(sessionId, AgentUIState.Idle)
                _completedSessions.value = _completedSessions.value - sessionId
            }
            throw e
        } catch (e: Exception) {
             FileLogger.e(TAG, "executeAgentRequestStream failed: request=$request", e)
             setAgentState(sessionId, AgentUIState.Error(e.toUserMessage()))
             _completedSessions.value = _completedSessions.value - sessionId
        } finally {
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream finally: sid=$sessionId isOwnJob=$isOwnJob state=${_agentStates.value[sessionId]}")
            if (isOwnJob) {
                sessionJobs.remove(sessionId)
            }
            _runningTools.value = _runningTools.value - sessionId
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setPreparingTool(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            setKeySwitchState(sessionId, null)

            // Background notifications not delivered piggybacked this turn: after the turn ends and the job is removed, merge them into one send
            flushPendingNotifications(sessionId)

            // On normal completion, return to Idle first, then process the queue; if the queue has a next turn it sets Streaming again
            val currentState = _agentStates.value[sessionId]
            if (currentState !is AgentUIState.Error && currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                setAgentState(sessionId, AgentUIState.Idle)
            }
            if (currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                processNextInQueue(sessionId)
            }
            // Placed after queue processing: flushPendingNotifications / processNextInQueue synchronously register successor jobs,
            // and only then does hasRunningSessions reflect the real state, instead of releasing and immediately re-acquiring.
            if (!hasRunningSessionsInCurrentWorkspace()) {
                releaseKeepalive()
            }
        }
    }.also { job ->
        // Register the job synchronously: the sessionJobs assignment inside launch is async, so in finally, flushPendingNotifications
        // and processNextInQueue would both see isActive=false before it lands and double-consume, starting two jobs,
        // with the earlier finisher setting Idle/Result and clobbering the still-running job's Streaming.
        if (targetSessionId != null && slashCommandRegistry.resolve(request) == null) {
            sessionJobs[targetSessionId] = job
        }
    }

    fun resolveToolPermission(id: String, choice: PermissionChoice) {
        toolPermissionManager.resolve(id, choice)
    }

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) {
        askUserQuestionManager.resolve(id, answer)
    }

    /** Stops all running AI sessions in the current workspace and closes all terminal tabs (called before switching workspaces). */
    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    /** Stops all running AI sessions in the current workspace (called before switching workspaces). */
    fun stopAllAgents() {
        val jobs = sessionJobs.values.filter { it.isActive }
        jobs.forEach { it.cancel() }
        sessionJobs.clear()
        agentNotificationCenter.clearAll()
        _queuedRequests.value = emptyMap()
        _runningCommandSessions.value = emptySet()
        _agentStates.value = _agentStates.value.mapValues { AgentUIState.Idle }
        _streamingTexts.value = emptyMap()
        _streamingReasonings.value = emptyMap()
        _reasoningTimings.value = emptyMap()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
        releaseKeepalive()
    }

    /**
     * Actively interrupts the agent running in the current session: cancels the coroutine (also cancelling pending network requests and container command processes),
     * and wraps up "executing" tool placeholder rows as "stopped", avoiding dangling spinners and orphan records.
     */
    fun stopAgent() {
        val sessionId = _currentSessionId.value ?: return
        stopAgentSession(sessionId)
    }

    /**
     * Stops the AI task of the given session (shared by subagent stop and manual user stop).
     * Cancels the job and persists unfinished streaming content as "stopped"; the next queue entry runs as usual.
     */
    fun stopAgentSession(sessionId: String) {
        val job = sessionJobs[sessionId] ?: return
        if (!job.isActive) return
        // User manually stopped a running subagent from the UI: return the concurrency slot and tell the parent, otherwise the slot leaks until process restart
        // and the parent waits forever for a completion notification that never comes. TaskTool's stop/del already released it when emitting STOPPED;
        // on that path release returns false and there is no duplicate notification.
        if (subAgentEventBus.release(sessionId)) {
            viewModelScope.launch {
                val sub = sessionUseCase.getSessionById(sessionId)
                val parentId = sub?.parentId
                if (parentId != null) {
                    notifyParentSubAgentFinished(
                        parentSessionId = parentId,
                        subSessionId = sessionId,
                        title = sub.title,
                        outcome = NotificationOutcome.STOPPED,
                        detail = "The user stopped this subagent from the UI; the task did not finish."
                    )
                }
            }
        }
        val runningTools = _runningTools.value[sessionId]?.values?.toList() ?: emptyList()
        val streamingText = _streamingTexts.value[sessionId]
        val streamingReasoning = _streamingReasonings.value[sessionId]
        val pendingPermission = toolPermissionManager.pendingForSession(sessionId)
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        val pendingNotifs = agentNotificationCenter.pendingCount(sessionId)
        FileLogger.d(TAG, "stopAgent: sid=$sessionId runningTools=${runningTools.size} pendingPerm=${pendingPermission?.id} pendingNotifs=$pendingNotifs state=${_agentStates.value[sessionId]}")
        // On Dispatchers.Main.immediate, cancel() may resume suspended coroutines immediately
        // (e.g. awaitApproval's CompletableDeferred.await); the old job's finally →
        // flushPendingNotifications then runs synchronously inside cancel()'s call stack and may start a new job.
        // Don't pre-clear pending notifications — the finally should flush them normally for the new job to handle.
        job.cancel()
        // cancel may have already run finally synchronously (flush started a new job and registered it in sessionJobs);
        // the new job's state must not be overwritten then; clean up only when no new job has taken over.
        if (sessionJobs[sessionId]?.isActive != true) {
            agentNotificationCenter.clear(sessionId)
            setAgentState(sessionId, AgentUIState.Idle)
        }
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        viewModelScope.launch {
            if (runningTools.isNotEmpty()) {
                // Parallel execution aborted: all unfinished tools are persisted as "stopped"
                runningTools.forEach { running ->
                    val partial = running.text.trimEnd()
                    val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                    messagePersistenceUseCase.persist(
                        sessionId = sessionId,
                        role = MessageRole.TOOL,
                        content = content,
                        id = running.messageId,
                        toolCallId = running.messageId.removePrefix("tool_"),
                        toolName = running.toolName.ifBlank { null },
                        toolArgs = running.toolArgs.ifBlank { toolArgsByMsgId[running.messageId] },
                        isError = true
                    )
                    toolArgsByMsgId.remove(running.messageId)
                }
            } else if (!streamingText.isNullOrEmpty() || !streamingReasoning.isNullOrEmpty()) {
                val partial = (streamingText ?: "").trimEnd()
                val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                val reasoning = streamingReasoning?.takeIf { it.hasVisibleContent() }
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = content,
                    reasoning = reasoning
                )
            }
            // Tool calls suspended at the permission dialog: _runningTools is empty while awaitApproval is suspended
            // (ToolCallStarted is emitted only after approval), yet AssistantText already persisted the assistant message
            // carrying the tool_call declaration. Without a backfilled result, that tool_call becomes an orphan,
            // filtered out by buildHistory's validIds intersection, and the AI never knows it made the call.
            if (pendingPermission != null) {
                val msgId = "tool_${pendingPermission.id}"
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.TOOL,
                    content = stoppedText,
                    id = msgId,
                    toolCallId = pendingPermission.id,
                    toolName = pendingPermission.toolName,
                    isError = true
                )
            }
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            // Tapping "Stop" = skip the current turn and immediately run the next queue entry
            processNextInQueue(sessionId)
        }
    }

    // region Session management

    /** Creates a new session; reuses the current one directly if it is still empty, to avoid piling up empty sessions. */
    fun newSession() = viewModelScope.launch {
        if (_currentWorkspace.value.isBlank()) return@launch
        val curId = _currentSessionId.value
        if (curId != null && sessionUseCase.isSessionEmpty(curId)) {
            setAgentState(curId, AgentUIState.Idle)
            return@launch
        }
        // On a new session, asynchronously reconnect unconnected MCP servers so configs added via manageMcp actually take effect;
        // don't block session creation — an unready/timed-out MCP environment must not stall "new session".
        mcpManager.reconnectUnconnectedAsync()
        val sid = createAndUpsertSession(_currentWorkspace.value)
        _currentSessionId.value = sid
    }

    fun setCurrentSessionId(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun setSessionMode(mode: AgentMode) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateMode(sid, mode.name)
            // Switched mid-task: deliver the new mode as a system event to the running agent, effective this very turn (see StatefulAgentWorkflow).
            // No delivery needed when idle — the next turn injects a one-time reminder per the new mode anyway.
            if (sessionJobs[sid]?.isActive == true) {
                agentNotificationCenter.enqueue(
                    sid,
                    PendingNotification(
                        kind = AgentNotificationKind.MODE_CHANGE,
                        sourceId = "user",
                        title = mode.name,
                        outcome = NotificationOutcome.COMPLETED,
                        newMode = mode
                    )
                )
            }
        }
    }

    fun setSessionReasoningEffort(effort: ReasoningEffort) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateReasoningEffort(sid, effort.name)
            // Also remember it as the model-level default tier for later new sessions to inherit
            val s = sessionUseCase.getSessionById(sid)?.toDomain()
            val pid = s?.providerId
            val model = s?.model
            if (!pid.isNullOrBlank() && !model.isNullOrBlank()) {
                modelReasoningEffortRepository.set(pid, model, effort.name)
            }
        }
    }

    fun setSessionProviderModel(providerId: String, model: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateProviderModel(sid, providerId, model)
            _contextUsages.value = _contextUsages.value - sid
            // A selection in an empty session counts as the "new-session default model", reused by the next new session
            if (sessionUseCase.isSessionEmpty(sid)) {
                defaultModelSettingsRepository.setDefaultModel(providerId, model)
            }
        }
    }

    /** Exposed to the UI: the command list shown in the input bar's `/` menu (built-in commands + enabled skills). */
    val slashCommands: StateFlow<List<SlashCommand>> get() = slashCommandRegistry.commands

    /** Rescans skills and refreshes the command menu; called when entering the `/` menu to reflect skill additions/removals/edits. */
    fun refreshSlashCommands() {
        viewModelScope.launch { slashCommandRegistry.refresh() }
    }

    /** /init — triggers an agent turn that analyzes the codebase and generates/improves the project rules file. */
    override fun initProject(prompt: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = prompt,
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** Skill trigger — executes the skill body as this turn's instruction. */
    override fun runSkill(skill: Skill, args: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = SlashCommandRegistry.buildSkillPrompt(skill, args),
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** /usage — outputs today's and cumulative call counts, token usage, and estimated cost as a Markdown table. */
    override fun showUsage() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val todayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = loadUsageSummary(todayStart)
            val allTime = loadUsageSummary(0L)
            val table = buildString {
                appendLine("| Item | Today | All time |")
                appendLine("|---|---|---|")
                appendLine("| Calls | ${today.calls} | ${allTime.calls} |")
                appendLine("| Input tokens | ${formatTokenCount(today.inputTokens)} | ${formatTokenCount(allTime.inputTokens)} |")
                appendLine("| Output tokens | ${formatTokenCount(today.outputTokens)} | ${formatTokenCount(allTime.outputTokens)} |")
                appendLine("| Cached input tokens | ${formatTokenCount(today.cachedInputTokens)} | ${formatTokenCount(allTime.cachedInputTokens)} |")
                appendLine("| Est. cost | ${formatCostUsd(today.costUsd)} | ${formatCostUsd(allTime.costUsd)} |")
            }
            sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isContextExcluded = true)
        }
    }

    private data class UsageSummary(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val costUsd: Double
    )

    /** Aggregates call counts, tokens, and estimated cost since [start]; cost is estimated group by "channel + model" — only that way can custom unit prices be found. */
    private suspend fun loadUsageSummary(start: Long): UsageSummary = withContext(Dispatchers.IO) {
        val summary = llmCallRecordDao.getSummary(start).first()
        val providerTypes = aiProviderRepository.getAllProviders().first().associate { it.id to it.type }
        val cost = llmCallRecordDao.getModelProviderCostStats(start).first().sumOf { stat ->
            val model = stat.model ?: return@sumOf 0.0
            modelCostCalculator.costUsd(
                providerId = stat.providerId.orEmpty(),
                providerType = stat.providerId?.let { providerTypes[it] } ?: ProviderType.OPENAI,
                model = model,
                inputTokens = stat.inputTokens,
                cachedInputTokens = stat.cachedInputTokens,
                outputTokens = stat.outputTokens,
                cacheCreationTokens = stat.cacheCreationTokens
            ) ?: 0.0
        }
        UsageSummary(summary.calls, summary.inputTokens, summary.outputTokens, summary.cachedInputTokens, cost)
    }

    /** /compress — manually triggers context compaction for the current session. */
    override fun compactCurrentSession() {
        val sid = _currentSessionId.value ?: return
        if (isRunning) return
        sessionJobs[sid]?.let { if (it.isActive) return }
        val job = viewModelScope.launch {
            setCompacting(sid, true)
            var failed = false
            try {
                val changed = agentWorkflow.compactSession(sid) { event ->
                    when (event) {
                        is AgentEvent.CompactionStarted -> setCompacting(sid, true)
                        AgentEvent.CompactionFinished -> {
                            setCompacting(sid, false)
                            _contextUsages.value = _contextUsages.value - sid
                        }
                        is AgentEvent.CompactionFailed -> {
                            failed = true
                            setCompacting(sid, false)
                            // Consistent with auto-compaction: persisted as an unpaired TOOL message rendering a failure card, dropped by buildHistory on replay.
                            messagePersistenceUseCase.persist(
                                sessionId = sid,
                                role = MessageRole.TOOL,
                                content = event.reason,
                                toolName = COMPACTION_FAILURE_TOOL_NAME,
                                isError = true
                            )
                        }
                        else -> {}
                    }
                }
                // On successful compaction, the marker divider + summary card already provide feedback, so no duplicate hint bubble is persisted;
                // a one-off hint is given only when "nothing to compact" (empty head / no compressible content).
                if (!failed && !changed) {
                    messagePersistenceUseCase.persist(
                        sessionId = sid,
                        role = MessageRole.ASSISTANT,
                        content = context.getString(R.string.agent_context_no_compaction)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "Manual compaction failed: session=$sid", e)
                messagePersistenceUseCase.persist(
                    sessionId = sid,
                    role = MessageRole.ASSISTANT,
                    content = context.getString(R.string.agent_compaction_failed, e.message)
                )
            } finally {
                setCompacting(sid, false)
                // Compaction is asynchronous; after it ends, continue with the next queued entry
                processNextInQueue(sid)
            }
        }
        sessionJobs[sid] = job
    }

    /** The user approves the plan; wakes the workflow to continue executing in BUILD mode. */
    fun approvePlanAndBuild() {
        planApprovalManager.resolve(PlanApprovalChoice.APPROVE)
    }

    /** The user chooses to keep refining; wakes the workflow to roll back to PLAN mode. */
    fun refinePlan() {
        planApprovalManager.resolve(PlanApprovalChoice.REFINE)
    }

    fun selectSession(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun deleteSessions(ids: Set<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        val allDeletedIds = mutableSetOf<String>()
        ids.forEach { id ->
            val deletedSession = sessionUseCase.getSessionById(id)
            if (deletedSession?.parentId != null) {
                subAgentEventBus.release(id)
            }
            checkpointManager.clearSessionCheckpoints(id)
            val deleted = sessionUseCase.deleteSession(id)
            allDeletedIds.addAll(deleted)
        }

        allDeletedIds.forEach { sid ->
            subAgentEventBus.release(sid)
            sessionJobs[sid]?.cancel()
            sessionJobs.remove(sid)
            _agentStates.value = _agentStates.value - sid
            _streamingTexts.value = _streamingTexts.value - sid
            setStreamingReasoning(sid, null)
            _runningTools.value = _runningTools.value - sid
            _retryStates.value = _retryStates.value - sid
            _queuedRequests.value = _queuedRequests.value - sid
            _inputDrafts.value = _inputDrafts.value - sid
            draftPrefs.edit().remove(sid).apply()
            agentNotificationCenter.clear(sid)
        }

        if (_currentSessionId.value in allDeletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        // When deleting a running subagent, fetch its parent session and title first (gone once deleted) to tell the parent agent later.
        // Deleting a parent session needs no notification (the parent itself is gone), but cascaded sub-sessions must still return their concurrency slots.
        val deletedSession = sessionUseCase.getSessionById(id)
        val stoppedSubAgent = deletedSession?.takeIf { it.parentId != null && subAgentEventBus.release(id) }

        checkpointManager.clearSessionCheckpoints(id)
        val deletedIds = sessionUseCase.deleteSession(id)

        deletedIds.forEach { sid ->
            subAgentEventBus.release(sid)
            sessionJobs[sid]?.cancel()
            sessionJobs.remove(sid)
            _agentStates.value = _agentStates.value - sid
            _streamingTexts.value = _streamingTexts.value - sid
            setStreamingReasoning(sid, null)
            _runningTools.value = _runningTools.value - sid
            _retryStates.value = _retryStates.value - sid
            _queuedRequests.value = _queuedRequests.value - sid
            _inputDrafts.value = _inputDrafts.value - sid
            draftPrefs.edit().remove(sid).apply()
            agentNotificationCenter.clear(sid)
        }

        if (_currentSessionId.value in deletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }

        stoppedSubAgent?.let { sub ->
            notifyParentSubAgentFinished(
                parentSessionId = sub.parentId!!,
                subSessionId = sub.id,
                title = sub.title,
                outcome = NotificationOutcome.STOPPED,
                detail = "The user deleted this subagent session; the task did not finish and its conversation is no longer readable."
            )
        }
    }

    // Target message id selected in Checkpoint Rewind
    private val _targetRewindMessageId = MutableStateFlow<String?>(null)
    val targetRewindMessageId: StateFlow<String?> = _targetRewindMessageId.asStateFlow()

    fun openRewindMenu(messageId: String) {
        _targetRewindMessageId.value = messageId
    }

    fun dismissRewindMenu() {
        _targetRewindMessageId.value = null
    }

    fun executeRewindOption(
        messageId: String,
        option: RewindOption,
        onFillPrompt: (String, List<AgentAttachment>) -> Unit
    ) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        dismissRewindMenu()

        // 1. Stop the currently running Agent task and anything queued after it
        _queuedRequests.value = _queuedRequests.value + (sessionId to emptyList())
        agentNotificationCenter.clear(sessionId)
        val runningJob = sessionJobs[sessionId]
        if (runningJob != null && runningJob.isActive) {
            runningJob.cancelAndJoin()
        }
        sessionJobs.remove(sessionId)

        // 2. Reset the session's running, streaming, and checkpoint state
        setAgentState(sessionId, AgentUIState.Idle)
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        _contextUsages.value = _contextUsages.value - sessionId
        setRetryState(sessionId, null)
        checkpointManager.setActiveCheckpointId(sessionId, null)

        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
        val targetMsgEntity = agentMessageDao.getMessageById(messageId) ?: return@launch
        val attachments = targetMsgEntity.toUIMessage().attachments

        when (option) {
            RewindOption.RESTORE_CODE_AND_CONVERSATION -> {
                if (checkpoint != null) {
                    checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                }
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CONVERSATION -> {
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CODE -> {
                if (checkpoint != null) {
                    checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id)
                }
            }
        }
    }

    /** Renames the session title. Updates only title, not updatedAt, so list order stays unchanged. */
    fun renameSession(id: String, newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        sessionUseCase.updateTitle(id, trimmed)
    }

    /** Pins/unpins a session. Pinned sessions sort to the very front (pinned group); updatedAt is unchanged. */
    fun togglePinSession(id: String) = viewModelScope.launch {
        val pinned = sessions.value.find { it.id == id }?.isPinned ?: return@launch
        sessionUseCase.updatePinned(id, !pinned)
    }

    /** Exports one session to the passwordless backup format (tar.gz), streaming into [output] (opened by the caller; this method closes it). Callbacks true on success, false on failure. */
    fun exportSession(sessionId: String, output: OutputStream, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            backupManager.exportSession(sessionId, output)
            onResult(true)
        } catch (e: Exception) {
            FileLogger.e("AIAgentViewModel", "exportSession failed", e)
            onResult(false)
        } finally {
            runCatching { output.close() }
        }
    }

    private suspend fun ensureSession(): String {
        _currentSessionId.value?.let { return it }
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return ""
        val id = sessionUseCase.getFirstSessionOfWorkspace(ws)?.id ?: createAndUpsertSession(ws)
        _currentSessionId.value = id
        return id
    }

    /** Creates a new session, persists it, and returns the id. */
    private suspend fun createAndUpsertSession(workspacePath: String): String {
        val s = createSession(workspacePath)
        sessionUseCase.upsertSession(s)
        return s.id
    }

    /**
     * Creates a new session and binds provider/model per the "new-session default model"; falls back to the global active provider when no default is set.
     * Every new-session entry point (cold start, new, delete fallback, ensureSession) goes through here.
     */
    private suspend fun createSession(workspacePath: String): ChatSessionEntity {
        val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) {
            modelReasoningEffortRepository.get(providerId, model) ?: ReasoningEffort.MEDIUM.name
        } else {
            ReasoningEffort.MEDIUM.name
        }
        return sessionUseCase.newSessionEntity(
            workspacePath = workspacePath,
            providerId = providerId,
            model = model,
            reasoningEffort = effort
        )
    }

    // endregion

    fun updateMessageContent(messageId: String, newContent: String) = viewModelScope.launch {
        try {
            messagePersistenceUseCase.updateContent(messageId, newContent)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to update message", e)
        }
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        try {
            val msg = agentMessageDao.getMessageById(messageId)
            if (msg != null && msg.role == MessageRole.USER.name) {
                agentMessageDao.deleteMessagesAfterTimestamp(msg.sessionId, msg.timestamp)
            }
            agentMessageDao.deleteMessageById(messageId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to delete message", e)
        }
    }

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "dart" -> "dart"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            else -> "text"
        }
    }
}
