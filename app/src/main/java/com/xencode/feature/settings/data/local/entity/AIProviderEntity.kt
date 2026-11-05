package com.xencode.feature.settings.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_providers")
data class AIProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    /** Stored in plaintext in Room, same policy as the git token; to be handled together when encryption is unified later. */
    val apiKey: String,
    /** Multi-key mode toggle; when disabled only [apiKey] is used. */
    val multiKeyEnabled: Boolean = false,
    /** Multi-key candidate list, persisted newline-delimited (same as [models]). */
    val apiKeys: String = "",
    /** Multi-key selection strategy: SEQUENTIAL / ROUND_ROBIN. */
    val keyRotationStrategy: String = "SEQUENTIAL",
    /** Deprecated: old "switch only after consecutive failures reach the threshold" threshold; column kept for legacy DB compatibility (SQLite 3.18 has no DROP COLUMN). */
    val keyFailoverThreshold: Int = 2,
    /** Cooldown in minutes for a key that was switched away; 0 means no cooldown. */
    val keyCooldownMinutes: Int = 5,
    /** HTTP status codes that trigger automatic multi-key switching, persisted comma-delimited; empty string means use the default. */
    val keySwitchStatusCodes: String = "",
    val baseUrl: String,
    val defaultModel: String,
    /** List of available models, persisted newline-delimited. */
    val models: String = "",
    /** Currently selected model; falls back to defaultModel when empty. */
    val selectedModel: String = "",
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Anthropic explicit cache breakpoints (cache_control). Only used by the ANTHROPIC type, enabled by default. */
    val anthropicCacheBreakpoints: Boolean = true,
    /** Sends prompt_cache_key on the Chat Completion path (shard routing). Only used by the OPENAI type, disabled by default (the official API rejects this field). */
    val openaiChatCacheKey: Boolean = false,
    /** Custom dashboard script path. Column name keeps the historical name for compatibility with released databases. */
    @ColumnInfo(name = "balanceScriptPath")
    val dashboardScriptPath: String = "",
    /** Custom dashboard auto-refresh interval in minutes. Defaults to 5 minutes. Column name keeps the historical name for compatibility with released databases. */
    @ColumnInfo(name = "balanceRefreshInterval")
    val dashboardRefreshInterval: Int = 5,
    /**
     * Deprecated: UA override column superseded by [customHeaders]. Field kept for legacy DB compatibility —
     * the physical column added by migration 40 cannot be dropped (SQLite 3.18 has no DROP COLUMN), and a missing
     * entity column would make Room fail post-migration schema validation (exact column-set comparison; any extra column crashes).
     */
    val userAgent: String = "",
    /** Custom request headers (JSON-encoded Map<header name, value>, "" when empty), fully overriding same-named default headers. */
    val customHeaders: String = "",
    /** Sort order in the provider list; smaller values come first; new entries get max+1. */
    val sortOrder: Int = 0,
    /** Per-provider proxy configuration (follows the global proxy settings when disabled). */
    val proxyEnabled: Boolean = false,
    val proxyType: String = "HTTP",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    /** Custom dashboard (DIY) script parameters (JSON-encoded Map<String, String>, "" when empty). */
    val scriptParams: String = ""
)
