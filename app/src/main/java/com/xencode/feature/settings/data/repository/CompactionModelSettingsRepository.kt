package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.compactionModelDataStore by preferencesDataStore(
    name = "compaction_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "dedicated compaction model" selection (two strings: providerId + model).
 *
 * Context compaction follows the current chat model by default; once the user designates a dedicated model here,
 * the compaction turn temporarily switches to that model to send the summary request, then restores the chat model.
 * A blank providerId (not configured) means "follow the current chat model".
 */
@Singleton
class CompactionModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.compactionModelDataStore, "compaction_provider_id", "compaction_model"
) {

    /** Writes the dedicated compaction model (setting an empty string is equivalent to [clear]). */
    suspend fun setCompactionModel(providerId: String, model: String) = setSelection(providerId, model)

    /** Clears the configuration — falls back to "follow the current chat model". */
    suspend fun clear() = clearSelection()

    /** Reads the current dedicated compaction providerId once (for cold reads). */
    suspend fun getCompactionProviderId(): String = readProviderId()

    /** Reads the current dedicated compaction model once (for cold reads). */
    suspend fun getCompactionModel(): String = readModel()
}
