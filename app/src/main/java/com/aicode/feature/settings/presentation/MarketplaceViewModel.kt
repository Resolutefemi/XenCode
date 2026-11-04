package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentCatalog
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import com.aicode.feature.agent.domain.skill.SkillForm
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.skill.SkillSaveError
import com.aicode.feature.agent.domain.skill.SkillScope
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.AgentDefinitionForm
import com.aicode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinitionScope
import com.aicode.feature.agent.domain.subagent.AgentSaveError
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.settings.data.remote.MarketplaceCategory
import com.aicode.feature.settings.data.remote.MarketplaceConfig
import com.aicode.feature.settings.data.remote.MarketplaceService
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Which collection the marketplace screen is showing. */
enum class MarketplaceType { SKILLS, PROMPTS, SUB_AGENTS }

/** Install failure categories; the UI maps each to a readable message. */
enum class MarketplaceInstallFailure {
    NETWORK,
    SERVER,
    INVALID_NAME,
    NAME_CONFLICT,
    EMPTY_CONTENT,

    /** Prompts only: every fragment number 1..99 is already taken. */
    NO_SLOT,
    IO
}

/** Normalized list row shared by all three collections. */
data class MarketplaceRow(
    val id: String,
    val name: String,
    val description: String
)

/**
 * Normalized detail shown in the bottom sheet. [body] holds the installable
 * payload (skill instructions / prompt body / agent prompt); [metaLine] is an
 * optional extra line for sub-agents (mode, effort, inject summary).
 */
data class MarketplaceDetailUi(
    val id: String,
    val name: String,
    val description: String,
    val creator: String? = null,
    val publishedAt: String? = null,
    val version: String? = null,
    val body: String,
    val metaLine: String? = null
)

data class MarketplaceUiState(
    val type: MarketplaceType = MarketplaceType.SKILLS,
    /** Skills only; empty for prompts and sub-agents. */
    val categories: List<MarketplaceCategory> = emptyList(),
    /** Selected category; null means "all". */
    val selectedCategoryId: String? = null,
    val rows: List<MarketplaceRow> = emptyList(),
    /** Last successfully loaded page (1-based); 0 means nothing loaded yet. */
    val page: Int = 0,
    val totalPages: Int = 0,
    val total: Int = 0,
    /** First load / category switch (full-screen skeleton). */
    val loading: Boolean = false,
    /** Full-page load failure; shows a retry button. */
    val loadFailed: Boolean = false,
    /** Next page being appended (small spinner at the list bottom). */
    val appendingPage: Boolean = false,
    /** Whether the detail bottom sheet is open. */
    val detailOpen: Boolean = false,
    /** Detail body being fetched inside the sheet. */
    val detailLoading: Boolean = false,
    /** Detail fetch failed (in-sheet error state, retryable). */
    val detailError: Boolean = false,
    val detail: MarketplaceDetailUi? = null,
    /** Installee id while installing; null when idle. */
    val installingId: String? = null,
    /** Install failure reason; one-shot, cleared by the UI after showing. */
    val installFailure: MarketplaceInstallFailure? = null,
    /** Name of the last successfully installed item; one-shot. */
    val lastInstalledName: String? = null
)

/**
 * Marketplace ViewModel for one collection (skills, prompt templates or
 * sub-agents). Sessions own their list data instead of going through
 * SettingsViewModel; after a successful install the UI invokes onInstalled()
 * so the host page rescans its local list.
 */
@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    private val marketService: MarketplaceService,
    private val skillRepository: SkillRepository,
    private val promptCatalog: PromptFragmentCatalog,
    private val agentRepository: AgentDefinitionRepository,
    private val workspaceRepository: WorkspaceRepository
) : ViewModel() {

    private val _state = MutableStateFlow(MarketplaceUiState())
    val state: StateFlow<MarketplaceUiState> = _state.asStateFlow()

    /** Detail sheet cache: avoids re-fetching bodies when flipping through items. */
    private val detailCache = LinkedHashMap<String, MarketplaceDetailUi>()

    /** Summary of the currently open detail row; used on fetch retries. */
    private var openDetailRow: MarketplaceRow? = null

    /** List request sequence: stale responses after category switches or retries get dropped. */
    private var listRequestId = 0

    private var initialLoadStarted = false

    /**
     * Binds this instance to [type] and starts the first load. The same
     * instance can be reused for a different type (Hilt keyed instances make
     * this a no-op in practice, but the guard keeps the state consistent).
     */
    fun bind(type: MarketplaceType) {
        if (initialLoadStarted) {
            if (_state.value.type == type) reload()
            return
        }
        initialLoadStarted = true
        _state.update { it.copy(type = type) }
        reload()
    }

    /** Retry after a full-page failure: refetch categories (skills) plus page 1. */
    fun reload() {
        val type = _state.value.type
        _state.update { it.copy(loading = true, loadFailed = false) }
        if (type != MarketplaceType.SKILLS) {
            fetchPage(reset = true)
            return
        }
        viewModelScope.launch {
            marketService.categories().fold(
                onSuccess = { categories ->
                    _state.update { it.copy(categories = categories) }
                    fetchPage(reset = true)
                },
                onFailure = {
                    _state.update { it.copy(loading = false, loadFailed = true) }
                }
            )
        }
    }

    /** Switch category (skills only); null means "all". Repeat clicks are ignored. */
    fun selectCategory(categoryId: String?) {
        if (_state.value.type != MarketplaceType.SKILLS) return
        if (_state.value.selectedCategoryId == categoryId) return
        _state.update { it.copy(selectedCategoryId = categoryId) }
        fetchPage(reset = true)
    }

    /** Requests the next page when the list nears its bottom; ignored while loading or drained. */
    fun loadNextPage() {
        val current = _state.value
        if (current.loading || current.appendingPage || current.loadFailed) return
        if (current.page < 1 || current.page >= current.totalPages) return
        fetchPage(reset = false)
    }

    /** Opens the detail sheet; cached bodies render instantly, others fetch first. */
    fun openDetail(row: MarketplaceRow) {
        openDetailRow = row
        val cached = detailCache[row.id]
        if (cached != null) {
            _state.update { it.copy(detailOpen = true, detail = cached, detailLoading = false, detailError = false) }
            return
        }
        _state.update { it.copy(detailOpen = true, detail = null, detailLoading = true, detailError = false) }
        val type = _state.value.type
        viewModelScope.launch {
            val result = when (type) {
                MarketplaceType.SKILLS ->
                    marketService.skill(row.id).map { raw ->
                        MarketplaceDetailUi(
                            id = raw.id,
                            name = raw.name,
                            description = raw.description,
                            creator = raw.creator,
                            publishedAt = raw.publishedAt,
                            version = raw.version,
                            body = raw.instructions
                        )
                    }
                MarketplaceType.PROMPTS ->
                    marketService.prompt(row.id).map { raw ->
                        MarketplaceDetailUi(
                            id = raw.id,
                            name = raw.name,
                            description = raw.description,
                            creator = raw.creator,
                            publishedAt = raw.publishedAt,
                            version = raw.version,
                            body = raw.body
                        )
                    }
                MarketplaceType.SUB_AGENTS ->
                    marketService.subAgent(row.id).map { raw ->
                        MarketplaceDetailUi(
                            id = raw.id,
                            name = raw.name,
                            description = raw.description,
                            creator = raw.creator,
                            publishedAt = raw.publishedAt,
                            version = raw.version,
                            body = raw.prompt,
                            metaLine = subAgentMetaLine(raw.mode, raw.reasoningEffort, raw.inject)
                        )
                    }
            }
            result.fold(
                onSuccess = { detail ->
                    detailCache[detail.id] = detail
                    _state.update { state ->
                        // Sheet closed or target changed meanwhile: only fill the cache.
                        if (state.detailOpen && openDetailRow?.id == detail.id) {
                            state.copy(detail = detail, detailLoading = false, detailError = false)
                        } else {
                            state.copy(detailLoading = false)
                        }
                    }
                },
                onFailure = {
                    _state.update { state ->
                        if (state.detailOpen && openDetailRow?.id == row.id && state.detail == null) {
                            state.copy(detailLoading = false, detailError = true)
                        } else {
                            state.copy(detailLoading = false)
                        }
                    }
                }
            )
        }
    }

    /** Retry after an in-sheet detail failure. */
    fun retryDetail() {
        val row = openDetailRow ?: return
        openDetail(row)
    }

    fun dismissDetail() {
        openDetailRow = null
        _state.update { it.copy(detailOpen = false, detail = null, detailLoading = false, detailError = false) }
    }

    /**
     * Installs the item currently shown in the sheet; name conflicts retry
     * with a numbered suffix. Ignored while the detail body is not ready.
     */
    fun install() {
        val current = _state.value
        val detail = current.detail ?: return
        if (current.installingId != null) return
        _state.update { it.copy(installingId = detail.id, installFailure = null) }
        viewModelScope.launch {
            val failure = when (current.type) {
                MarketplaceType.SKILLS -> installSkill(detail)
                MarketplaceType.PROMPTS -> installPrompt(detail)
                MarketplaceType.SUB_AGENTS -> installSubAgent(detail)
            }
            _state.update { state ->
                if (failure == null) {
                    state.copy(installingId = null, lastInstalledName = detail.name)
                } else {
                    state.copy(installingId = null, installFailure = failure)
                }
            }
        }
    }

    /** Clears one-shot feedback fields once the UI has shown them. */
    fun clearInstallFeedback() {
        _state.update { it.copy(installFailure = null, lastInstalledName = null) }
    }

    /** Saves the skill into project scope; NAME_CONFLICT retries with a numbered suffix. */
    private suspend fun installSkill(detail: MarketplaceDetailUi): MarketplaceInstallFailure? {
        val raw = marketService.skill(detail.id).getOrNull() ?: return MarketplaceInstallFailure.SERVER
        val baseName = detail.name.trim()
        var candidate = baseName
        var attempt = 0
        while (true) {
            val error = withContext(Dispatchers.IO) {
                skillRepository.save(
                    form = SkillForm(
                        name = candidate,
                        description = raw.description,
                        instructions = raw.instructions,
                        requiredTools = raw.requiredTools
                    ),
                    scope = SkillScope.PROJECT
                )
            }
            val failure = error?.toInstallFailure()
            if (failure != MarketplaceInstallFailure.NAME_CONFLICT || attempt >= MAX_NAME_ATTEMPTS) {
                return failure
            }
            attempt += 1
            candidate = "$baseName $attempt"
        }
    }

    /**
     * Saves the template as a prompt fragment: picks the smallest free number
     * in 1..99, writes into the project layer when a workspace is open and
     * into the global layer otherwise. No name conflicts exist because the
     * number is the identity.
     */
    private suspend fun installPrompt(detail: MarketplaceDetailUi): MarketplaceInstallFailure? {
        val projectRoot = withContext(Dispatchers.IO) { workspaceRepository.currentPath() }
        val usedNumbers = withContext(Dispatchers.IO) {
            promptCatalog.list(projectRoot).map(PromptFragment::number).toSet()
        }
        val number = (MIN_PROMPT_NUMBER..MAX_PROMPT_NUMBER).firstOrNull { it !in usedNumbers }
            ?: return MarketplaceInstallFailure.NO_SLOT
        val target = if (projectRoot.isNullOrBlank()) PromptFragmentSource.GLOBAL else PromptFragmentSource.PROJECT
        val content = PromptFragment.composeContent(description = detail.description, body = detail.body)
        val saved = withContext(Dispatchers.IO) {
            promptCatalog.saveOverride(
                number = number,
                title = detail.name.trim(),
                content = content,
                projectRoot = projectRoot,
                target = target
            )
        }
        return if (saved) null else MarketplaceInstallFailure.IO
    }

    /** Saves the sub-agent into project scope; NAME_CONFLICT retries with a numbered suffix. */
    private suspend fun installSubAgent(detail: MarketplaceDetailUi): MarketplaceInstallFailure? {
        val raw = marketService.subAgent(detail.id).getOrNull() ?: return MarketplaceInstallFailure.SERVER
        val baseName = detail.name.trim()
        var candidate = baseName
        var attempt = 0
        while (true) {
            val error = withContext(Dispatchers.IO) {
                agentRepository.save(
                    form = AgentDefinitionForm(
                        name = candidate,
                        description = raw.description,
                        providerId = raw.providerId,
                        model = raw.model,
                        reasoningEffort = raw.reasoningEffort,
                        mode = raw.mode?.let { parseAgentMode(it) },
                        allowedTools = raw.allowedTools,
                        disallowedTools = raw.disallowedTools,
                        inject = parseInject(raw.inject),
                        prompt = raw.prompt
                    ),
                    scope = AgentDefinitionScope.PROJECT
                )
            }
            val failure = error?.toInstallFailure()
            if (failure != MarketplaceInstallFailure.NAME_CONFLICT || attempt >= MAX_NAME_ATTEMPTS) {
                return failure
            }
            attempt += 1
            candidate = "$baseName $attempt"
        }
    }

    /** Fetches one page; reset=true clears and shows the skeleton, false appends. */
    private fun fetchPage(reset: Boolean) {
        val requestId = ++listRequestId
        val type = _state.value.type
        if (reset) {
            _state.update {
                it.copy(
                    loading = true,
                    loadFailed = false,
                    rows = emptyList(),
                    page = 0,
                    totalPages = 0,
                    total = 0,
                    appendingPage = false
                )
            }
        } else {
            _state.update { it.copy(appendingPage = true) }
        }

        val queryPage = _state.value.page + 1
        val queryCategory = _state.value.selectedCategoryId
        viewModelScope.launch {
            val result = when (type) {
                MarketplaceType.SKILLS -> marketService.skills(
                    category = queryCategory,
                    page = queryPage,
                    pageSize = MarketplaceConfig.DEFAULT_PAGE_SIZE
                ).map { page ->
                    page.items.map { MarketplaceRow(id = it.id, name = it.name, description = it.description) } to
                        MarketplaceMeta(page = page.page, totalPages = page.totalPages, total = page.total)
                }
                MarketplaceType.PROMPTS -> marketService.prompts(
                    page = queryPage,
                    pageSize = MarketplaceConfig.DEFAULT_PAGE_SIZE
                ).map { page ->
                    page.items.map { MarketplaceRow(id = it.id, name = it.name, description = it.description) } to
                        MarketplaceMeta(page = page.page, totalPages = page.totalPages, total = page.total)
                }
                MarketplaceType.SUB_AGENTS -> marketService.subAgents(
                    page = queryPage,
                    pageSize = MarketplaceConfig.DEFAULT_PAGE_SIZE
                ).map { page ->
                    page.items.map { MarketplaceRow(id = it.id, name = it.name, description = it.description) } to
                        MarketplaceMeta(page = page.page, totalPages = page.totalPages, total = page.total)
                }
            }
            result.fold(
                onSuccess = { (rows, meta) ->
                    if (requestId != listRequestId) return@fold
                    _state.update { state ->
                        val merged = (state.rows + rows).distinctBy { it.id }
                        state.copy(
                            rows = merged,
                            page = meta.page,
                            totalPages = meta.totalPages,
                            total = meta.total,
                            loading = false,
                            loadFailed = false,
                            appendingPage = false
                        )
                    }
                },
                onFailure = {
                    if (requestId != listRequestId) return@fold
                    _state.update { state ->
                        if (reset) {
                            state.copy(loading = false, appendingPage = false, loadFailed = true)
                        } else {
                            state.copy(appendingPage = false)
                        }
                    }
                }
            )
        }
    }

    private data class MarketplaceMeta(val page: Int, val totalPages: Int, val total: Int)

    /** Human-readable one-liner for a sub-agent's behavior fields; null when plain. */
    private fun subAgentMetaLine(mode: String?, effort: String?, inject: List<String>?): String? {
        val parts = buildList {
            mode?.let { add(it) }
            effort?.let { add(it) }
            inject?.takeIf { it.isNotEmpty() && it != listOf("none") }?.let { add(it.joinToString("+")) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
    }

    /** Server mode token to AgentMode; unmatched values fall back to inherit. */
    private fun parseAgentMode(raw: String): AgentMode? =
        AgentMode.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    /**
     * Server inject tokens to InjectPart set. ["none"] maps to an empty set
     * (inject nothing); unknown tokens are dropped, an all-empty result falls
     * back to the app default.
     */
    private fun parseInject(tokens: List<String>?): Set<InjectPart> {
        if (tokens == null) return AgentDefinition.DEFAULT_INJECT
        if (tokens.size == 1 && tokens[0].trim().equals("none", ignoreCase = true)) return emptySet()
        val parsed = tokens.mapNotNull { InjectPart.fromToken(it) }.toSet()
        return parsed.ifEmpty { AgentDefinition.DEFAULT_INJECT }
    }

    private fun SkillSaveError.toInstallFailure(): MarketplaceInstallFailure = when (this) {
        SkillSaveError.INVALID_NAME -> MarketplaceInstallFailure.INVALID_NAME
        SkillSaveError.EMPTY_INSTRUCTIONS -> MarketplaceInstallFailure.EMPTY_CONTENT
        SkillSaveError.NAME_CONFLICT -> MarketplaceInstallFailure.NAME_CONFLICT
        SkillSaveError.IO_FAILED -> MarketplaceInstallFailure.IO
    }

    private fun AgentSaveError.toInstallFailure(): MarketplaceInstallFailure = when (this) {
        AgentSaveError.INVALID_NAME -> MarketplaceInstallFailure.INVALID_NAME
        AgentSaveError.EMPTY_PROMPT -> MarketplaceInstallFailure.EMPTY_CONTENT
        AgentSaveError.NAME_CONFLICT -> MarketplaceInstallFailure.NAME_CONFLICT
        AgentSaveError.IO_FAILED -> MarketplaceInstallFailure.IO
    }

    private companion object {
        const val MAX_NAME_ATTEMPTS = 20
        const val MIN_PROMPT_NUMBER = 1
        const val MAX_PROMPT_NUMBER = 99
    }
}
