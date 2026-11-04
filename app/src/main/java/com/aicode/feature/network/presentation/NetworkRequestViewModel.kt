package com.aicode.feature.network.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.feature.network.domain.CurlParseResult
import com.aicode.feature.network.domain.DEFAULT_AUTH_JSON
import com.aicode.feature.network.domain.DEFAULT_JSON_OBJECT
import com.aicode.feature.network.domain.HttpMethod
import com.aicode.feature.network.domain.HttpRequestExecutor
import com.aicode.feature.network.domain.NetworkClientSettings
import com.aicode.feature.network.domain.NetworkHistoryEntry
import com.aicode.feature.network.domain.NetworkHistoryStore
import com.aicode.feature.network.domain.NetworkSendState
import com.aicode.feature.network.domain.exportCurlCommand
import com.aicode.feature.network.domain.normalizeRequestUrl
import com.aicode.feature.network.domain.parseCurlCommand
import com.aicode.feature.network.domain.parseTimeoutSeconds
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class NetworkRequestUiState(
    val url: String = "",
    val method: HttpMethod = HttpMethod.GET,
    val queryJson: String = DEFAULT_JSON_OBJECT,
    val headersJson: String = DEFAULT_JSON_OBJECT,
    val bodyJson: String = DEFAULT_JSON_OBJECT,
    val authJson: String = DEFAULT_AUTH_JSON,
    val settings: NetworkClientSettings = NetworkClientSettings(),
    val sendState: NetworkSendState = NetworkSendState.Idle,
    val history: List<NetworkHistoryEntry> = emptyList(),
    val selectedHistoryId: String? = null
)

@HiltViewModel
class NetworkRequestViewModel @Inject constructor(
    private val executor: HttpRequestExecutor,
    private val historyStore: NetworkHistoryStore,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(NetworkRequestUiState())
    val state: StateFlow<NetworkRequestUiState> = _state.asStateFlow()

    init {
        refreshHistory()
    }

    fun updateUrl(url: String) {
        _state.update { it.copy(url = url) }
    }

    fun selectMethod(method: HttpMethod) {
        _state.update { it.copy(method = method) }
    }

    fun updateQueryJson(value: String) {
        _state.update { it.copy(queryJson = value) }
    }

    fun updateHeadersJson(value: String) {
        _state.update { it.copy(headersJson = value) }
    }

    fun updateBodyJson(value: String) {
        _state.update { it.copy(bodyJson = value) }
    }

    fun updateAuthJson(value: String) {
        _state.update { it.copy(authJson = value) }
    }

    fun updateFollowRedirects(enabled: Boolean) {
        _state.update { it.copy(settings = it.settings.copy(followRedirects = enabled)) }
    }

    fun updateSslVerify(enabled: Boolean) {
        _state.update { it.copy(settings = it.settings.copy(sslVerify = enabled)) }
    }

    fun updateTimeout(raw: String) {
        _state.update {
            it.copy(settings = it.settings.copy(timeoutSeconds = parseTimeoutSeconds(raw)))
        }
    }

    fun clear() {
        val history = _state.value.history
        _state.value = NetworkRequestUiState(history = history)
    }

    fun loadHistory(entry: NetworkHistoryEntry) {
        val method = runCatching { HttpMethod.valueOf(entry.method) }.getOrDefault(HttpMethod.GET)
        _state.update {
            it.copy(
                url = entry.url,
                method = method,
                queryJson = entry.queryJson.ifBlank { DEFAULT_JSON_OBJECT },
                headersJson = entry.headersJson.ifBlank { DEFAULT_JSON_OBJECT },
                bodyJson = entry.bodyJson.ifBlank { DEFAULT_JSON_OBJECT },
                authJson = entry.authJson.ifBlank { DEFAULT_AUTH_JSON },
                settings = NetworkClientSettings(
                    followRedirects = entry.followRedirects,
                    sslVerify = entry.sslVerify,
                    timeoutSeconds = entry.timeoutSeconds
                ),
                selectedHistoryId = entry.id,
                sendState = NetworkSendState.Idle
            )
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            val items = withContext(Dispatchers.IO) { historyStore.list() }
            _state.update { it.copy(history = items) }
        }
    }

    fun importCurl(raw: String): Boolean {
        return when (val result = parseCurlCommand(raw)) {
            is CurlParseResult.Success -> {
                val parsed = result.parsed
                _state.update {
                    it.copy(
                        url = parsed.url,
                        method = parsed.method,
                        queryJson = parsed.queryJson.ifBlank { DEFAULT_JSON_OBJECT },
                        headersJson = parsed.headersJson.ifBlank { DEFAULT_JSON_OBJECT },
                        bodyJson = parsed.bodyJson.ifBlank { DEFAULT_JSON_OBJECT },
                        authJson = parsed.authJson.ifBlank { DEFAULT_AUTH_JSON },
                        settings = it.settings.copy(
                            followRedirects = parsed.followRedirects,
                            sslVerify = parsed.sslVerify
                        ),
                        sendState = NetworkSendState.Idle
                    )
                }
                true
            }
            is CurlParseResult.Failure -> false
        }
    }

    fun exportCurl(): String {
        val current = _state.value
        return exportCurlCommand(
            method = current.method,
            url = current.url,
            queryJson = current.queryJson,
            headersJson = current.headersJson,
            bodyJson = current.bodyJson,
            authJson = current.authJson,
            settings = current.settings
        )
    }

    fun send() {
        val current = _state.value
        val url = normalizeRequestUrl(current.url)
        if (url.isBlank()) {
            _state.update {
                it.copy(sendState = NetworkSendState.Failure(context.getString(R.string.network_request_url_empty)))
            }
            return
        }
        _state.update { it.copy(url = url, sendState = NetworkSendState.Sending) }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                historyStore.saveSnapshot(
                    method = current.method,
                    url = url,
                    queryJson = current.queryJson,
                    headersJson = current.headersJson,
                    bodyJson = current.bodyJson,
                    authJson = current.authJson,
                    settings = current.settings
                )
            }
            val result = withContext(Dispatchers.IO) {
                executor.execute(
                    method = current.method,
                    url = url,
                    queryJson = current.queryJson,
                    headersJson = current.headersJson,
                    bodyJson = current.bodyJson,
                    authJson = current.authJson,
                    settings = current.settings
                )
            }
            val history = withContext(Dispatchers.IO) { historyStore.list() }
            _state.update {
                it.copy(
                    sendState = result,
                    history = history,
                    selectedHistoryId = saved.id
                )
            }
        }
    }
}
