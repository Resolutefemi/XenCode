package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.toolSafetyDataStore by preferencesDataStore(name = "tool_safety_prefs")

/**
 * Command execution authorization tier (applies to the evaluation of shell-like tools in BUILD mode; the PLAN/ASK read-only sandbox is a physical block, unaffected by the tier).
 * - [AUTONOMOUS]: autonomous execution — the "AI command reviewer" independently reviews each command; approved commands run directly;
 *   rejections/uncertainty/failures all go to manual approval (rejections include the reviewer's hazard explanation).
 * - [RULE_BASED]: rule-based interception — commands hitting high-risk blocking rules prompt for manual confirmation, the rest execute automatically (default).
 * - [FULL_ACCESS]: full access — all commands execute directly, with only catastrophic-deletion protection retained.
 */
enum class CommandAuthLevel { AUTONOMOUS, RULE_BASED, FULL_ACCESS }

/**
 * Persists the "disable safety interception" toggle, off by default.
 *
 * When on, even catastrophic `rm` commands (deleting the root directory, critical system directories, the workspace root, etc.)
 * are no longer intercepted in any mode, all commands pass through, and `Shizuku` tools no longer prompt each time. The PLAN/ASK physical write sandbox
 * is unaffected (that is a mode-level hard denial, not an option of this toggle).
 */
@Singleton
class ToolSafetySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val DISABLED_KEY = booleanPreferencesKey("disable_safety_interception")
        val AUTH_LEVEL_KEY = stringPreferencesKey("command_auth_level")
    }

    /** Flow of the current toggle; falls back to false when unset (safety interception kept by default). */
    val disableSafetyInterceptionFlow: Flow<Boolean> =
        context.toolSafetyDataStore.data.map { it[DISABLED_KEY] ?: false }

    suspend fun setDisableSafetyInterception(disabled: Boolean) {
        context.toolSafetyDataStore.edit { it[DISABLED_KEY] = disabled }
    }

    /** Reads the current value once (used during permission evaluation). */
    suspend fun isSafetyInterceptionDisabled(): Boolean = disableSafetyInterceptionFlow.first()

    /** Flow of the current command authorization tier; falls back to [CommandAuthLevel.RULE_BASED] (rule-based interception) when unset. */
    val commandAuthLevelFlow: Flow<CommandAuthLevel> =
        context.toolSafetyDataStore.data.map { prefs ->
            prefs[AUTH_LEVEL_KEY]?.let { runCatching { CommandAuthLevel.valueOf(it) }.getOrNull() }
                ?: CommandAuthLevel.RULE_BASED
        }

    suspend fun setCommandAuthLevel(level: CommandAuthLevel) {
        context.toolSafetyDataStore.edit { it[AUTH_LEVEL_KEY] = level.name }
    }

    /** Reads the current tier once (used during permission evaluation). */
    suspend fun getCommandAuthLevel(): CommandAuthLevel = commandAuthLevelFlow.first()
}