package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reviewerModelDataStore by preferencesDataStore(
    name = "reviewer_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * Persists the "AI command reviewer dedicated model" selection (two strings: providerId + model).
 *
 * Under the "autonomous execution" tier, each command pending authorization is first reviewed independently by the reviewer model; with no dedicated model configured (blank providerId)
 * it follows the current chat model. DataStore usage matches TitleModelSettingsRepository.
 */
@Singleton
class ReviewerModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.reviewerModelDataStore, "reviewer_provider_id", "reviewer_model"
) {

    /** Writes the dedicated reviewer model (setting an empty string is equivalent to [clear]). */
    suspend fun setReviewerModel(providerId: String, model: String) = setSelection(providerId, model)

    /** Clears the configuration — falls back to "follow the current chat model". */
    suspend fun clear() = clearSelection()

    /** Reads the current dedicated reviewer providerId once (for cold reads). */
    suspend fun getReviewerProviderId(): String = readProviderId()

    /** Reads the current dedicated reviewer model once (for cold reads). */
    suspend fun getReviewerModel(): String = readModel()
}
