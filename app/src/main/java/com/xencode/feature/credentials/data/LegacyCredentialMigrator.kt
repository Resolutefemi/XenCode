package com.xencode.feature.credentials.data

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.data.local.database.AgentDatabase
import com.xencode.feature.credentials.data.repository.FileCredentialRepository
import com.xencode.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot migration of the old Room `git_credentials` table into the credentials file, then drops the table.
 *
 * Background: the source of truth for credentials moved from Room to the `git-credentials` file (one entry per host). After the Room entity was removed from [AgentDatabase],
 * the old table lingered in the database file (Room doesn't auto-drop unmanaged tables); this class reads it at app startup → writes the file → DROPs it.
 * Skipped outright when the table doesn't exist or was already migrated (idempotent).
 */
@Singleton
class LegacyCredentialMigrator @Inject constructor(
    private val database: AgentDatabase,
    private val fileCredentialRepository: FileCredentialRepository
) {
    private companion object {
        const val TAG = "LegacyCredentialMigrator"
        const val TABLE = "git_credentials"
    }

    /** Reads the old table (default/first entry per host), writes the credentials file, then drops the table. Repeatable; idempotent skip when the table is absent/already dropped. */
    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val db = database.openHelper.writableDatabase
            // SupportSQLiteDatabase has no rawQuery; use query to run the raw SQL; the returned Cursor isn't AutoCloseable, so close it manually.
            var hasTable = false
            val tableCursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$TABLE'", emptyArray()
            )
            try {
                hasTable = tableCursor.moveToFirst()
            } finally {
                tableCursor.close()
            }
            if (!hasTable) return@withContext
            val rowsCursor = db.query(
                "SELECT host, username, token, isDefault FROM $TABLE ORDER BY isDefault DESC", emptyArray()
            )
            val rows = mutableListOf<GitCredential>()
            try {
                while (rowsCursor.moveToNext()) {
                    val host = rowsCursor.getString(0)?.lowercase() ?: continue
                    val username = rowsCursor.getString(1) ?: continue
                    val token = rowsCursor.getString(2) ?: continue
                    if (host.isBlank() || username.isBlank()) continue
                    // Take only the first row per host (default first, thanks to ORDER BY isDefault DESC)
                    if (rows.any { it.host == host }) continue
                    rows.add(GitCredential(id = host, host = host, username = username, token = token))
                }
            } finally {
                rowsCursor.close()
            }
            if (rows.isNotEmpty()) {
                rows.forEach { fileCredentialRepository.save(it) }
                FileLogger.i(TAG, "Migrated ${rows.size} git credentials from the old table to the file")
            }
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            FileLogger.i(TAG, "Dropped old table $TABLE")
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to migrate old git credentials (ignorable; the user re-enters them if the file is empty): ${e.message}")
        }
    }
}
