package com.xencode.feature.credentials.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xencode.R
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.RemoteSshConnection
import com.xencode.feature.credentials.data.CredentialInjectSettingsRepository
import com.xencode.feature.credentials.domain.model.GitCredential
import com.xencode.feature.credentials.domain.repository.CredentialRepository
import com.xencode.feature.git.domain.GitRepository
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Credentials page UI orchestration: credential CRUD + commit identity (user.name/email) configuration.
 *
 * Commit identity reads/writes go through `git config` ([GitRepository.setUserIdentity] / [getUserName] / [getUserEmail]):
 * **project-level first** (the current workspace's ~/workspace/.git/config), falling back to global (the persistently mounted /root/.xencode/.gitconfig).
 * The UI and a terminal `git config user.name` read the same identity — project-level first, global fallback, no two competing sources.
 */
@HiltViewModel
class CredentialViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val gitRepository: GitRepository,
    private val injectSettings: CredentialInjectSettingsRepository,
    private val remoteSshConnection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private companion object { const val TAG = "CredentialViewModel" }

    data class UiState(
        val credentials: List<GitCredential> = emptyList(),
        /** The container's git config user.name, used as the edit field's initial value. */
        val userName: String = "",
        /** The container's git config user.email, used as the edit field's initial value. */
        val userEmail: String = "",
        /** The container's actual `git config --global` user.name, echoed back as the "git actual value" (same source as userName). */
        val globalUserName: String = "",
        /** The container's git config remote.origin.url, used as the repo URL edit field's initial value. */
        val repoUrl: String = "",
        /** Remote mode's "auto-inject credentials into the remote server" toggle state (remembered per server). */
        val autoInjectEnabled: Boolean = false,
        /** Remote mode: whether this server is entered for the first time (never asked); the UI shows a confirmation dialog accordingly. */
        val showAutoInjectPrompt: Boolean = false,
        /** Whether currently in remote mode (decides the toggle's visibility). */
        val isRemote: Boolean = false,
        val toast: String? = null
    )

    // State from non-reactive sources: identity and repo URL read from git config, the injection toggle, toast. credentials flow from the file repository's Flow.
    private val _extra = MutableStateFlow(Extra())
    private data class Extra(
        val userName: String = "",
        val userEmail: String = "",
        val globalUserName: String = "",
        val repoUrl: String = "",
        val autoInjectEnabled: Boolean = false,
        val showAutoInjectPrompt: Boolean = false,
        val isRemote: Boolean = false,
        val toast: String? = null
    )

    val state: StateFlow<UiState> = combine(credentialRepository.getAll(), _extra) { creds, extra ->
        UiState(
            credentials = creds,
            userName = extra.userName,
            userEmail = extra.userEmail,
            globalUserName = extra.globalUserName,
            repoUrl = extra.repoUrl,
            autoInjectEnabled = extra.autoInjectEnabled,
            showAutoInjectPrompt = extra.showAutoInjectPrompt,
            isRemote = extra.isRemote,
            toast = extra.toast
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        refreshIdentity()
        refreshAutoInjectState()
    }

    /** Reads the current identity and repo URL from the container's git config to refresh the UI (edit field initial values + actual-value echo). Reentrant; called once on entering the credentials page to catch terminal edits. */
    fun refreshIdentity() {
        viewModelScope.launch {
            runCatching {
                _extra.update {
                    it.copy(
                        userName = gitRepository.getUserName(),
                        userEmail = gitRepository.getUserEmail(),
                        globalUserName = gitRepository.getUserName(),
                        repoUrl = gitRepository.getRepoUrl()
                    )
                }
            }.onFailure { FileLogger.w(TAG, "Failed to read the git global identity: ${it.message}") }
        }
    }

    fun saveCredential(credential: GitCredential) {
        viewModelScope.launch {
            try {
                credentialRepository.save(credential)
                syncInjectionIfEnabled() // After a credential change, re-sync to remote servers with injection enabled
                toast(context.getString(R.string.credential_toast_saved))
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to save credential", e)
                toast(context.getString(R.string.credential_toast_save_failed, e.message))
            }
        }
    }

    fun deleteCredential(id: String) {
        viewModelScope.launch {
            credentialRepository.delete(id)
            syncInjectionIfEnabled() // After deleting a credential, remove it from the remote injection file, or the remote could keep using the old credential
            toast(context.getString(R.string.credential_toast_deleted))
        }
    }

    // ---------- Remote auto-inject toggle ----------

    /** Current remote server identity (host:port hash); null in local mode / disconnected. */
    private fun currentServerKey(): String? {
        val cfg = remoteSshConnection.config ?: return null
        return injectSettings.serverKey(cfg.host, cfg.port)
    }

    /** Refreshes the toggle state and "first-time ask" for the current server. Called on entering the credentials page and on connection changes. */
    fun refreshAutoInjectState() {
        val key = currentServerKey()
        _extra.update {
            it.copy(
                isRemote = key != null,
                autoInjectEnabled = key?.let { k -> injectSettings.isAutoInjectEnabled(k) } ?: false,
                showAutoInjectPrompt = key?.let { k -> !injectSettings.hasAsked(k) } ?: false
            )
        }
    }

    /** User toggled the switch: persist + immediately inject/revoke. */
    fun setAutoInject(enabled: Boolean) {
        viewModelScope.launch {
            val key = currentServerKey() ?: return@launch
            injectSettings.setAutoInject(key, enabled)
            applyInjection(key, enabled)
            refreshAutoInjectState()
        }
    }

    /** Called after the first-ask dialog choice: mark as asked + apply the choice. */
    fun dismissAutoInjectPrompt(enabled: Boolean) {
        viewModelScope.launch {
            val key = currentServerKey() ?: return@launch
            injectSettings.markAsked(key)
            injectSettings.setAutoInject(key, enabled)
            applyInjection(key, enabled)
            refreshAutoInjectState()
        }
    }

    /** When enabled, uploads the remote injection config (credentials + includeIf-scoped gitconfig); when disabled, revokes it. */
    private suspend fun applyInjection(key: String, enabled: Boolean) {
        if (!enabled) {
            remoteSshConnection.removeGitCredentialConfig()
            return
        }
        val creds = credentialRepository.getAll().first()
        val workspaceRoot = workspaceRepository.currentPath()
            .takeIf { it.isNotBlank() && it != "/" }
            ?: remoteSshConnection.config?.remoteWorkspacePath.orEmpty()
        remoteSshConnection.uploadGitCredentialConfig(creds, workspaceRoot)
    }

    /** After credential add/delete, re-sync to the remote if injection is enabled for the current server. */
    private suspend fun syncInjectionIfEnabled() {
        val key = currentServerKey() ?: return
        if (injectSettings.isAutoInjectEnabled(key)) applyInjection(key, true)
    }

    /** Saves the commit identity and repo URL: runs `git config` to write the source of truth for each key + refreshes the echo. The UI and the command line share one file. */
    fun saveUserIdentity(name: String, email: String, repoUrl: String) {
        viewModelScope.launch {
            try {
                gitRepository.setUserIdentity(name, email)
                gitRepository.setRepoUrl(repoUrl)
                refreshIdentity()
                toast(context.getString(R.string.credential_toast_identity_saved))
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to save identity", e)
                toast(context.getString(R.string.credential_toast_save_failed, e.message))
            }
        }
    }

    fun consumeToast() = _extra.update { it.copy(toast = null) }

    private fun toast(msg: String) = _extra.update { it.copy(toast = msg) }
}
