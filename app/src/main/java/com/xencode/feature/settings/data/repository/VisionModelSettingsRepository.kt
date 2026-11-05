package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.visionModelDataStore by preferencesDataStore(
    name = "vision_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "vision model" selection (two strings: providerId + model).
 *
 * Vision (viewImage) follows the current chat model by default; once the user designates a dedicated model here, vision sessions use that model.
 * The model's vision capability is not validated; on call failure the error message is returned as-is as the tool result.
 * DataStore usage matches [KeepaliveSettingsRepository] / [LogSettingsRepository].
 */
@Singleton
class VisionModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.visionModelDataStore, "vision_provider_id", "vision_model"
) {

    /** Writes the dedicated vision model (setting an empty string is equivalent to [clear]). */
    suspend fun setVisionModel(providerId: String, model: String) = setSelection(providerId, model)

    /** Clears the configuration — falls back to "follow the current chat model". */
    suspend fun clear() = clearSelection()

    /** Reads the current dedicated vision providerId once (for cold reads). */
    suspend fun getVisionProviderId(): String = readProviderId()

    /** Reads the current dedicated vision model once (for cold reads). */
    suspend fun getVisionModel(): String = readModel()
}
