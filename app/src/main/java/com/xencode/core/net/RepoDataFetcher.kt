package com.xencode.core.net

import android.content.Context
import com.xencode.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Generic component for fetching remote repository data:
 * purpose-built for pulling static JSON/text data (e.g. provider presets, model lists) from
 * this repository (or a branch/tag).
 *
 * Node scheduling and high-availability strategy:
 * 1. Prefer public CDN acceleration nodes such as Fastly CDN / jsDelivr (fast direct access
 *    from mainland China, no VPN needed);
 * 2. Fall back to other CDN mirrors in order;
 * 3. Finally fall back to a direct connection to the GitHub raw origin;
 * 4. Built-in on-disk persistent cache (TTL) plus lightweight ETag / If-None-Match conditional
 *    requests (prevents pointless re-downloads);
 * 5. When the network is completely down, gracefully fall back to the existing disk cache or
 *    bundled assets.
 */
class RepoDataFetcher(
    private val context: Context,
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
    private val branch: String = DEFAULT_BRANCH,
    private val defaultMaxAgeMs: Long = DEFAULT_CACHE_MAX_AGE_MS,
    private val client: OkHttpClient = DEFAULT_CLIENT
) {

    /**
     * Fetch result for a single data file
     */
    sealed class FetchResult {
        /** Fetched from the network, or 304 hit on a valid cache */
        data class Success(val content: String, val fromCache: Boolean) : FetchResult()
        /** Network request failed but the local disk cache was read successfully */
        data class FallbackDiskCache(val content: String, val error: Throwable) : FetchResult()
        /** Total failure (no local cache available) */
        data class Failure(val error: Throwable) : FetchResult()
    }

    /**
     * Fetches data: uses the local cache directly if it hasn't expired; otherwise probes and
     * fetches by node priority, with 304 support and disk fallback.
     *
     * @param pathInRepo Path relative to the repository root, e.g. "data/providers.json"
     * @param maxAgeMs Cache validity period in milliseconds; defaults to the constructor's
     * [defaultMaxAgeMs]; pass 0L to skip the local freshness check and force a network
     * reconciliation
     */
    suspend fun fetch(
        pathInRepo: String,
        maxAgeMs: Long = defaultMaxAgeMs
    ): FetchResult = withContext(Dispatchers.IO) {
        val cleanPath = pathInRepo.trim().removePrefix("/")
        val cacheFile = getCacheFile(cleanPath)
        val etagFile = getEtagFile(cleanPath)

        val now = System.currentTimeMillis()
        val cachedContent = if (cacheFile.isFile) runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull() else null
        val isCacheFresh = cachedContent != null && (now - cacheFile.lastModified() < maxAgeMs)

        // 1. Local cache is still fresh — return it directly
        if (isCacheFresh) {
            return@withContext FetchResult.Success(cachedContent!!, fromCache = true)
        }

        val cachedEtag = if (etagFile.isFile) runCatching { etagFile.readText(Charsets.UTF_8).trim() }.getOrNull() else null
        val candidateUrls = buildCandidateUrls(cleanPath)

        var lastError: Throwable? = null

        // 2. Iterate candidate nodes in order
        for (url in candidateUrls) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "xencode-android")
                    .get()

                if (!cachedEtag.isNullOrBlank()) {
                    reqBuilder.header("If-None-Match", cachedEtag)
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    when {
                        // 304 Not Modified: the existing cache is still valid; bump the file mtime and return it
                        response.code == 304 && cachedContent != null -> {
                            cacheFile.setLastModified(now)
                            return@withContext FetchResult.Success(cachedContent, fromCache = true)
                        }

                        response.isSuccessful -> {
                            val body = response.body?.string().orEmpty()
                            if (body.isNotBlank()) {
                                // Write to the disk cache
                                runCatching {
                                    cacheFile.parentFile?.mkdirs()
                                    cacheFile.writeText(body, Charsets.UTF_8)
                                    val newEtag = response.header("ETag")
                                    if (!newEtag.isNullOrBlank()) {
                                        etagFile.writeText(newEtag.trim(), Charsets.UTF_8)
                                    } else {
                                        etagFile.delete()
                                    }
                                }
                                return@withContext FetchResult.Success(body, fromCache = false)
                            }
                        }

                        else -> {
                            val errCode = response.code
                            FileLogger.d(TAG, "Node request failed url=$url code=$errCode")
                        }
                    }
                }
            } catch (e: Throwable) {
                lastError = e
                FileLogger.d(TAG, "Node connection error url=$url: ${e.message}")
            }
        }

        // 3. All nodes unavailable: if a stale cache exists on disk, degrade to it
        if (cachedContent != null) {
            FileLogger.w(TAG, "All network nodes failed to fetch, falling back to stale disk cache: $cleanPath", lastError)
            return@withContext FetchResult.FallbackDiskCache(
                cachedContent,
                lastError ?: IllegalStateException("All remote endpoints failed")
            )
        }

        // 4. Total failure
        FetchResult.Failure(lastError ?: IllegalStateException("Failed to fetch $cleanPath from all sources"))
    }

    /**
     * Builds the candidate node URL list:
     * 1. jsDelivr (Fastly CDN accelerated)
     * 2. jsDelivr (general CDN)
     * 3. raw.githubusercontent.com (official GitHub direct fallback)
     */
    fun buildCandidateUrls(path: String): List<String> = listOf(
        // Fastly-accelerated jsDelivr node
        "https://fastly.jsdelivr.net/gh/$owner/$repo@$branch/$path",
        // General CDN mirror
        "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path",
        // jsDelivr GCore node
        "https://gcore.jsdelivr.net/gh/$owner/$repo@$branch/$path",
        // GitHub origin fallback
        "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
    )

    /**
     * Reads the disk cache synchronously (no network): returns the text if present, otherwise null.
     * Lets read-only call sites reuse the unified cache directory instead of hard-coding cache paths.
     */
    fun readLocalCache(pathInRepo: String): String? {
        val file = getCacheFile(pathInRepo.trim().removePrefix("/"))
        return if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    fun clearCache(pathInRepo: String? = null) {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) return
        if (pathInRepo == null) {
            dir.deleteRecursively()
        } else {
            val cleanPath = pathInRepo.trim().removePrefix("/")
            getCacheFile(cleanPath).delete()
            getEtagFile(cleanPath).delete()
        }
    }

    private fun getCacheFile(path: String): File {
        val safeName = path.replace('/', '_')
        return File(File(context.filesDir, CACHE_DIR_NAME), safeName)
    }

    private fun getEtagFile(path: String): File {
        val safeName = path.replace('/', '_') + ".etag"
        return File(File(context.filesDir, CACHE_DIR_NAME), safeName)
    }

    companion object {
        private const val TAG = "RepoDataFetcher"
        private const val CACHE_DIR_NAME = "repo_data_cache"

        const val DEFAULT_OWNER = "Resolutefemi"
        const val DEFAULT_REPO = "XenCode"
        const val DEFAULT_BRANCH = "main"

        /** Cache for 12 hours by default */
        const val DEFAULT_CACHE_MAX_AGE_MS = 12 * 60 * 60 * 1000L

        val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .proxyAuthenticator(AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}
