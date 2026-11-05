package com.xencode.feature.agent.domain.workflow

import android.os.SystemClock
import android.util.Base64
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.data.local.dao.LlmCallRecordDao
import com.xencode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.xencode.feature.agent.data.remote.anthropic.AnthropicApi
import com.xencode.feature.agent.data.remote.gemini.GeminiApi
import com.xencode.feature.agent.data.remote.openai.OpenAIApi
import com.xencode.feature.agent.domain.model.AgentContext
import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.model.AgentMode
import com.xencode.feature.agent.domain.notification.AgentEventInjector
import com.xencode.feature.agent.domain.notification.AgentNotificationCenter
import com.xencode.feature.agent.domain.notification.AgentNotificationKind
import com.xencode.feature.agent.domain.notification.PendingNotification
import com.xencode.feature.agent.domain.session.SessionUseCase
import com.xencode.feature.agent.domain.session.MessagePersistenceUseCase
import com.xencode.feature.agent.domain.checkpoint.CheckpointManager
import com.xencode.feature.agent.domain.permission.PermissionChoice
import com.xencode.feature.agent.domain.permission.PermissionScope
import com.xencode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.xencode.feature.agent.domain.prompt.SystemPromptProvider
import com.xencode.feature.agent.domain.provider.AIProvider
import com.xencode.feature.agent.domain.provider.AIResponse
import com.xencode.feature.agent.domain.provider.AIStreamChunk
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.StreamingAgentTool
import com.xencode.feature.agent.domain.tool.ToolCall
import com.xencode.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.xencode.feature.agent.domain.tool.mode.PlanApprovalManager
import com.xencode.feature.agent.domain.tool.ToolPermissionManager
import com.xencode.feature.agent.domain.tool.ToolPermissionPolicy
import com.xencode.feature.agent.domain.tool.ToolRegistry
import com.xencode.feature.agent.domain.tool.ToolResult
import com.xencode.feature.agent.domain.tool.ToolInvoker
import com.xencode.feature.agent.domain.tool.ToolRunResult

import com.xencode.feature.agent.domain.tool.modelToolResultText
import com.xencode.feature.agent.domain.tool.toTransportString
import com.xencode.feature.agent.presentation.AgentAttachment
import com.xencode.feature.settings.data.remote.ModelMetadataService
import com.xencode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.xencode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.xencode.feature.settings.data.repository.GeneralSettingsRepository
import com.xencode.feature.settings.data.repository.LanguageSettingsRepository
import com.xencode.feature.settings.data.repository.ProviderKeyRotator
import com.xencode.feature.settings.data.repository.ReviewerModelSettingsRepository
import com.xencode.feature.settings.data.repository.TitleModelSettingsRepository
import com.xencode.feature.settings.domain.model.AIProviderConfig
import com.xencode.feature.agent.domain.provider.AnthropicAdapter
import com.xencode.feature.agent.domain.provider.fixedTemperature
import com.xencode.feature.agent.domain.provider.GeminiAdapter
import com.xencode.feature.agent.domain.provider.AllKeysFailedException
import com.xencode.feature.agent.domain.provider.KeySwitchOutcome
import com.xencode.feature.agent.domain.provider.isKeySwitchFailure
import com.xencode.feature.agent.domain.provider.OpenAIAdapter
import com.xencode.feature.settings.domain.model.ProviderType
import com.xencode.feature.settings.domain.model.ModelContextPolicy
import com.xencode.feature.agent.domain.provider.StreamApiException
import com.xencode.feature.settings.domain.repository.AIProviderRepository
import com.xencode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Phase-three refactor (full version): an Agent workflow engine built on immutable state and MVI architecture.
 * By defining explicit AgentSessionState, AgentAction, and AgentSideEffect,
 * it uses a Reducer for state transitions, fully decoupling pure-function business logic from side-effecting external environment operations.
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val toolInvoker: ToolInvoker,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val promptProvider: SystemPromptProvider,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val contextCompactor: ContextCompactor,
    private val planApprovalManager: PlanApprovalManager,
    private val modelMetadataService: ModelMetadataService,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val reviewerModelSettingsRepository: ReviewerModelSettingsRepository,
    private val languageSettingsRepository: LanguageSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val keyRotator: ProviderKeyRotator,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val eventInjector: AgentEventInjector,
    private val fileAccess: FileAccessProvider
) : AgentWorkflow {

    /**
     * Persistence-only scope: independent of the caller's coroutine tree, non-cancellable.
     *
     * Purpose: takes "writing the LLM call record to disk" off the coroutine cancellation-unwinding path.
     * See the comments at each finally — calling any suspend (including withContext) during unwinding
     * scrambles the coroutine state machine across suspend/resume, triggering a native ART abort "No pending
     * exception expected" / "Unexpected instruction" and an immediate crash.
     * Dispatched with [CoroutineScope.launch]: launch is not a suspend function and never suspends the current coroutine.
     */
    private val recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "StatefulAgentWorkflow"
        const val USER_REJECTED_CODE = "USER_REJECTED"
        const val TITLE_GENERATOR_FILE = "agent/title-generator.md"
        const val TITLE_MAX_CHARS = 50
        const val COMMIT_GENERATOR_FILE = "agent/commit-generator.md"
        /** AI command reviewer prompt: reviews shell commands one by one under the "autonomous execution" tier. */
        const val COMMAND_REVIEWER_FILE = "agent/command-reviewer.md"
        /** Mode reminder prompts: reuses prompts directory files (user-overridable); injected with a message on switch instead of going into system. */
        const val MODE_REMINDER_PLAN_FILE = "agent/plan-mode.md"
        const val MODE_REMINDER_ASK_FILE = "agent/ask-mode.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        /** Directory where model-emitted images are persisted (consistent with GenerateImageTool). */
        const val GENERATED_IMAGE_DIR = "~/.xencode/generated-images"
        const val MAX_GENERATED_IMAGE_BYTES = 20L * 1024 * 1024
    }

    /**
     * Per-session "mode whose reminder was last injected". Reminders are injected only on change, to avoid re-appending the same text
     * to the latest user message every turn — otherwise that position (an Anthropic cache breakpoint) differs when history is rebuilt next turn,
     * discarding a stretch of prefix cache for nothing. In-process cache; re-injected once on the first turn after restart (harmless).
     */
    private val lastInjectedMode = java.util.concurrent.ConcurrentHashMap<String, AgentMode>()

    /** Immutable state tree */
    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val iterations: Int = 0,
        val isFinished: Boolean = false,
        val error: String? = null,
        /** Error type code (e.g. server stop_reason) for the UI to map to localized copy; null means show [error] directly. */
        val errorCode: String? = null,
        /** Tool calls returned by the model in this batch (original order, used to assemble tool responses in order at the end) */
        val batchToolCalls: List<ToolCall> = emptyList(),
        /** Tool calls awaiting permission requests (collected one dialog at a time) */
        val pendingPermissionCalls: List<ToolCall> = emptyList(),
        /** Approved tool calls awaiting parallel execution */
        val approvedToolCalls: List<ToolCall> = emptyList(),
        /** Tool results rejected by policy/system (not by the user), keyed by toolCall.id */
        val rejectedToolResults: Map<String, ToolBatchResult> = emptyMap()
    )

    /** State-changing actions (Action) */
    sealed interface AgentAction {
        data class InitRequest(val initialMessages: List<AgentMessage>) : AgentAction
        data class LlmResponse(val response: AIResponse, val messageId: String) : AgentAction
        data class LlmError(val error: String, val reasonCode: String? = null) : AgentAction
        data class PermissionEvaluated(
            val toolCall: ToolCall,
            val approved: Boolean,
            val argsPreview: String,
            val denyReason: String = "The user declined this tool",
            val errorCode: String = "USER_REJECTED"
        ) : AgentAction
        data class ToolBatchFinished(
            val results: List<ToolBatchResult>
        ) : AgentAction
    }

    private data class PermissionCheckResult(
        val approved: Boolean,
        val denyReason: String = "The user declined this tool",
        val errorCode: String = "USER_REJECTED"
    )

    /** Batch tool execution results: carry toolCall metadata for assembling ToolResultMessages in the original order at the end. */
    data class ToolBatchResult(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        /** Display-only tools such as sendFile: file-card metadata attached to the result for UI rendering, not fed back into the model context. */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** Side effects to be executed in the external environment (SideEffect) */
    sealed interface AgentSideEffect {
        object CallLlm : AgentSideEffect
        data class PersistUser(val message: AgentMessage.UserMessage) : AgentSideEffect
        data class RequestPermission(val toolCall: ToolCall) : AgentSideEffect
        /** Executes the approved tools in parallel as a batch; an empty list means no tools to run this batch, going straight to wrap-up. */
        data class ExecuteToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        /** Cancels the whole batch (the user rejected one call in it): re-emits completion events for already-started tools and clears the UI's "executing" state. */
        data class CancelToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
    }

    private suspend fun getEffectiveProvider(sessionId: String?): AIProvider {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("No AI vendor configured. Add and select one in Settings.")
        if (!config.hasUsableApiKey) throw IllegalStateException("\"${config.name}\" is missing an API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("\"${config.name}\" has no model selected")
        return createStandaloneProvider(config, sessionId)
    }

    /**
     * Resolves the currently effective provider config: prefers the session-bound providerId/model, falling back to the global active provider.
     * If the session-bound provider no longer exists or is disabled, falls back to global so old sessions and abnormal data keep working.
     */
    private suspend fun resolveProviderConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                if (config != null && config.isEnabled && config.hasUsableApiKey) {
                    // The bound model may have been removed from the provider's model list; the binding is then void, so keep falling back to the default model below
                    if (boundModel.isNullOrBlank()) return config
                    if (boundModel in config.models) return config.copy(selectedModel = boundModel)
                }
            }
        }
        // Fallback: the new-session default model (remembered when chosen in the home empty session); if unset, return null and the caller errors out with guidance.
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.hasUsableApiKey && defaultModel in config.models) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("No AI vendor configured. Add and select one in Settings.")
        if (!config.hasUsableApiKey) throw IllegalStateException("\"${config.name}\" is missing an API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("\"${config.name}\" has no model selected")
        val provider = createStandaloneProvider(config, sessionId)
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__manual_compress__")
        if (history.size <= 2) return false
        val compactionProvider = resolveCompactionFallbackProvider(sessionId) ?: provider
        // The summary still uses the dedicated compaction model (respecting the user's choice), but the window budget for retained recent messages follows the main chat model,
        // consistent with auto-compaction — otherwise a small-window compaction model would retain too little recent context in manual compaction.
        val result = contextCompactor.compactIfNeeded(history, compactionProvider, sessionId, force = true, windowProvider = provider, onEvent = onEvent)
        return result.compacted
    }

    /**
     * Creates a brand-new, standalone [AIProvider] instance from [config].
     * Used for independent request scenarios such as context compaction; never occupies or mutates the Provider singleton used by the main conversation.
     * Also applies the provider-level LLM cache toggles (Anthropic breakpoints / OpenAI cache key) to the instance.
     */
    private suspend fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
            }
        }
        // In multi-key mode the rotator decides which key to use this time (sticky within a session; on key failure it switches to the next and resends).
        val activeKeys = config.effectiveApiKeys
        provider.apiKey = keyRotator.activeKey(config, sessionId)
            ?: throw IllegalStateException("All keys for \"${config.name}\" are cooling down. Try again later.")
        // Only genuine multi-key setups need auto-switching: with a single key there is nothing to switch to after cooldown — it would just lock the user out for a while.
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(config.id, sessionId, failedKey, triedKeys)
                        if (switched == null) {
                            throw AllKeysFailedException(
                                "All ${activeKeys.size} keys for \"${config.name}\" failed: ${error.message ?: error.javaClass.simpleName}",
                                error
                            )
                        }
                        KeySwitchOutcome(switched.newKey, switched.newIndex, switched.total)
                    }
                }
            } else {
                null
            }
        provider.keySwitcher = keySwitcher
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        // Output cap from model metadata (models.dev limit.output): when unset, Anthropic pins output at the adapter's fallback value.
        provider.maxOutputTokens = metadata.outputTokens
        // If metadata says custom temperature is unsupported, omit the field (kimi-k3 and gpt-5 series return 400 if present); when allowed, only the official fixed value is sent.
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    /** Core Reducer: takes the old state and an Action, returns the new state plus the list of triggered side effects (pure function) */
    private fun reduce(
        state: AgentSessionState,
        action: AgentAction
    ): Pair<AgentSessionState, List<AgentSideEffect>> {
        var newState = state
        val effects = mutableListOf<AgentSideEffect>()

        when (action) {
            is AgentAction.InitRequest -> {
                newState = state.copy(messages = action.initialMessages)
                effects.add(AgentSideEffect.CallLlm)
            }
            is AgentAction.LlmResponse -> {
                val assistantMsg = AgentMessage.AssistantMessage(
                    id = action.messageId,
                    content = action.response.content,
                    toolCalls = action.response.toolCalls,
                    reasoning = action.response.reasoning ?: "",
                    signature = action.response.signature ?: "",
                    thinkingBlocksJson = action.response.thinkingBlocksJson ?: "",
                    images = action.response.images,
                    inputTokens = action.response.inputTokens
                )
                newState = state.copy(
                    messages = state.messages + assistantMsg,
                    iterations = state.iterations + 1
                )
                
                if (action.response.toolCalls.isEmpty()) {
                    if (action.response.isAborted) {
                        // Refusal / context overflow: the body may be empty; ending silently would leave the user staring at a blank bubble, thinking it hung.
                        newState = newState.copy(
                            isFinished = true,
                            error = action.response.stopDetail ?: "",
                            errorCode = action.response.stopReason
                        )
                    } else if (action.response.isTruncated) {
                        val continuation = AgentMessage.UserMessage(content = "Your response was truncated. Continue from where it stopped.")
                        newState = newState.copy(messages = newState.messages + continuation)
                        effects.add(AgentSideEffect.PersistUser(continuation))
                        effects.add(AgentSideEffect.CallLlm)
                    } else {
                        newState = newState.copy(isFinished = true)
                    }
                } else {
                    // Multiple tool_calls in this batch: all go into the permission queue, collecting approvals one dialog at a time;
                    // only after all are approved does parallel execution begin (see PermissionEvaluated / ToolBatchFinished).
                    val toolCalls = action.response.toolCalls.toList()
                    newState = newState.copy(
                        batchToolCalls = toolCalls,
                        pendingPermissionCalls = toolCalls,
                        approvedToolCalls = emptyList(),
                        rejectedToolResults = emptyMap()
                    )
                    effects.add(AgentSideEffect.RequestPermission(toolCalls.first()))
                }
            }
            is AgentAction.LlmError -> {
                newState = state.copy(isFinished = true, error = action.error, errorCode = action.reasonCode)
            }
            is AgentAction.PermissionEvaluated -> {
                if (action.approved) {
                    // Approved: move the current toolCall into the approved set; if permission requests remain, keep showing dialogs, otherwise start parallel execution.
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    val approved = newState.approvedToolCalls + action.toolCall
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        approvedToolCalls = approved
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(approved))
                    }
                } else {
                    val rawResult = ToolResult.Error(action.denyReason, action.errorCode).toTransportString()
                    if (action.errorCode == USER_REJECTED_CODE) {
                        // The model may return multiple tool_calls at once. The user rejecting any one → cancel the whole batch:
                        // backfill tool responses for every call in the original batchToolCalls order (no duplicates, no omissions),
                        // otherwise assistant(toolCalls=N) is followed by only some of the tool messages and OpenAI returns 400
                        // "insufficient tool messages following tool_calls".
                        val cancelled = newState.batchToolCalls.map { call ->
                            AgentMessage.ToolResultMessage(
                                id = call.id,
                                toolName = call.name,
                                result = ToolResult.Error(
                                    "The user declined this round of tool calls; this call was not executed.",
                                    USER_REJECTED_CODE
                                ).toTransportString()
                            )
                        }
                        newState = state.copy(
                            messages = state.messages + cancelled,
                            batchToolCalls = emptyList(),
                            pendingPermissionCalls = emptyList(),
                            approvedToolCalls = emptyList(),
                            isFinished = true
                        )
                        // Tools approved but not yet executed (whose ToolCallStarted was sent) need completion events re-emitted,
                        // otherwise the UI and persisted messages would stay stuck in "executing".
                        if (state.approvedToolCalls.isNotEmpty()) {
                            effects.add(AgentSideEffect.CancelToolBatch(state.approvedToolCalls))
                        }
                        return newState to effects
                    }
                    // Policy/system rejection (e.g. execution forbidden in PLAN mode): record the rejection and keep collecting subsequent permissions.
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        rejectedToolResults = newState.rejectedToolResults + (
                            action.toolCall.id to ToolBatchResult(
                                id = action.toolCall.id,
                                toolName = action.toolCall.name,
                                result = rawResult,
                                isError = true
                            )
                        )
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                    }
                }
            }
            is AgentAction.ToolBatchFinished -> {
                // All tools in this batch have finished; assemble tool responses in the original batchToolCalls order:
                // prefer policy-rejection results, then parallel execution results, keeping the order consistent with assistant(toolCalls).
                val resultsById = action.results.associateBy { it.id }
                val appendedMessages = mutableListOf<AgentMessage>()
                newState.batchToolCalls.forEach { call ->
                    val batchResult = newState.rejectedToolResults[call.id] ?: resultsById[call.id] ?: return@forEach
                    appendedMessages.add(
                        AgentMessage.ToolResultMessage(
                            id = batchResult.id,
                            toolName = batchResult.toolName,
                            result = batchResult.result,
                            images = batchResult.images,
                            modelResult = modelToolResultText(batchResult.toolName, batchResult.result)
                        )
                    )
                }
                newState = state.copy(
                    messages = state.messages + appendedMessages,
                    batchToolCalls = emptyList(),
                    pendingPermissionCalls = emptyList(),
                    approvedToolCalls = emptyList(),
                    rejectedToolResults = emptyMap()
                )
                effects.add(AgentSideEffect.CallLlm)
            }
        }
        
        return Pair(newState, effects)
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        var currentContext = context
        var state = AgentSessionState()
        var currentTools = tools
        val actionQueue = ArrayDeque<AgentAction>()
        // The mode reminder is injected once with the latest user message only when the mode changes (kept out of system, so mode switches don't change the system prefix and break caching).
        val modeReminder = takeModeReminderIfChanged(currentContext.sessionId, currentContext.mode)
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + AgentMessage.UserMessage(
                    id = currentContext.inputMessageId,
                    content = if (modeReminder == null) userRequest else "$userRequest\n\n$modeReminder",
                    images = currentContext.inputImages
                )
            )
        )

        // Building the system prompt scans the Skill / rules / memory directories; on the on-device ROOT channel those file reads/writes go through
        // `su -c` subprocesses + Process.waitFor(), which is blocking IO and must be moved to the IO dispatcher;
        // otherwise the main thread gets stuck in waitFor() until ANR and the process is killed by the system (manifesting as a crash).
        val systemPrompt = withContext(Dispatchers.IO) { promptProvider.build(currentContext) }
        val aiProvider = getEffectiveProvider(currentContext.sessionId)
        // After a compaction failure, do not retry compaction within this turn (this user request), to avoid a wasted attempt on every LLM call.
        var compactionAttemptFailed = false
        val metadata = modelMetadataService.resolve(aiProvider.providerId, when (aiProvider) {
            is AnthropicAdapter -> ProviderType.ANTHROPIC
            is GeminiAdapter -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }, aiProvider.model)
        val inputBudget = ModelContextPolicy.effectiveInputBudget(metadata)
        if (aiProvider is AnthropicAdapter) {
            aiProvider.maxOutputTokens = ModelContextPolicy.outputReserveTokens(metadata)
        }
        val lastAssistantIndex = currentContext.history.indexOfLast { it is AgentMessage.AssistantMessage && it.inputTokens > 0 }
        var baselineEstimate = ContextTokenEstimator.estimate(systemPrompt,
            currentContext.history.take(lastAssistantIndex.coerceAtLeast(0)), currentTools)
        var baselineUsage = if (currentContext.lastInputTokens > 0 && lastAssistantIndex >= 0) {
            (currentContext.history[lastAssistantIndex] as AgentMessage.AssistantMessage).inputTokens
        } else 0
        var overflowRecoveryAttempted = false

        while (!state.isFinished && actionQueue.isNotEmpty()) {
            val action = actionQueue.removeFirst()
            val (newState, effects) = reduce(state, action)
            state = newState

            for (effect in effects) {
                when (effect) {
                    is AgentSideEffect.PersistUser -> {
                        currentContext.sessionId?.let { sessionId ->
                            messagePersistenceUseCase.persist(sessionId, com.xencode.feature.agent.presentation.MessageRole.USER,
                                effect.message.content, id = effect.message.id)
                            messagePersistenceUseCase.invalidateHistory(sessionId)
                        }
                    }
                    is AgentSideEffect.CallLlm -> {
                        val providerInUse = aiProvider
                        // Compaction round: if a dedicated compaction model is configured, compact with the standalone compaction model
                        val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
                        var compactedMessages = state.messages
                        if (!compactionAttemptFailed) {
                            val estimate = ContextTokenEstimator.estimate(systemPrompt, state.messages, currentTools)
                            val predictedInput = ContextTokenEstimator.calibrated(estimate, baselineEstimate, baselineUsage)
                            val compaction = contextCompactor.compactIfNeeded(state.messages, compactionProvider, context.sessionId,
                                windowProvider = aiProvider, systemPrompt = systemPrompt, tools = currentTools,
                                currentInputTokens = predictedInput) { event ->
                                if (event is AgentEvent.CompactionFailed) compactionAttemptFailed = true
                                send(event)
                            }
                            compactedMessages = compaction.messages
                            if (compaction.compacted) {
                                state = state.copy(messages = compaction.messages)
                                baselineUsage = 0
                                baselineEstimate = 0
                            }
                        }
                        val requestEstimate = ContextTokenEstimator.estimate(systemPrompt, compactedMessages, currentTools)
                        val predictedInput = ContextTokenEstimator.calibrated(requestEstimate, baselineEstimate, baselineUsage)
                        send(AgentEvent.ContextUsage(predictedInput, inputBudget, true))
                        if (predictedInput >= inputBudget) {
                            actionQueue.addLast(AgentAction.LlmError("", "input_budget_exceeded"))
                            continue
                        }

                        val acc = StringBuilder()
                        val reasoningAcc = StringBuilder()
                        var finalResponse: AIResponse? = null

                        // Call telemetry: record request-sent / first-byte / completion times and usage; failures and cancellations are recorded too.
                        val callStartElapsed = SystemClock.elapsedRealtime()
                        val callStartWall = System.currentTimeMillis()
                        val callKind = "chat"
                        var ttfbElapsed: Long? = null
                        var callError: String? = null
                        var callCompleted = false

                        // Streaming delta throttling: every upstream chunk carries the full accumulated text; sending each one would make
                        // the ViewModel rebuild state dozens of times per second and the UI repeatedly restart the typewriter coroutine. Merge by time window:
                        // keep only the latest accumulated text within the window and send only at the window boundary, pressing downstream event frequency down to ~16/s.
                        // Typewriter rendering already throttles at 100ms, so fewer intermediate states are imperceptible; after collect ends, flush the last pending one.
                        val DELTA_THROTTLE_MS = 60L
                        var lastTextDeltaSentAt = 0L
                        var lastReasoningDeltaSentAt = 0L
                        var pendingTextDelta: String? = null
                        var pendingReasoningDelta: String? = null
                        var retryAttempts = 0
                        suspend fun flushPendingTextDelta() {
                            val text = pendingTextDelta ?: return
                            pendingTextDelta = null
                            send(AgentEvent.AssistantDelta(text))
                        }
                        suspend fun flushPendingReasoningDelta() {
                            val text = pendingReasoningDelta ?: return
                            pendingReasoningDelta = null
                            send(AgentEvent.ReasoningDelta(text))
                        }

                        try {
                            // Process images according to the actual model's vision capability before sending (same as the execute path).
                            val supportsVision = activeModelSupportsVision(currentContext.sessionId)
                            val messagesToSend = withSkillHint(
                                sanitizeImagesForModel(compactedMessages, supportsVision),
                                currentContext,
                                currentTools
                            )
                            providerInUse.completeStream(systemPrompt, messagesToSend, currentTools, currentContext.reasoningEffort).collect { chunk ->
                                when (chunk) {
                                    is AIStreamChunk.TextDelta -> {
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        acc.append(chunk.text)
                                        pendingTextDelta = acc.toString()
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastTextDeltaSentAt >= DELTA_THROTTLE_MS) {
                                            flushPendingTextDelta()
                                            lastTextDeltaSentAt = now
                                        }
                                    }
                                    is AIStreamChunk.ReasoningDelta -> {
                                        // Thinking content also counts as the first byte (reasoning models emit thinking before body text)
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        reasoningAcc.append(chunk.text)
                                        pendingReasoningDelta = reasoningAcc.toString()
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastReasoningDeltaSentAt >= DELTA_THROTTLE_MS) {
                                            flushPendingReasoningDelta()
                                            lastReasoningDeltaSentAt = now
                                        }
                                    }
                                    is AIStreamChunk.Retrying -> {
                                        retryAttempts = maxOf(retryAttempts, chunk.attempt)
                                        acc.setLength(0)
                                        reasoningAcc.setLength(0)
                                        pendingTextDelta = null
                                        pendingReasoningDelta = null
                                        lastTextDeltaSentAt = 0L
                                        lastReasoningDeltaSentAt = 0L
                                        send(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries, chunk.error))
                                    }
                                    is AIStreamChunk.KeySwitched -> {
                                        acc.setLength(0)
                                        reasoningAcc.setLength(0)
                                        pendingTextDelta = null
                                        pendingReasoningDelta = null
                                        lastTextDeltaSentAt = 0L
                                        lastReasoningDeltaSentAt = 0L
                                        send(AgentEvent.KeySwitched(chunk.newIndex, chunk.total))
                                    }
                                    is AIStreamChunk.Final -> {
                                        // A tool-call-only turn has no text/thinking deltas; Final is the first content event, so record it as the fallback TTFB
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        finalResponse = chunk.response
                                    }
                                    is AIStreamChunk.ToolCallDeclared -> {
                                        // The tool name arrives before the arguments: tell the UI right away "what the model is about to call",
                                        // instead of sitting on "Thinking" throughout long argument streaming.
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        send(AgentEvent.ToolCallPreparing(chunk.name))
                                    }
                                }
                            }
                            // The latest accumulated text may still be held inside the throttle window: flush it, so the UI tail gets the complete text before the persistence handoff.
                            flushPendingTextDelta()
                            flushPendingReasoningDelta()
                            val aiResponse = finalResponse ?: AIResponse(content = acc.toString())
                            // Model-emitted images (Gemini image models) do not arrive as streaming deltas; they come whole in Final:
                            // first persist the base64 to ~/.xencode/generated-images/ and build UI attachments (paths only, no base64),
                            // then hand the path-carrying images to reduce via LlmResponse to attach to the AssistantMessage for the next playback.
                            val (persistedImages, attachments) =
                                if (aiResponse.images.isNotEmpty()) persistModelImages(aiResponse.images) else emptyList<AgentImage>() to emptyList()
                            callCompleted = true
                            // Attach this turn's reasoning to the AIResponse so reduce stores it in the AssistantMessage and passes it back next turn
                            if (aiResponse.stopReason == "model_context_window_exceeded" && !overflowRecoveryAttempted) {
                                overflowRecoveryAttempted = true
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    state = state.copy(messages = recovery.messages)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                                    continue
                                }
                            }
                            baselineEstimate = requestEstimate
                            baselineUsage = aiResponse.inputTokens
                            send(AgentEvent.ContextUsage(
                                if (baselineUsage > 0) baselineUsage else requestEstimate, inputBudget, baselineUsage <= 0
                            ))
                            val responseMessageId = UUID.randomUUID().toString()
                            val responseWithReasoning = if (reasoningAcc.isNotEmpty()) {
                                aiResponse.copy(reasoning = reasoningAcc.toString())
                            } else aiResponse

                            if (aiResponse.content.isNotBlank() || aiResponse.toolCalls.isNotEmpty() || attachments.isNotEmpty()) {
                                val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                                send(
                                    AgentEvent.AssistantText(
                                        aiResponse.content,
                                        aiResponse.toolCalls,
                                        reasoningAcc.toString(),
                                        aiResponse.signature ?: "",
                                        aiResponse.inputTokens,
                                        aiResponse.outputTokens,
                                        aiResponse.cachedInputTokens,
                                        aiResponse.thinkingBlocksJson ?: "",
                                        attachments = attachments,
                                        messageId = responseMessageId,
                                        persisted = persisted
                                    )
                                )
                                persisted.await()
                            }
                            actionQueue.addLast(
                                AgentAction.LlmResponse(
                                    if (persistedImages.isNotEmpty()) responseWithReasoning.copy(images = persistedImages)
                                    else responseWithReasoning,
                                    messageId = responseMessageId
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (!overflowRecoveryAttempted && acc.isEmpty() && reasoningAcc.isEmpty() && isContextOverflow(e)) {
                                overflowRecoveryAttempted = true
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    state = state.copy(messages = recovery.messages)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                                    callError = e.message
                                    continue
                                }
                            }
                            val partial = acc.toString()
                            val reasoning = reasoningAcc.toString()
                            // When the stream is interrupted, persist the thinking received so far: otherwise the finally below clears the streaming thinking bubble
                            // while no relay message was persisted either, manifesting as "thinking displayed then vanishing with no error".
                            // Persist if there is body text or thinking; write nothing when both are empty.
                            if (partial.isNotEmpty() || reasoning.isNotBlank()) {
                                send(AgentEvent.AssistantText(partial, emptyList(), reasoning))
                            }
                            // Multi-key auto-switching and resending already happened inside the adapter (see AIProvider.keySwitcher);
                            // reaching here means it wasn't a key issue, or all candidate keys failed, so report the original error directly.
                            val errorText = "LLM call failed: ${e.message}"
                            actionQueue.addLast(AgentAction.LlmError(errorText))
                            callError = e.message ?: e.javaClass.simpleName
                        } finally {
                            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                            val usage = finalResponse
                            // Critical: never call suspend during cancellation unwinding (user tapped stop).
                            // Even wrapping the Room insert in withContext(NonCancellable) still crashes —
                            // the device tombstone (2026-10-05, 8+ reproductions) stacks all land at
                            // StatefulAgentWorkflow$executeEvents$1.invokeSuspend+0, with abort messages
                            // "No pending exception expected: JobCancellationException" and
                            // "Unexpected instruction: unused-e5", i.e. the coroutine state machine's
                            // labels got scrambled across suspend/resume, and the ART interpreter then died on an illegal instruction.
                            // So this dispatches to a separate non-cancellable scope instead: launch is not a suspend function,
                            // never suspends/resumes the current coroutine, and completely avoids state-machine jumps on the cancellation-unwinding path.
                            val record = LlmCallRecordEntity(
                                sessionId = currentContext.sessionId,
                                providerId = providerInUse.providerId.ifBlank { null },
                                model = providerInUse.model,
                                reasoningEffort = currentContext.reasoningEffort,
                                kind = callKind,
                                inputTokens = usage?.inputTokens ?: 0,
                                outputTokens = usage?.outputTokens ?: 0,
                                cachedInputTokens = usage?.cachedInputTokens ?: 0,
                                cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                                ttfbMillis = ttfbElapsed?.toInt(),
                                durationMillis = durationMillis,
                                status = when {
                                    callCompleted -> "success"
                                    callError != null -> "error"
                                    else -> "cancelled"
                                },
                                errorMessage = callError,
                                stopReason = usage?.stopReason,
                                retryCount = retryAttempts,
                                createdAt = callStartWall
                            )
                            recordScope.launch { runCatching { llmCallRecordDao.insert(record) } }
                        }
                    }
                    is AgentSideEffect.RequestPermission -> {
                        val tool = toolRegistry.getTool(effect.toolCall.name)
                        val argsPreview = JsonObject(effect.toolCall.arguments).toString().take(500)
                        val checkResult = requestPermissionIfNeeded(tool, effect.toolCall.id, effect.toolCall.arguments, argsPreview, currentContext.mode, currentContext.sessionId)

                        if (!checkResult.approved) {
                            val rawResult = ToolResult.Error(checkResult.denyReason, checkResult.errorCode).toTransportString()
                            send(AgentEvent.ToolCallFinished(effect.toolCall.id, effect.toolCall.name, rawResult, true, argsPreview))
                        } else {
                            send(AgentEvent.ToolCallStarted(effect.toolCall.id, effect.toolCall.name, argsPreview))
                        }
                        actionQueue.addLast(AgentAction.PermissionEvaluated(effect.toolCall, checkResult.approved, argsPreview, checkResult.denyReason, checkResult.errorCode))
                    }
                    is AgentSideEffect.CancelToolBatch -> {
                        // Whole-batch cancellation: re-emit completion events for approved-but-not-executed tools (content: not executed),
                        // letting the ViewModel clear runningTool and REPLACE the "executing" placeholder messages.
                        effect.toolCalls.forEach { toolCall ->
                            send(
                                AgentEvent.ToolCallFinished(
                                    id = toolCall.id,
                                    toolName = toolCall.name,
                                    result = ToolResult.Error(
                                        "The user declined this round of tool calls; this call was not executed.",
                                        USER_REJECTED_CODE
                                    ).toTransportString(),
                                    isError = true
                                )
                            )
                        }
                    }
                    is AgentSideEffect.ExecuteToolBatch -> {
                        // Execute this batch's approved tools in parallel. Record checkpoints (pre-modification snapshots for editFile/writeFile) uniformly first,
                        // then execute in parallel; mode-switch checks are handled serially on the main coroutine after results are collected (planApproval singleton).
                        val toolCalls = effect.toolCalls
                        toolCalls.forEach { toolCall ->
                            if (toolCall.name == "editFile" || toolCall.name == "writeFile") {
                                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                                    currentContext.sessionId?.let { sid ->
                                        checkpointManager.beforeFileModified(sid, path)
                                    }
                                }
                            }
                        }

                        val runResults = if (toolCalls.isEmpty()) {
                            emptyList()
                        } else {
                            coroutineScope {
                                toolCalls.map { toolCall ->
                                    async {
                                        val tool = toolRegistry.getTool(toolCall.name)
                                        if (tool is StreamingAgentTool) {
                                            toolInvoker.runStream(tool, toolCall, currentContext) { send(it) }
                                        } else {
                                            toolInvoker.runSync(tool, toolCall, currentContext)
                                        }
                                    }
                                }.awaitAll()
                            }
                        }

                        // Handle mode switches serially and assemble the batch results.
                        val batchResults = mutableListOf<ToolBatchResult>()
                        toolCalls.forEachIndexed { index, toolCall ->
                            val runResult = runResults.getOrNull(index)
                                ?: ToolRunResult(ToolResult.Error("Tool was not executed", "TOOL_NOT_EXECUTED").toTransportString(), true)
                            var rawResult = runResult.raw
                            var isError = runResult.isError
                            val (newCtx, updated) = checkAndUpdateMode(toolCall, isError, currentContext)
                            if (updated) {
                                val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.content?.trim()
                                    ?: toolCall.arguments["reason"]?.toString()?.replace("\"", "")?.trim()
                                    ?: ""
                                send(AgentEvent.ModeChanged(newCtx.mode, reason))

                                // On leaving PLAN, suspend the workflow and wait for the user's approval in the plan-review panel before continuing
                                if (currentContext.mode == AgentMode.PLAN && newCtx.mode != AgentMode.PLAN) {
                                    val choice = planApprovalManager.awaitApproval(reason, currentContext.sessionId)
                                    if (choice == PlanApprovalChoice.APPROVE) {
                                        currentContext = newCtx
                                        // system and mode are decoupled (SystemPromptProvider no longer injects mode prompts),
                                        // so switching doesn't rebuild systemPrompt, avoiding a system-prefix change breaking the cache; mode state is conveyed via the tool result and next turn's message reminder.
                                        rawResult += buildModeSwitchNotice(newCtx.mode)
                                    } else {
                                        // The user chose to keep refining: roll back to PLAN mode and rewrite the tool result so the AI knows the switch was cancelled and it should await user feedback
                                        currentContext = currentContext.copy(mode = AgentMode.PLAN)
                                        rawResult = ToolResult.Error(
                                            "The user wants to add notes or adjust the plan; staying in PLAN mode. Wait for their specific feedback. Do not invent changes on your own.",
                                            "MODE_SWITCH_REJECTED"
                                        ).toTransportString()
                                        isError = true
                                    }
                                } else {
                                    currentContext = newCtx
                                    rawResult += buildModeSwitchNotice(newCtx.mode)
                                }
                            }
                            batchResults.add(ToolBatchResult(toolCall.id, toolCall.name, rawResult, isError, runResult.attachments, runResult.images))
                        }

                        // Background task / subagent completion notifications that arrived within this turn: delivered immediately by piggybacking on this batch's last tool result,
                        // so the AI perceives them this very turn instead of waiting for a new turn after this one ends. The ack happens after the completion event is emitted (result already persisted):
                        // if cancelled midway, the notifications stay queued and are delivered by a later batch or this turn's final fallback path.
                        val notifySessionId = currentContext.sessionId
                        val notifications = if (notifySessionId != null && batchResults.isNotEmpty()) {
                            agentNotificationCenter.peek(notifySessionId)
                        } else {
                            emptyList()
                        }
                        if (notifications.isNotEmpty()) {
                            val modeChange = notifications.lastOrNull { it.kind == AgentNotificationKind.MODE_CHANGE }
                            val newMode = modeChange?.newMode
                            if (newMode != null) {
                                // The user switched modes mid-task: permission checks for subsequent batches this turn use the new mode immediately.
                                // Same semantics as SessionUseCase.updateMode: entering PLAN records the prior mode; all other cases clear it.
                                val prevMode = currentContext.mode
                                currentContext = if (newMode == AgentMode.PLAN) {
                                    currentContext.copy(
                                        mode = AgentMode.PLAN,
                                        modeBeforePlan = if (prevMode != AgentMode.PLAN) prevMode else currentContext.modeBeforePlan
                                    )
                                } else {
                                    currentContext.copy(mode = newMode, modeBeforePlan = null)
                                }
                            }
                            val last = batchResults.last()
                            var injected = eventInjector.inject(last.result, notifications)
                            if (newMode != null) {
                                // The mode-constraint notice is persisted with the tool result and stays in history for subsequent turns.
                                injected += buildExternalModeSwitchNotice(newMode)
                            }
                            batchResults[batchResults.lastIndex] = last.copy(result = injected)
                        }

                        // Emit completion events one by one (keeping the same order as batchToolCalls), then wrap up.
                        batchResults.forEach { br ->
                            val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                            send(AgentEvent.ToolCallFinished(br.id, br.toolName, br.result, br.isError,
                                attachments = br.attachments, persisted = persisted))
                            persisted.await()
                        }
                        if (notifySessionId != null && notifications.isNotEmpty()) {
                            agentNotificationCenter.ack(notifySessionId, notifications.map { it.seq })
                        }
                        actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                    }
                }
            }
        }
        
        state.error?.let { send(AgentEvent.Failed(it, state.errorCode)) }
        send(AgentEvent.Completed)
    }

    private fun isContextOverflow(error: Throwable): Boolean {
        if (error is StreamApiException && error.code == "context_window_exceeded") return true
        val text = error.message.orEmpty().lowercase()
        return listOf("context_length_exceeded", "context_window_exceeded", "maximum context length",
            "prompt is too long", "input token limit").any { it in text }
    }

    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /**
     * Handles images in messages according to the model's vision capability before sending:
     * - Vision supported: returned as-is.
     * - Not supported: strip all images (affects only this send, never persisted data), so images in history/input are not sent as-is to
     *   non-multimodal models and failing the request; after switching back to a multimodal model, the image context remains usable.
     */
    private fun sanitizeImagesForModel(
        messages: List<AgentMessage>,
        supportsVision: Boolean
    ): List<AgentMessage> {
        if (supportsVision) return messages
        return messages.map { msg ->
            when (msg) {
                is AgentMessage.UserMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(images = emptyList(), content = msg.content.ifBlank { "(Images omitted: the current model does not support image input)" })
                is AgentMessage.ToolResultMessage ->
                    if (msg.images.isEmpty()) msg else msg.copy(images = emptyList())
                is AgentMessage.AssistantMessage -> msg
            }
        }
    }

    /**
     * Injects a "relevant skills" reminder every turn (modeled on Claude Code's "Skills relevant to your task:"):
     * the skill list in the system prompt is injected once per session, and the model forgets it after a few tool rounds; before this turn's request goes out,
     * push the few most relevant skills with their descriptions in front of it again based on the latest user message, raising the `loadSkill` call rate.
     *
     * Appended only to the end of this turn's latest user message, never persisted: the system prompt and history stay byte-for-byte identical,
     * so provider prefix caches still hit (KV Cache friendly).
     * Main agent only: subagents' tool allowlists may exclude loadSkill, and their prompts may not include the skill list.
     * The first skill scan already happened and was cached during system prompt construction, so this is normally pure in-memory work;
     * switch to the IO dispatcher defensively, to keep blocking IO on the on-device ROOT channel off the main thread.
     */
    private suspend fun withSkillHint(
        messages: List<AgentMessage>,
        ctx: AgentContext,
        tools: List<AgentTool>
    ): List<AgentMessage> {
        if (ctx.agentDefinition != null) return messages
        if (tools.none { it.name == "loadSkill" }) return messages
        val lastUserIndex = messages.indexOfLast { it is AgentMessage.UserMessage }
        if (lastUserIndex < 0) return messages
        val lastUser = messages[lastUserIndex] as AgentMessage.UserMessage
        if (lastUser.content.isBlank()) return messages
        val hint = withContext(Dispatchers.IO) {
            runCatching { promptProvider.buildSkillHint(ctx, lastUser.content) }.getOrNull()
        } ?: return messages
        return messages.toMutableList().apply {
            this[lastUserIndex] = lastUser.copy(content = lastUser.content.trimEnd() + "\n\n" + hint)
        }
    }

    /**
     * Provider resolution for compaction rounds. If the user configured a dedicated compaction model and the provider exists, is enabled, and has an apiKey,
     * returns a brand-new standalone AIProvider instance; otherwise returns null (keep the current chat model).
     */
    private suspend fun resolveCompactionFallbackProvider(sessionId: String? = null): AIProvider? {
        val providerId = compactionModelSettingsRepository.getCompactionProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = compactionModelSettingsRepository.getCompactionModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * Provider resolution for title generation. If the user configured a dedicated title-summary model and the provider exists, is enabled, and has an apiKey,
     * returns a brand-new standalone AIProvider instance; otherwise returns null (keep the current chat model).
     */
    private suspend fun resolveTitleFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = titleModelSettingsRepository.getTitleProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = titleModelSettingsRepository.getTitleModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * Provider resolution for the AI command reviewer. If the user configured a dedicated reviewer model and the provider exists, is enabled, and has an apiKey,
     * returns a brand-new standalone AIProvider instance; otherwise returns null (keep the current chat model).
     */
    private suspend fun resolveReviewerFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = reviewerModelSettingsRepository.getReviewerProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = reviewerModelSettingsRepository.getReviewerModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /** AI command review outcome: [Allow] passes directly; [Escalate] falls back to a manual dialog (with the reviewer's risk note, possibly empty). */
    private sealed interface AiReviewResult {
        data object Allow : AiReviewResult
        data class Escalate(val reason: String?) : AiReviewResult
    }

    /**
     * Independent command review under the "autonomous execution" tier: follows the current chat model by default, using the dedicated reviewer model if configured.
     * The prompt comes from `agent/command-reviewer.md` via [SystemPromptProvider].
     * Call failure / parse failure / UNSURE all return [AiReviewResult.Escalate] to fall back to a human (safety fallback).
     */
    private suspend fun reviewCommandWithAi(command: String, sessionId: String?): AiReviewResult {
        return try {
            val provider = resolveReviewerFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
            val prompt = promptProvider.resolvePrompt(COMMAND_REVIEWER_FILE)
                .replace(LEADING_COMMENT, "") + reviewerLanguageInstruction()
            val callStartWall = System.currentTimeMillis()
            val callStartElapsed = SystemClock.elapsedRealtime()
            var callCompleted = false
            var callError: String? = null
            var usage: AIResponse? = null
            val response = try {
                val resp = provider.complete(
                    systemPrompt = prompt,
                    messages = listOf(AgentMessage.UserMessage(content = "Command under review:\n```sh\n$command\n```")),
                    tools = emptyList()
                )
                usage = resp
                callCompleted = true
                resp
            } catch (e: CancellationException) {
                callError = "cancelled"
                throw e
            } catch (e: Exception) {
                callError = e.message ?: e.javaClass.simpleName
                throw e
            } finally {
                val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                // Same as executeEvents: no suspend calls during cancellation unwinding; dispatch to the non-cancellable scope for persistence.
                val record = LlmCallRecordEntity(
                    sessionId = sessionId,
                    providerId = provider.providerId.ifBlank { null },
                    model = provider.model,
                    kind = "command_review",
                    inputTokens = usage?.inputTokens ?: 0,
                    outputTokens = usage?.outputTokens ?: 0,
                    cachedInputTokens = usage?.cachedInputTokens ?: 0,
                    cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                    ttfbMillis = null,
                    durationMillis = durationMillis,
                    status = if (callCompleted) "success" else "error",
                    errorMessage = callError,
                    stopReason = usage?.stopReason,
                    createdAt = callStartWall
                )
                recordScope.launch { runCatching { llmCallRecordDao.insert(record) } }
            }
            parseReviewVerdict(response.content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "AI command review failed; falling back to manual confirmation", e)
            AiReviewResult.Escalate(null)
        }
    }

    /**
     * Extra instruction on the reviewer's warning language: REASON is read by the human at the "deny/unsure" dialog,
     * so it must follow the app's UI language (Chinese for a Chinese UI), otherwise an English warning is useless to the user.
     * Only the natural-language REASON part is constrained; machine-parsed keywords like VERDICT/ALLOW/DENY/UNSURE stay fixed in English.
     */
    private suspend fun reviewerLanguageInstruction(): String {
        val tag = languageSettingsRepository.snapshot() ?: Locale.getDefault().toLanguageTag()
        val name = when (tag.substringBefore('-').lowercase()) {
            "zh" -> "Simplified Chinese"
            "en" -> "English"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "ru" -> "Russian"
            "fr" -> "French"
            "de" -> "German"
            "es" -> "Spanish"
            else -> null
        }
        val lang = name ?: "the language identified by BCP-47 tag \"$tag\""
        return "\n\n<language>\nThe human approver reads $lang. " +
            "Write the REASON line in $lang. " +
            "Keep the literal keywords \"VERDICT:\", \"ALLOW\", \"DENY\", \"UNSURE\", \"REASON:\" in English exactly as specified.\n</language>"
    }

    /** Parses the reviewer output: first line `VERDICT: ALLOW|DENY|UNSURE`; DENY/UNSURE may carry a `REASON:` line. */
    private fun parseReviewVerdict(content: String): AiReviewResult {        val lines = content.lines()
        val verdictLine = lines.firstOrNull { it.trim().startsWith("VERDICT:", ignoreCase = true) }
            ?: return AiReviewResult.Escalate(null)
        val verdict = verdictLine.substringAfter(':').trim().uppercase()
        val reason = lines.firstOrNull { it.trim().startsWith("REASON:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
        return when (verdict) {
            "ALLOW" -> AiReviewResult.Allow
            "DENY" -> AiReviewResult.Escalate(reason ?: "The AI reviewer judged this command as risky")
            else -> AiReviewResult.Escalate(reason)
        }
    }

    /**
     * Generates a title for a newly created session: follows the current chat model by default, using the dedicated title-summary model if configured.
     * The prompt comes from `agent/title-generator.md` via [SystemPromptProvider].
     * Returns null when generation fails or no title is produced (the caller keeps the temporary title).
     */
    override suspend fun generateTitle(sessionId: String, request: String): String? = runCatching {
        val provider = resolveTitleFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        val prompt = promptProvider.resolvePrompt(TITLE_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        val response = try {
            val resp = provider.complete(
                systemPrompt = prompt,
                messages = listOf(AgentMessage.UserMessage(content = request)),
                tools = emptyList()
            )
            usage = resp
            callCompleted = true
            resp
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            // Same as executeEvents: no suspend calls during cancellation unwinding; dispatch to the non-cancellable scope for persistence.
            val record = LlmCallRecordEntity(
                sessionId = sessionId,
                providerId = provider.providerId.ifBlank { null },
                model = provider.model,
                kind = "title",
                inputTokens = usage?.inputTokens ?: 0,
                outputTokens = usage?.outputTokens ?: 0,
                cachedInputTokens = usage?.cachedInputTokens ?: 0,
                cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                ttfbMillis = null,
                durationMillis = durationMillis,
                status = if (callCompleted) "success" else "error",
                errorMessage = callError,
                stopReason = usage?.stopReason,
                createdAt = callStartWall
            )
            recordScope.launch { runCatching { llmCallRecordDao.insert(record) } }
        }
        response.content.trim().take(TITLE_MAX_CHARS).ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "Failed to generate session title", e)
    }.getOrNull()

    override suspend fun generateCommitMessage(diff: String): String? = runCatching {
        if (diff.isBlank()) return@runCatching null
        val provider = getEffectiveProvider(sessionId = null)
        val prompt = promptProvider.resolvePrompt(COMMIT_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val truncatedDiff = diff.take(12000)
        val resp = provider.complete(
            systemPrompt = prompt,
            messages = listOf(AgentMessage.UserMessage(content = "git diff:\n```diff\n$truncatedDiff\n```")),
            tools = emptyList()
        )
        val line = resp.content.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?.removeSurrounding("`")
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
        line?.take(100)?.ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "Failed to generate commit message", e)
    }.getOrNull()

    /**
     * Persists model-emitted images (base64) to `~/.xencode/generated-images/`, returning images carrying container paths
     * and one-to-one UI attachments (attachments carry paths only, no base64, so persistence doesn't blow up DB rows).
     */
    private suspend fun persistModelImages(images: List<AgentImage>): Pair<List<AgentImage>, List<AgentAttachment>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val persisted = mutableListOf<AgentImage>()
            val attachments = mutableListOf<AgentAttachment>()
            images.forEach { image ->
                runCatching {
                    val estimatedBytes = image.base64Data.length.toLong() * 3 / 4
                    if (estimatedBytes > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                    if (bytes.isEmpty() || bytes.size > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val unique = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                    val targetPath = "$GENERATED_IMAGE_DIR/gen_$unique.${extForMime(image.mimeType)}"
                    fileAccess.writeBytes(targetPath, bytes, overwrite = false)
                    val displayPath = fileAccess.toDisplayPath(targetPath)
                    val localFile = fileAccess.copyToLocal(targetPath)
                    persisted.add(image.copy(path = displayPath))
                    attachments.add(
                        AgentAttachment(
                            fileName = targetPath.substringAfterLast('/'),
                            containerPath = displayPath,
                            localPath = localFile.absolutePath,
                            mimeType = image.mimeType,
                            sizeBytes = bytes.size.toLong(),
                            isImage = true
                        )
                    )
                }
            }
            persisted to attachments
        }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun checkAndUpdateMode(toolCall: ToolCall, isError: Boolean, currentContext: AgentContext): Pair<AgentContext, Boolean> {
        if (toolCall.name == "planMode" && !isError) {
            val action = (toolCall.arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: toolCall.arguments["action"]?.toString()?.replace("\"", "")?.trim()?.lowercase()
            when (action) {
                // On entering PLAN, remember the current mode; on exit, restore it (after a BUILD/ASK→PLAN round trip, return to the original mode instead of BUILD).
                "enter" -> if (currentContext.mode != AgentMode.PLAN) {
                    return currentContext.copy(mode = AgentMode.PLAN, modeBeforePlan = currentContext.mode) to true
                }
                "exit" -> if (currentContext.mode == AgentMode.PLAN) {
                    return currentContext.copy(mode = currentContext.modeBeforePlan ?: AgentMode.BUILD) to true
                }
            }
        }
        return currentContext to false
    }

    /**
     * Current-mode reminder: injected with the latest user message (inspired by opencode's SessionReminders).
     * Mode prompts stay out of system — a switch would rebuild system and break the prefix cache;
     * hence message-level reminders: appended to the end of the latest user message on every request, sitting at the tail of the message stream so the prefix stays stable.
     */
    private fun buildModeReminder(mode: AgentMode): String? = when (mode) {
        AgentMode.PLAN -> promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "[Mode reminder] $it" }
        AgentMode.ASK -> promptProvider.resolvePrompt(MODE_REMINDER_ASK_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "[Mode reminder] $it" }
        AgentMode.BUILD -> null
    }

    /**
     * Injects pending notifications into a tool result: preferably as a top-level `notifications` field of the transport JSON, keeping the result valid JSON,
     * so the UI's formatToolResult (which reads only data/message) and other structured parsing are unaffected.
     * If raw has already been appended with plain text such as a mode-switch notice and is no longer valid JSON, degrade to a text append.
     */
    /**
     * Takes the mode reminder to inject this time: returns and records the text only when the mode differs from the last injection (including the first), otherwise null.
     */
    private fun takeModeReminderIfChanged(sessionId: String?, mode: AgentMode): String? {
        if (sessionId == null) return buildModeReminder(mode)
        if (lastInjectedMode[sessionId] == mode) return null
        lastInjectedMode[sessionId] = mode
        return buildModeReminder(mode)
    }

    /** Mode-status notice appended to the planMode tool result after a successful call (visible this very turn, no need to wait for the next user message). */
    private fun buildModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n[Mode change] The plan was approved. You are now in BUILD mode and may start implementing it."
        AgentMode.ASK -> "\n\n[Mode change] The plan was approved. You are back in ASK mode: stay read-only, answer follow-up questions, and do not make changes."
    }

    /** Notice appended to a tool result when the user manually switches modes externally (in the UI); distinguished from the AI-initiated [buildModeSwitchNotice] to avoid falsely saying "the plan was approved". */
    private fun buildExternalModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n[Mode change] The user switched to BUILD mode; write operations are allowed."
        AgentMode.ASK -> "\n\n[Mode change] The user switched to ASK mode: stay read-only, answer and explore, and do not make changes."
    }

    private suspend fun requestPermissionIfNeeded(
        tool: AgentTool?,
        callId: String,
        arguments: Map<String, JsonElement>,
        argsPreview: String,
        mode: AgentMode,
        sessionId: String?
    ): PermissionCheckResult {
        if (tool == null) {
            return PermissionCheckResult(true)
        }

        // When planMode exits PLAN, the plan-review panel later captures the user's decision,
        // so a permission dialog here is redundant — approve directly; entering PLAN has no follow-up review panel and still goes through the permission dialog.
        if (tool.name == "planMode" && mode == AgentMode.PLAN) {
            val action = (arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            if (action == "exit") {
                return PermissionCheckResult(true)
            }
        }

        val eval = policyEngine.evaluate(tool, tool.name, arguments, mode)
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "This tool is blocked by the project safety policy"
            val code = if (mode == AgentMode.PLAN) "PLAN_MODE_REJECTED" else "SYSTEM_DENIED"
            return PermissionCheckResult(false, reason, code)
        }

        if (tool.permissionPolicy == ToolPermissionPolicy.AUTO_APPROVE) {
            return PermissionCheckResult(true)
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionCheckResult(true)
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionCheckResult(false)
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                // "Autonomous execution" tier: first have the AI command reviewer review it independently; pass and approve directly;
                // denial / unsure / failure falls back to a manual dialog (denial attaches the reviewer's risk note).
                var aiReviewNote: String? = null
                if (eval.aiReviewable) {
                    val command = ((arguments["command"] ?: arguments["input"]) as? JsonPrimitive)?.contentOrNull
                    if (command != null) {
                        when (val review = reviewCommandWithAi(command, sessionId)) {
                            is AiReviewResult.Allow -> return PermissionCheckResult(true)
                            is AiReviewResult.Escalate -> aiReviewNote = review.reason
                        }
                    }
                }
                val base = tool.buildPermissionRequest(callId, arguments, argsPreview)
                val request = base.copy(
                    title = eval.askTitle ?: base.title,
                    rememberablePatterns = eval.rememberablePatterns,
                    rememberDisabledReason = eval.rememberDisabledReason,
                    sessionId = sessionId.orEmpty(),
                    reviewNote = aiReviewNote
                )
                when (permissionManager.awaitApproval(request)) {
                    PermissionChoice.REJECT -> PermissionCheckResult(false, "The user declined this tool", "USER_REJECTED")
                    PermissionChoice.ONCE -> PermissionCheckResult(true)
                    PermissionChoice.ALWAYS -> {
                        if (eval.rememberablePatterns.isNotEmpty()) {
                            policyEngine.remember(tool.name, eval.rememberablePatterns, PermissionScope.PROJECT)
                        }
                        PermissionCheckResult(true)
                    }
                }
            }
        }
    }
}
