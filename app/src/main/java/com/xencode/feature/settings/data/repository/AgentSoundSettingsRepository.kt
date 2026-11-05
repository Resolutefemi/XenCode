package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.agentSoundDataStore by preferencesDataStore(
    name = "agent_sound_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "agent completion sound" toggle. Disabled by default — the user must enable it manually on the app permissions page.
 * When enabled, the system notification ringtone plays each time the agent finishes a turn while the app is not in the foreground. DataStore usage matches
 * [KeepaliveSettingsRepository].
 */
@Singleton
class AgentSoundSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("agent_sound_enabled")
    }

    /** Flow of the currently persisted toggle; falls back to false when unset (disabled by default). */
    val enabledFlow: Flow<Boolean> = context.agentSoundDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** Writes the toggle. */
    suspend fun setEnabled(enabled: Boolean) {
        context.agentSoundDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    /** Reads the current value once (used to decide whether to ring when the agent finishes). */
    suspend fun isEnabled(): Boolean = enabledFlow.first()

    /** Backup snapshot: whether the completion sound toggle is on. */
    suspend fun snapshot(): Boolean = enabledFlow.first()

    /** Restores the completion sound toggle from backup. */
    suspend fun restore(enabled: Boolean) = setEnabled(enabled)
}