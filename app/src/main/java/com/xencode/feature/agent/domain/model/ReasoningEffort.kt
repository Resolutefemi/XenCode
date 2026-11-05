package com.xencode.feature.agent.domain.model

/**
 * Reasoning effort tiers. apiValue matches the values of models.dev reasoning_options (effort type),
 * passed straight through to providers that support the parameter (OpenAI reasoning_effort / Gemini thinkingLevel);
 * Anthropic is mapped by the adapter, per model generation, to adaptive effort or budget_tokens.
 */
enum class ReasoningEffort(val apiValue: String) {
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    companion object {
        /** Parse models.dev effort values into the recognizable tier list (preserving the values' order). */
        fun fromValues(values: List<String>): List<ReasoningEffort> =
            values.mapNotNull { v -> entries.firstOrNull { it.apiValue == v } }
    }
}