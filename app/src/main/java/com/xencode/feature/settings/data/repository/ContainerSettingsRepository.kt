package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import com.xencode.feature.agent.domain.container.ContainerProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.containerDataStore by preferencesDataStore(
    name = "container_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** Record of downloaded/installed images on the image download page, persisted across restarts. */
@Serializable
data class DownloadedImageRecord(
    val entryId: String,
    val fileUri: String,
    val installed: Boolean = false
)

/**
 * Persists the currently selected container profile and the user's custom profile list.
 *
 * DataStore usage matches [ThemeSettingsRepository] (constructor injection suffices, no DI module needed).
 * Without [ACTIVE_PROFILE_ID_KEY] it returns [ContainerProfile.BUILTIN_ID], same as before the change — built-in Alpine by default.
 */
@Singleton
class ContainerSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ACTIVE_PROFILE_ID_KEY = stringPreferencesKey("active_profile_id")
        val CUSTOM_PROFILES_KEY = stringPreferencesKey("custom_profiles_json")
        /** Container that runs local services such as MCP stdio in remote workspace mode; built-in Alpine by default when unset. */
        val DEFAULT_CONTAINER_ID_KEY = stringPreferencesKey("default_container_profile_id")
        /** Whether the built-in Alpine default entry has been written on first launch; once set, an emptied list is no longer auto-restored. */
        val INITIALIZED_KEY = booleanPreferencesKey("initialized")
        /** Hash of the usage-notice announcement content already shown on the "Containers & Images" page; shown again when unset or mismatching the current content hash. */
        val ANNOUNCEMENT_SHOWN_HASH_KEY = stringPreferencesKey("container_announcement_shown_hash")
        /** Downloaded/installed records on the image download page (JSON list). */
        val DOWNLOADED_IMAGES_KEY = stringPreferencesKey("downloaded_images_json")
        val downloadedImageSerializer = ListSerializer(DownloadedImageRecord.serializer())
        val profileSerializer = ListSerializer(ContainerProfile.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }

    /** Currently selected profile id; built-in Alpine by default when unset. */
    val activeProfileIdFlow: Flow<String> = context.containerDataStore.data.map { prefs ->
        prefs[ACTIVE_PROFILE_ID_KEY]?.takeIf { it.isNotBlank() } ?: ContainerProfile.BUILTIN_ID
    }

    /** Default container id in remote mode; built-in Alpine by default when unset. */
    val defaultContainerIdFlow: Flow<String> = context.containerDataStore.data.map { prefs ->
        prefs[DEFAULT_CONTAINER_ID_KEY]?.takeIf { it.isNotBlank() } ?: ContainerProfile.BUILTIN_ID
    }

    /** User's custom profile list (built-in excluded). Falls back to an empty list on parse failure. */
    val customProfilesFlow: Flow<List<ContainerProfile>> = context.containerDataStore.data.map { prefs ->
        prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
            runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun setActiveProfile(id: String) {
        context.containerDataStore.edit { it[ACTIVE_PROFILE_ID_KEY] = id }
    }

    suspend fun setDefaultContainerId(id: String) {
        context.containerDataStore.edit { it[DEFAULT_CONTAINER_ID_KEY] = id }
    }

    /**
     * First-launch (or upgrade from an old version) fallback: adds the built-in Alpine to the list if missing, guaranteeing the list always contains the built-in entry.
     * Once the initialization flag is set it is no longer auto-restored — if the user later empties the list, recovery is manual via the empty state.
     * Existing devices with custom images configured also get it added (the old version's list always contained the built-in entry, and upgrading must not make it disappear).
     */
    suspend fun ensureBuiltinDefault() {
        context.containerDataStore.edit { prefs ->
            if (prefs[INITIALIZED_KEY] == true) return@edit
            prefs[INITIALIZED_KEY] = true
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            if (current.none { it.id == ContainerProfile.BUILTIN_ID }) {
                prefs[CUSTOM_PROFILES_KEY] = json.encodeToString(
                    profileSerializer, listOf(ContainerProfile.BUILTIN_ALPINE) + current
                )
            }
        }
    }

    /** Hash of the announcement content already shown; unset means never shown. */
    val announcementShownHashFlow: Flow<String?> = context.containerDataStore.data.map { prefs ->
        prefs[ANNOUNCEMENT_SHOWN_HASH_KEY]
    }

    suspend fun markAnnouncementShown(hash: String) {
        context.containerDataStore.edit { it[ANNOUNCEMENT_SHOWN_HASH_KEY] = hash }
    }

    /** List of downloaded image records (deduplicated by entryId, newest overwriting older). */
    val downloadedImagesFlow: Flow<List<DownloadedImageRecord>> = context.containerDataStore.data.map { prefs ->
        prefs[DOWNLOADED_IMAGES_KEY]?.let { raw ->
            runCatching { json.decodeFromString(downloadedImageSerializer, raw) }.getOrNull()
        } ?: emptyList()
    }

    suspend fun upsertDownloadedImage(record: DownloadedImageRecord) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[DOWNLOADED_IMAGES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(downloadedImageSerializer, raw) }.getOrNull()
            } ?: emptyList()
            val merged = current.filterNot { it.entryId == record.entryId } + record
            prefs[DOWNLOADED_IMAGES_KEY] = json.encodeToString(downloadedImageSerializer, merged)
        }
    }

    suspend fun removeDownloadedImage(entryId: String) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[DOWNLOADED_IMAGES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(downloadedImageSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[DOWNLOADED_IMAGES_KEY] =
                json.encodeToString(downloadedImageSerializer, current.filterNot { it.entryId == entryId })
        }
    }

    /**
     * Adds or overwrites the custom profile with the same id.
     * createdAt rule: a non-zero passed-in value is used; when 0, if the original record exists (edit scenario where the rebuilt form lost the time) the original time is kept,
     * otherwise it is treated as new and the current time is written — so saving an edit does not change the sort position.
     */
    suspend fun upsertCustomProfile(profile: ContainerProfile) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            val existing = current.firstOrNull { it.id == profile.id }
            val final = when {
                profile.createdAt != 0L -> profile
                existing != null && existing.createdAt != 0L -> profile.copy(createdAt = existing.createdAt)
                else -> profile.copy(createdAt = System.currentTimeMillis())
            }
            val merged = (current.filterNot { it.id == final.id } + final)
            prefs[CUSTOM_PROFILES_KEY] = json.encodeToString(profileSerializer, merged)
        }
    }

    suspend fun deleteCustomProfile(id: String) {
        context.containerDataStore.edit { prefs ->
            val current = prefs[CUSTOM_PROFILES_KEY]?.let { raw ->
                runCatching { json.decodeFromString(profileSerializer, raw) }.getOrNull()
            } ?: emptyList()
            prefs[CUSTOM_PROFILES_KEY] =
                json.encodeToString(profileSerializer, current.filterNot { it.id == id })
        }
    }
}
