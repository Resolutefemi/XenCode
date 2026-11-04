package com.aicode.feature.settings.data.local

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Display info for a single parameter in the available-tools catalog. */
@Serializable
data class ToolCatalogParam(
    val name: String,
    val type: String = "string",
    val required: Boolean = false,
    val description: String = ""
)

/**
 * Static display entry for the Available Tools page: tool name + title/description/parameter docs.
 * Pure resource catalog (assets/tools_catalog.json), not coupled to the ToolRegistry runtime;
 * keep it in sync when tools are added or changed.
 */
@Serializable
data class ToolCatalogEntry(
    val name: String,
    val title: String,
    val description: String = "",
    /** Category key: files / execution / network / ai / extensions. */
    val category: String = "extensions",
    /** Permission mode: auto (no confirmation) / ask (confirmation required). */
    val permission: String = "ask",
    val parameters: List<ToolCatalogParam> = emptyList()
)

/**
 * Available-tools catalog loader: reads built-in assets only, returns in milliseconds,
 * never touches the network. JSON parsing lives in a pure function for unit tests.
 */
object ToolCatalogLibrary {
    const val CATALOG_ASSET_FILE_NAME = "tools_catalog.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<ToolCatalogEntry>? = null

    /** Pure function: parse catalog JSON text; returns an empty list on malformed input. */
    fun parse(text: String): List<ToolCatalogEntry> = runCatching {
        json.decodeFromString<List<ToolCatalogEntry>>(text)
    }.getOrDefault(emptyList())

    /** Synchronous read of the built-in catalog (cached per process). */
    fun load(context: Context): List<ToolCatalogEntry> {
        cached?.let { return it }
        val entries = runCatching {
            context.assets.open(CATALOG_ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        }.map { parse(it) }.getOrDefault(emptyList())
        cached = entries
        return entries
    }
}
