package com.xencode.feature.settings.data.repository

import android.content.Context
import com.xencode.R
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore by preferencesDataStore(
    name = "theme_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

enum class AppThemeMode(val labelRes: Int) {
    AUTO(R.string.theme_auto),
    DARK(R.string.theme_dark),
    LIGHT(R.string.theme_light);

    companion object {
        fun fromPersisted(value: String?): AppThemeMode? = entries.firstOrNull { it.name == value }
    }
}

/** Persists the app appearance theme. Follows the system by default, with legacy dark-mode toggle compatibility. */
@Singleton
class ThemeSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val DARK_THEME_KEY = booleanPreferencesKey("dark_theme_enabled")
        val THEME_PRESET_KEY = stringPreferencesKey("theme_preset_id")
        val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
    }

    val themeModeFlow: Flow<AppThemeMode> = context.themeDataStore.data.map { prefs ->
        AppThemeMode.fromPersisted(prefs[THEME_MODE_KEY])
            ?: prefs[DARK_THEME_KEY]?.let { if (it) AppThemeMode.DARK else AppThemeMode.LIGHT }
            ?: AppThemeMode.AUTO
    }

    /** Color theme id, matching `AppThemePreset.id`; null when unset, callers fall back to the default. */
    val themePresetIdFlow: Flow<String?> = context.themeDataStore.data.map { it[THEME_PRESET_KEY] }

    /** Whether system Material You (Monet) colors are enabled. When on, the color theme selection no longer applies. */
    val dynamicColorFlow: Flow<Boolean> =
        context.themeDataStore.data.map { it[DYNAMIC_COLOR_KEY] == true }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.themeDataStore.edit { it[THEME_MODE_KEY] = mode.name }
    }

    suspend fun setThemePresetId(id: String) {
        context.themeDataStore.edit { it[THEME_PRESET_KEY] = id }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.themeDataStore.edit { it[DYNAMIC_COLOR_KEY] = enabled }
    }

    /** Backup snapshot: returns the currently persisted theme mode name (null when unset; falls back to default on import). */
    suspend fun snapshot(): String? = themeModeFlow.first().name

    /** Restores the theme mode from backup; null clears the key and falls back to default. */
    suspend fun restore(value: String?) {
        context.themeDataStore.edit {
            if (value == null) it.remove(THEME_MODE_KEY) else it[THEME_MODE_KEY] = value
        }
    }

    /** Backup snapshot: current color theme id. */
    suspend fun presetSnapshot(): String? = themePresetIdFlow.first()

    /** Backup snapshot: Material You (Monet) color toggle. */
    suspend fun dynamicColorSnapshot(): Boolean = dynamicColorFlow.first()

    /** Restores the color theme and Monet toggle from backup; a null id clears the key and falls back to default. */
    suspend fun restoreColors(presetId: String?, dynamicColor: Boolean) {
        context.themeDataStore.edit {
            if (presetId == null) it.remove(THEME_PRESET_KEY) else it[THEME_PRESET_KEY] = presetId
            it[DYNAMIC_COLOR_KEY] = dynamicColor
        }
    }
}
