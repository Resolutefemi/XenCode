package com.xencode.feature.settings.data.repository

import android.content.Context
import com.xencode.feature.settings.data.remote.UpdateCheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** Update channel: stable (releases only) / latest (includes prereleases). */
enum class UpdateChannel {
    STABLE, LATEST
}

/**
 * Storage for "auto-check for updates" preferences: the toggle, update channel, and last-check date are all kept in SharedPreferences
 * (a key-value file, not a database). The last-check date is not written to the database, nor placed in cacheDir — the cache directory may be
 * cleaned by the system, breaking the "once a day" rule; persisting to SharedPreferences is more reliable.
 */
@Singleton
class UpdateCheckSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("update_check_prefs", Context.MODE_PRIVATE)

    /** Auto-check-for-updates toggle, on by default. */
    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** Update channel, stable by default. */
    var channel: UpdateChannel
        get() = prefs.getString(KEY_CHANNEL, null)
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.name).apply()
        }

    /** Whether already checked today (judged by the recorded date). */
    fun hasCheckedToday(): Boolean = prefs.getString(KEY_LAST_CHECKED, null) == today()

    /** Records that today has been checked. */
    fun markCheckedToday() {
        prefs.edit().putString(KEY_LAST_CHECKED, today()).apply()
    }

    /**
     * Writes version and update info to `~/.xencode/update-info.json` (host filesDir/xencode/),
     * for the in-container AI to read (current version, update channel, last check time, latest version, and per-version changelogs).
     * Silent on write failure; does not affect the check flow.
     */
    fun writeUpdateInfo(currentVersion: String, channel: UpdateChannel, result: UpdateCheckResult) {
        runCatching {
            val dir = File(context.filesDir, "xencode").apply { mkdirs() }
            val obj = JSONObject()
            obj.put("currentVersion", currentVersion)
            obj.put("channel", channel.name.lowercase())
            obj.put("lastCheckedAt", java.time.LocalDateTime.now().toString())
            when (result) {
                is UpdateCheckResult.UpToDate -> obj.put("hasUpdate", false)
                is UpdateCheckResult.NewVersion -> {
                    obj.put("hasUpdate", true)
                    obj.put("latestVersion", result.info.latestTag)
                    val updates = org.json.JSONArray()
                    result.info.updates.forEach { u ->
                        updates.put(
                            JSONObject().apply {
                                put("tag", u.tag)
                                put("changelog", u.changelog)
                            }
                        )
                    }
                    obj.put("updates", updates)
                }
                is UpdateCheckResult.Error -> {
                    obj.put("hasUpdate", false)
                    obj.put("error", result.message)
                }
            }
            File(dir, "update-info.json").writeText(obj.toString())
        }
    }

    private fun today(): String = java.time.LocalDate.now().toString()

    private companion object {
        const val KEY_ENABLED = "auto_check_enabled"
        const val KEY_CHANNEL = "update_channel"
        const val KEY_LAST_CHECKED = "last_checked_date"
    }
}
