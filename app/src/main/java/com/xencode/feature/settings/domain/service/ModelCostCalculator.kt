package com.xencode.feature.settings.domain.service

import com.xencode.feature.settings.data.remote.ModelMetadataService
import com.xencode.feature.settings.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM call cost estimation. Prices are resolved by [ModelMetadataService] with a three-level fallback of "channel custom metadata → models.dev → built-in",
 * so the channel (providerId + type) must be passed; computing by model alone would miss channel custom prices.
 */
@Singleton
class ModelCostCalculator @Inject constructor(
    private val modelMetadataService: ModelMetadataService
) {
    /**
     * Estimated cost (USD) for a set of token usage; returns null when the model has no prices.
     * When the cache read price is missing it is estimated as the input price times [CACHE_READ_DISCOUNT]; when the cache write price is missing, as the input price times [CACHE_WRITE_MARKUP].
     */
    suspend fun costUsd(
        providerId: String,
        providerType: ProviderType,
        model: String,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
        cacheCreationTokens: Long = 0
    ): Double? {
        val meta = modelMetadataService.resolve(providerId, providerType, model)
        val inputPrice = meta.inputCostUsdPerM ?: return null
        val outputPrice = meta.outputCostUsdPerM ?: 0.0
        val cachePrice = meta.cacheReadCostUsdPerM ?: inputPrice * CACHE_READ_DISCOUNT
        val cacheWritePrice = meta.cacheWriteCostUsdPerM ?: inputPrice * CACHE_WRITE_MARKUP
        val uncached = (inputTokens - cachedInputTokens - cacheCreationTokens).coerceAtLeast(0)
        return (uncached * inputPrice + cachedInputTokens * cachePrice +
            cacheCreationTokens * cacheWritePrice + outputTokens * outputPrice) / 1_000_000.0
    }

    companion object {
        /** Discount applied to the input price when the cache read price is missing. */
        const val CACHE_READ_DISCOUNT = 0.1

        /** Multiplier relative to the input price when the cache write price is missing (officially 1.25x for Anthropic). */
        const val CACHE_WRITE_MARKUP = 1.25
    }
}
