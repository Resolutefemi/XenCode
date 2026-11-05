package com.xencode.feature.settings.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import com.xencode.core.util.LogLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.logDataStore by preferencesDataStore(
    name = "log_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "minimum log level". The level is stored and read as the enum name (string).
 *
 * The default level depends on the build type: debug builds (FLAG_DEBUGGABLE) use [LogLevel.VERBOSE] (full logging during development),
 * release builds use [LogLevel.INFO] (filtering debug noise in production). Once the user sets it manually, the user's setting always wins.
 *
 * The value is collected by `AIEditorApp` from [levelFlow] at startup and on settings changes and synced to [com.xencode.core.util.FileLogger].
 * DataStore usage matches `WorkspaceRepository` (constructor injection suffices, no DI module needed).
 */
@Singleton
class LogSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val LEVEL_KEY = stringPreferencesKey("log_min_level")

    /** Default level: full logging for debug, filtered debug noise for release. */
    private val defaultLevel: LogLevel =
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            LogLevel.VERBOSE
        } else {
            LogLevel.INFO
        }

    /** Flow of the currently persisted log level; falls back to the default level on parse failure or when unset. */
    val levelFlow: Flow<LogLevel> = context.logDataStore.data.map { prefs ->
        prefs[LEVEL_KEY]?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() } ?: defaultLevel
    }

    /** Writes a new log level. */
    suspend fun setLevel(level: LogLevel) {
        context.logDataStore.edit { it[LEVEL_KEY] = level.name }
    }

    /** Backup snapshot: current log level name. */
    suspend fun snapshot(): String = levelFlow.first().name

    /** Restores the log level from backup; ignored on parse failure. */
    suspend fun restore(value: String?) {
        val level = value?.let { runCatching { LogLevel.valueOf(it) }.getOrNull() } ?: return
        setLevel(level)
    }
}
