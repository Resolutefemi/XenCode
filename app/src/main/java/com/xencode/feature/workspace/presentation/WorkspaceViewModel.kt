package com.xencode.feature.workspace.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import com.xencode.feature.workspace.domain.model.Workspace
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder
) : ViewModel() {

    val workspaces: StateFlow<List<Workspace>> = repository.workspaces
    val current: StateFlow<Workspace?> = repository.current

    /** Local execution mode (not remote SSH): only in local mode can external local directory workspaces be added. */
    val isLocalMode: StateFlow<Boolean> = executionModeHolder.mode
        .map { it != ExecutionMode.REMOTE_SSH }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Message for remote workspace initialization failure (root path/default workspace creation failed). */
    val initError: StateFlow<String?> = repository.initError

    /** Message for adding an external local workspace failing (directory not writable/unresolvable etc.). */
    val addError: StateFlow<String?> = repository.addError
    val externalWarningDismissed: StateFlow<Boolean> = repository.externalWarningDismissed
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether removing an external local workspace also deletes its chat history (used by the delete-confirmation message). */
    val deleteExternalWorkspaceSessions: StateFlow<Boolean> = repository.deleteExternalWorkspaceSessionsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Consume the initialization error message, preventing duplicate toasts. */
    fun consumeInitError() {
        repository.consumeInitError()
    }

    /** Consume the add-external-workspace failure message, preventing duplicate toasts. */
    fun consumeAddError() {
        repository.consumeAddError()
    }

    init {
        viewModelScope.launch { runCatching { repository.initialize() } }
        // After a mode switch, reload the workspace list (local File.listFiles <-> remote SFTP ls).
        // drop(1) skips the first frame (init already called initialize), reacting only to later switches.
        viewModelScope.launch {
            executionModeHolder.mode.drop(1).distinctUntilChanged().collect {
                runCatching { repository.initialize() }
            }
        }
    }

    fun selectWorkspace(name: String) = viewModelScope.launch {
        runCatching { repository.selectWorkspace(name) }
    }

    fun createWorkspace(name: String, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.createWorkspace(name) }.getOrNull()
        onResult(ws)
    }

    /** Refresh external workspace availability before opening the panel (keeps the list and current selection up to date after storage changes). */
    fun refreshAvailability() = viewModelScope.launch {
        runCatching { repository.refreshAvailability() }
    }

    fun setExternalWarningDismissed(dismissed: Boolean) = viewModelScope.launch {
        runCatching { repository.setExternalWarningDismissed(dismissed) }
    }

    /** Registers the user-chosen device directory as an external local workspace without auto-switching; switching is done by the caller via the confirmation flow. */
    fun addExternalWorkspace(uri: Uri, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.addExternalWorkspace(uri) }.getOrNull()
        onResult(ws)
    }

    fun deleteWorkspace(name: String) = viewModelScope.launch {
        runCatching { repository.deleteWorkspace(name) }
    }
}
