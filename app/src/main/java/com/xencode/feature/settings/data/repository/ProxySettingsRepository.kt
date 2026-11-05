package com.xencode.feature.settings.data.repository

import android.content.Context
import androidx.core.content.edit
import com.xencode.feature.settings.domain.model.ProxyType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Default list of hosts/CIDR blocks that bypass the proxy (includes private-network CIDRs). Top-level constant: the ProxyConfig primary constructor default cannot reference companion members. */
const val DEFAULT_NO_PROXY = "localhost,127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,::1"

/** Snapshot of the global HTTP/SOCKS5 proxy configuration. */
data class ProxyConfig(
    val enabled: Boolean = false,
    val type: ProxyType = ProxyType.HTTP,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /** List of hosts that bypass the proxy (comma-separated, exact host match). */
    val noProxy: String = DEFAULT_NO_PROXY
) {
    /** Whether the configuration is complete and usable: the proxy is actually used only when enabled with a non-blank host and a valid port. */
    val valid: Boolean
        get() = host.isNotBlank() && port in 1..65535
}

/**
 * Global proxy settings. Stored with SharedPreferences (not DataStore):
 * needs to support synchronous reads during Application.attachBaseContext (see [AppProxy]),
 * where there is no injection and no waiting for the async first frame.
 */
@Singleton
class ProxySettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(readConfig(prefs))
    val config: StateFlow<ProxyConfig> = _config.asStateFlow()

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }
    fun setType(type: ProxyType) = update { it.copy(type = type) }
    fun setHost(host: String) = update { it.copy(host = host.trim()) }
    fun setPort(port: Int) = update { it.copy(port = port) }
    fun setUsername(username: String) = update { it.copy(username = username) }
    fun setPassword(password: String) = update { it.copy(password = password) }
    fun setNoProxy(noProxy: String) = update { it.copy(noProxy = noProxy) }

    private fun update(transform: (ProxyConfig) -> ProxyConfig) {
        val next = transform(_config.value)
        prefs.edit {
            putBoolean(KEY_ENABLED, next.enabled)
            putString(KEY_TYPE, next.type.name)
            putString(KEY_HOST, next.host)
            putInt(KEY_PORT, next.port)
            putString(KEY_USERNAME, next.username)
            putString(KEY_PASSWORD, next.password)
            putString(KEY_NO_PROXY, next.noProxy)
        }
        _config.value = next
    }

    companion object {
        const val PREFS_NAME = "proxy_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_TYPE = "type"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_NO_PROXY = "no_proxy"

        /** Legacy default bypass list (no CIDR blocks): reading this value counts as uncustomized and migrates to the new default. */
        private const val LEGACY_DEFAULT_NO_PROXY = "localhost,127.0.0.1,::1"

        /** Reads the current configuration synchronously, for non-injected contexts (e.g. Application.attachBaseContext). */
        fun readConfig(context: Context): ProxyConfig =
            readConfig(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        private fun readConfig(prefs: android.content.SharedPreferences): ProxyConfig = ProxyConfig(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            type = runCatching { ProxyType.valueOf(prefs.getString(KEY_TYPE, ProxyType.HTTP.name)!!) }
                .getOrDefault(ProxyType.HTTP),
            host = prefs.getString(KEY_HOST, "") ?: "",
            port = prefs.getInt(KEY_PORT, 0),
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = prefs.getString(KEY_PASSWORD, "") ?: "",
            // The old default (no private-network CIDRs) migrates to the new default list so the input field directly shows the full bypass addresses.
            noProxy = (prefs.getString(KEY_NO_PROXY, null) ?: "")
                .takeUnless { it.isBlank() || it == LEGACY_DEFAULT_NO_PROXY }
                ?: DEFAULT_NO_PROXY
        )
    }
}
