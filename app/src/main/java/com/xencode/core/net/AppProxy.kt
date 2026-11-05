package com.xencode.core.net

import android.content.Context
import com.xencode.feature.settings.data.repository.DEFAULT_NO_PROXY
import com.xencode.feature.settings.data.repository.ProviderProxyEntry
import com.xencode.feature.settings.data.repository.ProviderProxyRegistry
import com.xencode.feature.settings.data.repository.ProxyConfig
import com.xencode.feature.settings.data.repository.ProxySettingsRepository
import com.xencode.feature.settings.domain.model.ProxyType
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Route
import java.io.IOException
import java.net.Authenticator.RequestorType
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URLEncoder

/**
 * Global HTTP proxy integration point (stateless singleton; reads the latest SharedPreferences
 * values each time, so a settings change takes effect for new connections immediately).
 *
 * How it hooks in:
 * - [applyGlobal] is called in Application.attachBaseContext (before any Hilt injection): it
 *   installs the dynamic [selector] as the JVM-wide default. OkHttpClient captures this
 *   reference when built, and HttpURLConnection consults the global selector on every new
 *   connection; both call select() live to read the latest config — no client rebuild needed.
 * - OkHttp proxy authentication goes through [okHttpAuthenticator] (must be explicitly mounted
 *   when building the OkHttpClient; OkHttp does not answer 407 challenges by default);
 *   HttpURLConnection authentication goes through the global java.net.Authenticator
 *   (also installed by [applyGlobal]).
 * - Commands inside the container (curl/git/npm/pip etc.) get HTTP_PROXY/HTTPS_PROXY/ALL_PROXY
 *   environment variables injected via [proxyEnv], which LinuxContainerEngine merges into the
 *   container process environment.
 */
object AppProxy {

    @Volatile
    private var appContext: Context? = null

    /** Provider-level proxy registry (registered after DI injection): dispatches provider-specific proxies by target host. */
    @Volatile
    private var providerRegistry: ProviderProxyRegistry? = null

    /** Call in Application.attachBaseContext (before the first OkHttpClient is built). */
    fun applyGlobal(context: Context) {
        // During attachBaseContext, getApplicationContext() may be null (the Application is not
        // fully bound yet); in that case use the passed-in base directly (the Application itself
        // is a valid Context) — otherwise config() later can't read the proxy settings and every
        // request silently goes direct (symptom: proxy works in the container but not app-side).
        appContext = context.applicationContext ?: context
        ProxySelector.setDefault(selector)
        java.net.Authenticator.setDefault(httpUrlConnectionAuthenticator)
    }

    /** Registered by the DI layer after startup assembly (AIEditorApp.onCreate); from then on the selector dispatches provider proxies by host. */
    fun registerProviderProxyRegistry(registry: ProviderProxyRegistry) {
        providerRegistry = registry
    }

    private fun config(): ProxyConfig {
        val ctx = appContext ?: return ProxyConfig()
        return ProxySettingsRepository.readConfig(ctx)
    }

    /** The proxy chosen by a select() call (provider-level wins over global) and its credentials. */
    private data class SelectedProxy(
        val type: ProxyType, val host: String, val port: Int,
        val username: String, val password: String
    )

    /** Dispatch by target host+path: provider-level proxy wins (most specific path prefix), otherwise the global proxy; returns null if unavailable. */
    private fun selectProxy(host: String, path: String?): SelectedProxy? {
        providerRegistry?.findByUrl(host, path)?.takeIf { it.valid }?.let { e ->
            return SelectedProxy(e.type, e.host, e.port, e.username, e.password)
        }
        val cfg = config()
        if (!cfg.enabled || !cfg.valid) return null
        return SelectedProxy(cfg.type, cfg.host, cfg.port, cfg.username, cfg.password)
    }

    private fun proxyOf(sel: SelectedProxy): Proxy {
        val type = when (sel.type) {
            ProxyType.HTTP -> Proxy.Type.HTTP
            ProxyType.SOCKS5 -> Proxy.Type.SOCKS
        }
        return Proxy(type, InetSocketAddress(sel.host, sel.port))
    }

    /**
     * Dynamic ProxySelector: reads the latest config on every select().
     * Dispatches by target host: a matching provider-level proxy uses the provider config,
     * otherwise the global config; returns a direct connection when disabled / incomplete /
     * NO_PROXY matches.
     */
    val selector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val cfg = config()
            val host = uri.host ?: return listOf(Proxy.NO_PROXY)
            if (isNoProxy(host, uri.port, cfg.noProxy)) return listOf(Proxy.NO_PROXY)
            val sel = selectProxy(host, uri.path) ?: return listOf(Proxy.NO_PROXY)
            return listOf(proxyOf(sel))
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
    }

    /** Mounted when building an OkHttpClient: answers Basic auth on a proxy 407, dispatching credentials by target host (provider first). */
    val okHttpAuthenticator: Authenticator = object : Authenticator {
        override fun authenticate(route: Route?, response: okhttp3.Response): okhttp3.Request? {
            if (response.code != 407) return null
            val url = response.request.url
            providerRegistry?.findByUrl(url.host, url.encodedPath)?.takeIf { it.username.isNotBlank() }?.let { e ->
                return response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(e.username, e.password))
                    .build()
            }
            val cfg = config()
            if (!cfg.enabled || cfg.username.isBlank()) return null
            return response.request.newBuilder()
                .header("Proxy-Authorization", Credentials.basic(cfg.username, cfg.password))
                .build()
        }
    }

    /** Global java.net.Authenticator: all proxy authentication at the java.net layer goes through
     *  here (HttpURLConnection's 407, plus every SOCKS5 handshake via `Socket(proxy)` —
     *  including OkHttp's SOCKS connections).
     *
     *  Android's SocksSocketImpl (unlike OpenJDK) does not read the java.net.socks.username/password
     *  system properties; SOCKS5 username/password auth only goes through this callback, and it
     *  invokes the six-argument requestPasswordAuthentication overload without a RequestorType
     *  (getRequestorType() is null), so the SOCKS5 branch must not check requestorType, nor rely
     *  on the global toggle (testProxy must work even while the toggle is off).
     *  At this point [getRequestingHost] yields the "address of the proxy server being connected
     *  to", so we can match provider/global config by host and return the corresponding
     *  credentials; returning null makes Android fall back to user.name plus an empty password,
     *  which always fails against proxies that require authentication.
     *  HTTP: only answers the global config (no HttpURLConnection callers remain in the project;
     *  kept for defense).
     */
    private val httpUrlConnectionAuthenticator: java.net.Authenticator =
        object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val protocol = getRequestingProtocol()?.lowercase()
                if (protocol == "socks5") {
                    val proxyHost = getRequestingHost()
                        ?: getRequestingSite()?.hostAddress
                        ?: return null
                    providerRegistry?.findByProxyHost(proxyHost)?.let { e ->
                        if (e.username.isNotBlank()) {
                            return PasswordAuthentication(e.username, e.password.toCharArray())
                        }
                    }
                    val cfg = config()
                    if (cfg.type == ProxyType.SOCKS5 && cfg.username.isNotBlank() &&
                        cfg.host.equals(proxyHost, ignoreCase = true)
                    ) {
                        return PasswordAuthentication(cfg.username, cfg.password.toCharArray())
                    }
                    return null
                }
                if (requestorType != RequestorType.PROXY) return null
                if (protocol != "http" && protocol != "https") return null
                val cfg = config()
                if (!cfg.enabled || cfg.username.isBlank()) return null
                return PasswordAuthentication(cfg.username, cfg.password.toCharArray())
            }
        }

    /** Proxy environment variables for processes inside the container; returns an empty map (direct connection) when disabled or incomplete. */
    fun proxyEnv(context: Context): Map<String, String> {
        val cfg = ProxySettingsRepository.readConfig(context)
        if (!cfg.enabled || !cfg.valid) return emptyMap()
        val noProxy = cfg.noProxy.ifBlank { DEFAULT_NO_PROXY }
        return when (cfg.type) {
            ProxyType.HTTP -> {
                val proxyUrl = "http://${authority(cfg)}"
                mapOf(
                    "HTTP_PROXY" to proxyUrl,
                    "HTTPS_PROXY" to proxyUrl,
                    "ALL_PROXY" to proxyUrl,
                    "NO_PROXY" to noProxy
                )
            }
            ProxyType.SOCKS5 -> {
                // socks5h: DNS resolution also goes through the proxy (prevents DNS leaks). Only
                // ALL_PROXY is set, not HTTP_PROXY/HTTPS_PROXY — otherwise curl would prefer
                // http_proxy for http/https targets and fail by speaking the plain-proxy
                // protocol to a SOCKS port.
                val proxyUrl = "socks5h://${authority(cfg)}"
                mapOf(
                    "ALL_PROXY" to proxyUrl,
                    "NO_PROXY" to noProxy
                )
            }
        }
    }

    /** The host:port part of the proxy URL; appends userinfo (percent-encoded) when a username is set. */
    private fun authority(cfg: ProxyConfig): String =
        if (cfg.username.isBlank()) {
            "${cfg.host}:${cfg.port}"
        } else {
            "${encodeUserInfo(cfg.username)}:${encodeUserInfo(cfg.password)}@${cfg.host}:${cfg.port}"
        }

    /** NO_PROXY matching: comma-separated, same semantics as curl (on the container side) —
     *  `*` means everything goes direct; `example.com` / `.example.com` matches itself and its
     *  subdomains; `*.example.com` matches subdomains only; `host:port` requires the port to
     *  match; IPv4/IPv6 CIDR ranges (e.g. 10.0.0.0/8, fd00::/8) match by prefix;
     *  case-insensitive; falls back to the default when the config is empty (same as the
     *  container injection). */
    private fun isNoProxy(host: String, port: Int, noProxy: String): Boolean {
        val list = noProxy.ifBlank { DEFAULT_NO_PROXY }
        val h = host.lowercase()
        return list.split(",").any { e ->
            val entry = e.trim().lowercase()
            when {
                entry.isEmpty() -> false
                entry == "*" -> true
                else -> {
                    val (hostPart, entryPort) = splitEntryHostPort(entry)
                    (entryPort == null || port == entryPort) && when {
                        hostPart.contains('/') -> cidrMatch(h, hostPart)
                        hostPart.startsWith("*.") -> h.endsWith("." + hostPart.removePrefix("*."))
                        else -> {
                            val hp = hostPart.removePrefix(".")
                            h == hp || h.endsWith(".$hp")
                        }
                    }
                }
            }
        }
    }

    /** Splits an entry into host and optional port: `[::1]:1080`, `example.com:8080` split at
     *  the port; a bare IPv6 literal (more than one colon) is treated as having no port, so `::1`
     *  isn't misread as host+port. */
    private fun splitEntryHostPort(entry: String): Pair<String, Int?> {
        if (entry.startsWith("[")) {
            val close = entry.indexOf(']')
            if (close > 0) {
                val port = entry.substring(close + 1).removePrefix(":").toIntOrNull()
                return entry.substring(1, close) to port
            }
        }
        if (entry.count { it == ':' } > 1) return entry to null
        val colon = entry.indexOf(':')
        if (colon >= 0) {
            val p = entry.substring(colon + 1).toIntOrNull()
            if (p != null) return entry.substring(0, colon) to p
        }
        return entry to null
    }

    /** CIDR prefix matching. Accepts IP literals only (no DNS resolution — select() runs on the
     *  network path); returns false if either side is invalid or v4/v6 are mixed. */
    private fun cidrMatch(host: String, cidr: String): Boolean {
        val slash = cidr.indexOf('/')
        if (slash <= 0) return false
        val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
        val hostBytes = parseLiteralIp(host) ?: return false
        val netBytes = parseLiteralIp(cidr.substring(0, slash)) ?: return false
        if (hostBytes.size != netBytes.size || prefix !in 0..hostBytes.size * 8) return false
        var i = prefix / 8
        while (i > 0) {
            i--
            if (hostBytes[i] != netBytes[i]) return false
        }
        val rem = prefix % 8
        if (rem == 0) return true
        val mask = (0xFF shl (8 - rem)) and 0xFF
        return (hostBytes[prefix / 8].toInt() and mask) == (netBytes[prefix / 8].toInt() and mask)
    }

    private fun parseLiteralIp(s: String): ByteArray? = try {
        val isLiteral = if (s.contains(':')) {
            s.none { !it.isDigit() && it !in "abcdefABCDEF:." }
        } else {
            val parts = s.split(".")
            parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }
        }
        if (isLiteral) java.net.InetAddress.getByName(s)?.address else null
    } catch (e: Exception) {
        null
    }

    /** Percent-encodes userinfo (the RFC 3986 allowed subset), so characters like @ or : in the password don't break URL parsing. */
    private fun encodeUserInfo(value: String): String {
        val sb = StringBuilder()
        for (ch in value) {
            if (ch.isLetterOrDigit() || ch in "-._~!$&'()*+,;=") {
                sb.append(ch)
            } else {
                sb.append(URLEncoder.encode(ch.toString(), "UTF-8"))
            }
        }
        return sb.toString()
    }

    // ── Proxy connectivity test ───────────────────────────────────────────

    /** Proxy test result. */
    data class ProxyTestResult(val ok: Boolean, val message: String)

    /** Probe URL: a fixed connectivity endpoint on Google's search domain (returns 204 and is
     *  directly reachable from mainland China) to test whether the proxy actually forwards
     *  traffic. Avoids third-party IP lookup services: no privacy exposure (the egress IP never
     *  leaves this machine), and a 204 response doesn't depend on any probe service's text
     *  format. */
    private const val PROBE_URL = "https://www.google.com/generate_204"
    private const val TEST_TIMEOUT_SECONDS = 8L

    /**
     * Fires one probe request with the given config (unaffected by the global toggle, so the
     * current form values can be tested directly).
     * [probeUrl] is the target URL for the test; falls back to the default when empty.
     * HTTP proxy auth uses 407 Basic; SOCKS5 auth is dispatched by host via the global
     * Authenticator (see above).
     */
    suspend fun testProxy(context: Context, cfg: ProxyConfig, probeUrl: String = PROBE_URL): ProxyTestResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (cfg.host.isBlank() || cfg.port !in 1..65535) {
                return@withContext ProxyTestResult(false, context.getString(com.xencode.R.string.proxy_test_invalid_config))
            }
            probe(context, SelectedProxy(cfg.type, cfg.host, cfg.port, cfg.username, cfg.password), probeUrl)
        }

    private suspend fun probe(context: Context, sel: SelectedProxy, probeUrl: String): ProxyTestResult {
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(TEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(TEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(TEST_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .proxy(proxyOf(sel))
            .proxyAuthenticator(okHttpAuthenticator)
            .build()
        return try {
            client.newCall(okhttp3.Request.Builder().url(probeUrl.ifBlank { PROBE_URL }).build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty().trim()
                if (resp.isSuccessful) {
                    ProxyTestResult(true, context.getString(com.xencode.R.string.proxy_test_ok, resp.code))
                } else {
                    ProxyTestResult(false, context.getString(com.xencode.R.string.proxy_test_http_error, resp.code, body.take(120)))
                }
            }
        } catch (e: Exception) {
            ProxyTestResult(false, context.getString(com.xencode.R.string.proxy_test_connect_failed, e.message ?: ""))
        }
    }
}
