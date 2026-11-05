package com.xencode.feature.settings.presentation

/**
 * UI snapshot for the unified advanced context-window settings page.
 *
 * [enabled] is the actually effective state — always false when required fields (total context window, output window) are incomplete;
 * the storage and business layers each provide a fallback, so the UI needs no check of its own.
 */
data class ContextWindowOverrideUiState(
    val enabled: Boolean = false,
    val contextTokens: Int = 0,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
)
