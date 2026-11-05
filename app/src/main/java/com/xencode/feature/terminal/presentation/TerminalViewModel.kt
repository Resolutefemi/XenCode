package com.xencode.feature.terminal.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xencode.R
import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.ContainerInitState
import com.xencode.feature.agent.domain.container.LinuxContainerEngine
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import com.xencode.feature.terminal.domain.RemoteTerminalSessionManager
import com.xencode.feature.terminal.domain.TerminalSessionManager
import com.xencode.feature.terminal.presentation.component.TerminalKeyModifiers
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.xencode.feature.terminal.data.repository.TerminalSettings
import com.xencode.feature.terminal.data.repository.TerminalSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin observation layer for the terminal screen.
 *
 * Session ownership lives in [TerminalSessionManager] (a Singleton); this ViewModel merely forwards UI actions and exposes its state flows,
 * **it neither holds nor destroys any session** — the key to "staying alive in the background": even if leaving the terminal screen clears this VM via onCleared,
 * the session remains owned by the manager and keeps running in the background, ready for direct reuse when you return to the terminal screen.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val localManager: TerminalSessionManager,
    private val remoteManager: RemoteTerminalSessionManager,
    private val modeHolder: ExecutionModeHolder,
    private val containerEngine: LinuxContainerEngine,
    private val terminalSettingsRepository: TerminalSettingsRepository
) : ViewModel() {

    private companion object { const val TAG = "TerminalViewModel" }

    private fun isRemote() = modeHolder.currentMode() == ExecutionMode.REMOTE_SSH

    /** Whether we are currently in remote SSH mode (the UI hides local-container-only entry points accordingly, e.g. environment tools). */
    val isRemoteMode: Boolean get() = isRemote()

    /** Overall state of the container preparation phase: only used for Loading/Error hints before the first tab is created. */
    sealed interface PrepareState {
        data object Loading : PrepareState
        data object Ready : PrepareState
        data class Error(val message: String) : PrepareState
    }

    private val _prepareState = MutableStateFlow<PrepareState>(PrepareState.Loading)
    val prepareState: StateFlow<PrepareState> = _prepareState.asStateFlow()

    /** A new tab is being created: in SSH mode opening the shell channel must wait for the server; the UI shows a waiting animation and blocks repeat taps. */
    private val _creatingTab = MutableStateFlow(false)
    val creatingTab: StateFlow<Boolean> = _creatingTab.asStateFlow()

    /** Live container initialization progress (extraction/deployment/package install); shown as fine-grained text during the Loading phase. */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    val tabs get() = if (isRemote()) remoteManager.tabs else localManager.tabs
    val activeTabId get() = if (isRemote()) remoteManager.activeTabId else localManager.activeTabId
    val revision get() = if (isRemote()) remoteManager.revision else localManager.revision

    /** Virtual modifier keys driven by the extra key row, read by TerminalView. */
    val modifiers = TerminalKeyModifiers()

    val terminalSettings: StateFlow<TerminalSettings> = terminalSettingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TerminalSettings())

    fun setTheme(themeId: String) {
        viewModelScope.launch { terminalSettingsRepository.setThemeId(themeId) }
    }

    fun setFontSize(sizeSp: Int) {
        viewModelScope.launch { terminalSettingsRepository.setFontSizeSp(sizeSp) }
    }

    fun setCursorStyle(style: Int) {
        viewModelScope.launch { terminalSettingsRepository.setCursorStyle(style) }
    }

    fun setFontPath(path: String) {
        viewModelScope.launch { terminalSettingsRepository.setFontPath(path) }
    }

    init {
        prepare()
    }

    /** Entering the terminal screen: ensures at least one tab exists (the first run extracts the container or connects via SSH). */
    fun prepare() {
        viewModelScope.launch {
            _prepareState.value = PrepareState.Loading
            try {
                if (isRemote()) remoteManager.ensureInitialTab() else localManager.ensureInitialTab()
                _prepareState.value = PrepareState.Ready
            } catch (e: Exception) {
                FileLogger.e(TAG, "Terminal preparation failed", e)
                _prepareState.value = PrepareState.Error(e.message ?: context.getString(R.string.terminal_prepare_error_unknown))
            }
        }
    }

    fun newTab() {
        if (_creatingTab.value) return
        _creatingTab.value = true
        viewModelScope.launch {
            try {
                if (isRemote()) remoteManager.createInteractiveTab() else localManager.createInteractiveTab()
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to create new tab", e)
            } finally {
                _creatingTab.value = false
            }
        }
    }

    fun activate(id: String) = if (isRemote()) remoteManager.activate(id) else localManager.activate(id)

    fun closeTab(id: String) = if (isRemote()) remoteManager.closeTab(id) else localManager.closeTab(id)

    /** Closes all terminal tabs (called before switching workspaces). */
    fun closeAllTabs() {
        (if (isRemote()) remoteManager.tabs else localManager.tabs).value.map { it.id }.forEach { closeTab(it) }
    }

    /** Writes text to the currently active tab (extra key row: arrows/Tab etc.). */
    fun write(text: String) = if (isRemote()) remoteManager.writeToActive(text) else localManager.writeToActive(text)

    /** Writes raw bytes to the currently active tab (sending control characters, e.g. Ctrl-C=0x03, Ctrl-D=0x04). */
    fun writeBytes(vararg bytes: Int) = if (isRemote()) remoteManager.writeBytesToActive(*bytes) else localManager.writeBytesToActive(*bytes)

    // Note: sessions are deliberately not destroyed in onCleared — sessions are owned by the Singleton manager and must stay alive in the background.
}
