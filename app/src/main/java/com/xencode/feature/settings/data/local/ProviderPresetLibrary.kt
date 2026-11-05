package com.xencode.feature.settings.data.local

import android.content.Context
import com.xencode.core.net.RepoDataFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Display presets for built-in/dynamic providers: name/type/baseUrl plus recommended and promotional extras. */
@Serializable
data class ProviderPreset(
    val id: String,
    val name: String,
    /** Protocol type: OPENAI / ANTHROPIC / GEMINI. */
    val type: String,
    /** Base URL, auto-filled on selection and editable in the edit page. */
    val baseUrl: String,
    /** Associated model id list (optional; imported as available models on selection). */
    val models: List<String> = emptyList(),
    /** Whether recommended/pinned */
    val isRecommended: Boolean = false,
    /** Badge/label text (e.g. "Recommended", "Sponsored") */
    val badge: String? = null,
    /** Vendor website or promotional signup link */
    val websiteUrl: String? = null,
    /** Feature/perk tag list (e.g. ["10 USD signup credit"]) */
    val tags: List<String> = emptyList()
)

/**
 * Library of official and custom provider presets:
 * Two-track mechanism:
 * 1. At startup a background coroutine warms it up asynchronously ([refreshFromNetworkIfStale]), writing to disk and updating the in-memory [cached];
 * 2. Any UI read ([loadOfficial]) synchronously resolves from "memory -> downloaded disk file -> bundled assets",
 *    never issuing a network request, returning in milliseconds.
 */
object ProviderPresetLibrary {
    const val PROVIDERS_ASSET_FILE_NAME = "providers.json"
    const val REMOTE_PROVIDERS_PATH = "data/providers.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<ProviderPreset>? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    /**
     * Only for the App startup phase (AIEditorApp) to call asynchronously in a background coroutine:
     * Uses RepoDataFetcher to fetch the latest config when reachable and persist it; failures are fully silent.
     */
    suspend fun refreshFromNetworkIfStale(context: Context) = withContext(Dispatchers.IO) {
        if (refreshAttemptedThisProcess) return@withContext
        refreshAttemptedThisProcess = true

        val fetcher = RepoDataFetcher(context)
        val remoteResult = runCatching { fetcher.fetch(REMOTE_PROVIDERS_PATH) }.getOrNull()
        val remoteContent = when (remoteResult) {
            is RepoDataFetcher.FetchResult.Success -> remoteResult.content
            is RepoDataFetcher.FetchResult.FallbackDiskCache -> remoteResult.content
            else -> null
        }

        if (!remoteContent.isNullOrBlank()) {
            val parsedRemote = runCatching {
                json.decodeFromString<List<ProviderPreset>>(remoteContent)
            }.getOrNull()
            if (!parsedRemote.isNullOrEmpty()) {
                val sorted = parsedRemote.sortedWith(
                    compareByDescending<ProviderPreset> { it.isRecommended }
                        .thenBy { it.name.lowercase() }
                )
                cached = sorted
            }
        }
    }

    /**
     * Fast synchronous read for the UI:
     * Order: memory cache -> downloaded disk file -> bundled assets file.
     * Pure local IO, absolutely no network requests, returns instantly.
     */
    fun loadOfficial(context: Context): List<ProviderPreset> {
        cached?.let { return it }

        // 1. Try reading the local disk cache file directly (written by RepoDataFetcher)
        val diskContent = RepoDataFetcher(context).readLocalCache(REMOTE_PROVIDERS_PATH)
        if (!diskContent.isNullOrBlank()) {
            val parsed = runCatching {
                json.decodeFromString<List<ProviderPreset>>(diskContent)
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) {
                val sorted = parsed.sortedWith(
                    compareByDescending<ProviderPreset> { it.isRecommended }
                        .thenBy { it.name.lowercase() }
                )
                cached = sorted
                return sorted
            }
        }

        // 2. Fallback to the bundled assets (always present, always instant)
        val fallback = loadFromAssets(context)
        cached = fallback
        return fallback
    }

    private fun loadFromAssets(context: Context): List<ProviderPreset> = runCatching {
        val text = context.assets.open(PROVIDERS_ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        val list = json.decodeFromString<List<ProviderPreset>>(text)
        list.sortedWith(
            compareByDescending<ProviderPreset> { it.isRecommended }
                .thenBy { it.name.lowercase() }
        )
    }.getOrDefault(emptyList())
}
