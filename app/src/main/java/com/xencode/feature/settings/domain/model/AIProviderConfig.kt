package com.xencode.feature.settings.domain.model

data class AIProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val apiKey: String,
    /** Multi-key mode toggle: when on, keys rotate from [apiKeys]; when off, only [apiKey] is used. */
    val multiKeyEnabled: Boolean = false,
    /** Candidate keys in multi-key mode (list order preferred, each with its own scheduling weight). */
    val apiKeys: List<ProviderKey> = emptyList(),
    /** Multi-key selection strategy: sequential (switch only on failure) or round robin (new sessions start in turn). */
    val keyRotationStrategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
    /** Deprecated: the early "switch only after consecutive failures reach the threshold" threshold. Now hitting [keySwitchStatusCodes] switches immediately; kept solely for legacy DB column compatibility. */
    val keyFailoverThreshold: Int = 2,
    /** How many minutes a switched-away key cools down before rejoining the candidates; 0 means no cooldown. */
    val keyCooldownMinutes: Int = 5,
    /** HTTP status codes that trigger automatic multi-key switching; falls back to [DEFAULT_KEY_SWITCH_STATUS_CODES] when empty. */
    val keySwitchStatusCodes: List<Int> = emptyList(),
    val baseUrl: String,
    val defaultModel: String,
    /** List of models added for this provider (fetched or manually added). */
    val models: List<String> = emptyList(),
    /** Currently selected model; falls back to defaultModel when empty. */
    val selectedModel: String = defaultModel,
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Anthropic explicit cache breakpoints (cache_control). Only effective for the ANTHROPIC type, enabled by default. */
    val anthropicCacheBreakpoints: Boolean = true,
    /** Sends prompt_cache_key on the Chat Completion path (shard routing). Only effective for the OPENAI type, disabled by default. */
    val openaiChatCacheKey: Boolean = false,
    /** Custom dashboard script path (under ~/.xencode/scripts/, or an absolute path/custom command). */
    val dashboardScriptPath: String = "",
    /** Custom dashboard auto-refresh interval in minutes; 0 means refresh only on entry/manually; supports 1, 3, 5, 10, etc. Defaults to 5 minutes. */
    val dashboardRefreshInterval: Int = 5,
    /**
     * Custom request headers (header name -> value), fully overriding same-named default headers on all requests to this provider.
     * Values support the placeholders `{{SESSION_ID}}` (session id) and `{{API_KEY}}` (the key actually used this time),
     * substituted by the provider adapter before the request is written out.
     */
    val customHeaders: Map<String, String> = emptyMap(),
    /** Sort order in the provider list; smaller values come first; -1 means unassigned (gets max+1 and goes last on save). */
    val sortOrder: Int = -1,
    /** Per-provider proxy configuration (follows the global proxy settings when disabled). */
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    /**
     * Custom dashboard (DIY) script parameters (key-value).
     * Injected as extra environment variables `AICODE_KEY_<KEY>` when the dashboard script runs;
     * values may reference provider config placeholders such as `{{PROVIDER_API_KEY}}` and `{{BASE_URL}}`.
     */
    val scriptParams: Map<String, String> = emptyMap()
) {
    /** The actually effective model: selectedModel first, then defaultModel. */
    val effectiveModel: String
        get() = selectedModel.ifBlank { defaultModel }

    /**
     * The actually usable key list. Uses [apiKeys] when multi-key mode is on and the list is non-empty, otherwise falls back to the single [apiKey],
     * so both "toggle off" and "on but empty" land on the existing single-key behavior.
     */
    val effectiveApiKeys: List<ProviderKey>
        get() = if (multiKeyEnabled && apiKeys.any { it.value.isNotBlank() }) {
            apiKeys.filter { it.value.isNotBlank() }
        } else {
            listOf(ProviderKey(apiKey)).filter { it.value.isNotBlank() }
        }

    /** Whether at least one usable key is configured. */
    val hasUsableApiKey: Boolean get() = effectiveApiKeys.isNotEmpty()

    /** The effective switch status codes: falls back to the default set when empty. */
    val effectiveKeySwitchStatusCodes: Set<Int>
        get() = keySwitchStatusCodes.filter { it in 100..599 }.toSet()
            .ifEmpty { DEFAULT_KEY_SWITCH_STATUS_CODES }

    /** Requests without session context, such as model list fetch / connectivity tests, use the first usable key. */
    val firstUsableApiKey: String get() = effectiveApiKeys.firstOrNull()?.value ?: ""
}

/**
 * A candidate key in multi-key mode: value + scheduling weight.
 *
 * [weight] participates in round-robin scheduling: the larger it is, the more new sessions are assigned; ≤ 0 means the key temporarily sits out scheduling (kept but not selected).
 */
data class ProviderKey(
    val value: String,
    val weight: Int = DEFAULT_KEY_WEIGHT
)

/** Default and maximum scheduling weight. */
const val DEFAULT_KEY_WEIGHT: Int = 1
const val MAX_KEY_WEIGHT: Int = 1_000_000

/** Fields that must contain no whitespace (API key / URL / proxy host): remove even interior whitespace. */
private fun String.stripAllWhitespace(): String = filterNot { it.isWhitespace() }

/** Fields allowing interior spaces (name / model name / UA / path / proxy account): strip newlines and tabs, then trim the ends. */
private fun String.stripLineBreaks(): String =
    filterNot { it == '\n' || it == '\r' || it == '\t' }.trim()

/**
 * Cleans hand-entered/pasted provider configuration. Pasted API keys often carry newlines or surrounding spaces; putting them straight into the Authorization
 * header gets rejected by OkHttp (Unexpected char 0x0a in Authorization value); whitespace in baseUrl / proxy hosts produces
 * an invalid URL. Cleaning on both the write and read sides lets already-stored dirty data become usable again without a migration.
 */
fun AIProviderConfig.sanitized(): AIProviderConfig = copy(
    name = name.stripLineBreaks(),
    apiKey = apiKey.stripAllWhitespace(),
    apiKeys = apiKeys
        .map { it.copy(value = it.value.stripAllWhitespace(), weight = it.weight.coerceIn(0, MAX_KEY_WEIGHT)) }
        .filter { it.value.isNotEmpty() }
        .distinctBy { it.value },
    keySwitchStatusCodes = keySwitchStatusCodes.filter { it in 100..599 }.distinct(),
    baseUrl = baseUrl.stripAllWhitespace(),
    defaultModel = defaultModel.stripLineBreaks(),
    models = models.map { it.stripLineBreaks() }.filter { it.isNotEmpty() }.distinct(),
    selectedModel = selectedModel.stripLineBreaks(),
    dashboardScriptPath = dashboardScriptPath.stripLineBreaks(),
    customHeaders = customHeaders
        .mapKeys { (k, _) -> k.trim() }
        .mapValues { (_, v) -> v.stripLineBreaks() }
        .filterKeys { it.isNotEmpty() },
    proxyHost = proxyHost.stripAllWhitespace(),
    proxyUsername = proxyUsername.stripLineBreaks(),
    proxyPassword = proxyPassword.stripLineBreaks(),
    scriptParams = scriptParams
        .mapKeys { (k, _) -> k.trim() }
        .mapValues { (_, v) -> v.stripLineBreaks() }
        .filterKeys { it.isNotEmpty() }
)

enum class ProviderType {
    OPENAI, ANTHROPIC, GEMINI
}

/** HTTP status codes that trigger automatic multi-key switching by default: auth (401), billing/quota (402), permission (403), rate limit (429). */
val DEFAULT_KEY_SWITCH_STATUS_CODES: Set<Int> = setOf(401, 402, 403, 429)

enum class KeyRotationStrategy {
    /** Sequential: always use the first non-cooling key; only move forward after consecutive failures reach the threshold. */
    SEQUENTIAL,

    /** Round robin: new sessions take turns being assigned the starting key; within one session the same key is kept to preserve the server-side prompt cache. */
    ROUND_ROBIN
}

fun defaultProviderApiPath(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "v1/messages"
    ProviderType.GEMINI -> "v1beta"
    else -> "v1/chat/completions"
}
