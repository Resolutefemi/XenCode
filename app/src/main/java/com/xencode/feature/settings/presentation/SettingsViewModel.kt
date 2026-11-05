package com.xencode.feature.settings.presentation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xencode.core.net.AppProxy
import com.xencode.core.util.FileLogger
import com.xencode.core.util.LogLevel
import com.xencode.feature.agent.data.local.dao.LlmCallRecordDao
import com.xencode.feature.agent.data.local.dao.RecentCallRecord
import com.xencode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.xencode.feature.agent.domain.container.ConnectionState
import com.xencode.feature.agent.domain.container.ContainerImageCatalog
import com.xencode.feature.agent.domain.container.ContainerImageEntry
import com.xencode.feature.agent.domain.container.ContainerInstaller
import com.xencode.feature.agent.domain.container.ContainerOsDetector
import com.xencode.feature.agent.domain.container.ContainerProfile
import com.xencode.feature.agent.domain.container.RemoteSshConnection
import com.xencode.feature.agent.domain.container.RootfsSource
import com.xencode.feature.agent.domain.device.DeviceRootEngine
import com.xencode.feature.agent.domain.mcp.McpConfigRepository
import com.xencode.feature.agent.domain.mcp.McpManager
import com.xencode.feature.agent.domain.mcp.McpScope
import com.xencode.feature.agent.domain.mcp.McpServerConfig
import com.xencode.feature.agent.domain.mcp.McpServerEntry
import com.xencode.feature.agent.domain.mcp.McpServerStatus
import com.xencode.feature.agent.domain.mcp.McpToolDescriptor
import com.xencode.feature.agent.domain.model.AgentMode
import com.xencode.feature.agent.domain.permission.HighRiskRule
import com.xencode.feature.agent.domain.permission.HighRiskRulesRepository
import com.xencode.feature.agent.domain.permission.PermissionRule
import com.xencode.feature.agent.domain.permission.PermissionRulesRepository
import com.xencode.feature.agent.domain.skill.SkillConfigRepository
import com.xencode.feature.agent.domain.skill.SkillForm
import com.xencode.feature.agent.domain.skill.SkillImportError
import com.xencode.feature.agent.domain.skill.SkillImportReport
import com.xencode.feature.agent.domain.skill.SkillRepository
import com.xencode.feature.agent.domain.skill.SkillSaveError
import com.xencode.feature.agent.domain.skill.SkillScope
import com.xencode.feature.agent.domain.subagent.AgentDefinitionConfigRepository
import com.xencode.feature.agent.domain.subagent.AgentDefinitionForm
import com.xencode.feature.agent.domain.subagent.AgentDefinitionRepository
import com.xencode.feature.agent.domain.subagent.AgentDefinitionScope
import com.xencode.feature.agent.domain.subagent.AgentSaveError
import com.xencode.feature.agent.domain.subagent.InjectPart
import com.xencode.feature.agent.domain.tool.ToolRegistry
import com.xencode.feature.settings.data.remote.ModelApiService
import com.xencode.feature.settings.data.remote.ContainerImageDownloader
import com.xencode.feature.settings.data.remote.ModelMetadataService
import com.xencode.feature.settings.data.remote.ModelTestResult
import com.xencode.feature.settings.data.remote.UpdateCheckResult
import com.xencode.feature.settings.data.remote.UpdateCheckService
import com.xencode.feature.settings.data.repository.UpdateCheckSettingsRepository
import com.xencode.feature.settings.data.repository.UpdateChannel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.xencode.feature.settings.data.repository.AppThemeMode
import com.xencode.feature.settings.data.repository.ContainerSettingsRepository
import com.xencode.feature.settings.data.repository.DownloadedImageRecord
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.CommandAuthLevel
import com.xencode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.xencode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.xencode.feature.settings.data.repository.TitleModelSettingsRepository
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import com.xencode.feature.settings.data.repository.ExecutionModeRepository
import com.xencode.feature.settings.data.repository.GeneralSettingsRepository
import com.xencode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.xencode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.xencode.feature.settings.data.repository.LanguageSettingsRepository
import com.xencode.feature.settings.data.repository.LogSettingsRepository
import com.xencode.feature.settings.data.repository.ReviewerModelSettingsRepository
import com.xencode.feature.settings.data.repository.ContextWindowOverrideRepository
import com.xencode.feature.settings.data.repository.ProxyConfig
import com.xencode.feature.settings.data.repository.ProxySettingsRepository
import com.xencode.feature.settings.data.repository.ScreenOnSettingsRepository
import com.xencode.feature.settings.data.repository.StartupSessionMode
import com.xencode.feature.settings.data.repository.TodoDisplayPosition
import com.xencode.feature.settings.data.repository.ThemeSettingsRepository
import com.xencode.feature.settings.data.repository.ToolSafetySettingsRepository
import com.xencode.feature.settings.data.repository.BackgroundSettingsRepository
import com.xencode.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.xencode.feature.settings.data.repository.VisionModelSettingsRepository
import com.xencode.feature.workspace.domain.model.RemoteConnection
import com.xencode.feature.workspace.domain.repository.RemoteRepository
import com.xencode.feature.settings.domain.model.AIProviderConfig
import com.xencode.feature.settings.domain.model.DashboardContext
import com.xencode.feature.settings.domain.model.ModelMetadata
import com.xencode.feature.settings.domain.model.modelMetadataKey
import com.xencode.feature.settings.domain.model.ProviderDashboardResult
import com.xencode.feature.settings.domain.model.ProviderDashboardState
import com.xencode.feature.settings.domain.service.ModelCostCalculator
import com.xencode.feature.settings.domain.service.ProviderDashboardRunner
import com.xencode.feature.settings.domain.model.ProviderType
import com.xencode.feature.settings.domain.model.ProxyType
import com.xencode.R
import com.xencode.feature.settings.domain.repository.AIProviderRepository
import com.xencode.feature.terminal.data.repository.TerminalSettings
import com.xencode.feature.terminal.data.repository.TerminalSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class FetchState {
    object Idle : FetchState()
    object Loading : FetchState()
    data class Success(val models: List<String>, val debugInfo: ModelTestResult? = null) : FetchState()
    data class Error(val message: String, val debugInfo: ModelTestResult? = null) : FetchState()
}

/** Full UI state for the image download page: idle / downloading (progress) / downloaded (installable) / failed. */
sealed interface ContainerImageDownloadUiState {
    data object Idle : ContainerImageDownloadUiState
    data class Downloading(
        val entryId: String,
        val name: String,
        val version: String,
        val sourceId: String,
        val bytesRead: Long,
        val totalBytes: Long
    ) : ContainerImageDownloadUiState
    data class Done(
        val entryId: String,
        val name: String,
        val version: String,
        val fileUri: String
    ) : ContainerImageDownloadUiState
    data class Error(val entryId: String, val message: String) : ContainerImageDownloadUiState
}

/** In-progress state of a container reset: only set while resetting; [deleted] is the number of files and directories removed. */
data class ContainerResetUiState(val name: String, val deleted: Int)

/** Device ROOT (su) probe state: the "Command execution channel" page shows it and decides whether switching is allowed. */
enum class RootProbeState { UNKNOWN, PROBING, AVAILABLE, UNAVAILABLE }

data class LogViewerUiState(
    val files: List<String> = emptyList(),
    val selectedFileName: String? = null,
    val filterServerName: String? = null,
    val content: String = "",
    val totalLines: Int = 0,
    val shownLines: Int = 0,
    val loading: Boolean = false,
    val error: String? = null
)

/** Update-check dialog state (rendered by the global dialog host at the home AppNavigation layer, shared by auto/manual checks). */
sealed interface UpdateCheckUiState {
    data object Idle : UpdateCheckUiState
    data object Checking : UpdateCheckUiState
    data object UpToDate : UpdateCheckUiState
    data class NewVersion(val latestTag: String, val changelog: String) : UpdateCheckUiState
    data class Error(val message: String) : UpdateCheckUiState
}

/** Token statistics period: determines the range start and trend granularity (today = hourly, others = daily). */
enum class TokenStatsPeriod(val labelRes: Int, val startMillis: (Long) -> Long) {
    TODAY(R.string.settings_token_stats_period_today, { now ->
        java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
    }),
    LAST_7_DAYS(R.string.settings_token_stats_period_7d, { now -> now - 7 * 86_400_000L }),
    LAST_30_DAYS(R.string.settings_token_stats_period_30d, { now -> now - 30 * 86_400_000L }),
    ALL(R.string.settings_token_stats_period_all, { 0L })
}

/** Full UI state of the Token statistics page, composed from the period + 5 aggregate flows. */
data class TokenStatsUiState(
    val period: TokenStatsPeriod = TokenStatsPeriod.LAST_7_DAYS,
    val summary: com.xencode.feature.agent.data.local.dao.CallSummary? = null,
    val trend: List<com.xencode.feature.agent.data.local.dao.DayCallStats> = emptyList(),
    val providers: List<com.xencode.feature.agent.data.local.dao.ProviderCallStats> = emptyList(),
    val models: List<com.xencode.feature.agent.data.local.dao.ModelCallStats> = emptyList(),
    val recentCalls: List<RecentCallRecord> = emptyList(),
    /** Current page of call details (0-based). */
    val callsPage: Int = 0,
    /** Total calls in the current period, for showing the total page count in pagination. */
    val callsTotal: Int = 0,
    /** Current page of channels (0-based). */
    val providersPage: Int = 0,
    /** Total number of channels. */
    val providersTotal: Int = 0,
    /** Current page of models (0-based). */
    val modelsPage: Int = 0,
    /** Total number of models. */
    val modelsTotal: Int = 0,
    /** Total cost of the current period (USD; channel custom prices first, otherwise estimated from models.dev prices). */
    val totalCostUsd: Double = 0.0,
    /** Cost of each record within the current call details page (key = record id, null = model has no prices). */
    val recentCallCosts: Map<Long, Double?> = emptyMap(),
    /** Filter: specific provider ID (null = no restriction). */
    val filterProviderId: String? = null,
    /** Filter: specific model name (null = no restriction). */
    val filterModel: String? = null,
    /** Selectable provider list (id to name). */
    val availableProviders: List<Pair<String, String>> = emptyList(),
    /** Selectable model list. */
    val availableModels: List<String> = emptyList(),
    /** Number of keys/credentials per provider ID. */
    val providerKeyCounts: Map<String, Int> = emptyMap()
)

private data class ModelStatsPaging(
    val paged: List<com.xencode.feature.agent.data.local.dao.ModelCallStats>,
    val page: Int,
    val total: Int
)

/** UI state of the skills list page: skills + source scope + enabled state. */
data class SkillUiEntry(
    val name: String,
    val description: String,
    val scope: SkillScope,
    val disabled: Boolean,
    val instructions: String,
    /** The `required_tools` of a handwritten skill; hidden on the edit page and written back verbatim on save. */
    val requiredTools: List<String> = emptyList()
)

/** UI state of the subagents list page: definitions + source scope + enabled state. */
data class SubAgentUiEntry(
    val name: String,
    val description: String,
    val scope: AgentDefinitionScope,
    val disabled: Boolean,
    val providerId: String?,
    val model: String?,
    val reasoningEffort: String?,
    val mode: AgentMode?,
    val allowedTools: List<String>,
    val disallowedTools: List<String>,
    val inject: Set<InjectPart>,
    val prompt: String,
    val filePath: String?
)

/** Save result of the skill edit page: the UI decides whether to return to the list or show an inline error. */
sealed interface SkillSaveState {
    data object Idle : SkillSaveState
    data object Saving : SkillSaveState
    data object Saved : SkillSaveState
    data class Failed(val error: SkillSaveError) : SkillSaveState
}

/** UI state of skill import: idle / importing / done (with a result report). */
sealed interface SkillImportState {
    data object Idle : SkillImportState
    data object Running : SkillImportState
    data class Done(val report: SkillImportReport) : SkillImportState
}

/** Save result of the subagent edit page: the UI decides whether to return to the list or show an inline error. */
sealed interface SubAgentSaveState {
    data object Idle : SubAgentSaveState
    data object Saving : SubAgentSaveState
    data object Saved : SubAgentSaveState
    data class Failed(val error: AgentSaveError) : SubAgentSaveState
}

/**
 * Pads the trend aggregation into a complete timeline within the period: days/hours without records get 0, so that when calls within a period concentrate on a single day
 * the trend does not end up with a single point that cannot be drawn as a line chart. The "All" period pads from the earliest recorded day through today.
 */
private fun padTrend(
    period: TokenStatsPeriod,
    trend: List<com.xencode.feature.agent.data.local.dao.DayCallStats>,
    tzOffsetMillis: Long
): List<com.xencode.feature.agent.data.local.dao.DayCallStats> {
    if (trend.isEmpty()) return emptyList()
    val now = System.currentTimeMillis()
    val byDay = trend.associateBy { it.day }
    val isHourly = period == TokenStatsPeriod.TODAY
    val bucketMillis = if (isHourly) 3_600_000L else 86_400_000L
    val endIndex = (now + tzOffsetMillis) / bucketMillis
    val startIndex = when (period) {
        TokenStatsPeriod.TODAY -> (period.startMillis(now) + tzOffsetMillis) / bucketMillis
        TokenStatsPeriod.ALL -> trend.first().day
        else -> (period.startMillis(now) + tzOffsetMillis) / bucketMillis
    }
    if (startIndex > endIndex) return trend
    return (startIndex..endIndex).map { index ->
        byDay[index] ?: com.xencode.feature.agent.data.local.dao.DayCallStats(index, 0, 0, 0, 0, 0, null, null)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: AIProviderRepository,
    private val modelApiService: ModelApiService,
    private val modelMetadataService: ModelMetadataService,
    private val logSettingsRepository: LogSettingsRepository,
    private val themeSettingsRepository: ThemeSettingsRepository,
    private val backgroundSettingsRepository: BackgroundSettingsRepository,
    private val keepaliveSettingsRepository: KeepaliveSettingsRepository,
    private val screenOnSettingsRepository: ScreenOnSettingsRepository,
    private val agentSoundSettingsRepository: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val languageSettingsRepository: LanguageSettingsRepository,
    private val mcpConfigRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val permissionRulesRepository: PermissionRulesRepository,
    private val highRiskRulesRepository: HighRiskRulesRepository,
    private val toolSafetySettingsRepository: ToolSafetySettingsRepository,
    private val skillRepository: SkillRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val agentDefinitionConfigRepository: AgentDefinitionConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val skillConfigRepository: SkillConfigRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val imageGenModelSettingsRepository: ImageGenModelSettingsRepository,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val reviewerModelSettingsRepository: ReviewerModelSettingsRepository,
    private val contextWindowOverrideRepository: ContextWindowOverrideRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val containerImageCatalog: ContainerImageCatalog,
    private val containerImageDownloader: ContainerImageDownloader,
    private val containerInstaller: ContainerInstaller,
    private val containerOsDetector: ContainerOsDetector,
    private val executionModeRepository: ExecutionModeRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val deviceRootEngine: DeviceRootEngine,
    private val remoteSshConnection: RemoteSshConnection,
    private val remoteRepository: RemoteRepository,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val updateCheckSettingsRepository: UpdateCheckSettingsRepository,
    private val updateCheckService: UpdateCheckService,
    private val providerDashboardRunner: ProviderDashboardRunner,
    private val terminalSettingsRepository: TerminalSettingsRepository,
    private val proxySettingsRepository: ProxySettingsRepository
) : ViewModel() {
    private companion object {
        const val MAX_LOG_LINES = 1200
        const val CALLS_PAGE_SIZE = 10
        const val STATS_PAGE_SIZE = 5
        /** Delay before persisting the background opacity after dragging stops. */
        const val BACKGROUND_ALPHA_WRITE_DEBOUNCE_MS = 80L

        /** Accepted extensions for skill file import (lowercase, no dot). */
        val MARKDOWN_EXTENSIONS = setOf("md", "markdown", "txt")

        /** Accepted extensions for skill archive import (lowercase, no dot). */
        val ZIP_EXTENSIONS = setOf("zip")
    }

    /** Terminal personalization settings. */
    val terminalSettings: StateFlow<TerminalSettings> = terminalSettingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalSettings())

    fun setTerminalTheme(themeId: String) {
        viewModelScope.launch { terminalSettingsRepository.setThemeId(themeId) }
    }

    fun setTerminalFontSize(sizeSp: Int) {
        viewModelScope.launch { terminalSettingsRepository.setFontSizeSp(sizeSp) }
    }

    fun setTerminalCursorStyle(style: Int) {
        viewModelScope.launch { terminalSettingsRepository.setCursorStyle(style) }
    }

    fun setTerminalFontPath(path: String) {
        viewModelScope.launch { terminalSettingsRepository.setFontPath(path) }
    }

    // ── Global proxy ──

    /** Snapshot of the global proxy config (read/written instantly via SharedPreferences; changes apply to new connections immediately). */
    val proxyConfig: StateFlow<ProxyConfig> = proxySettingsRepository.config

    /** Proxy connectivity test state. */
    sealed interface ProxyTestUiState {
        data object Idle : ProxyTestUiState
        data object Testing : ProxyTestUiState
        data class Success(val message: String) : ProxyTestUiState
        data class Error(val message: String) : ProxyTestUiState
    }

    private val _proxyTestState = MutableStateFlow<ProxyTestUiState>(ProxyTestUiState.Idle)
    val proxyTestState: StateFlow<ProxyTestUiState> = _proxyTestState.asStateFlow()

    fun setProxyEnabled(enabled: Boolean) = proxySettingsRepository.setEnabled(enabled)
    fun setProxyType(type: ProxyType) = proxySettingsRepository.setType(type)
    fun setProxyHost(host: String) = proxySettingsRepository.setHost(host)
    fun setProxyPort(port: Int) = proxySettingsRepository.setPort(port)
    fun setProxyUsername(username: String) = proxySettingsRepository.setUsername(username)
    fun setProxyPassword(password: String) = proxySettingsRepository.setPassword(password)
    fun setProxyNoProxy(noProxy: String) = proxySettingsRepository.setNoProxy(noProxy)

    fun testProxy(probeUrl: String) = testProxy(proxySettingsRepository.config.value, probeUrl)

    /** Tests any proxy configuration (reused by the per-provider proxy page for form-state configs). */
    fun testProxy(cfg: ProxyConfig, probeUrl: String) {
        if (_proxyTestState.value is ProxyTestUiState.Testing) return
        _proxyTestState.value = ProxyTestUiState.Testing
        viewModelScope.launch {
            val result = AppProxy.testProxy(context, cfg, probeUrl)
            _proxyTestState.value = if (result.ok) {
                ProxyTestUiState.Success(result.message)
            } else {
                ProxyTestUiState.Error(result.message)
            }
        }
    }

    private val _providers = MutableStateFlow<List<AIProviderConfig>>(emptyList())
    val providers: StateFlow<List<AIProviderConfig>> = _providers.asStateFlow()

    /** Default model for new sessions (remembered when selected in an empty home session), as the fallback for unbound sessions. */
    val defaultModelProviderId: StateFlow<String> = defaultModelSettingsRepository.providerIdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val defaultModel: StateFlow<String> = defaultModelSettingsRepository.modelFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Vision model selection: a blank providerId means "follow the current chat model". */
    private val _visionProviderId = MutableStateFlow("")
    val visionProviderId: StateFlow<String> = _visionProviderId.asStateFlow()

    private val _visionModel = MutableStateFlow("")
    val visionModel: StateFlow<String> = _visionModel.asStateFlow()

    /** Dedicated compaction model selection: a blank providerId means "follow the current chat model". */
    private val _compactionProviderId = MutableStateFlow("")
    val compactionProviderId: StateFlow<String> = _compactionProviderId.asStateFlow()

    private val _compactionModel = MutableStateFlow("")
    val compactionModel: StateFlow<String> = _compactionModel.asStateFlow()

    /** Dedicated title summarization model selection: a blank providerId means "follow the current chat model". */
    private val _titleProviderId = MutableStateFlow("")
    val titleProviderId: StateFlow<String> = _titleProviderId.asStateFlow()

    private val _titleModel = MutableStateFlow("")
    val titleModel: StateFlow<String> = _titleModel.asStateFlow()

    /** Dedicated image generation model selection: a blank providerId means unconfigured (image generation does not follow the chat model). */
    private val _imageGenProviderId = MutableStateFlow("")
    val imageGenProviderId: StateFlow<String> = _imageGenProviderId.asStateFlow()

    private val _imageGenModel = MutableStateFlow("")
    val imageGenModel: StateFlow<String> = _imageGenModel.asStateFlow()

    /** AI command reviewer dedicated model (empty = follow the chat model). */
    private val _reviewerProviderId = MutableStateFlow("")
    val reviewerProviderId: StateFlow<String> = _reviewerProviderId.asStateFlow()

    private val _reviewerModel = MutableStateFlow("")
    val reviewerModel: StateFlow<String> = _reviewerModel.asStateFlow()

    /** Unified advanced context-window settings: [ContextWindowOverrideUiState.enabled] is the actually effective state. */
    private val _contextWindowOverride = MutableStateFlow(ContextWindowOverrideUiState())
    val contextWindowOverride: StateFlow<ContextWindowOverrideUiState> = _contextWindowOverride.asStateFlow()

    private val _logLevel = MutableStateFlow(LogLevel.VERBOSE)
    val logLevel: StateFlow<LogLevel> = _logLevel.asStateFlow()

    private val _logViewerState = MutableStateFlow(LogViewerUiState())
    val logViewerState: StateFlow<LogViewerUiState> = _logViewerState.asStateFlow()

    private val _updateCheckState = MutableStateFlow<UpdateCheckUiState>(UpdateCheckUiState.Idle)
    val updateCheckState: StateFlow<UpdateCheckUiState> = _updateCheckState.asStateFlow()

    private val _updateCheckEnabled = MutableStateFlow(updateCheckSettingsRepository.autoCheckEnabled)
    val updateCheckEnabled: StateFlow<Boolean> = _updateCheckEnabled.asStateFlow()

    private val _updateCheckChannel = MutableStateFlow(updateCheckSettingsRepository.channel)
    val updateCheckChannel: StateFlow<UpdateChannel> = _updateCheckChannel.asStateFlow()

    private val _keepaliveEnabled = MutableStateFlow(false)
    val keepaliveEnabled: StateFlow<Boolean> = _keepaliveEnabled.asStateFlow()

    private val _screenOnEnabled = MutableStateFlow(false)
    val screenOnEnabled: StateFlow<Boolean> = _screenOnEnabled.asStateFlow()

    private val _agentSoundEnabled = MutableStateFlow(false)
    val agentSoundEnabled: StateFlow<Boolean> = _agentSoundEnabled.asStateFlow()

    private val _autoRemoveStaleModels = MutableStateFlow(true)
    val autoRemoveStaleModels: StateFlow<Boolean> = _autoRemoveStaleModels.asStateFlow()

    private val _startupSessionMode = MutableStateFlow(StartupSessionMode.NEW_SESSION)
    val startupSessionMode: StateFlow<StartupSessionMode> = _startupSessionMode.asStateFlow()

    private val _firstByteTimeoutSec = MutableStateFlow(300)
    val firstByteTimeoutSec: StateFlow<Int> = _firstByteTimeoutSec.asStateFlow()

    private val _streamIdleTimeoutSec = MutableStateFlow(0)
    val streamIdleTimeoutSec: StateFlow<Int> = _streamIdleTimeoutSec.asStateFlow()

    private val _maxNetworkRetries = MutableStateFlow(6)
    val maxNetworkRetries: StateFlow<Int> = _maxNetworkRetries.asStateFlow()

    private val _enterToSend = MutableStateFlow(false)
    val enterToSend: StateFlow<Boolean> = _enterToSend.asStateFlow()

    private val _compactionThresholdPercent = MutableStateFlow(90)
    val compactionThresholdPercent: StateFlow<Int> = _compactionThresholdPercent.asStateFlow()

    private val _sendFileMaxSizeMb = MutableStateFlow(100)
    val sendFileMaxSizeMb: StateFlow<Int> = _sendFileMaxSizeMb.asStateFlow()

    private val _deleteExternalWorkspaceSessions = MutableStateFlow(false)
    val deleteExternalWorkspaceSessions: StateFlow<Boolean> = _deleteExternalWorkspaceSessions.asStateFlow()

    private val _todoDisplayPosition = MutableStateFlow(TodoDisplayPosition.ABOVE_INPUT_BAR)
    val todoDisplayPosition: StateFlow<TodoDisplayPosition> = _todoDisplayPosition.asStateFlow()

    private val _themeMode = MutableStateFlow(AppThemeMode.AUTO)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _themePresetId = MutableStateFlow<String?>(null)
    val themePresetId: StateFlow<String?> = _themePresetId.asStateFlow()

    private val _dynamicColorEnabled = MutableStateFlow(false)
    val dynamicColorEnabled: StateFlow<Boolean> = _dynamicColorEnabled.asStateFlow()

    /** File path of the global custom background image (null = not set), for display and preview in the settings dialog. */
    private val _backgroundImagePath = MutableStateFlow<String?>(null)
    val backgroundImagePath: StateFlow<String?> = _backgroundImagePath.asStateFlow()

    /** Background image opacity (0~0.2; actual range set by BackgroundSettingsRepository's MIN/MAX), written to DataStore live so the global background updates in sync. */
    private val _backgroundAlpha = MutableStateFlow(BackgroundSettingsRepository.DEFAULT_ALPHA)
    val backgroundAlpha: StateFlow<Float> = _backgroundAlpha.asStateFlow()

    /** Throttle job for persisting the opacity, see [setBackgroundAlpha]. */
    private var backgroundAlphaWriteJob: Job? = null

    /** User-selected app language tag (null means follow the system). */
    private val _languageTag = MutableStateFlow<String?>(null)
    val languageTag: StateFlow<String?> = _languageTag.asStateFlow()

    private val _mcpEntries = MutableStateFlow<List<McpServerEntry>>(emptyList())
    val mcpEntries: StateFlow<List<McpServerEntry>> = _mcpEntries.asStateFlow()

    private val _skills = MutableStateFlow<List<SkillUiEntry>>(emptyList())
    val skills: StateFlow<List<SkillUiEntry>> = _skills.asStateFlow()

    private val _skillSaveState = MutableStateFlow<SkillSaveState>(SkillSaveState.Idle)
    val skillSaveState: StateFlow<SkillSaveState> = _skillSaveState.asStateFlow()

    private val _skillImportState = MutableStateFlow<SkillImportState>(SkillImportState.Idle)
    val skillImportState: StateFlow<SkillImportState> = _skillImportState.asStateFlow()

    /** Name of the skill being deleted (non-null means deletion is in progress, for the dialog spinner and disabled buttons). */
    private val _skillDeleting = MutableStateFlow<String?>(null)
    val skillDeleting: StateFlow<String?> = _skillDeleting.asStateFlow()

    private val _subAgents = MutableStateFlow<List<SubAgentUiEntry>>(emptyList())
    val subAgents: StateFlow<List<SubAgentUiEntry>> = _subAgents.asStateFlow()

    private val _subAgentSaveState = MutableStateFlow<SubAgentSaveState>(SubAgentSaveState.Idle)
    val subAgentSaveState: StateFlow<SubAgentSaveState> = _subAgentSaveState.asStateFlow()

    /** Name of the subagent being deleted (non-null means deletion is in progress, for the dialog spinner and disabled buttons). */
    private val _subAgentDeleting = MutableStateFlow<String?>(null)
    val subAgentDeleting: StateFlow<String?> = _subAgentDeleting.asStateFlow()

    val mcpStatuses: StateFlow<List<McpServerStatus>> = mcpManager.statuses

    private val _mcpReloading = MutableStateFlow(false)
    val mcpReloading: StateFlow<Boolean> = _mcpReloading.asStateFlow()

    private val _fetchState = MutableStateFlow<FetchState>(FetchState.Idle)
    val fetchState: StateFlow<FetchState> = _fetchState.asStateFlow()

    private val _testResults = MutableStateFlow<Map<String, ModelTestResult>>(emptyMap())
    val testResults: StateFlow<Map<String, ModelTestResult>> = _testResults.asStateFlow()

    /** Model metadata cache keyed by [modelMetadataKey] (channel + model) — prices and capabilities can both vary by channel. */
    private val _modelMetadata = MutableStateFlow<Map<String, ModelMetadata>>(emptyMap())
    val modelMetadata: StateFlow<Map<String, ModelMetadata>> = _modelMetadata.asStateFlow()

    private val _testing = MutableStateFlow<Set<String>>(emptySet())
    val testing: StateFlow<Set<String>> = _testing.asStateFlow()

    private val _dashboardTestState = MutableStateFlow<ProviderDashboardState>(ProviderDashboardState.Idle)
    val dashboardTestState: StateFlow<ProviderDashboardState> = _dashboardTestState.asStateFlow()

    private val _dashboardScripts = MutableStateFlow<List<String>>(emptyList())
    /** Available dashboard script file names (loaded asynchronously via [loadDashboardScripts] when the script picker opens). */
    val dashboardScripts: StateFlow<List<String>> = _dashboardScripts.asStateFlow()

    private val _providerDashboards = MutableStateFlow<Map<String, ProviderDashboardState>>(emptyMap())
    val providerDashboards: StateFlow<Map<String, ProviderDashboardState>> = _providerDashboards.asStateFlow()

    private val _globalRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val globalRules: StateFlow<List<PermissionRule>> = _globalRules.asStateFlow()

    private val _projectRules = MutableStateFlow<List<PermissionRule>>(emptyList())
    val projectRules: StateFlow<List<PermissionRule>> = _projectRules.asStateFlow()

    val currentProjectName: StateFlow<String?> = permissionRulesRepository.currentProjectNameFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _disableSafetyInterception = MutableStateFlow(false)
    val disableSafetyInterception: StateFlow<Boolean> = _disableSafetyInterception.asStateFlow()

    /** Command execution authorization tier (autonomous / rule-based / full access). */
    private val _commandAuthLevel = MutableStateFlow(CommandAuthLevel.RULE_BASED)
    val commandAuthLevel: StateFlow<CommandAuthLevel> = _commandAuthLevel.asStateFlow()

    /** High-risk interception rules (including built-in presets and user-created ones). */
    private val _highRiskRules = MutableStateFlow<List<HighRiskRule>>(emptyList())
    val highRiskRules: StateFlow<List<HighRiskRule>> = _highRiskRules.asStateFlow()

    private val _activeProfileId = MutableStateFlow(ContainerProfile.BUILTIN_ID)
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    private val _defaultContainerId = MutableStateFlow(ContainerProfile.BUILTIN_ID)
    val defaultContainerId: StateFlow<String> = _defaultContainerId.asStateFlow()

    private val _customProfiles = MutableStateFlow<List<ContainerProfile>>(emptyList())
    val customProfiles: StateFlow<List<ContainerProfile>> = _customProfiles.asStateFlow()

    /** All profiles (built-in Alpine is also persisted in the list as a regular entry, written automatically on first launch). */
    val profiles: StateFlow<List<ContainerProfile>> = customProfiles
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, listOf(ContainerProfile.BUILTIN_ALPINE))

    /** Detected OS type per container (profile id → os id), used by the UI to show the matching OS icon. */
    val containerOsMap: StateFlow<Map<String, String>> = containerOsDetector.osMap

    /** Announcement content (following the UI language), paired with [containerAnnouncementOutdated] for dialog rendering. */
    private val _containerAnnouncementText = MutableStateFlow("")
    val containerAnnouncementText: StateFlow<String> = _containerAnnouncementText.asStateFlow()

    /** Whether the announcement should pop up: true when no local hash is stored or it mismatches the current content hash (content updated). */
    private val _containerAnnouncementOutdated = MutableStateFlow(false)
    val containerAnnouncementOutdated: StateFlow<Boolean> = _containerAnnouncementOutdated.asStateFlow()

    /** Announcement content for the "Prompts" page (following the UI language), paired with [promptsAnnouncementOutdated] for dialog rendering. */
    private val _promptsAnnouncementText = MutableStateFlow("")
    val promptsAnnouncementText: StateFlow<String> = _promptsAnnouncementText.asStateFlow()

    /** Whether the "Prompts" page announcement should pop up: true when no local hash is stored or it mismatches the current content hash (content updated). */
    private val _promptsAnnouncementOutdated = MutableStateFlow(false)
    val promptsAnnouncementOutdated: StateFlow<Boolean> = _promptsAnnouncementOutdated.asStateFlow()

    private val _imageCatalog = MutableStateFlow<List<ContainerImageEntry>>(emptyList())
    val imageCatalog: StateFlow<List<ContainerImageEntry>> = _imageCatalog.asStateFlow()

    /** Global download source id list (from the sources key of the catalog JSON, order preserved), for switching at the top-right corner. */
    private val _imageSourceOptions = MutableStateFlow<List<String>>(emptyList())
    val imageSourceOptions: StateFlow<List<String>> = _imageSourceOptions.asStateFlow()

    /** Currently selected download source (official by default). */
    private val _selectedImageSource = MutableStateFlow("official")
    val selectedImageSource: StateFlow<String> = _selectedImageSource.asStateFlow()

    /** Downloaded/installed image records (entryId → record), persisted across restarts. */
    private val _downloadedImages = MutableStateFlow<Map<String, DownloadedImageRecord>>(emptyMap())
    val downloadedImages: StateFlow<Map<String, DownloadedImageRecord>> = _downloadedImages.asStateFlow()

    /** Image ids with no download URL under the currently selected source (e.g. Tencent Cloud does not offer Ubuntu), used by the UI to disable download buttons. */
    private val _sourceUnavailableIds = MutableStateFlow<Set<String>>(emptySet())
    val sourceUnavailableIds: StateFlow<Set<String>> = _sourceUnavailableIds.asStateFlow()

    private val _containerImageDownload = MutableStateFlow<ContainerImageDownloadUiState>(ContainerImageDownloadUiState.Idle)
    val containerImageDownload: StateFlow<ContainerImageDownloadUiState> = _containerImageDownload.asStateFlow()

    /** Container being reset (with deleted entry count); null means no reset in progress. The UI shows a progress dialog based on this. */
    private val _containerReset = MutableStateFlow<ContainerResetUiState?>(null)
    val containerReset: StateFlow<ContainerResetUiState?> = _containerReset.asStateFlow()

    /** Handle of the current download task; cancelled to cancel the download (the underlying OkHttp call is interrupted synchronously). */
    private var downloadJob: kotlinx.coroutines.Job? = null

    /** Current execution mode (local PRoot / remote SSH), for the UI to decide whether to show the remote connection indicator. */
    val executionMode: StateFlow<ExecutionMode> = executionModeHolder.mode

    /** Remote SSH connection state, for the UI indicator. */
    val connectionState: StateFlow<ConnectionState> = remoteSshConnection.connectionState

    /** Token statistics: currently selected period. */
    private val _tokenStatsPeriod = MutableStateFlow(TokenStatsPeriod.LAST_7_DAYS)
    val tokenStatsPeriod: StateFlow<TokenStatsPeriod> = _tokenStatsPeriod.asStateFlow()

    /** Token statistics: combined state of in-period totals, trend, channels, models, and details. */
    private val _tokenStats = MutableStateFlow(TokenStatsUiState())
    val tokenStats: StateFlow<TokenStatsUiState> = _tokenStats.asStateFlow()

    /** Token statistics: call details pagination page (0-based). */
    private val _tokenStatsPage = MutableStateFlow(0)

    /** Token statistics: channel pagination page (0-based). */
    private val _providerStatsPage = MutableStateFlow(0)

    /** Token statistics: model pagination page (0-based). */
    private val _modelStatsPage = MutableStateFlow(0)

    /** Token statistics: provider filter (null = all). */
    private val _filterProviderId = MutableStateFlow<String?>(null)
    val filterProviderId: StateFlow<String?> = _filterProviderId.asStateFlow()

    /** Token statistics: model filter (null = all). */
    private val _filterModel = MutableStateFlow<String?>(null)
    val filterModel: StateFlow<String?> = _filterModel.asStateFlow()

    /** Remote connection channels configured in the workspace, reused by the SSH-mode dropdown for container images. */
    val remoteConnections: StateFlow<List<RemoteConnection>> = remoteRepository.getConnections()
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    init {
        _imageCatalog.value = containerImageCatalog.load()
        _imageSourceOptions.value = containerImageCatalog.sourceIds
        _selectedImageSource.value = containerImageCatalog.sourceIds.firstOrNull { it == "official" }
            ?: containerImageCatalog.sourceIds.firstOrNull() ?: "official"
        // Downloaded/installed records persist across restarts
        viewModelScope.launch {
            containerSettingsRepository.downloadedImagesFlow.collect { records ->
                _downloadedImages.value = records.associateBy { it.entryId }
            }
        }
        // Set of images unavailable under the current source (updated automatically on source switch)
        viewModelScope.launch {
            combine(_imageCatalog, _selectedImageSource) { entries, sourceId ->
                entries.filter { containerImageCatalog.urlFor(it, sourceId, ContainerImageCatalog.CURRENT_ABI) == null }
                    .map { it.id }.toSet()
            }.collect { _sourceUnavailableIds.value = it }
        }
        // The announcement pops up based on "content hash comparison": first time (no hash) or content update (hash mismatch).
        viewModelScope.launch {
            combine(_languageTag, containerSettingsRepository.announcementShownHashFlow) { tag, storedHash ->
                val text = loadContainerAnnouncement(context, tag)
                _containerAnnouncementText.value = text
                _containerAnnouncementOutdated.value = storedHash != sha256(text)
            }.collect {}
        }
        // The "Prompts" page announcement likewise pops up based on "content hash comparison".
        viewModelScope.launch {
            combine(_languageTag, generalSettingsRepository.promptsAnnouncementShownHashFlow) { tag, storedHash ->
                val text = loadPromptsAnnouncement(context, tag)
                _promptsAnnouncementText.value = text
                _promptsAnnouncementOutdated.value = storedHash != sha256(text)
            }.collect {}
        }
        viewModelScope.launch {
            launch {
                repository.getAllProviders().collectLatest {
                    _providers.value = it
                }
            }

            launch {
                visionModelSettingsRepository.providerIdFlow.collectLatest {
                    _visionProviderId.value = it
                }
            }

            launch {
                visionModelSettingsRepository.modelFlow.collectLatest {
                    _visionModel.value = it
                }
            }

            launch {
                compactionModelSettingsRepository.providerIdFlow.collectLatest {
                    _compactionProviderId.value = it
                }
            }

            launch {
                compactionModelSettingsRepository.modelFlow.collectLatest {
                    _compactionModel.value = it
                }
            }

            launch {
                titleModelSettingsRepository.providerIdFlow.collectLatest {
                    _titleProviderId.value = it
                }
            }

            launch {
                titleModelSettingsRepository.modelFlow.collectLatest {
                    _titleModel.value = it
                }
            }

            launch {
                imageGenModelSettingsRepository.providerIdFlow.collectLatest {
                    _imageGenProviderId.value = it
                }
            }

            launch {
                imageGenModelSettingsRepository.modelFlow.collectLatest {
                    _imageGenModel.value = it
                }
            }

            launch {
                reviewerModelSettingsRepository.providerIdFlow.collectLatest {
                    _reviewerProviderId.value = it
                }
            }

            launch {
                reviewerModelSettingsRepository.modelFlow.collectLatest {
                    _reviewerModel.value = it
                }
            }

            launch {
                contextWindowOverrideRepository.overrideFlow.collectLatest { o ->
                    _contextWindowOverride.value = ContextWindowOverrideUiState(
                        enabled = o.enabled,
                        contextTokens = o.contextTokens,
                        inputTokens = o.inputTokens,
                        outputTokens = o.outputTokens
                    )
                }
            }

            launch {
                logSettingsRepository.levelFlow.collectLatest {
                    _logLevel.value = it
                }
            }

            launch {
                keepaliveSettingsRepository.enabledFlow.collectLatest {
                    _keepaliveEnabled.value = it
                }
            }

            launch {
                screenOnSettingsRepository.enabledFlow.collectLatest {
                    _screenOnEnabled.value = it
                }
            }

            launch {
                agentSoundSettingsRepository.enabledFlow.collectLatest {
                    _agentSoundEnabled.value = it
                }
            }

            launch {
                generalSettingsRepository.autoRemoveStaleModelsFlow.collectLatest {
                    _autoRemoveStaleModels.value = it
                }
            }

            launch {
                generalSettingsRepository.startupSessionModeFlow.collectLatest {
                    _startupSessionMode.value = it
                }
            }

            launch {
                generalSettingsRepository.firstByteTimeoutSecFlow.collectLatest {
                    _firstByteTimeoutSec.value = it
                }
            }

            launch {
                generalSettingsRepository.streamIdleTimeoutSecFlow.collectLatest {
                    _streamIdleTimeoutSec.value = it
                }
            }

            launch {
                generalSettingsRepository.maxNetworkRetriesFlow.collectLatest {
                    _maxNetworkRetries.value = it
                }
            }

            launch {
                generalSettingsRepository.enterToSendFlow.collectLatest {
                    _enterToSend.value = it
                }
            }

            launch {
                generalSettingsRepository.compactionThresholdPercentFlow.collectLatest {
                    _compactionThresholdPercent.value = it
                }
            }

            launch {
                generalSettingsRepository.sendFileMaxSizeMbFlow.collectLatest {
                    _sendFileMaxSizeMb.value = it
                }
            }

            launch {
                generalSettingsRepository.deleteExternalWorkspaceSessionsFlow.collectLatest {
                    _deleteExternalWorkspaceSessions.value = it
                }
            }

            launch {
                generalSettingsRepository.todoDisplayPositionFlow.collectLatest {
                    _todoDisplayPosition.value = it
                }
            }

            launch {
                themeSettingsRepository.themeModeFlow.collectLatest {
                    _themeMode.value = it
                }
            }

            launch {
                themeSettingsRepository.themePresetIdFlow.collectLatest {
                    _themePresetId.value = it
                }
            }

            launch {
                themeSettingsRepository.dynamicColorFlow.collectLatest {
                    _dynamicColorEnabled.value = it
                }
            }

            launch {
                backgroundSettingsRepository.imagePathFlow.collectLatest {
                    _backgroundImagePath.value = it
                }
            }

            launch {
                backgroundSettingsRepository.alphaFlow.collectLatest {
                    _backgroundAlpha.value = it
                }
            }

            launch {
                languageSettingsRepository.languageFlow.collectLatest {
                    _languageTag.value = it
                }
            }

            launch {
                containerSettingsRepository.activeProfileIdFlow.collectLatest {
                    _activeProfileId.value = it
                }
            }

            launch {
                containerSettingsRepository.defaultContainerIdFlow.collectLatest {
                    _defaultContainerId.value = it
                }
            }

            launch {
                // Write the built-in Alpine default entry on first launch (once the flag is set it is never auto-restored)
                containerSettingsRepository.ensureBuiltinDefault()
            }

            launch {
                containerSettingsRepository.customProfilesFlow.collectLatest {
                    // Descending by creation time (newest first); legacy entries with createdAt all 0 keep storage order
                    _customProfiles.value = it.sortedByDescending { profile -> profile.createdAt }
                }
            }

            launch {
                mcpConfigRepository.effectiveEntriesFlow.collectLatest {
                    _mcpEntries.value = it
                }
            }

            launch {
                skillConfigRepository.changes.collectLatest {
                    refreshSkills()
                }
            }

            launch {
                agentDefinitionConfigRepository.changes.collectLatest {
                    refreshSubAgents()
                }
            }

            launch {
                refreshSkills()
            }

            // Global skills/subagents come from the remote side in remote mode, so local directory watching (skills.changes) does not fire;
            // a rescan is needed after an execution-mode switch or once the remote connection is ready, otherwise the list stays on the old environment's content.
            launch {
                executionMode.collectLatest {
                    refreshSkills()
                    refreshSubAgents()
                }
            }

            launch {
                connectionState.collectLatest { state ->
                    if (state == ConnectionState.CONNECTED) {
                        refreshSkills()
                        refreshSubAgents()
                    }
                }
            }

            launch {
                permissionRulesRepository.globalRulesFlow.collectLatest {
                    _globalRules.value = it
                }
            }

            launch {
                permissionRulesRepository.currentProjectRulesFlow.collectLatest {
                    _projectRules.value = it
                }
            }

            launch {
                toolSafetySettingsRepository.disableSafetyInterceptionFlow.collectLatest {
                    _disableSafetyInterception.value = it
                }
            }

            launch {
                toolSafetySettingsRepository.commandAuthLevelFlow.collectLatest {
                    _commandAuthLevel.value = it
                }
            }

            launch {
                highRiskRulesRepository.rulesFlow.collectLatest {
                    _highRiskRules.value = it
                }
            }

            launch {
                combine(
                    _tokenStatsPeriod,
                    _filterProviderId,
                    _filterModel
                ) { period, filterProviderId, filterModel ->
                    Triple(period, filterProviderId, filterModel)
                }.flatMapLatest { (period, filterProviderId, filterModel) ->
                    val start = period.startMillis(System.currentTimeMillis())
                    val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
                    combine(
                        combine(
                            if (period == TokenStatsPeriod.TODAY) {
                                llmCallRecordDao.getHourStats(start, tz, filterProviderId, filterModel)
                            } else {
                                llmCallRecordDao.getDayStats(start, tz, filterProviderId, filterModel)
                            },
                            llmCallRecordDao.getSummary(start, filterProviderId, filterModel)
                        ) { rawTrend, summary -> rawTrend to summary },
                        combine(
                            llmCallRecordDao.getProviderStats(start, filterProviderId, filterModel),
                            _providerStatsPage
                        ) { list, page ->
                            val total = list.size
                            val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
                            val safePage = page.coerceIn(0, lastPage)
                            val paged = list.drop(safePage * STATS_PAGE_SIZE).take(STATS_PAGE_SIZE)
                            Triple(paged, safePage, total)
                        },
                        combine(
                            combine(
                                llmCallRecordDao.getModelStats(start, filterProviderId, filterModel),
                                _modelStatsPage
                            ) { list, page ->
                                val total = list.size
                                val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
                                val safePage = page.coerceIn(0, lastPage)
                                val paged = list.drop(safePage * STATS_PAGE_SIZE).take(STATS_PAGE_SIZE)
                                ModelStatsPaging(paged, safePage, total)
                            },
                            // Total cost must aggregate with the channel: custom prices are stored per channel; dropping it picks the wrong price.
                            llmCallRecordDao.getModelProviderCostStats(start, filterProviderId, filterModel)
                        ) { modelPaging, costStats -> modelPaging to costStats },
                        // Details pagination: re-query the current page when the page number or total changes; other aggregates are not recomputed
                        combine(_tokenStatsPage, llmCallRecordDao.getCallsCount(start, filterProviderId, filterModel)) { page, total -> page to total }
                            .flatMapLatest { (page, total) ->
                                llmCallRecordDao.getRecentCalls(start, CALLS_PAGE_SIZE, page * CALLS_PAGE_SIZE, filterProviderId, filterModel)
                                    .map { calls -> Triple(calls, total, page) }
                            },
                        // Selectable filter option lists (channel and model lists when no global filter is applied)
                        combine(
                            llmCallRecordDao.getProviderStats(start),
                            llmCallRecordDao.getModelStats(start),
                            _providers
                        ) { allProviders, allModels, configuredProviders ->
                            val providerList = (allProviders.mapNotNull { p ->
                                val id = p.providerId ?: return@mapNotNull null
                                id to (p.providerName ?: id)
                            } + configuredProviders.map { it.id to it.name }).distinctBy { it.first }
                            val modelList = allModels.mapNotNull { it.model }.distinct()
                            val keyCounts = configuredProviders.associate { it.id to it.apiKeys.size }
                            Triple(providerList, modelList, keyCounts)
                        }
                    ) { (rawTrend, summary), (pProviders, pPage, pTotal), (modelPaging, costStats), (calls, total, page), (availableProviders, availableModels, keyCounts) ->
                        val trend = padTrend(period, rawTrend, tz)
                        val costs = withContext(Dispatchers.Default) {
                            val perCall = calls.associate {
                                it.record.id to callCostUsd(it.record.providerId, it.record.model, it.record.inputTokens.toLong(), it.record.cachedInputTokens.toLong(), it.record.outputTokens.toLong(), it.record.cacheCreationTokens.toLong())
                            }
                            val periodTotal = costStats.sumOf { s ->
                                callCostUsd(s.providerId, s.model, s.inputTokens, s.cachedInputTokens, s.outputTokens, s.cacheCreationTokens) ?: 0.0
                            }
                            perCall to periodTotal
                        }
                        TokenStatsUiState(
                            period = period,
                            summary = summary,
                            trend = trend,
                            providers = pProviders,
                            models = modelPaging.paged,
                            recentCalls = calls,
                            callsPage = page,
                            callsTotal = total,
                            providersPage = pPage,
                            providersTotal = pTotal,
                            modelsPage = modelPaging.page,
                            modelsTotal = modelPaging.total,
                            totalCostUsd = costs.second,
                            recentCallCosts = costs.first,
                            filterProviderId = filterProviderId,
                            filterModel = filterModel,
                            availableProviders = availableProviders,
                            availableModels = availableModels,
                            providerKeyCounts = keyCounts
                        )
                    }
                }.collectLatest { _tokenStats.value = it }
            }
        }
    }

    fun upsertMcpServer(originalName: String?, initialScope: McpScope?, config: McpServerConfig, scope: McpScope) {
        viewModelScope.launch {
            // Scope migration: only when the saved scope differs from the original is the old entry removed from the original scope,
            // preventing leftover entries from continuing to override the new scope's config during merging (project takes precedence).
            if (originalName != null && initialScope != null && initialScope != scope) {
                val oldBase = if (initialScope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
                val oldUpdated = oldBase.filterNot { it.name == originalName }
                if (initialScope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(oldUpdated) else mcpConfigRepository.setProjectServers(oldUpdated)
            }
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val ordered = LinkedHashMap<String, McpServerConfig>()
            base.forEach { ordered[it.name] = it }
            if (originalName != null && originalName != config.name) {
                ordered.remove(originalName)
            }
            ordered[config.name] = config
            val updated = ordered.values.toList()
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                // Reconnect only the changed server; other servers are unaffected; on rename, disconnect the old name first.
                if (originalName != null && originalName != config.name) {
                    mcpManager.removeServer(originalName)
                }
                mcpManager.reloadServer(config.name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun deleteMcpServer(name: String, scope: McpScope) {
        viewModelScope.launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.filterNot { it.name == name }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                mcpManager.removeServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun setMcpServerEnabled(name: String, enabled: Boolean, scope: McpScope) {
        viewModelScope.launch {
            val base = if (scope == McpScope.GLOBAL) mcpConfigRepository.getGlobalServers() else mcpConfigRepository.getProjectServers()
            val updated = base.map { if (it.name == name) it.copy(enabled = enabled) else it }
            if (scope == McpScope.GLOBAL) mcpConfigRepository.setGlobalServers(updated) else mcpConfigRepository.setProjectServers(updated)
            _mcpReloading.value = true
            try {
                mcpManager.reloadServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    fun reloadMcp() {
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                mcpManager.reload()
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** Reconnects only the specified server (for the refresh tool at the top-right of the edit dialog). */
    fun reloadMcpServer(name: String) {
        viewModelScope.launch {
            _mcpReloading.value = true
            try {
                mcpManager.reloadServer(name)
            } finally {
                _mcpReloading.value = false
            }
        }
    }

    /** Rescans skills (called when entering the settings page / after toggling, reflecting on-disk additions, deletions and edits). */
    fun refreshSkills() {
        viewModelScope.launch {
            _skills.value = withContext(Dispatchers.IO) {
                skillRepository.listAllSkills().map { entry ->
                    SkillUiEntry(
                        name = entry.skill.name,
                        description = entry.skill.description,
                        scope = entry.scope,
                        disabled = skillRepository.isSkillDisabled(entry.skill.name),
                        instructions = entry.skill.instructions,
                        requiredTools = entry.skill.requiredTools
                    )
                }
            }
        }
    }

    /** Toggles a skill's enabled/disabled state (written to the corresponding scope's skills.json). */
    fun setSkillEnabled(name: String, enabled: Boolean, scope: SkillScope) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { skillRepository.setSkillDisabled(name, !enabled, scope) }
            refreshSkills()
        }
    }

    /**
     * Deletes the skill in the specified scope (removes its directory, unrecoverable), then refreshes the list.
     * The deletion itself runs on the IO dispatcher (remote mode recursively deletes over SFTP, not on the main thread); meanwhile [skillDeleting] holds the name for the UI spinner.
     */
    fun deleteSkill(name: String, scope: SkillScope) {
        if (_skillDeleting.value != null) return
        _skillDeleting.value = name
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { skillRepository.deleteSkill(name, scope) }
                refreshSkills()
            } finally {
                _skillDeleting.value = null
            }
        }
    }

    /**
     * Saves a skill. [originalName] is the pre-edit name, null when creating;
     * the result is converted to [skillSaveState], by which the edit page returns to the list or shows an inline error.
     */
    fun saveSkill(form: SkillForm, scope: SkillScope, originalName: String? = null) {
        if (_skillSaveState.value is SkillSaveState.Saving) return
        _skillSaveState.value = SkillSaveState.Saving
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                skillRepository.save(form, scope, originalName)
            }
            _skillSaveState.value = if (error == null) {
                SkillSaveState.Saved
            } else {
                SkillSaveState.Failed(error)
            }
            if (error == null) refreshSkills()
        }
    }

    fun clearSkillSaveState() {
        _skillSaveState.value = SkillSaveState.Idle
    }

    /**
     * Imports a skill from the selected Markdown file: reads the text and writes it to the specified scope.
     * An extension not on the allowlist fails immediately with "type not supported"; nothing is written.
     */
    fun importSkillFromMarkdown(uri: Uri, scope: SkillScope) {
        if (_skillImportState.value is SkillImportState.Running) return
        _skillImportState.value = SkillImportState.Running
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val name = queryDisplayName(uri)
                if (!name.hasExtension(MARKDOWN_EXTENSIONS)) {
                    SkillImportReport(emptyList(), fatal = SkillImportError.UNSUPPORTED_FILE)
                } else {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()?.use { it.readText() }
                            ?: throw java.io.IOException("openInputStream returned null")
                        skillRepository.importMarkdown(text, name.substringBeforeLast('.'), scope)
                    }.getOrElse { SkillImportReport(emptyList(), fatal = SkillImportError.IO_FAILED) }
                }
            }
            finishSkillImport(report)
        }
    }

    /** Imports skills from the selected zip archive (may contain multiple skills), writing to the specified scope. */
    fun importSkillsFromZip(uri: Uri, scope: SkillScope) {
        if (_skillImportState.value is SkillImportState.Running) return
        _skillImportState.value = SkillImportState.Running
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                val name = queryDisplayName(uri)
                if (!name.hasExtension(ZIP_EXTENSIONS)) {
                    SkillImportReport(emptyList(), fatal = SkillImportError.UNSUPPORTED_FILE)
                } else {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            skillRepository.importZip(input, name.substringBeforeLast('.'), scope)
                        } ?: throw java.io.IOException("openInputStream returned null")
                    }.getOrElse { SkillImportReport(emptyList(), fatal = SkillImportError.INVALID_ARCHIVE) }
                }
            }
            finishSkillImport(report)
        }
    }

    private suspend fun finishSkillImport(report: SkillImportReport) {
        _skillImportState.value = SkillImportState.Done(report)
        if (report.imported.isNotEmpty()) refreshSkills()
    }

    fun clearSkillImportState() {
        _skillImportState.value = SkillImportState.Idle
    }

    /** Queries the display name (with extension) of the selected file; falls back to the last URI segment when unavailable. */
    private fun queryDisplayName(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    /** Rescans subagent definitions (called when entering the settings page / after deletion, reflecting on-disk additions, deletions and edits). */
    fun refreshSubAgents() {
        viewModelScope.launch {
            _subAgents.value = withContext(Dispatchers.IO) {
                agentDefinitionRepository.listAll().map { entry ->
                    SubAgentUiEntry(
                        name = entry.definition.name,
                        description = entry.definition.description,
                        scope = entry.scope,
                        disabled = agentDefinitionRepository.isDisabled(entry.definition.name),
                        providerId = entry.definition.providerId,
                        model = entry.definition.model,
                        reasoningEffort = entry.definition.reasoningEffort,
                        mode = entry.definition.mode,
                        allowedTools = entry.definition.allowedTools,
                        disallowedTools = entry.definition.disallowedTools,
                        inject = entry.definition.inject,
                        prompt = entry.definition.prompt,
                        filePath = entry.definition.filePath
                    )
                }
            }
        }
    }

    /** Deletes the subagent definition file in the specified scope (unrecoverable), then immediately refreshes the list. */
    /** Toggles subagent enabled/disabled (written to the corresponding scope's agents.json); once disabled it leaves the main agent's dispatchable list. */
    fun setSubAgentEnabled(name: String, enabled: Boolean, scope: AgentDefinitionScope) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { agentDefinitionRepository.setDisabled(name, !enabled, scope) }
            refreshSubAgents()
        }
    }

    /**
     * Saves a subagent definition. [originalName] is the pre-edit name, null when creating;
     * the result is converted to [subAgentSaveState], by which the edit page returns to the list or shows an inline error.
     */
    fun saveSubAgent(
        form: AgentDefinitionForm,
        scope: AgentDefinitionScope,
        originalName: String? = null
    ) {
        if (_subAgentSaveState.value is SubAgentSaveState.Saving) return
        _subAgentSaveState.value = SubAgentSaveState.Saving
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                agentDefinitionRepository.save(form, scope, originalName)
            }
            _subAgentSaveState.value = if (error == null) {
                SubAgentSaveState.Saved
            } else {
                SubAgentSaveState.Failed(error)
            }
            if (error == null) refreshSubAgents()
        }
    }

    fun clearSubAgentSaveState() {
        _subAgentSaveState.value = SubAgentSaveState.Idle
    }

    /** All currently registered tool names (including dynamic MCP tools), for the edit page's tool allowlist and blocklist checkboxes. */
    fun availableToolNames(): List<String> = toolRegistry.getAvailableTools().map { it.name }

    fun deleteSubAgent(name: String, scope: AgentDefinitionScope) {
        if (_subAgentDeleting.value != null) return
        _subAgentDeleting.value = name
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { agentDefinitionRepository.delete(name, scope) }
                refreshSubAgents()
            } finally {
                _subAgentDeleting.value = null
            }
        }
    }

    fun getMcpServerTools(serverName: String?): List<McpToolDescriptor> {
        if (serverName.isNullOrBlank()) return emptyList()
        return mcpManager.getServerTools(serverName)
    }

    fun setLogLevel(level: LogLevel) {
        viewModelScope.launch {
            logSettingsRepository.setLevel(level)
        }
    }

    // ── Check for updates ──

    /**
     * Checks for updates. Manual mode (About page tap) is not bound by the toggle or the once-a-day limit; auto mode (home startup)
     * requires the toggle on and no check yet today, stays silent on failure (no error dialog), and records today as checked regardless of the result.
     */
    fun checkUpdate(manual: Boolean) {
        if (!manual) {
            if (!updateCheckSettingsRepository.autoCheckEnabled) return
            if (updateCheckSettingsRepository.hasCheckedToday()) return
        }
        if (_updateCheckState.value == UpdateCheckUiState.Checking) return
        viewModelScope.launch {
            // Manual check shows a "checking" indicator; the auto check is invisible throughout, showing a dialog only when a new version is found
            if (manual) {
                _updateCheckState.value = UpdateCheckUiState.Checking
            }
            val result = updateCheckService.checkForUpdate(
                currentVersion = currentVersionName(),
                channel = updateCheckSettingsRepository.channel
            )
            _updateCheckState.value = when (result) {
                is UpdateCheckResult.UpToDate -> {
                    if (manual) UpdateCheckUiState.UpToDate else UpdateCheckUiState.Idle
                }
                is UpdateCheckResult.NewVersion -> UpdateCheckUiState.NewVersion(
                    latestTag = result.info.latestTag,
                    changelog = result.info.changelog
                )
                is UpdateCheckResult.Error -> {
                    if (manual) UpdateCheckUiState.Error(result.message) else UpdateCheckUiState.Idle
                }
            }
            // Regardless of the result, refresh ~/.xencode/update-info.json for the AI to read
            updateCheckSettingsRepository.writeUpdateInfo(
                currentVersion = currentVersionName(),
                channel = updateCheckSettingsRepository.channel,
                result = result
            )
            if (!manual) {
                updateCheckSettingsRepository.markCheckedToday()
            }
        }
    }

    /** Dismisses the update dialog. */
    fun dismissUpdateCheck() {
        _updateCheckState.value = UpdateCheckUiState.Idle
    }

    /** Auto-check-for-updates toggle (on by default). */
    fun setUpdateCheckEnabled(enabled: Boolean) {
        updateCheckSettingsRepository.autoCheckEnabled = enabled
        _updateCheckEnabled.value = enabled
    }

    /** Update channel: stable / latest. */
    fun setUpdateCheckChannel(channel: UpdateChannel) {
        updateCheckSettingsRepository.channel = channel
        _updateCheckChannel.value = channel
    }

    private fun currentVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    /**
     * Determines whether a log line belongs to a given MCP server: only logs written by MCP components count (tag starting with `Mcp`,
     * e.g. McpManager/McpClient/McpStdioTransport/McpTool), and the message must reference that server —
     * either as the message prefix `[name]` or as the namespaced tool name `mcp__name__`.
     * A plain substring match on the server name will not do: it would dredge up unrelated logs that merely mention the name.
     */
    private fun isMcpServerLogLine(line: String, serverName: String): Boolean {
        if (!line.contains("[Mcp")) return false
        return line.contains("[$serverName]") || line.contains("mcp__${serverName}__")
    }

    fun refreshLogs(filterServerName: String? = _logViewerState.value.filterServerName, silent: Boolean = false) {
        loadLogs(
            filterServerName = filterServerName?.takeIf { it.isNotBlank() },
            preferredFileName = _logViewerState.value.selectedFileName,
            silent = silent
        )
    }

    fun selectLogFile(fileName: String) {
        loadLogs(
            filterServerName = _logViewerState.value.filterServerName,
            preferredFileName = fileName
        )
    }

    private fun loadLogs(filterServerName: String?, preferredFileName: String?, silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _logViewerState.update {
                    it.copy(
                        loading = true,
                        filterServerName = filterServerName,
                        error = null
                    )
                }
            }
            val state = withContext(Dispatchers.IO) {
                runCatching {
                    val files = FileLogger.listLogFiles()
                    val todayName = "log-${java.time.LocalDate.now()}.txt"
                    val selected = files.firstOrNull { it.name == preferredFileName }
                        ?: files.firstOrNull { it.name == todayName }
                        ?: files.lastOrNull()
                    if (selected == null) {
                        return@runCatching LogViewerUiState(
                            filterServerName = filterServerName,
                            error = context.getString(R.string.settings_log_no_files)
                        )
                    }

                    val rawLines = selected.readLines(Charsets.UTF_8)
                    val filteredLines = if (filterServerName.isNullOrBlank()) {
                        rawLines
                    } else {
                        rawLines.filter { line -> isMcpServerLogLine(line, filterServerName) }
                    }
                    val visibleLines = filteredLines.takeLast(MAX_LOG_LINES)

                    LogViewerUiState(
                        files = files.map { it.name },
                        selectedFileName = selected.name,
                        filterServerName = filterServerName,
                        content = visibleLines.joinToString("\n"),
                        totalLines = filteredLines.size,
                        shownLines = visibleLines.size
                    )
                }.getOrElse { e ->
                    LogViewerUiState(
                        filterServerName = filterServerName,
                        error = context.getString(R.string.settings_log_read_failed, e.message ?: "")
                    )
                }
            }
            _logViewerState.value = state
        }
    }

    // Only persists the flag — starting/stopping the Service is handled uniformly by AIEditorApp observing enabledFlow.
    fun setKeepaliveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            keepaliveSettingsRepository.setEnabled(enabled)
        }
    }

    // Only persists the flag — adding/removing the window FLAG_KEEP_SCREEN_ON is handled uniformly by MainActivity observing enabledFlow.
    fun setScreenOnEnabled(enabled: Boolean) {
        viewModelScope.launch {
            screenOnSettingsRepository.setEnabled(enabled)
        }
    }

    fun setAgentSoundEnabled(enabled: Boolean) {
        viewModelScope.launch {
            agentSoundSettingsRepository.setEnabled(enabled)
        }
    }

    /** Whether to automatically remove local models that no longer exist remotely after a successful model fetch. */
    fun setAutoRemoveStaleModels(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setAutoRemoveStaleModels(enabled)
        }
    }

    /** Display position of the task todo area on the chat page (below the title bar / above the input bar / off). */
    fun setTodoDisplayPosition(position: TodoDisplayPosition) {
        viewModelScope.launch {
            generalSettingsRepository.setTodoDisplayPosition(position)
        }
    }

    /** Whether startup (including workspace switches) enters a new or the most recent session. */
    fun setStartupSessionMode(mode: StartupSessionMode) {
        viewModelScope.launch {
            generalSettingsRepository.setStartupSessionMode(mode)
        }
    }

    /** First-byte timeout for streaming requests (seconds); 0 means unlimited. */
    fun setFirstByteTimeoutSec(sec: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setFirstByteTimeoutSec(sec)
        }
    }

    /** Idle timeout between adjacent stream chunks (seconds); 0 means unlimited. */
    fun setStreamIdleTimeoutSec(sec: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setStreamIdleTimeoutSec(sec)
        }
    }

    /** Max network request retries; 0 means no retry. */
    fun setMaxNetworkRetries(count: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setMaxNetworkRetries(count)
        }
    }

    /** Whether the Enter key sends the message directly. */
    fun setEnterToSend(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setEnterToSend(enabled)
        }
    }

    /** Auto-compaction trigger threshold (context window percentage, 1..100). */
    fun setCompactionThresholdPercent(percent: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setCompactionThresholdPercent(percent)
        }
    }

    /** sendFile per-file size cap (MB, at least 1). */
    fun setSendFileMaxSizeMb(mb: Int) {
        viewModelScope.launch {
            generalSettingsRepository.setSendFileMaxSizeMb(mb)
        }
    }

    /** Whether removing an external local workspace also deletes its chat history. */
    fun setDeleteExternalWorkspaceSessions(enabled: Boolean) {
        viewModelScope.launch {
            generalSettingsRepository.setDeleteExternalWorkspaceSessions(enabled)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            themeSettingsRepository.setThemeMode(mode)
        }
    }

    fun setThemePreset(id: String) {
        viewModelScope.launch {
            themeSettingsRepository.setThemePresetId(id)
        }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            themeSettingsRepository.setDynamicColorEnabled(enabled)
        }
    }

    /** Picks a new background image (copied into the private directory, then replaces the old one). */
    fun setBackgroundImage(uri: Uri) {
        viewModelScope.launch {
            backgroundSettingsRepository.setBackgroundImage(uri)
        }
    }

    /** Removes the background image. */
    fun clearBackgroundImage() {
        viewModelScope.launch {
            backgroundSettingsRepository.clearBackground()
        }
    }

    /**
     * Adjusts the background image opacity (0~0.2; actual range set by BackgroundSettingsRepository's MIN_ALPHA/MAX_ALPHA).
     *
     * Dragging the slider fires dozens of values in a row; DataStore writes are serial, so persisting each one would build up visible lag,
     * and reading back would recompose the whole settings page. Only the last value is kept here and written once about 80ms after release.
     */
    fun setBackgroundAlpha(alpha: Float) {
        backgroundAlphaWriteJob?.cancel()
        backgroundAlphaWriteJob = viewModelScope.launch {
            delay(BACKGROUND_ALPHA_WRITE_DEBOUNCE_MS)
            backgroundSettingsRepository.setBackgroundAlpha(alpha)
        }
    }

    /** Sets the app language; an empty string or null tag means follow the system. */
    fun setLanguage(tag: String?) {
        viewModelScope.launch {
            languageSettingsRepository.setLanguage(tag?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * Switches the currently selected container profile and syncs the global execution mode per its [ContainerProfile.mode].
     *
     * Local image → [ExecutionMode.LOCAL_PROOT]; remote SSH image → [ExecutionMode.REMOTE_SSH],
     * with connection parameters resolved in real time by ActiveRemoteConnectionResolver from the active profile; AIEditorApp's resident subscription reconnects accordingly.
     * The delegating layers read the holder on each call, so the switch takes effect immediately without a restart.
     */
    fun setActiveContainerProfile(id: String) {
        viewModelScope.launch {
            applyProfile(id)
        }
    }

    /** Sets the default container for remote workspace mode (only local PRoot containers are selectable). */
    fun setDefaultContainerId(id: String) {
        viewModelScope.launch {
            containerSettingsRepository.setDefaultContainerId(id)
        }
    }

    /** Device ROOT probe state ("Command execution channel" page). */
    private val _rootProbeState = MutableStateFlow(RootProbeState.UNKNOWN)
    val rootProbeState: StateFlow<RootProbeState> = _rootProbeState.asStateFlow()

    /** Probes whether su is available (forced re-probe bypassing the engine's 30s cache; runs on the IO dispatcher; the first probe may trigger the su authorization dialog). */
    fun probeDeviceRoot() {
        if (_rootProbeState.value == RootProbeState.PROBING) return
        _rootProbeState.value = RootProbeState.PROBING
        viewModelScope.launch(Dispatchers.IO) {
            val ok = deviceRootEngine.reprobeRoot()
            _rootProbeState.value = if (ok) RootProbeState.AVAILABLE else RootProbeState.UNAVAILABLE
        }
    }

    /**
     * Switches the command execution channel ("Command execution channel" settings page).
     *
     * Device ROOT is decoupled from container profiles and switches ExecutionMode directly; sandbox/remote goes through the container profile system
     * ([applyProfile] syncs ExecutionMode internally): switching to sandbox first restores the currently active local image,
     * otherwise the default container, falling back to built-in Alpine; switching to remote uses the currently active remote image, otherwise the first remote image,
     * and does nothing when there is no remote image (the UI already disables the entry).
     */
    fun switchExecutionChannel(target: ExecutionMode) {
        if (target == executionModeHolder.currentMode()) return
        viewModelScope.launch {
            when (target) {
                ExecutionMode.DEVICE_ROOT -> {
                    executionModeRepository.setExecutionMode(ExecutionMode.DEVICE_ROOT)
                    executionModeHolder.setMode(ExecutionMode.DEVICE_ROOT)
                }
                ExecutionMode.LOCAL_PROOT -> {
                    val local = _customProfiles.value.firstOrNull {
                        it.id == _activeProfileId.value && it.mode == ExecutionMode.LOCAL_PROOT
                    } ?: _customProfiles.value.firstOrNull {
                        it.id == _defaultContainerId.value && it.mode == ExecutionMode.LOCAL_PROOT
                    } ?: _customProfiles.value.firstOrNull { it.mode == ExecutionMode.LOCAL_PROOT }
                        ?: ContainerProfile.BUILTIN_ALPINE
                    applyProfile(local.id)
                    // Switching to the sandbox channel immediately starts preparing the container (extracting the rootfs; idempotent: a fast path when already ready).
                    // This makes "switching channels" itself the initialization entry — previously only entering the terminal page triggered extraction,
                    // but in device ROOT mode the terminal page takes the su-probe branch (TerminalSessionManager),
                    // never extracting, leaving the container stuck "uninitialized" forever.
                    runCatching { containerInstaller.installRootfsIfNeed(local) }
                        .onFailure {
                            FileLogger.w("SettingsViewModel", "Failed to prepare container after switching to sandbox channel: ${it.message}")
                        }
                }
                ExecutionMode.REMOTE_SSH -> {
                    val remote = _customProfiles.value.firstOrNull {
                        it.id == _activeProfileId.value && it.mode == ExecutionMode.REMOTE_SSH
                    } ?: _customProfiles.value.firstOrNull { it.mode == ExecutionMode.REMOTE_SSH }
                        ?: return@launch
                    applyProfile(remote.id)
                }
            }
        }
    }

    /** Applies [id] as the active profile: persists it and switches the execution mode per mode. Falls back to the first list entry when not found, then built-in. */
    private suspend fun applyProfile(id: String) {
        val profile = _customProfiles.value.firstOrNull { it.id == id }
            ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == id }
            ?: _customProfiles.value.firstOrNull()
            ?: return
        containerSettingsRepository.setActiveProfile(profile.id)
        when (profile.mode) {
            ExecutionMode.LOCAL_PROOT -> {
                executionModeRepository.setExecutionMode(ExecutionMode.LOCAL_PROOT)
                executionModeHolder.setMode(ExecutionMode.LOCAL_PROOT)
            }

            ExecutionMode.REMOTE_SSH -> {
                if (profile.rootfsSource !is RootfsSource.RemoteSsh) return
                // Connection parameters are resolved in real time by ActiveRemoteConnectionResolver from the active profile; AIEditorApp's resident subscription reconnects accordingly.
                executionModeRepository.setExecutionMode(ExecutionMode.REMOTE_SSH)
                executionModeHolder.setMode(ExecutionMode.REMOTE_SSH)
            }

            // Profiles can only be local/remote images; the device ROOT channel is switched directly via ExecutionMode on the "Command execution channel" settings page
            ExecutionMode.DEVICE_ROOT -> Unit
        }
    }

    /** Resets a container: built-in restores factory state (clears override config + deletes rootfs), custom local images delete the rootfs to re-extract next time. Remote SSH has no local data; the UI offers no entry. */
    fun resetContainer(profile: ContainerProfile) {
        if (_containerReset.value != null) return
        viewModelScope.launch {
            if (profile.isBuiltin) {
                containerSettingsRepository.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
            }
            withResetProgress(profile) { onProgress ->
                containerInstaller.resetRootfs(profile, onProgress)
            }
        }
    }

    /** rootfs cleanup accompanying profile deletion, with progress; built-in and remote SSH have no local rootfs to delete, so no progress dialog is shown. */
    private suspend fun cleanRootfsWithProgress(profile: ContainerProfile) {
        if (profile.isBuiltin || profile.rootfsSource is RootfsSource.RemoteSsh) {
            containerInstaller.deleteCustomRootfs(profile)
            return
        }
        withResetProgress(profile) { onProgress ->
            containerInstaller.deleteCustomRootfs(profile, onProgress)
        }
    }

    /**
     * Wraps one rootfs deletion: writes the deleted entry count into [_containerReset] to drive the progress dialog, clearing it afterwards.
     * Deleting a large container takes tens of seconds; the deletion itself runs on the IO dispatcher so the UI just waits without jank.
     */
    private suspend fun withResetProgress(
        profile: ContainerProfile,
        block: suspend ((Int) -> Unit) -> Unit
    ) {
        _containerReset.value = ContainerResetUiState(profile.name, 0)
        try {
            block { deleted -> _containerReset.value = ContainerResetUiState(profile.name, deleted) }
        } finally {
            _containerReset.value = null
        }
    }

    /** Empty-state recovery: adds the built-in Alpine default configuration back to the list. */
    fun restoreBuiltinAlpine() {
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(ContainerProfile.BUILTIN_ALPINE)
        }
    }

    /** Saves (inserts/overwrites) a custom container profile. */
    fun saveCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** Edits a custom profile: overwrites the config; if the image source changed, deletes the old rootfs to trigger re-extraction. */
    fun editCustomContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            val old = _customProfiles.value.firstOrNull { it.id == profile.id }
            val oldUri = (old?.rootfsSource as? RootfsSource.LocalFile)?.uri
            val newUri = (profile.rootfsSource as? RootfsSource.LocalFile)?.uri
            if (old != null && oldUri != newUri) {
                cleanRootfsWithProgress(profile)
            }
            containerSettingsRepository.upsertCustomProfile(profile)
        }
    }

    /** Deletes a container profile (built-in Alpine included; the empty state restores after all are deleted), also cleaning up the local rootfs. */
    fun deleteContainerProfile(profile: ContainerProfile) {
        viewModelScope.launch {
            containerSettingsRepository.deleteCustomProfile(profile.id)
            cleanRootfsWithProgress(profile)
            if (_activeProfileId.value == profile.id) {
                // The deleted one was the active entry: switch to the first remaining; if the list is empty, fall back to the built-in id (engine falls back to Alpine)
                val remaining = _customProfiles.value.filterNot { it.id == profile.id }
                applyProfile(remaining.firstOrNull()?.id ?: ContainerProfile.BUILTIN_ID)
            }
        }
    }

    /** Marks the announcement as shown: writes the current content hash to storage so unchanged content never pops again; updated content changes the hash and naturally pops again. */
    fun markContainerAnnouncementShown() {
        viewModelScope.launch {
            containerSettingsRepository.markAnnouncementShown(sha256(_containerAnnouncementText.value))
        }
    }

    /** Marks the "Prompts" page announcement as shown; same as the container announcement, updated content (changed hash) pops again automatically. */
    fun markPromptsAnnouncementShown() {
        viewModelScope.launch {
            generalSettingsRepository.markPromptsAnnouncementShown(sha256(_promptsAnnouncementText.value))
        }
    }

    /** Switches the global download source (official/Huawei Cloud/Alibaba Cloud/Tencent Cloud, etc.). */
    fun setImageSource(sourceId: String) {
        _selectedImageSource.value = sourceId
    }

    /** Source display name: takes the name defined in the catalog JSON per the UI language, or the system language when following the system. */
    fun sourceDisplayName(sourceId: String, languageTag: String?): String {
        val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
        val key = if (lang.startsWith("zh", ignoreCase = true)) "zh" else "en"
        return containerImageCatalog.sourceName(sourceId, key) ?: sourceId
    }

    /** Starts downloading an image: builds the URL from the current architecture and global source, writing progress into [_containerImageDownload] live. */
    fun startContainerImageDownload(entry: ContainerImageEntry, sourceId: String) {
        if (_containerImageDownload.value is ContainerImageDownloadUiState.Downloading) return
        val url = containerImageCatalog.urlFor(entry, sourceId, ContainerImageCatalog.CURRENT_ABI) ?: return
        val suffix = when {
            url.endsWith(".tar.gz") -> "tar.gz"
            url.endsWith(".tar.xz") || url.endsWith(".txz") -> "tar.xz"
            url.endsWith(".tgz") -> "tgz"
            else -> "tar.gz"
        }
        val fileName = "download_${entry.id}_${entry.version}_${System.currentTimeMillis()}.$suffix"
        downloadJob = viewModelScope.launch {
            _containerImageDownload.value =
                ContainerImageDownloadUiState.Downloading(entry.id, entry.name, entry.version, sourceId, 0, 0)
            try {
                val uri = containerImageDownloader.download(url, fileName) { read, total ->
                    _containerImageDownload.value = ContainerImageDownloadUiState.Downloading(
                        entry.id, entry.name, entry.version, sourceId, read, total
                    )
                }
                // Persist the record as soon as the download completes, kept whether or not it gets installed, visible across restarts.
                containerSettingsRepository.upsertDownloadedImage(DownloadedImageRecord(entry.id, uri))
                _containerImageDownload.value =
                    ContainerImageDownloadUiState.Done(entry.id, entry.name, entry.version, uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _containerImageDownload.value = ContainerImageDownloadUiState.Idle
                throw e
            } catch (e: Exception) {
                _containerImageDownload.value =
                    ContainerImageDownloadUiState.Error(entry.id, e.message ?: context.getString(R.string.container_download_failed, ""))
            }
        }
    }

    /** Cancels the ongoing download (partial files are cleaned up by the downloader). */
    fun cancelContainerImageDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /**
     * Imports a downloaded image into the container list as a custom profile (repeatable, creating a new profile each time); the follow-up flow matches manual import.
     */
    fun importDownloadedImage(entryId: String, fileUri: String) {
        val entry = _imageCatalog.value.firstOrNull { it.id == entryId } ?: return
        viewModelScope.launch {
            val profile = ContainerProfile(
                id = "custom-${System.currentTimeMillis()}",
                name = "${entry.name} ${entry.version}",
                rootfsSource = RootfsSource.LocalFile(fileUri),
                shellPath = null,
                extraArgs = ContainerProfile.DEFAULT_PROOT_ARGS,
                isBuiltin = false
            )
            containerSettingsRepository.upsertCustomProfile(profile)
            // Keep the downloaded record, only marking it installed; the UI state does not disappear.
            containerSettingsRepository.upsertDownloadedImage(DownloadedImageRecord(entryId, fileUri, installed = true))
            _containerImageDownload.value = ContainerImageDownloadUiState.Idle
        }
    }

    /** Deletes a downloaded image: clears the record + deletes the file (restricted to the rootfs_images directory to prevent misuse); cancels first if this entry is downloading. */
    fun deleteDownloadedImage(entryId: String) {
        val record = _downloadedImages.value[entryId] ?: return
        if ((_containerImageDownload.value as? ContainerImageDownloadUiState.Downloading)?.entryId == entryId) {
            cancelContainerImageDownload()
        }
        viewModelScope.launch {
            runCatching {
                val file = java.io.File(Uri.parse(record.fileUri).path ?: return@runCatching)
                if (file.parentFile?.name == "rootfs_images") file.delete()
            }
            containerSettingsRepository.removeDownloadedImage(entryId)
        }
    }

    /** Sets the vision model; a blank providerId is equivalent to [clearVisionModel] (follow the chat model). */
    fun setVisionModel(providerId: String, model: String) {
        viewModelScope.launch {
            visionModelSettingsRepository.setVisionModel(providerId, model)
        }
    }

    /** Clears the vision model — falls back to following the current chat model. */
    fun clearVisionModel() {
        viewModelScope.launch {
            visionModelSettingsRepository.clear()
        }
    }

    /** Sets the dedicated compaction model; a blank providerId is equivalent to [clearCompactionModel] (follow the chat model). */
    fun setCompactionModel(providerId: String, model: String) {
        viewModelScope.launch {
            compactionModelSettingsRepository.setCompactionModel(providerId, model)
        }
    }

    /** Clears the dedicated compaction model — falls back to following the current chat model. */
    fun clearCompactionModel() {
        viewModelScope.launch {
            compactionModelSettingsRepository.clear()
        }
    }

    /** Sets the dedicated title summarization model; a blank providerId is equivalent to [clearTitleModel] (follow the chat model). */
    fun setTitleModel(providerId: String, model: String) {
        viewModelScope.launch {
            titleModelSettingsRepository.setTitleModel(providerId, model)
        }
    }

    /** Clears the dedicated title summarization model — falls back to following the current chat model. */
    fun clearTitleModel() {
        viewModelScope.launch {
            titleModelSettingsRepository.clear()
        }
    }

    /** Sets the dedicated image generation model; a blank providerId is equivalent to [clearImageGenModel] (unconfigured). */
    fun setImageGenModel(providerId: String, model: String) {
        viewModelScope.launch {
            imageGenModelSettingsRepository.setImageGenModel(providerId, model)
        }
    }

    /** Clears the dedicated image generation model — back to unconfigured (the image generation tool will prompt to configure first). */
    fun clearImageGenModel() {
        viewModelScope.launch {
            imageGenModelSettingsRepository.clear()
        }
    }

    /** Sets the AI command reviewer dedicated model; a blank providerId is equivalent to [clearReviewerModel] (follow the chat model). */
    fun setReviewerModel(providerId: String, model: String) {
        viewModelScope.launch {
            reviewerModelSettingsRepository.setReviewerModel(providerId, model)
        }
    }

    fun clearReviewerModel() {
        viewModelScope.launch {
            reviewerModelSettingsRepository.clear()
        }
    }

    /**
     * Saves the unified context-window values. When required fields (total context window, output window) are missing, the storage layer
     * automatically turns the toggle back off and [ContextWindowOverrideUiState] refreshes accordingly — the UI needs no check of its own.
     */
    fun saveContextWindowOverride(contextTokens: Int, inputTokens: Int?, outputTokens: Int?) {
        viewModelScope.launch {
            contextWindowOverrideRepository.save(contextTokens, inputTokens, outputTokens)
        }
    }

    /** Directly toggles the unified context-window values; cannot be turned on with required fields incomplete. */
    fun setContextWindowOverrideEnabled(enabled: Boolean) {
        viewModelScope.launch {
            contextWindowOverrideRepository.setEnabled(enabled)
        }
    }

    fun setTokenStatsPeriod(period: TokenStatsPeriod) {
        _tokenStatsPeriod.value = period
        // After switching periods, details and stat slices return to the first page
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /** Sets the provider filter (null = all). */
    fun setFilterProviderId(providerId: String?) {
        if (_filterProviderId.value == providerId) return
        _filterProviderId.value = providerId
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /** Sets the model filter (null = all). */
    fun setFilterModel(model: String?) {
        if (_filterModel.value == model) return
        _filterModel.value = model
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /** Clears all filter conditions. */
    fun clearTokenStatsFilters() {
        if (_filterProviderId.value == null && _filterModel.value == null) return
        _filterProviderId.value = null
        _filterModel.value = null
        _tokenStatsPage.value = 0
        _providerStatsPage.value = 0
        _modelStatsPage.value = 0
    }

    /**
     * Estimated cost (USD) of a single call; returns null when the model has no prices.
     * Price resolution and cache-price fallback are unified in [ModelCostCalculator].
     */
    private suspend fun callCostUsd(
        providerId: String?,
        model: String?,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
        cacheCreationTokens: Long = 0
    ): Double? {
        val modelId = model ?: return null
        val provider = _providers.value.firstOrNull { it.id == providerId }
        return modelCostCalculator.costUsd(
            providerId = providerId.orEmpty(),
            providerType = provider?.type ?: ProviderType.OPENAI,
            model = modelId,
            inputTokens = inputTokens,
            cachedInputTokens = cachedInputTokens,
            outputTokens = outputTokens,
            cacheCreationTokens = cacheCreationTokens
        )
    }

    /** Paginates call details; clamped to the valid range when out of bounds. */
    fun setTokenStatsPage(page: Int) {
        val total = _tokenStats.value.callsTotal
        val lastPage = if (total == 0) 0 else (total - 1) / CALLS_PAGE_SIZE
        _tokenStatsPage.value = page.coerceIn(0, lastPage)
    }

    /** Paginates channel stats; clamped to the valid range when out of bounds. */
    fun setProviderStatsPage(page: Int) {
        val total = _tokenStats.value.providersTotal
        val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
        _providerStatsPage.value = page.coerceIn(0, lastPage)
    }

    /** Paginates model stats; clamped to the valid range when out of bounds. */
    fun setModelStatsPage(page: Int) {
        val total = _tokenStats.value.modelsTotal
        val lastPage = if (total == 0) 0 else (total - 1) / STATS_PAGE_SIZE
        _modelStatsPage.value = page.coerceIn(0, lastPage)
    }

    /** Clears all call records (reset at the top-right of the Token statistics page); Room flows automatically refresh the aggregates to empty. */
    fun resetTokenStats() {
        viewModelScope.launch {
            llmCallRecordDao.deleteAll()
            _filterProviderId.value = null
            _filterModel.value = null
            _tokenStatsPage.value = 0
            _providerStatsPage.value = 0
            _modelStatsPage.value = 0
        }
    }

    fun setProviderEnabled(id: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setProviderEnabled(id, isEnabled)
        }
    }

    fun saveProvider(provider: AIProviderConfig) {
        viewModelScope.launch {
            val removedModels = (repository.getProviderById(provider.id)?.models ?: emptyList())
                .toSet() - provider.models.toSet()
            repository.saveProvider(provider)
            if (removedModels.isNotEmpty()) cleanupRemovedModels(provider, removedModels)
            // Reset the provider's in-memory dashboard state after saving, so the home page reloads immediately with the latest script and config
            _providerDashboards.update { it - provider.id }
        }
    }

    /**
     * After a model is removed from the list, clears every selection still pointing at it; otherwise the home page and dedicated models would keep sending requests with the deleted model.
     * Session-bound models are not cleaned here; resolveProviderConfig / AIChatPanel validate and fall back when reading, avoiding a scan of all historical sessions.
     */
    private suspend fun cleanupRemovedModels(provider: AIProviderConfig, removed: Set<String>) {
        if (provider.selectedModel in removed) {
            repository.setSelectedModel(provider.id, provider.models.firstOrNull() ?: "")
        }
        if (defaultModelSettingsRepository.getDefaultProviderId() == provider.id &&
            defaultModelSettingsRepository.getDefaultModel() in removed
        ) {
            defaultModelSettingsRepository.clear()
        }
        if (visionModelSettingsRepository.getVisionProviderId() == provider.id &&
            visionModelSettingsRepository.getVisionModel() in removed
        ) {
            visionModelSettingsRepository.clear()
        }
        if (imageGenModelSettingsRepository.getImageGenProviderId() == provider.id &&
            imageGenModelSettingsRepository.getImageGenModel() in removed
        ) {
            imageGenModelSettingsRepository.clear()
        }
        if (compactionModelSettingsRepository.getCompactionProviderId() == provider.id &&
            compactionModelSettingsRepository.getCompactionModel() in removed
        ) {
            compactionModelSettingsRepository.clear()
        }
        if (titleModelSettingsRepository.getTitleProviderId() == provider.id &&
            titleModelSettingsRepository.getTitleModel() in removed
        ) {
            titleModelSettingsRepository.clear()
        }
        if (reviewerModelSettingsRepository.getReviewerProviderId() == provider.id &&
            reviewerModelSettingsRepository.getReviewerModel() in removed
        ) {
            reviewerModelSettingsRepository.clear()
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            repository.deleteProvider(id)
        }
    }

    /** Provider list long-press drag reorder: updates the in-memory order synchronously (the reorderable library requires the list to be updated before onMove returns, else the dragged item flickers), then persists sortOrder asynchronously. */
    fun reorderProviders(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val current = _providers.value
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val reordered = current.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        _providers.value = reordered
        viewModelScope.launch {
            repository.reorderProviders(reordered)
        }
    }

    fun fetchModels(provider: AIProviderConfig) {
        viewModelScope.launch {
            _fetchState.value = FetchState.Loading
            modelApiService.fetchModels(provider.baseUrl, provider.firstUsableApiKey, provider.type, provider.useFullUrl, provider.customHeaders)
                .onSuccess { result ->
                    _fetchState.value = FetchState.Success(result.models, result.debugInfo)
                    resolveModelMetadata(provider.id, provider.type, result.models)
                }
                .onFailure { error ->
                    val debug = (error as? com.xencode.feature.settings.data.remote.FetchModelsException)?.debugInfo
                    _fetchState.value = FetchState.Error(
                        error.message ?: context.getString(R.string.settings_models_fetch_failed),
                        debug
                    )
                }
        }
    }

    fun resolveModelMetadata(providerId: String, type: ProviderType, modelIds: List<String>) {
        val normalizedIds = modelIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (normalizedIds.isEmpty()) return
        viewModelScope.launch {
            val metadata = modelMetadataService.resolveAll(providerId, type, normalizedIds)
            _modelMetadata.update { current ->
                current + metadata.mapKeys { (model, _) -> modelMetadataKey(providerId, model) }
            }
        }
    }

    /**
     * Loads model metadata for all models of all enabled providers and merges it into [modelMetadata].
     * Called on entry by pages like "vision model" that show cross-provider model capability tags--
     * these pages, unlike ProviderEditor, do not resolve while editing a single provider,
     * so without an eager load the map is empty and every model is wrongly flagged as not supporting images.
     *
     * Implementation notes (to avoid jank on the settings page):
     * - One coroutine processes providers sequentially: the first resolveAll triggers catalog loading (memory/disk 24h cache/built-in assets) and populates the memory cache,
     *   and subsequent providers hit the cache. The resolve path issues no network requests; models.dev refresh is triggered asynchronously at app startup.
     * - A single update after all resolutions, avoiding repeated emits that would recompose the settings page over and over.
     */
    fun loadAllModelMetadata() {
        val enabled = _providers.value.filter { it.isEnabled }
        if (enabled.isEmpty()) return
        viewModelScope.launch {
            val resolved = mutableMapOf<String, ModelMetadata>()
            for (provider in enabled) {
                val ids = provider.models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (ids.isEmpty()) continue
                modelMetadataService.resolveAll(provider.id, provider.type, ids)
                    .forEach { (model, meta) -> resolved[modelMetadataKey(provider.id, model)] = meta }
            }
            if (resolved.isNotEmpty()) {
                _modelMetadata.update { it + resolved }
            }
        }
    }

    fun resetFetchState() {
        _fetchState.value = FetchState.Idle
    }

    fun testModel(provider: AIProviderConfig, model: String) {
        viewModelScope.launch {
            _testing.update { it + model }
            val result = modelApiService.testModel(provider.baseUrl, provider.firstUsableApiKey, provider.type, provider.useFullUrl, provider.useResponseApi, model, provider.customHeaders)
            _testResults.update { it + (model to result) }
            _testing.update { it - model }
        }
    }

    fun clearTestResults() {
        _testResults.value = emptyMap()
        _testing.value = emptySet()
    }

    /**
     * Loads the available dashboard script list (called when the script picker opens). Enumerated via the engine on the IO dispatcher;
     * in remote mode this is the remote `~/.xencode/scripts`, avoiding SFTP enumeration on the main thread.
     */
    fun loadDashboardScripts() {
        viewModelScope.launch {
            _dashboardScripts.value = withContext(Dispatchers.IO) {
                providerDashboardRunner.listAvailableScripts()
            }
        }
    }

    fun testDashboardScript(provider: AIProviderConfig, scriptPath: String) {
        viewModelScope.launch {
            _dashboardTestState.value = ProviderDashboardState.Loading
            providerDashboardRunner.runScript(provider, scriptPath)
                .onSuccess { result ->
                    _dashboardTestState.value = ProviderDashboardState.Success(result)
                }
                .onFailure { error ->
                    _dashboardTestState.value = ProviderDashboardState.Error(
                        error.message ?: context.getString(R.string.dashboard_exec_failed),
                        error.localizedMessage ?: ""
                    )
                }
        }
    }

    fun clearDashboardTestState() {
        _dashboardTestState.value = ProviderDashboardState.Idle
    }

    fun refreshProviderDashboard(
        provider: AIProviderConfig,
        context: DashboardContext? = null,
        force: Boolean = false
    ) {
        if (provider.dashboardScriptPath.isBlank()) {
            _providerDashboards.update { it - provider.id }
            return
        }
        val current = _providerDashboards.value[provider.id]
        if (!force && current is ProviderDashboardState.Success) return
        if (current is ProviderDashboardState.Loading) return

        // Do not switch to Loading when a Success already exists, avoiding the UI height collapsing and restoring (collapse→expand flicker)
        val showLoading = current !is ProviderDashboardState.Success
        viewModelScope.launch {
            if (showLoading) {
                _providerDashboards.update { it + (provider.id to ProviderDashboardState.Loading) }
            }
            providerDashboardRunner.runScript(provider, context = context)
                .onSuccess { result ->
                    _providerDashboards.update { it + (provider.id to ProviderDashboardState.Success(result)) }
                }
                .onFailure { error ->
                    // The function parameter context: DashboardContext shadows the class's ApplicationContext;
                    // and inside launch{} this becomes a CoroutineScope, so the class label qualifier is required
                    _providerDashboards.update { it + (provider.id to ProviderDashboardState.Error(error.message ?: this@SettingsViewModel.context.getString(R.string.dashboard_query_failed))) }
                }
        }
    }

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            repository.setSelectedModel(providerId, model)
        }
    }

    fun deleteGlobalRule(rule: PermissionRule) {
        viewModelScope.launch { permissionRulesRepository.removeGlobalRule(rule) }
    }

    fun deleteProjectRule(rule: PermissionRule) {
        val name = currentProjectName.value ?: return
        viewModelScope.launch { permissionRulesRepository.removeProjectRule(name, rule) }
    }

    fun promoteRuleToGlobal(rule: PermissionRule) {
        val name = currentProjectName.value ?: return
        viewModelScope.launch { permissionRulesRepository.promoteToGlobal(name, rule) }
    }

    fun setDisableSafetyInterception(disabled: Boolean) {
        viewModelScope.launch { toolSafetySettingsRepository.setDisableSafetyInterception(disabled) }
    }

    /** Toggles the command authorization tier. */
    fun setCommandAuthLevel(level: CommandAuthLevel) {
        viewModelScope.launch { toolSafetySettingsRepository.setCommandAuthLevel(level) }
    }

    /** Adds a user-created high-risk interception rule. */
    fun addHighRiskRule(rule: HighRiskRule) {
        viewModelScope.launch { highRiskRulesRepository.addRule(rule) }
    }

    /** Updates a high-risk interception rule (edited content or toggle). */
    fun updateHighRiskRule(rule: HighRiskRule) {
        viewModelScope.launch { highRiskRulesRepository.updateRule(rule) }
    }

    fun removeHighRiskRule(id: String) {
        viewModelScope.launch { highRiskRulesRepository.removeRule(id) }
    }

    /** Restores the high-risk rule presets (user-created rules are kept). */
    fun resetHighRiskRules() {
        viewModelScope.launch { highRiskRulesRepository.resetToPresets() }
    }
}

/** Reads the announcement markdown (the zh version for zh, otherwise the English version); when following the system language, judged by the system language. Returns an empty string on read failure (empty content never pops the dialog). */
private fun loadContainerAnnouncement(context: Context, languageTag: String?): String {
    val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
    val zh = lang.startsWith("zh", ignoreCase = true)
    return runCatching {
        context.assets.open("announcements/container-guide.${if (zh) "zh" else "en"}.md")
            .bufferedReader().use { it.readText() }
    }.getOrDefault("")
}

/** Reads the "Prompts" page announcement markdown (the zh version for zh, otherwise the English version); returns an empty string on read failure (empty content never pops the dialog). */
private fun loadPromptsAnnouncement(context: Context, languageTag: String?): String {
    val lang = languageTag?.takeIf { it.isNotBlank() } ?: java.util.Locale.getDefault().language
    val zh = lang.startsWith("zh", ignoreCase = true)
    return runCatching {
        context.assets.open("announcements/prompts-guide.${if (zh) "zh" else "en"}.md")
            .bufferedReader().use { it.readText() }
    }.getOrDefault("")
}

/** SHA-256 hex digest, used for announcement content comparison: updated content changes the hash and pops the dialog again automatically. */
private fun sha256(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

/** Whether the file name's extension (lowercase, no dot) is on the [exts] allowlist. */
private fun String.hasExtension(exts: Set<String>): Boolean {
    val dot = lastIndexOf('.')
    if (dot < 0 || dot == length - 1) return false
    return substring(dot + 1).lowercase() in exts
}
