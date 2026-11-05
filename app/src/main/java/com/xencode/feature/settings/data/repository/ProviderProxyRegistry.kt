package com.xencode.feature.settings.data.repository

import com.xencode.core.util.FileLogger
import com.xencode.feature.settings.domain.model.AIProviderConfig
import com.xencode.feature.settings.domain.model.ProxyType
import com.xencode.feature.settings.domain.repository.AIProviderRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Per-provider proxy entry: looked up by [ProviderProxyRegistry] based on the target URL. */
data class ProviderProxyEntry(
    val providerId: String,
    val type: ProxyType,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** Path prefix of the baseUrl (normalized: trailing slash removed; empty string for root path, matching all requests to the host). */
    val basePath: String = ""
) {
    val valid: Boolean get() = host.isNotBlank() && port in 1..65535
}

/**
 * Per-provider proxy registry: indexes providers with dedicated proxies by baseUrl host + path prefix.
 *
 * AppProxy's global dynamic ProxySelector consults this table on each select(uri) by target host+path —
 * on a hit the provider's proxy is used, otherwise it falls back to the global proxy. Providers on different
 * paths (different URLs) of the same host can each be dispatched precisely; only entries with an identical host+path cannot be distinguished (the first configured wins, with a warning logged).
 */
@Singleton
class ProviderProxyRegistry @Inject constructor(
    aiProviderRepository: AIProviderRepository
) {
    // Application-level scope: the singleton's lifetime matches the app process (dies with it), no external cancellation needed.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** host (lowercase) → list of proxy entries under that host; empty map when nothing is configured. */
    @Volatile
    private var byHost: Map<String, List<ProviderProxyEntry>> = emptyMap()

    init {
        scope.launch {
            aiProviderRepository.getAllProviders().collectLatest { providers ->
                val entries = providers
                    .filter { it.proxyEnabled && it.proxyHost.isNotBlank() && it.proxyPort in 1..65535 }
                    .mapNotNull { entry ->
                        providerHostAndPath(entry.baseUrl)?.let { (host, path) -> host to entry.toEntry(path) }
                    }
                // Multiple providers sharing the same host+path cannot be distinguished at the proxy layer: keep the first configured and log it.
                entries.groupBy { it.first }.filter { it.value.size > 1 }.forEach { (key, pairs) ->
                    FileLogger.w(
                        TAG,
                        "Multiple providers share $key; conflicting proxy configs, only the first configured takes effect: ${pairs.joinToString { p -> p.second.providerId }}"
                    )
                }
                byHost = entries.groupBy({ it.first }, { it.second })
                FileLogger.d(TAG, "Provider proxy table refreshed: ${byHost.size} hosts, ${entries.size} entries")
            }
        }
    }

    /**
     * Looks up by target URL host + path prefix. Path matching rule: the entry's basePath is empty (root path,
     * matching all requests to the host), the request path equals basePath, or the request path starts with basePath + "/".
     * With multiple matches the longest basePath wins (most specific first); for equally long duplicates the first configured wins.
     */
    fun findByUrl(host: String, path: String?): ProviderProxyEntry? {
        val candidates = byHost[host.lowercase()] ?: return null
        val reqPath = path?.takeIf { it.isNotBlank() } ?: "/"
        val matched = candidates.filter { e ->
            val base = e.basePath
            base.isEmpty() || reqPath == base || reqPath.startsWith("$base/")
        }
        if (matched.size <= 1) return matched.firstOrNull()
        return matched.maxByOrNull { it.basePath.length }
    }

    /** Looks up by proxy server address (not target host): matches credentials by the proxy used for the connection during SOCKS5 handshake auth. */
    fun findByProxyHost(proxyHost: String): ProviderProxyEntry? =
        byHost.values.flatten().firstOrNull { it.host.equals(proxyHost, ignoreCase = true) && it.username.isNotBlank() }

    private fun AIProviderConfig.toEntry(basePath: String) = ProviderProxyEntry(
        providerId = id,
        type = proxyType,
        host = proxyHost,
        port = proxyPort,
        username = proxyUsername,
        password = proxyPassword,
        basePath = basePath
    )

    private fun providerHostAndPath(baseUrl: String): Pair<String, String>? =
        runCatching {
            val uri = java.net.URI(baseUrl.trim())
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val path = uri.path?.trimEnd('/') ?: ""
            host to path
        }.getOrNull()

    private companion object {
        const val TAG = "ProviderProxyRegistry"
    }
}
