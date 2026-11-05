package com.xencode.feature.agent.domain.provider

import com.xencode.feature.agent.domain.model.AgentImage
import com.xencode.feature.agent.domain.model.AgentMessage
import com.xencode.feature.agent.domain.tool.AgentTool
import com.xencode.feature.agent.domain.tool.ToolCall
import kotlinx.coroutines.flow.Flow

data class AIResponse(
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    /**
     * Reason the model stopped. Anthropic: "end_turn" / "tool_use" / "max_tokens" / "refusal" /
     * "model_context_window_exceeded" / "pause_turn"; OpenAI: "stop" / "tool_calls" / "length";
     * Gemini (generateContent finishReason, all uppercase): "STOP" / "MAX_TOKENS" / "SAFETY" etc.;
     * Gemini (Interactions status, lowercase): "completed" / "requires_action" / "failed" /
     * "budget_exceeded" (`incomplete` is already normalized to "length" by [interactionStopReason]).
     * A value in [TRUNCATION_STOP_REASONS] means the output was truncated by the token limit; the Agent loop should auto-continue.
     */
    val stopReason: String? = null,
    /**
     * Stop-reason explanation from the server (Anthropic `stop_details.explanation`).
     * On refusals the body may be empty and this is the only displayable reason. null when unavailable.
     */
    val stopDetail: String? = null,
    /** The model's full reasoning for this turn (OpenAI/DeepSeek reasoning_content). Must be sent back to the API when non-null, or DeepSeek thinking mode returns 400. */
    val reasoning: String? = null,
    /** Encrypted signature of Anthropic extended thinking (the thinking block's signature). Must be replayed unchanged with the thinking across turns/tool loops, or 400. null for other providers. */
    val signature: String? = null,
    /**
     * Verbatim snapshot (JSON text) of this provider's native "thinking / signature" blocks this turn; the next turn must replay them unchanged and in order:
     * - Anthropic: array of thinking / redacted_thinking content blocks (redacted `data` cannot be rebuilt, and merging multiple blocks returns 400).
     * - Gemini (generateContent): the model turn's `parts` array, because `thoughtSignature` is metadata on an arbitrary part
     *   (often the functionCall part or the last); only replaying the whole array verbatim preserves reasoning continuity.
     * - Gemini (Interactions): the `steps` array the model produced (thought / model_output / function_call);
     *   the official API requires replaying these steps verbatim in stateless mode; a thought's `signature` and function_call cannot be rebuilt.
     * null for other providers.
     */
    val thinkingBlocksJson: String? = null,
    /** Input tokens this turn (from the API's usage). 0 when unavailable. */
    val inputTokens: Int = 0,
    /** Output tokens this turn (from the API's usage). 0 when unavailable. */
    val outputTokens: Int = 0,
    /** Portion of this turn's input served from the server cache (OpenAI cached_tokens / Anthropic cache_read_input_tokens / Gemini cachedContentTokenCount). 0 when unavailable. */
    val cachedInputTokens: Int = 0,
    /** Tokens written to the server cache this turn (Anthropic cache_creation_input_tokens). Billed above the normal input rate; 0 when unavailable. */
    val cacheCreationTokens: Int = 0,
    /**
     * Images the model output directly this turn (produced when the Gemini Nano Banana image model
     * runs over Interactions). Each carries base64 data; the workflow persists them and builds UI attachments. Always empty for other providers / models.
     */
    val images: List<AgentImage> = emptyList()
) {
    val isTruncated: Boolean
        get() = stopReason in TRUNCATION_STOP_REASONS

    /** Aborted this turn by server policy / context overflow: the body may be partial or empty, and the Agent loop must not treat it as a normal completion. */
    val isAborted: Boolean
        get() = stopReason in ABORT_STOP_REASONS

    companion object {
        /**
         * Output truncated by the token limit (as spelled by Anthropic / OpenAI / Gemini).
         * Gemini Interactions `incomplete` matches only after [interactionStopReason] normalizes it to
         * `length`; `incomplete` itself is not listed, since the same-named Responses API status also covers non-truncation causes such as content_filter.
         */
        val TRUNCATION_STOP_REASONS = setOf("max_tokens", "length", "MAX_TOKENS")

        /**
         * stop_reasons that must be explained to the user instead of silently completing.
         * The first two are Anthropic; the uppercase set is Gemini generateContent finishReasons (safety
         * block / prohibited content / blocklist / sensitive info); the last two are Gemini Interactions statuses.
         */
        val ABORT_STOP_REASONS = setOf(
            "refusal",
            "model_context_window_exceeded",
            "SAFETY",
            "PROHIBITED_CONTENT",
            "BLOCKLIST",
            "SPII",
            "failed",
            "budget_exceeded"
        )
    }
}

/**
 * Result of one key switch: the new key plus its 1-based index in the candidate list, for the adapter to rewrite credentials and notify the UI.
 */
data class KeySwitchOutcome(val newKey: String, val newIndex: Int, val total: Int)

/**
 * Thrown when all candidate keys have failed. The message already contains the original failure reason; upper layers display it as-is.
 */
class AllKeysFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Chunks pushed upstream during a streaming completion.
 * [TextDelta] is a small piece of newly produced model text (a delta, not cumulative);
 * [Final] carries the full result at end of turn (aggregated text + tool calls) so the Agent loop can drive subsequent tool execution.
 * [Retrying] is emitted on network retry, for the UI to show a "retrying" notice.
 * [KeySwitched] is emitted on automatic multi-key switch, for the UI to indicate the key this request now uses.
 */
sealed class AIStreamChunk {
    data class TextDelta(val text: String) : AIStreamChunk()
    /** A small piece of newly produced model reasoning (a delta, not cumulative). Live UI display only; never replayed into context. */
    data class ReasoningDelta(val text: String) : AIStreamChunk()
    /**
     * The model has just started producing a tool call and the tool name is known (arguments still streaming in).
     *
     * Tools like whole-file writes and long commands have long arguments, so this streaming phase
     * can last seconds with no body or reasoning deltas and the UI can only show a generic
     * "thinking" state; emitting this event when the tool name appears lets the UI show a concrete scene ("editing file"). Purely a UI hint; no role in execution decisions.
     */
    data class ToolCallDeclared(val name: String) : AIStreamChunk()
    data class Final(val response: AIResponse) : AIStreamChunk()
    /** A network request is retrying. Live UI display only; never replayed into context. [error] summarizes the error that triggered the retry, so the UI can show the reason. */
    data class Retrying(val attempt: Int, val maxRetries: Int, val error: RetryErrorInfo) : AIStreamChunk()
    /** The current key is unavailable; automatically switched to key [newIndex] of [total] and resent this request. UI hint only. */
    data class KeySwitched(val newIndex: Int, val total: Int) : AIStreamChunk()
}

interface AIProvider {
    var apiKey: String
    var baseUrl: String
    var useFullUrl: Boolean

    /**
     * Switch to this provider's newer endpoint: OpenAI uses the Responses API (`v1/responses`),
     * Gemini the Interactions API (`v1beta/interactions`). Ignored by Anthropic.
     */
    var useResponseApi: Boolean
    var model: String

    /**
     * Current provider config id (database primary key), used to attach custom model metadata.
     * Set by the workflow before the call; when empty, metadata resolution falls back to fully automatic (fetched / built-in / default).
     */
    var providerId: String

    /**
     * Current session id, used only for log archiving: set by the workflow before the call;
     * [com.xencode.core.util.AILogger] uses it to write each request/response to that session's file. Falls back to `session-unknown.log` when null.
     */
    var logSessionId: String?

    /**
     * Custom request headers (header name -> value; values may contain `{{SESSION_ID}}` / `{{API_KEY}}` placeholders).
     * [com.xencode.feature.agent.domain.provider.resolveCustomHeaders] substitutes the placeholders
     * before writing; they fully override same-named default headers on this provider's requests.
     */
    var customHeaders: Map<String, String>

    /**
     * Multi-key auto-switch hook, injected by the workflow when assembling the provider; null means
     * disabled (e.g. image-generation side paths). Inputs: the failure exception, the key in use,
     * and the keys already tried this request. A non-null return means the switch to [KeySwitchOutcome.newKey]
     * succeeded and the adapter should rewrite [apiKey] and resend; null means it is not a "key unavailable" failure, or candidates are exhausted (implementations then throw [AllKeysFailedException]).
     */
    var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)?

    /**
     * Maximum output tokens allowed for this request, taken from the model metadata output limit (models.dev `limit.output`).
     * Set by the workflow before the call; when null, each adapter uses its own default or omits the parameter.
     */
    var maxOutputTokens: Int?

    /**
     * Sampling temperature for this request; set by the workflow from model metadata before the
     * call (see [fixedTemperature]). null omits the field and uses the server default; models whose
     * temperature is pinned server-side (kimi-k3, gpt-5 series, etc.) return 400 for any value.
     */
    var temperature: Float?

    /**
     * Upper bound (ms) for waiting for the first content chunk of a streaming request; written by the workflow from Settings -> Network before the call.
     * Defaults to 5 minutes; 0 means unlimited, leaving only the connect timeout and manual cancellation as fallbacks.
     */
    var firstByteTimeoutMs: Long

    /**
     * Maximum wait (ms) between consecutive data chunks in a streaming response; 0 (default) means unlimited.
     * When non-zero, receiving nothing within that interval closes the stream and raises a retryable IOException.
     */
    var streamIdleTimeoutMs: Long

    /**
     * Maximum retries for network requests (streaming included), excluding the initial attempt; written by the workflow from Settings -> Network before the call.
     * Defaults to 6; 0 means fail immediately without retrying.
     */
    var maxNetworkRetries: Int

    /**
     * Single-turn completion. [tools] are actually sent to the model in the provider's
     * function-calling format; if the model decides to call tools, they appear in the returned
     * [AIResponse.toolCalls]. [reasoningEffort] is the reasoning effort ("low"/"medium"/"high"),
     * effective only for the OpenAI family; ignored by Anthropic/Gemini and models without support.
     */
    suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool> = emptyList(),
        reasoningEffort: String? = null
    ): AIResponse

    /**
     * Streaming single-turn completion: receives the model's reply incrementally over SSE. Text is
     * pushed as [AIStreamChunk.TextDelta] deltas; at end of turn [AIStreamChunk.Final] delivers the
     * aggregated full [AIResponse] (tool calls included). Tool-calling semantics match [complete].
     * [reasoningEffort] is the same as for [complete].
     */
    fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool> = emptyList(),
        reasoningEffort: String? = null
    ): Flow<AIStreamChunk>
}

private val VERSION_SEGMENT_REGEX = Regex("""^v\d+.*$""", RegexOption.IGNORE_CASE)

/**
 * Returns the final header map to write out, with placeholders in the provider's custom headers replaced by runtime values.
 * Currently supports `{{SESSION_ID}}` (current session id; empty string when there is none) and `{{API_KEY}}` (the key actually used for this request).
 * Blank header names are already sanitized on save; skipped here again defensively.
 */
fun resolveCustomHeaders(
    customHeaders: Map<String, String>,
    sessionId: String?,
    apiKey: String
): Map<String, String> = customHeaders
    .filterKeys { it.isNotBlank() }
    .mapValues { (_, raw) ->
        raw.replace("{{SESSION_ID}}", sessionId ?: "")
            .replace("{{API_KEY}}", apiKey)
    }

/** Gemini generations where the vendor publishes a recommended sampling temperature and other values clearly degrade quality; other Gemini models send no temperature. */
private val GEMINI_MODELS_WITH_SAMPLING_DEFAULTS = listOf(
    Regex("""gemini-2[.-]5([.-]|$)"""),
    Regex("""gemini-3-(flash|pro)([.-]|$)"""),
    Regex("""gemini-3[.-]1([.-]|$)"""),
    Regex("""gemini-3[.-]5-flash(?!-lite)([.-]|$)""")
)

/**
 * Some models have their sampling temperature pinned server-side to a specific value (or the vendor
 * explicitly requires one); this returns it by model id. Models outside the table return null: omit
 * temperature and use the server default. Consulted only when model metadata allows a custom temperature; when metadata forbids it (kimi-k3, gpt-5 series, etc.) nothing is sent.
 */
fun fixedTemperature(modelId: String): Float? {
    val id = modelId.lowercase()
    return when {
        id.contains("glm-4.6") || id.contains("glm-4.7") -> 1.0f
        id.contains("minimax-m2") -> 1.0f
        id.contains("gemini") ->
            if (GEMINI_MODELS_WITH_SAMPLING_DEFAULTS.any { it.containsMatchIn(id) }) 1.0f else null
        // Kimi K2 thinking variants (k2-thinking / k2.5 / k2.6 ...) pin 1.0; non-thinking K2 pins 0.6.
        id.contains("kimi-k2") ->
            if (listOf("thinking", "k2.", "k2p", "k2-5").any { id.contains(it) }) 1.0f else 0.6f
        else -> null
    }
}

/**
 * Builds an absolute request URL from a user-configured base URL and an API path
 * such as "v1/chat/completions". Tolerates trailing slashes and a base URL that
 * already ends with a version segment (e.g. "https://host/v1", "https://host/api/v3", "https://host/v1beta")
 * so it isn't duplicated or conflicted with "v1/".
 *
 * Edge-case note (rule 2's "base version wins" assumption): when base ends with a version segment
 * and the path also starts with one, the path's version segment is dropped and base's version wins.
 * This is correct for every base shape the project supports: the only built-in source whose base
 * ends with a version segment is Zhipu (`https://open.bigmodel.cn/api/paas/v4`, whose real endpoint
 * is `.../v4/chat/completions`), and swapping the default OpenAI path's `v1` for base's `v4` is exactly
 * right there. Other built-in sources (OpenAI/Anthropic/Gemini/DeepSeek etc.) have no version segment
 * in base and take the plain-concatenation branch. If a future source has differing base/path versions where the path's must win, this needs rewriting to dedupe only on equality and concatenate otherwise, instead of unconditionally dropping the path's version segment.
 */
/**
 * Report a key failure and switch to the next available key. Returns null when [keySwitcher] is not
 * injected, the failure is judged non-key-related, or no switchable candidate remains; on success it rewrites [AIProvider.apiKey] and adds the new key to [triedKeys].
 */
suspend fun AIProvider.switchKeyOnFailure(error: Throwable, triedKeys: MutableSet<String>): KeySwitchOutcome? {
    val switcher = keySwitcher ?: return null
    val outcome = switcher(error, apiKey, triedKeys) ?: return null
    apiKey = outcome.newKey
    triedKeys += outcome.newKey
    return outcome
}

fun joinUrl(baseUrl: String, path: String): String {
    val base = baseUrl.trim().trimEnd('/')
    val cleanPath = path.trimStart('/')
    
    val lastSegment = base.substringAfterLast('/', "")
    
    // 1. If base's trailing segment exactly matches path's leading segment (e.g. /v1 and v1/chat), dedupe
    if (lastSegment.isNotEmpty() && cleanPath.startsWith("$lastSegment/", ignoreCase = true)) {
        return "$base/${cleanPath.substring(lastSegment.length + 1)}"
    }
    
    // 2. If base already ends with a version segment (e.g. /v3, /v2, /v1beta) and the path to join also starts with one (e.g. v1/chat, v1/models)
    if (lastSegment.matches(VERSION_SEGMENT_REGEX)) {
        val pathFirstSegment = cleanPath.substringBefore('/')
        if (pathFirstSegment.matches(VERSION_SEGMENT_REGEX)) {
            val remainingPath = cleanPath.substringAfter('/', "")
            return if (remainingPath.isNotEmpty()) "$base/$remainingPath" else base
        }
    }
    
    return "$base/$cleanPath"
}
