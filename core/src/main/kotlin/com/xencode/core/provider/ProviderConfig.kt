package com.xencode.core.provider

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * PC-line provider configuration, persisted at `~/.xencode/pc-config.json`.
 *
 * A provider is either an OpenAI-compatible chat-completions endpoint (OpenAI,
 * DeepSeek, Groq, OpenRouter, Ollama, Z.AI, LM Studio, ...), a native Anthropic
 * Messages endpoint, or a native Google Gemini endpoint. Everything the
 * [ChatClient] needs is here; `active` selects the default.
 */
data class ProviderConfig(
    val name: String,
    /** "openai" (chat-completions compatible), "anthropic" (native Messages API) or "gemini" (native Generative Language API). */
    val kind: String = "openai",
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** Extra headers, e.g. HTTPReferer/XTitle for some gateways. */
    val extraHeaders: Map<String, String> = emptyMap()
) {
    fun validate(): String? = when {
        name.isBlank() -> "provider name is empty"
        kind !in setOf("openai", "anthropic", "gemini") -> "kind must be openai, anthropic or gemini"
        baseUrl.isBlank() -> "baseUrl is empty"
        !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") -> "baseUrl must start with http(s)://"
        apiKey.isBlank() -> "apiKey is empty (set one even for local endpoints, e.g. 'none')"
        model.isBlank() -> "model is empty"
        else -> null
    }
}

data class PcConfig(
    val providers: List<ProviderConfig> = emptyList(),
    val active: String? = null,
    /** Default workspace for goal/dag/xp when --workspace is not given. */
    val defaultWorkspace: String? = null
) {
    fun activeProvider(): ProviderConfig? =
        providers.firstOrNull { it.name == active } ?: providers.firstOrNull()
}

object PcConfigStore {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun defaultPath(): String =
        System.getProperty("user.home")?.trimEnd('/')?.let { "$it/.xencode/pc-config.json" } ?: ".xencode/pc-config.json"

    fun file(): File = File(defaultPath())

    fun load(path: String = defaultPath()): PcConfig {
        val f = File(path)
        if (!f.isFile) return PcConfig()
        return runCatching {
            gson.fromJson(f.readText(Charsets.UTF_8), PcConfig::class.java) ?: PcConfig()
        }.getOrElse { PcConfig() }
    }

    fun save(config: PcConfig, path: String = defaultPath()): Boolean = runCatching {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(gson.toJson(config), Charsets.UTF_8)
        true
    }.getOrElse { false }
}
