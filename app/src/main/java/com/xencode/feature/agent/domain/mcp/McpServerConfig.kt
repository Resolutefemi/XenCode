package com.xencode.feature.agent.domain.mcp

import kotlinx.serialization.Serializable

// Two shapes: remote HTTP (has url) or local stdio (has command); inferred by isStdio.
@Serializable
data class McpServerConfig(
    val name: String,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val disabledTools: Set<String> = emptySet()
) {
    val isStdio: Boolean get() = !command.isNullOrBlank()

    companion object {
        /**
         * The name is spliced into the function-calling tool name (`mcp__{name}__{tool}`); only ASCII letters, digits, underscore, and hyphen are allowed.
         * Other Unicode characters, such as Chinese, do not conform to providers' tool-naming rules.
         */
        private val NAME_REGEX = Regex("[a-zA-Z0-9_-]+")

        fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)
    }
}
