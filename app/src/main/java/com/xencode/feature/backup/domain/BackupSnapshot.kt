package com.xencode.feature.backup.domain

import com.xencode.feature.agent.domain.mcp.McpServerConfig
import com.xencode.feature.agent.domain.permission.PermissionRule
import com.xencode.feature.settings.data.repository.SyncSettingsSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Backup snapshot: the complete dataset of one export/import. The DTOs mirror Room entities but stay decoupled, keeping the serialization framework independent of Room.
 *
 * [schemaVersion] records the AgentDatabase version at export; import compatibility is judged from it:
 * backup version > current app version is rejected (fields may be missing); < current is allowed (new fields take defaults).
 */
@Serializable
data class BackupSnapshot(
    val schemaVersion: Int,
    val appVersion: String = "",
    val createdAt: Long,
    val providers: List<ProviderDto> = emptyList(),
    val remoteConnections: List<RemoteConnectionDto> = emptyList(),
    val remoteMounts: List<RemoteMountDto> = emptyList(),
    val chatSessions: List<ChatSessionDto> = emptyList(),
    val agentMessages: List<AgentMessageDto> = emptyList(),
    val todoItems: List<TodoItemDto> = emptyList(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    val globalPermissionRules: List<PermissionRule> = emptyList(),
    val themeMode: String? = null,
    val themePresetId: String? = null,
    val dynamicColorEnabled: Boolean = false,
    val keepaliveEnabled: Boolean = false,
    val screenOnEnabled: Boolean = false,
    val agentSoundEnabled: Boolean = false,
    val autoRemoveStaleModels: Boolean = true,
    val startupSessionMode: String? = null,
    val firstByteTimeoutSec: Int = 300,
    val streamIdleTimeoutSec: Int = 0,
    val maxNetworkRetries: Int = 6,
    val enterToSend: Boolean = false,
    val compactionThresholdPercent: Int = 90,
    val sendFileMaxSizeMb: Int = 100,
    val deleteExternalWorkspaceSessions: Boolean = false,
    /** Todo display position (the TodoDisplayPosition enum name); backups without this field fall back to "above the message bar" on import. */
    val todoDisplayPosition: String? = null,
    val logLevel: String? = null,
    val visionProviderId: String = "",
    val visionModel: String = "",
    val compactionProviderId: String = "",
    val compactionModel: String = "",
    val syncSettings: SyncSettingsSnapshot? = null
)

/**
 * Metadata shard of a streamed backup (metadata.json inside the tar): excludes the big chat tables, which go into the *.jsonl files.
 * Mirrors [BackupSnapshot]'s metadata fields one-to-one; import merges the jsonl files to restore the full data.
 */
@Serializable
data class BackupMetadata(
    val schemaVersion: Int,
    val appVersion: String = "",
    val createdAt: Long,
    val providers: List<ProviderDto> = emptyList(),
    val remoteConnections: List<RemoteConnectionDto> = emptyList(),
    val remoteMounts: List<RemoteMountDto> = emptyList(),
    val mcpServers: List<McpServerConfig> = emptyList(),
    val globalPermissionRules: List<PermissionRule> = emptyList(),
    val themeMode: String? = null,
    val themePresetId: String? = null,
    val dynamicColorEnabled: Boolean = false,
    val keepaliveEnabled: Boolean = false,
    val screenOnEnabled: Boolean = false,
    val agentSoundEnabled: Boolean = false,
    val autoRemoveStaleModels: Boolean = true,
    val startupSessionMode: String? = null,
    val firstByteTimeoutSec: Int = 300,
    val streamIdleTimeoutSec: Int = 0,
    val maxNetworkRetries: Int = 6,
    val enterToSend: Boolean = false,
    val compactionThresholdPercent: Int = 90,
    val sendFileMaxSizeMb: Int = 100,
    val deleteExternalWorkspaceSessions: Boolean = false,
    val todoDisplayPosition: String? = null,
    val logLevel: String? = null,
    val visionProviderId: String = "",
    val visionModel: String = "",
    val compactionProviderId: String = "",
    val compactionModel: String = "",
    val syncSettings: SyncSettingsSnapshot? = null,
    val workspaces: List<WorkspaceBackupMeta> = emptyList()
)

/** A workspace section in backup metadata: name + backed-up file count (for the import summary). */
@Serializable
data class WorkspaceBackupMeta(
    val name: String,
    val fileCount: Int
)

fun BackupSnapshot.toMetadata() = BackupMetadata(
    schemaVersion = schemaVersion,
    appVersion = appVersion,
    createdAt = createdAt,
    providers = providers,
    remoteConnections = remoteConnections,
    remoteMounts = remoteMounts,
    mcpServers = mcpServers,
    globalPermissionRules = globalPermissionRules,
    themeMode = themeMode,
    themePresetId = themePresetId,
    dynamicColorEnabled = dynamicColorEnabled,
    keepaliveEnabled = keepaliveEnabled,
    screenOnEnabled = screenOnEnabled,
    agentSoundEnabled = agentSoundEnabled,
    autoRemoveStaleModels = autoRemoveStaleModels,
    startupSessionMode = startupSessionMode,
    firstByteTimeoutSec = firstByteTimeoutSec,
    streamIdleTimeoutSec = streamIdleTimeoutSec,
    maxNetworkRetries = maxNetworkRetries,
    enterToSend = enterToSend,
    compactionThresholdPercent = compactionThresholdPercent,
    sendFileMaxSizeMb = sendFileMaxSizeMb,
    deleteExternalWorkspaceSessions = deleteExternalWorkspaceSessions,
    todoDisplayPosition = todoDisplayPosition,
    logLevel = logLevel,
    visionProviderId = visionProviderId,
    visionModel = visionModel,
    compactionProviderId = compactionProviderId,
    compactionModel = compactionModel,
    syncSettings = syncSettings
)

@Serializable
data class ProviderDto(
    val id: String,
    val name: String,
    val type: String,
    val apiKey: String,
    val baseUrl: String,
    val defaultModel: String,
    val models: String = "",
    val selectedModel: String = "",
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Provider-level cache toggles; null means the old backup lacks this field, import falls back to the default. */
    val anthropicCacheBreakpoints: Boolean? = null,
    val openaiChatCacheKey: Boolean? = null,
    /** Custom dashboard script path; null means the old backup lacks this field, import falls back to "". The serial name keeps the historical name for old-backup compatibility. */
    @SerialName("balanceScriptPath")
    val dashboardScriptPath: String? = null,
    /** Custom dashboard refresh interval (minutes); null means the old backup lacks this field, import falls back to 5. The serial name keeps the historical name for old-backup compatibility. */
    @SerialName("balanceRefreshInterval")
    val dashboardRefreshInterval: Int? = null,
    /** Provider list sort order; null means the old backup lacks this field, import falls back to 0. */
    val sortOrder: Int? = null,
    /** Multi-key mode fields; null means the old backup lacks this field, import falls back to defaults. */
    val multiKeyEnabled: Boolean? = null,
    val apiKeys: String? = null,
    val keyRotationStrategy: String? = null,
    val keyFailoverThreshold: Int? = null,
    val keyCooldownMinutes: Int? = null,
    /** Custom dashboard (DIY) script params (JSON-encoded); null means the old backup lacks this field, import falls back to empty. */
    val scriptParams: String? = null,
    /** Multi-key auto-switch status codes (comma-separated); null means the old backup lacks this field, import falls back to an empty string (the default code list). */
    val keySwitchStatusCodes: String? = null
)

@Serializable
data class RemoteConnectionDto(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String = "password",
    val authData: String,
    val passphrase: String? = null
)

@Serializable
data class RemoteMountDto(
    val id: String,
    val connectionId: String,
    val remotePath: String,
    val localMountPath: String,
    val isActive: Boolean = false,
    val autoConnect: Boolean = true
)

@Serializable
data class ChatSessionDto(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: String = "BUILD",
    val modeBeforePlan: String? = null,
    val reasoningEffort: String = "MEDIUM",
    val providerId: String? = null,
    val model: String? = null,
    val isPinned: Boolean = false,
    /** Parent session id of a sub-agent session; without it, restored sub-sessions turn into a pile of root sessions. */
    val parentId: String? = null,
    val subagentType: String? = null
)

@Serializable
data class AgentMessageDto(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val isError: Boolean = false,
    val reasoning: String? = null,
    val signature: String? = null,
    val attachmentsJson: String? = null,
    val isCompacted: Boolean = false,
    val isContextSummary: Boolean = false,
    val isCompactionMarker: Boolean = false,
    /** Verbatim snapshot of Anthropic thinking / redacted_thinking content blocks (JSON array text). */
    val thinkingBlocksJson: String? = null,
    val isContextExcluded: Boolean = false,
    val compactedBySummaryId: String? = null
)

@Serializable
data class TodoItemDto(
    val id: String,
    val sessionId: String,
    val subject: String,
    val description: String = "",
    val status: String = "PENDING",
    val priority: Int = 0,
    val order: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)
