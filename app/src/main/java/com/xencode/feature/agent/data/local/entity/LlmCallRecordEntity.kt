package com.xencode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single LLM call record: the data source for the Token stats page.
 * Written via instrumentation from the workflow main loop (chat) and the context compaction pipeline (compaction),
 * covering provider, model, input/output/cached tokens, TTFB, duration, and outcome.
 */
@Entity(
    tableName = "llm_call_records",
    indices = [
        Index(value = ["createdAt"]),
        Index(name = "index_llm_call_records_provider_model", value = ["providerId", "model"])
    ]
)
data class LlmCallRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String? = null,
    /** Provider (ai_providers.id); LEFT JOIN for the provider name when displaying. */
    val providerId: String? = null,
    /** The model actually called (including the compaction fallback model). */
    val model: String? = null,
    /** The reasoning effort for this call (ReasoningEffort.apiValue, e.g. "medium"); null when unset or for models without tiers. */
    val reasoningEffort: String? = null,
    /** Call kind: chat (regular conversation) / compaction (context compaction). Historical data includes the deprecated vision (image-recognition turns). */
    val kind: String = "chat",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** The portion of this turn's input that hit the server-side cache (OpenAI cached_tokens / Anthropic cache_read_input_tokens / Gemini cachedContentTokenCount). */
    val cachedInputTokens: Int = 0,
    /** Tokens written to the server-side cache this turn (Anthropic cache_creation_input_tokens), billed at a higher rate than input. */
    val cacheCreationTokens: Int = 0,
    /** TTFB in milliseconds: first text chunk arrival - request sent for streaming calls; null for non-streaming or failed calls. */
    val ttfbMillis: Int? = null,
    /** Total duration in milliseconds: end - request sent. */
    val durationMillis: Int? = null,
    /** Call outcome: success / error. */
    val status: String = "success",
    val errorMessage: String? = null,
    /** Model stop reason: end_turn / tool_use / max_tokens / stop / length. */
    val stopReason: String? = null,
    /** Number of network retries that occurred underneath this interaction (0 means first-try success or a non-retry failure). */
    val retryCount: Int = 0,
    /** Request sent time (epoch milliseconds). */
    val createdAt: Long
)
