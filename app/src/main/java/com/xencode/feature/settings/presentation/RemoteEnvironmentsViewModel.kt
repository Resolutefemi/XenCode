package com.xencode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xencode.feature.agent.domain.tool.instabox.InstaboxClient
import com.xencode.feature.settings.data.repository.InstaboxConfig
import com.xencode.feature.settings.data.repository.RemoteEnvironmentSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class InstaboxTestUiState {
    data object Idle : InstaboxTestUiState()
    data object Running : InstaboxTestUiState()
    data class Success(val message: String) : InstaboxTestUiState()
    data class Failure(val message: String) : InstaboxTestUiState()
}

@HiltViewModel
class RemoteEnvironmentsViewModel @Inject constructor(
    private val repository: RemoteEnvironmentSettingsRepository,
    private val client: InstaboxClient
) : ViewModel() {

    val config: StateFlow<InstaboxConfig> = repository.instaboxConfigFlow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        InstaboxConfig()
    )

    private val _testState = MutableStateFlow<InstaboxTestUiState>(InstaboxTestUiState.Idle)
    val testState: StateFlow<InstaboxTestUiState> = _testState.asStateFlow()

    private val _saveNotice = MutableStateFlow<String?>(null)
    val saveNotice: StateFlow<String?> = _saveNotice.asStateFlow()

    fun save(config: InstaboxConfig) {
        viewModelScope.launch {
            repository.saveInstaboxConfig(config)
            _saveNotice.value = "saved"
        }
    }

    fun clearSaveNotice() {
        _saveNotice.value = null
    }

    fun testConnection(config: InstaboxConfig) {
        viewModelScope.launch {
            _testState.value = InstaboxTestUiState.Running
            val result = client.testConnection(config)
            _testState.value = result.fold(
                onSuccess = { InstaboxTestUiState.Success(it) },
                onFailure = { InstaboxTestUiState.Failure(it.message ?: "Connection failed") }
            )
        }
    }
}
