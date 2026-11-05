package com.xencode.feature.settings.data.repository

import com.xencode.core.security.KeystoreCipher
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.data.local.database.AgentDatabase
import com.xencode.feature.settings.data.local.dao.AIProviderDao
import com.xencode.feature.settings.data.local.entity.AIProviderEntity
import com.xencode.feature.settings.domain.model.AIProviderConfig
import com.xencode.feature.settings.domain.model.DEFAULT_KEY_WEIGHT
import com.xencode.feature.settings.domain.model.KeyRotationStrategy
import com.xencode.feature.settings.domain.model.MAX_KEY_WEIGHT
import com.xencode.feature.settings.domain.model.ProviderKey
import com.xencode.feature.settings.domain.model.ProviderType
import com.xencode.feature.settings.domain.model.ProxyType
import com.xencode.feature.settings.domain.model.sanitized
import com.xencode.feature.settings.domain.repository.AIProviderRepository
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIProviderRepositoryImpl @Inject constructor(
    private val aiProviderDao: AIProviderDao,
    private val agentDatabase: AgentDatabase
) : AIProviderRepository {

    private companion object {
        const val TAG = "AIProviderRepo"
        private val json = Json { ignoreUnknownKeys = true }

        private fun encodeMap(map: Map<String, String>): String =
            if (map.isEmpty()) "" else json.encodeToString(map)

        private fun decodeMap(raw: String): Map<String, String> =
            if (raw.isBlank()) emptyMap()
            else runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())

        /** Parses the multi-key storage string: one `value|weight` per line; legacy data without `|` gets the default weight. */
        private fun parseProviderKeys(raw: String): List<ProviderKey> =
            raw.split("\n").mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val sep = trimmed.lastIndexOf('|')
                if (sep < 0) return@mapNotNull ProviderKey(trimmed)
                val value = trimmed.substring(0, sep).trim()
                if (value.isEmpty()) return@mapNotNull null
                val weight = trimmed.substring(sep + 1).trim().toIntOrNull() ?: DEFAULT_KEY_WEIGHT
                ProviderKey(value, weight.coerceIn(0, MAX_KEY_WEIGHT))
            }
    }

    override fun getAllProviders(): Flow<List<AIProviderConfig>> {
        return aiProviderDao.getAllProviders().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun getProviderById(id: String): AIProviderConfig? {
        return aiProviderDao.getProviderById(id)?.toDomainModel()
    }

    /**
     * Saves a provider. The sort order comes from the current DB value (reordering may be persisted asynchronously, so the
     * sortOrder passed in from the UI may be stale, and using it directly would undo the just-completed reorder); new providers get max sort order + 1.
     */
    override suspend fun saveProvider(provider: AIProviderConfig) {
        agentDatabase.withTransaction {
            val current = aiProviderDao.getProviderById(provider.id)
            val sortOrder = current?.sortOrder ?: (aiProviderDao.getMaxSortOrder() + 1)
            FileLogger.i(TAG, "Saving provider id=${provider.id} name=${provider.name} sortOrder=$sortOrder")
            aiProviderDao.insertProvider(provider.copy(sortOrder = sortOrder).sanitized().toEntity())
        }
    }

    /**
     * Reorders providers by the given order. Only the id + sortOrder columns are written,
     * avoiding a full-row REPLACE that would overwrite other concurrently modified fields (e.g. apiKey/model list).
     */
    override suspend fun reorderProviders(providers: List<AIProviderConfig>) {
        FileLogger.d(TAG, "Reordering providers, ${providers.size} total")
        agentDatabase.withTransaction {
            providers.forEachIndexed { index, p -> aiProviderDao.updateSortOrder(p.id, index) }
        }
    }

    override suspend fun deleteProvider(id: String) {
        FileLogger.i(TAG, "Deleting provider id=$id")
        aiProviderDao.deleteProvider(id)
    }

    override suspend fun setSelectedModel(id: String, model: String) {
        FileLogger.i(TAG, "Switching model provider=$id model=$model")
        aiProviderDao.setSelectedModel(id, model)
    }

    override suspend fun updateModels(id: String, models: List<String>) {
        FileLogger.d(TAG, "Updating model list provider=$id, ${models.size} total")
        aiProviderDao.setModels(id, models.joinToString("\n"))
    }

    override suspend fun setProviderEnabled(id: String, isEnabled: Boolean) {
        FileLogger.i(TAG, "Setting provider state provider=$id isEnabled=$isEnabled")
        aiProviderDao.setProviderEnabled(id, isEnabled)
    }

    private fun AIProviderEntity.toDomainModel(): AIProviderConfig {
        val modelList = models.split("\n").map { it.substringBefore('|').trim() }.filter { it.isNotEmpty() }
        return AIProviderConfig(
            id = id,
            name = name,
            type = try { ProviderType.valueOf(type) } catch (e: Exception) { ProviderType.OPENAI },
            apiKey = KeystoreCipher.decryptString(apiKey),
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = parseProviderKeys(KeystoreCipher.decryptString(apiKeys)),
            keyRotationStrategy = runCatching { KeyRotationStrategy.valueOf(keyRotationStrategy) }
                .getOrDefault(KeyRotationStrategy.SEQUENTIAL),
            keyFailoverThreshold = keyFailoverThreshold,
            keyCooldownMinutes = keyCooldownMinutes,
            keySwitchStatusCodes = keySwitchStatusCodes
                .split(",").mapNotNull { it.trim().toIntOrNull() }.distinct(),
            baseUrl = baseUrl,
            defaultModel = defaultModel,
            models = modelList,
            selectedModel = selectedModel.ifBlank { defaultModel },
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi,
            anthropicCacheBreakpoints = anthropicCacheBreakpoints,
            openaiChatCacheKey = openaiChatCacheKey,
            dashboardScriptPath = dashboardScriptPath,
            dashboardRefreshInterval = dashboardRefreshInterval,
            sortOrder = sortOrder,
            proxyEnabled = proxyEnabled,
            proxyType = runCatching { ProxyType.valueOf(proxyType) }.getOrDefault(ProxyType.HTTP),
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = KeystoreCipher.decryptString(proxyPassword),
            customHeaders = decodeMap(KeystoreCipher.decryptString(customHeaders)),
            scriptParams = decodeMap(KeystoreCipher.decryptString(scriptParams))
        ).sanitized()
    }

    private fun AIProviderConfig.toEntity(): AIProviderEntity {
        return AIProviderEntity(
            id = id,
            name = name,
            type = type.name,
            apiKey = KeystoreCipher.encryptString(apiKey),
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = KeystoreCipher.encryptString(apiKeys.joinToString("\n") { "${it.value}|${it.weight}" }),
            keyRotationStrategy = keyRotationStrategy.name,
            keyFailoverThreshold = keyFailoverThreshold,
            keyCooldownMinutes = keyCooldownMinutes,
            keySwitchStatusCodes = keySwitchStatusCodes.joinToString(","),
            baseUrl = baseUrl,
            useFullUrl = useFullUrl,
            defaultModel = defaultModel,
            models = models.joinToString("\n"),
            selectedModel = selectedModel,
            isEnabled = isEnabled,
            useResponseApi = useResponseApi,
            anthropicCacheBreakpoints = anthropicCacheBreakpoints,
            openaiChatCacheKey = openaiChatCacheKey,
            dashboardScriptPath = dashboardScriptPath,
            dashboardRefreshInterval = dashboardRefreshInterval,
            sortOrder = sortOrder,
            proxyEnabled = proxyEnabled,
            proxyType = proxyType.name,
            proxyHost = proxyHost,
            proxyPort = proxyPort,
            proxyUsername = proxyUsername,
            proxyPassword = KeystoreCipher.encryptString(proxyPassword),
            customHeaders = KeystoreCipher.encryptString(encodeMap(customHeaders)),
            scriptParams = KeystoreCipher.encryptString(encodeMap(scriptParams))
        )
    }
}
