package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.generalDataStore by preferencesDataStore(name = "general_prefs")

/**
 * Display position of the "task todo" area on the chat page.
 *
 * Todos are maintained by the AI via TodoTool; in the UI it is a collapsible lightweight bar: by default it shows only
 * "x/y done" and the current in-progress task; tap to expand the full list. This enum decides where it is attached on the chat page.
 */
enum class TodoDisplayPosition {
    /** Below the title bar: right under the floating top title bar, taking one line of height before the message flow begins. */
    BELOW_TITLE_BAR,

    /** Above the input bar: right above the bottom input area (pre-existing behavior before the redesign, kept as default). */
    ABOVE_INPUT_BAR,

    /** Off: the todo area is not rendered on the chat page (the AI can still maintain it within the session; it is just not displayed). */
    OFF
}

/** Which session to enter on app startup (including workspace switches). */
enum class StartupSessionMode {
    /** New session: reuses an empty session in the current workspace that has not yet received messages, otherwise creates one. */
    NEW_SESSION,

    /** Recent session: goes straight to the most recently updated session; creates one only if the workspace has no sessions yet. */
    RECENT_SESSION
}

/**
 * User preferences from the "Preferences" settings.
 *
 * Currently eight items: whether to automatically remove local models that no longer exist remotely after a successful model fetch (enabled by default),
 * whether startup enters a new or the most recent session (new session by default), first-byte / inter-chunk idle timeouts (seconds),
 * max network request retries (default 6), enter-to-send, auto-compaction threshold,
 * sendFile per-file size cap (default 100MB),
 * and whether removing an external local workspace also deletes its chat history (disabled by default).
 * DataStore usage matches [KeepaliveSettingsRepository].
 */
@Singleton
class GeneralSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val AUTO_REMOVE_STALE_MODELS_KEY = booleanPreferencesKey("auto_remove_stale_models")
        val STARTUP_SESSION_MODE_KEY = stringPreferencesKey("startup_session_mode")
        val FIRST_BYTE_TIMEOUT_SEC_KEY = intPreferencesKey("first_byte_timeout_sec")
        val STREAM_IDLE_TIMEOUT_SEC_KEY = intPreferencesKey("stream_idle_timeout_sec")
        val MAX_NETWORK_RETRIES_KEY = intPreferencesKey("max_network_retries")
        val ENTER_TO_SEND_KEY = booleanPreferencesKey("enter_to_send")
        val COMPACTION_THRESHOLD_PERCENT_KEY = intPreferencesKey("compaction_threshold_percent")
        val SENDFILE_MAX_SIZE_MB_KEY = intPreferencesKey("sendfile_max_size_mb")
        val DELETE_EXTERNAL_WORKSPACE_SESSIONS_KEY = booleanPreferencesKey("delete_external_workspace_sessions")
        val TODO_DISPLAY_POSITION_KEY = stringPreferencesKey("todo_display_position")
        /** Hash of the usage-notice announcement content already shown on the "Prompts" page; shown again when unset or mismatching the current content hash. */
        val PROMPTS_ANNOUNCEMENT_SHOWN_HASH_KEY = stringPreferencesKey("prompts_announcement_shown_hash")

        /** First-byte timeout defaults to 5 minutes, same as the original hardcoded value. */
        const val DEFAULT_FIRST_BYTE_TIMEOUT_SEC = 300

        /** Network retries default to 6, same as the original hardcoded value. */
        const val DEFAULT_MAX_NETWORK_RETRIES = 6

        /** Auto-compaction threshold defaults to 90%, same as the original hardcoded value. */
        const val DEFAULT_COMPACTION_THRESHOLD_PERCENT = 90

        /** sendFile per-file size cap defaults to 100MB, same as the original hardcoded value. */
        const val DEFAULT_SENDFILE_MAX_SIZE_MB = 100
    }

    /** Flow of the toggle for auto-aligning the local list after fetching models; falls back to true when unset (enabled by default). */
    val autoRemoveStaleModelsFlow: Flow<Boolean> =
        context.generalDataStore.data.map { it[AUTO_REMOVE_STALE_MODELS_KEY] ?: true }

    suspend fun setAutoRemoveStaleModels(enabled: Boolean) {
        context.generalDataStore.edit { it[AUTO_REMOVE_STALE_MODELS_KEY] = enabled }
    }

    /** Flow of the startup session preference; falls back to [StartupSessionMode.NEW_SESSION] when unset or unrecognized. */
    val startupSessionModeFlow: Flow<StartupSessionMode> = context.generalDataStore.data.map { prefs ->
        when (prefs[STARTUP_SESSION_MODE_KEY]) {
            StartupSessionMode.RECENT_SESSION.name -> StartupSessionMode.RECENT_SESSION
            else -> StartupSessionMode.NEW_SESSION
        }
    }

    suspend fun setStartupSessionMode(mode: StartupSessionMode) {
        context.generalDataStore.edit { it[STARTUP_SESSION_MODE_KEY] = mode.name }
    }

    /** Reads the preference once before switching to a workspace, avoiding an extra collection flow on the session initialization path. */
    suspend fun startupSessionMode(): StartupSessionMode = startupSessionModeFlow.first()

    /** Backup snapshot: current auto-align toggle. */
    suspend fun autoRemoveStaleModelsSnapshot(): Boolean = autoRemoveStaleModelsFlow.first()

    /** Backup snapshot: current startup session preference name. */
    suspend fun startupSessionModeSnapshot(): String = startupSessionModeFlow.first().name

    /** Restores the auto-align toggle from backup. */
    suspend fun restoreAutoRemoveStaleModels(enabled: Boolean) = setAutoRemoveStaleModels(enabled)

    /** Restores the startup session preference from backup; falls back to new session when an old backup lacks this field (null) or the value is unrecognized. */
    suspend fun restoreStartupSessionMode(mode: String?) {
        setStartupSessionMode(
            StartupSessionMode.entries.firstOrNull { it.name == mode } ?: StartupSessionMode.NEW_SESSION
        )
    }

    /** First-byte timeout (seconds); 0 means unlimited, falls back to 300 seconds when unset. */
    val firstByteTimeoutSecFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[FIRST_BYTE_TIMEOUT_SEC_KEY] ?: DEFAULT_FIRST_BYTE_TIMEOUT_SEC).coerceAtLeast(0)
    }

    /** Idle timeout between adjacent stream chunks (seconds); 0 (default) means unlimited. */
    val streamIdleTimeoutSecFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[STREAM_IDLE_TIMEOUT_SEC_KEY] ?: 0).coerceAtLeast(0)
    }

    suspend fun setFirstByteTimeoutSec(sec: Int) {
        context.generalDataStore.edit { it[FIRST_BYTE_TIMEOUT_SEC_KEY] = sec.coerceAtLeast(0) }
    }

    suspend fun setStreamIdleTimeoutSec(sec: Int) {
        context.generalDataStore.edit { it[STREAM_IDLE_TIMEOUT_SEC_KEY] = sec.coerceAtLeast(0) }
    }

    /** Reads the first-byte timeout once (milliseconds) before assembling the provider; 0 means unlimited. */
    suspend fun firstByteTimeoutMs(): Long = firstByteTimeoutSecFlow.first() * 1000L

    /** Reads the inter-chunk idle timeout once (milliseconds) before assembling the provider; 0 means unlimited. */
    suspend fun streamIdleTimeoutMs(): Long = streamIdleTimeoutSecFlow.first() * 1000L

    /** Backup snapshot: both timeout settings (seconds). */
    suspend fun firstByteTimeoutSecSnapshot(): Int = firstByteTimeoutSecFlow.first()

    suspend fun streamIdleTimeoutSecSnapshot(): Int = streamIdleTimeoutSecFlow.first()

    suspend fun restoreFirstByteTimeoutSec(sec: Int) = setFirstByteTimeoutSec(sec)

    suspend fun restoreStreamIdleTimeoutSec(sec: Int) = setStreamIdleTimeoutSec(sec)

    /** Max network request retries (excluding the first attempt); 0 means no retry, falls back to 6 when unset. */
    val maxNetworkRetriesFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[MAX_NETWORK_RETRIES_KEY] ?: DEFAULT_MAX_NETWORK_RETRIES).coerceAtLeast(0)
    }

    suspend fun setMaxNetworkRetries(count: Int) {
        context.generalDataStore.edit { it[MAX_NETWORK_RETRIES_KEY] = count.coerceAtLeast(0) }
    }

    /** Reads the max retry count once before assembling the provider. */
    suspend fun maxNetworkRetries(): Int = maxNetworkRetriesFlow.first()

    /** Backup snapshot: max retry count. */
    suspend fun maxNetworkRetriesSnapshot(): Int = maxNetworkRetriesFlow.first()

    suspend fun restoreMaxNetworkRetries(count: Int) = setMaxNetworkRetries(count)

    /** Whether the Enter key sends the message directly; disabled by default (Enter inserts a newline; the send button sends). */
    val enterToSendFlow: Flow<Boolean> =
        context.generalDataStore.data.map { it[ENTER_TO_SEND_KEY] ?: false }

    suspend fun setEnterToSend(enabled: Boolean) {
        context.generalDataStore.edit { it[ENTER_TO_SEND_KEY] = enabled }
    }

    /** Auto-compaction trigger threshold (percentage of the context window); defaults to 90, clamped to 1..100. */
    val compactionThresholdPercentFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[COMPACTION_THRESHOLD_PERCENT_KEY] ?: DEFAULT_COMPACTION_THRESHOLD_PERCENT).coerceIn(1, 100)
    }

    suspend fun setCompactionThresholdPercent(percent: Int) {
        context.generalDataStore.edit { it[COMPACTION_THRESHOLD_PERCENT_KEY] = percent.coerceIn(1, 100) }
    }

    /** Reads the trigger threshold percentage once before compaction. */
    suspend fun compactionThresholdPercent(): Int = compactionThresholdPercentFlow.first()

    /** Backup snapshot: enter-to-send toggle and compaction threshold. */
    suspend fun enterToSendSnapshot(): Boolean = enterToSendFlow.first()

    suspend fun compactionThresholdPercentSnapshot(): Int = compactionThresholdPercentFlow.first()

    suspend fun restoreEnterToSend(enabled: Boolean) = setEnterToSend(enabled)

    suspend fun restoreCompactionThresholdPercent(percent: Int) = setCompactionThresholdPercent(percent)

    /** sendFile per-file size cap (MB); defaults to 100, minimum 1, no upper limit. */
    val sendFileMaxSizeMbFlow: Flow<Int> = context.generalDataStore.data.map {
        (it[SENDFILE_MAX_SIZE_MB_KEY] ?: DEFAULT_SENDFILE_MAX_SIZE_MB).coerceAtLeast(1)
    }

    suspend fun setSendFileMaxSizeMb(mb: Int) {
        context.generalDataStore.edit { it[SENDFILE_MAX_SIZE_MB_KEY] = mb.coerceAtLeast(1) }
    }

    /** Reads the per-file size cap (MB) once before executing sendFile. */
    suspend fun sendFileMaxSizeMb(): Int = sendFileMaxSizeMbFlow.first()

    /** Backup snapshot: sendFile per-file size cap. */
    suspend fun sendFileMaxSizeMbSnapshot(): Int = sendFileMaxSizeMbFlow.first()

    suspend fun restoreSendFileMaxSizeMb(mb: Int) = setSendFileMaxSizeMb(mb)

    /** Whether removing an external local workspace also deletes its chat history; disabled by default (only unlinks, keeping the chat history). */
    val deleteExternalWorkspaceSessionsFlow: Flow<Boolean> =
        context.generalDataStore.data.map { it[DELETE_EXTERNAL_WORKSPACE_SESSIONS_KEY] ?: false }

    suspend fun setDeleteExternalWorkspaceSessions(enabled: Boolean) {
        context.generalDataStore.edit { it[DELETE_EXTERNAL_WORKSPACE_SESSIONS_KEY] = enabled }
    }

    /** Reads this preference once before removing an external workspace. */
    suspend fun deleteExternalWorkspaceSessions(): Boolean = deleteExternalWorkspaceSessionsFlow.first()

    /**
     * Task todo display position; falls back to [TodoDisplayPosition.ABOVE_INPUT_BAR] when unset or unrecognized,
     * i.e. the pre-redesign position (above the input bar), keeping behavior unchanged for existing users after upgrade.
     */
    val todoDisplayPositionFlow: Flow<TodoDisplayPosition> = context.generalDataStore.data.map { prefs ->
        TodoDisplayPosition.entries.firstOrNull { it.name == prefs[TODO_DISPLAY_POSITION_KEY] }
            ?: TodoDisplayPosition.ABOVE_INPUT_BAR
    }

    suspend fun setTodoDisplayPosition(position: TodoDisplayPosition) {
        context.generalDataStore.edit { it[TODO_DISPLAY_POSITION_KEY] = position.name }
    }

    /** Backup snapshot: task todo display position name. */
    suspend fun todoDisplayPositionSnapshot(): String = todoDisplayPositionFlow.first().name

    /** Restores the task todo display position from backup; falls back to above the input bar when an old backup lacks this field (null) or the value is unrecognized. */
    suspend fun restoreTodoDisplayPosition(position: String?) {
        setTodoDisplayPosition(
            TodoDisplayPosition.entries.firstOrNull { it.name == position }
                ?: TodoDisplayPosition.ABOVE_INPUT_BAR
        )
    }

    /** Hash of the announcement content already shown on the "Prompts" page; unset means never shown. */
    val promptsAnnouncementShownHashFlow: Flow<String?> = context.generalDataStore.data.map { prefs ->
        prefs[PROMPTS_ANNOUNCEMENT_SHOWN_HASH_KEY]
    }

    suspend fun markPromptsAnnouncementShown(hash: String) {
        context.generalDataStore.edit { it[PROMPTS_ANNOUNCEMENT_SHOWN_HASH_KEY] = hash }
    }

    /** Backup snapshot: preference for handling external workspace chat history. */
    suspend fun deleteExternalWorkspaceSessionsSnapshot(): Boolean = deleteExternalWorkspaceSessionsFlow.first()

    suspend fun restoreDeleteExternalWorkspaceSessions(enabled: Boolean) = setDeleteExternalWorkspaceSessions(enabled)
}