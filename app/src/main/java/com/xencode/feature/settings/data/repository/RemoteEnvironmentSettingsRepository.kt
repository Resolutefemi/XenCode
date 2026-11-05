package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xencode.core.datastore.preferencesCorruptionHandler
import com.xencode.core.security.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.remoteEnvironmentDataStore by preferencesDataStore(
    name = "remote_environment_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

data class InstaboxConfig(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val apiUrl: String = InstaboxConfig.DEFAULT_API_URL,
    val template: String = InstaboxConfig.DEFAULT_TEMPLATE,
    val machineId: String = ""
) {
    companion object {
        const val DEFAULT_API_URL = "https://api.instabox.sh"
        const val DEFAULT_TEMPLATE = "agent-browser"
        const val DEFAULT_HOME = "/data/home"
    }

    val isReady: Boolean get() = enabled && apiKey.isNotBlank()
}

@Singleton
class RemoteEnvironmentSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val INSTABOX_ENABLED = booleanPreferencesKey("instabox_enabled")
        val INSTABOX_API_KEY = stringPreferencesKey("instabox_api_key")
        val INSTABOX_API_URL = stringPreferencesKey("instabox_api_url")
        val INSTABOX_TEMPLATE = stringPreferencesKey("instabox_template")
        val INSTABOX_MACHINE_ID = stringPreferencesKey("instabox_machine_id")
    }

    val instaboxConfigFlow: Flow<InstaboxConfig> = context.remoteEnvironmentDataStore.data.map { prefs ->
        InstaboxConfig(
            enabled = prefs[INSTABOX_ENABLED] == true,
            apiKey = prefs[INSTABOX_API_KEY]?.let { KeystoreCipher.decryptString(it) }.orEmpty(),
            apiUrl = prefs[INSTABOX_API_URL]?.takeIf { it.isNotBlank() } ?: InstaboxConfig.DEFAULT_API_URL,
            template = prefs[INSTABOX_TEMPLATE]?.takeIf { it.isNotBlank() } ?: InstaboxConfig.DEFAULT_TEMPLATE,
            machineId = prefs[INSTABOX_MACHINE_ID].orEmpty()
        )
    }

    suspend fun getInstaboxConfig(): InstaboxConfig = instaboxConfigFlow.first()

    suspend fun saveInstaboxConfig(config: InstaboxConfig) {
        context.remoteEnvironmentDataStore.edit { prefs ->
            prefs[INSTABOX_ENABLED] = config.enabled
            prefs[INSTABOX_API_KEY] = KeystoreCipher.encryptString(config.apiKey.trim())
            prefs[INSTABOX_API_URL] = config.apiUrl.trim().ifBlank { InstaboxConfig.DEFAULT_API_URL }
            prefs[INSTABOX_TEMPLATE] = config.template.trim().ifBlank { InstaboxConfig.DEFAULT_TEMPLATE }
            prefs[INSTABOX_MACHINE_ID] = config.machineId.trim()
        }
    }

    suspend fun setPinnedMachineId(machineId: String) {
        context.remoteEnvironmentDataStore.edit { it[INSTABOX_MACHINE_ID] = machineId.trim() }
    }
}
