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

private val Context.screenOnDataStore by preferencesDataStore(
    name = "screen_on_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "keep screen on" toggle. Disabled by default.
 *
 * When enabled, [com.xencode.MainActivity] observes this toggle and adds
 * [android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON] to the window. The flag only constrains
 * the screen while the app is in the foreground; it is automatically voided by the system when backgrounded, so unlike a wakeLock it needs no manual release.
 * DataStore usage matches [KeepaliveSettingsRepository].
 */
@Singleton
class ScreenOnSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("screen_on_enabled")
    }

    /** Flow of the currently persisted toggle; falls back to false when unset (disabled by default, must be enabled manually). */
    val enabledFlow: Flow<Boolean> = context.screenOnDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** Writes the toggle. Adding/removing the window flag is handled uniformly by [com.xencode.MainActivity] observing this flow. */
    suspend fun setEnabled(enabled: Boolean) {
        context.screenOnDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    /** Backup snapshot: whether the keep-screen-on toggle is on. */
    suspend fun snapshot(): Boolean = enabledFlow.first()

    /** Restores the keep-screen-on toggle from backup. */
    suspend fun restore(enabled: Boolean) = setEnabled(enabled)
}
