package com.xencode.feature.settings.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ModelMetadata(
    val id: String,
    val providerId: String? = null,
    val displayName: String = id,
    val contextTokens: Int,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
    /**
     * models.dev `temperature`: whether this model accepts a custom temperature.
     * false (including missing metadata) means the server pins the sampling temperature and the field must be omitted from requests —
     * models like the kimi-k3 / gpt-5 families return a straight 400 if it is included.
     */
    val supportsCustomTemperature: Boolean = false,
    val modelType: ModelType = ModelType.CHAT,
    val supportsImageOutput: Boolean = false,
    val source: Source = Source.INFERRED,
    /** models.dev reasoning effort tiers (the values of the effort type in reasoning_options, e.g. ["low","medium","high"]); null/empty = no tiers (hide the reasoning effort picker). */
    val reasoningEffortOptions: List<String>? = null,
    /** models.dev cost: input price (USD/1M tokens). */
    val inputCostUsdPerM: Double? = null,
    /** models.dev cost: output price (USD/1M tokens). */
    val outputCostUsdPerM: Double? = null,
    /** models.dev cost: cache read price (USD/1M tokens). */
    val cacheReadCostUsdPerM: Double? = null,
    /** models.dev cost: cache write price (USD/1M tokens), usually higher than the regular input price. */
    val cacheWriteCostUsdPerM: Double? = null
) {
    enum class ModelType { CHAT, EMBEDDING }

    enum class Source {
        MODELS_DEV,
        INFERRED
    }
}

/**
 * Key for the model metadata cache.
 * Metadata cannot be indexed by model name alone: custom prices are stored as "providerId:modelName", and different channel types
 * auto-match the same model name differently — indexing by name alone would cross-wire (pulling another channel's prices/capabilities).
 */
fun modelMetadataKey(providerId: String, model: String): String = "$providerId:$model"

/**
 * Merges custom metadata with auto-resolved (fetched/built-in) metadata, custom first; auto values are kept for unfilled windows.
 * When [base] is null, fallback metadata is constructed (zero windows, all capabilities false).
 */
fun mergeModelMetadata(
    model: String,
    base: ModelMetadata?,
    custom: ModelMetadata?
): ModelMetadata {
    val a = base ?: ModelMetadata(
        id = model,
        displayName = model,
        contextTokens = 0,
        inputTokens = null,
        outputTokens = null
    )
    val c = custom ?: return a
    return a.copy(
        modelType = c.modelType,
        supportsVision = c.supportsVision,
        supportsImageOutput = c.supportsImageOutput,
        supportsTools = c.supportsTools,
        supportsReasoning = c.supportsReasoning,
        contextTokens = c.contextTokens.takeIf { it > 0 } ?: a.contextTokens,
        inputTokens = c.inputTokens ?: a.inputTokens,
        outputTokens = c.outputTokens ?: a.outputTokens,
        inputCostUsdPerM = c.inputCostUsdPerM ?: a.inputCostUsdPerM,
        outputCostUsdPerM = c.outputCostUsdPerM ?: a.outputCostUsdPerM,
        cacheReadCostUsdPerM = c.cacheReadCostUsdPerM ?: a.cacheReadCostUsdPerM,
        cacheWriteCostUsdPerM = c.cacheWriteCostUsdPerM ?: a.cacheWriteCostUsdPerM
    )
}

