package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.titleModelDataStore by preferencesDataStore(
    name = "title_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "dedicated title summarization model" selection (two strings: providerId + model).
 *
 * Title generation for new sessions follows the current chat model by default; once the user designates a dedicated model here,
 * title generation temporarily switches to that model to send the request, then restores. A blank providerId (not configured) means "follow the current chat model".
 */
@Singleton
class TitleModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.titleModelDataStore, "title_provider_id", "title_model"
) {

    /** Writes the dedicated title summarization model (setting an empty string is equivalent to [clear]). */
    suspend fun setTitleModel(providerId: String, model: String) = setSelection(providerId, model)

    /** Clears the configuration — falls back to "follow the current chat model". */
    suspend fun clear() = clearSelection()

    /** Reads the current dedicated title summarization providerId once (for cold reads). */
    suspend fun getTitleProviderId(): String = readProviderId()

    /** Reads the current dedicated title summarization model once (for cold reads). */
    suspend fun getTitleModel(): String = readModel()
}
