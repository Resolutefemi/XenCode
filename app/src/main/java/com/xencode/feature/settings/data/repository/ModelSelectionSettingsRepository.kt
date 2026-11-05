package com.xencode.feature.settings.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Shared persistence implementation for "dedicated model selection": two strings, providerId + model, stored in a standalone DataStore.
 *
 * The three use cases (vision / compaction / title summarization) have identical structure, differing only in DataStore file and keys, hence this base class;
 * subclasses each declare their own `preferencesDataStore(name=...)` delegate and pass in key names, with unified method semantics:
 * a blank providerId (not configured) means "follow the current chat model".
 */
abstract class ModelSelectionSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    providerIdKeyName: String,
    modelKeyName: String
) {
    private val providerIdKey = stringPreferencesKey(providerIdKeyName)
    private val modelKey = stringPreferencesKey(modelKeyName)

    /** Flow of the persisted dedicated providerId; empty string when unset (= follow the chat model). */
    val providerIdFlow: Flow<String> = dataStore.data.map { it[providerIdKey] ?: "" }

    /** Flow of the persisted dedicated model; empty string when unset. */
    val modelFlow: Flow<String> = dataStore.data.map { it[modelKey] ?: "" }

    /** Writes the dedicated model (setting an empty string is equivalent to [clearSelection]). */
    protected suspend fun setSelection(providerId: String, model: String) {
        dataStore.edit {
            it[providerIdKey] = providerId
            it[modelKey] = model
        }
    }

    /** Clears the configuration — falls back to "follow the current chat model". */
    protected suspend fun clearSelection() {
        dataStore.edit {
            it.remove(providerIdKey)
            it.remove(modelKey)
        }
    }

    /** Reads the current dedicated providerId once (for cold reads). */
    protected suspend fun readProviderId(): String = providerIdFlow.first()

    /** Reads the current dedicated model once (for cold reads). */
    protected suspend fun readModel(): String = modelFlow.first()
}
