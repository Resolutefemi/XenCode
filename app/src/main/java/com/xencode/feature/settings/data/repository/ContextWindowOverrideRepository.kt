package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.contextWindowOverrideDataStore by preferencesDataStore(
    name = "context_window_override_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persistence for the global unified context-window values.
 *
 * When enabled, each model's own context window (models.dev / built-in / custom) is ignored, and all models uniformly use the values
 * configured here to decide the "available input budget" and "output reserve", which in turn determines when auto-compaction triggers; disabled, each model falls back to its own values.
 *
 * Required-field convention: the total context window and the output window are required (guaranteed by the business layer) — missing either one makes the configuration incomplete,
 * and the upper layer auto-disables the toggle accordingly, avoiding the ambiguous state of "toggle on but no effective values". The input window is optional;
 * leaving it blank means input limit = total window - output reserve (computed by [com.xencode.feature.settings.domain.model.ModelContextPolicy]).
 */
@Singleton
class ContextWindowOverrideRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("override_enabled")
        val CONTEXT_TOKENS = intPreferencesKey("context_tokens")
        val INPUT_TOKENS = intPreferencesKey("input_tokens")
        val OUTPUT_TOKENS = intPreferencesKey("output_tokens")
    }

    /**
     * Flow of the current snapshot. The unified outlet for UI and policy layers:
     * when the toggle is off, [ContextWindowOverride.enabled] is false (values are still retained for repopulating on re-enable).
     */
    val overrideFlow: Flow<ContextWindowOverride> =
        context.contextWindowOverrideDataStore.data.map { prefs -> prefs.toOverride() }

    /** Reads the current snapshot once (for cold reads in the policy layer). */
    suspend fun getOverride(): ContextWindowOverride =
        context.contextWindowOverrideDataStore.data.first().toOverride()

    /**
     * Saves the configuration and decides whether it is actually enabled.
     *
     * @return The enabled state actually persisted: forced off when any required field (total context, output) is missing or non-positive.
     *   This way the caller (UI) can refresh the toggle state directly from the return value without re-validating itself.
     */
    suspend fun save(contextTokens: Int, inputTokens: Int?, outputTokens: Int?): Boolean {
        val valid = contextTokens > 0 && (outputTokens ?: 0) > 0
        context.contextWindowOverrideDataStore.edit { prefs ->
            prefs[Keys.CONTEXT_TOKENS] = contextTokens.coerceAtLeast(0)
            if (inputTokens != null && inputTokens > 0) {
                prefs[Keys.INPUT_TOKENS] = inputTokens
            } else {
                prefs.remove(Keys.INPUT_TOKENS)
            }
            if (outputTokens != null && outputTokens > 0) {
                prefs[Keys.OUTPUT_TOKENS] = outputTokens
            } else {
                prefs.remove(Keys.OUTPUT_TOKENS)
            }
            prefs[Keys.ENABLED] = valid
        }
        return valid
    }

    /** Turns off the toggle and returns to each model's own context window; filled-in values are retained for re-enabling. */
    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            // Self-check before re-enabling: only allow turning on when required fields are still complete
            val current = getOverride()
            if (current.contextTokens <= 0 || (current.outputTokens ?: 0) <= 0) return
        }
        context.contextWindowOverrideDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = enabled
        }
    }

    private fun Preferences.toOverride(): ContextWindowOverride {
        val enabled = this[Keys.ENABLED] ?: false
        val contextTokens = this[Keys.CONTEXT_TOKENS] ?: 0
        val inputTokens = this[Keys.INPUT_TOKENS]?.takeIf { it > 0 }
        val outputTokens = this[Keys.OUTPUT_TOKENS]?.takeIf { it > 0 }
        // Storage-layer fallback: with required fields missing, even an ENABLED value of true does not take effect, avoiding an "on but valueless" state.
        val effective = enabled && contextTokens > 0 && (outputTokens ?: 0) > 0
        return ContextWindowOverride(effective, contextTokens, inputTokens, outputTokens)
    }
}

/**
 * Snapshot of the unified context-window values.
 *
 * @param enabled Whether it takes effect (true only when required fields are satisfied).
 * @param contextTokens Total context window.
 * @param inputTokens Input limit; null means derived by the policy layer as "total window - output reserve".
 * @param outputTokens Output limit.
 */
data class ContextWindowOverride(
    val enabled: Boolean,
    val contextTokens: Int,
    val inputTokens: Int?,
    val outputTokens: Int?
)
