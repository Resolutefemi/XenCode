package com.xencode.feature.terminal.domain

import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Delegating layer for [TerminalSessionProvider]: holds both the local and remote implementations, and on each
 * method call forwards to the right one based on [ExecutionModeHolder.currentMode].
 *
 * That way Hilt injection timing no longer affects behavior — no matter when [TerminalSessionProvider] is first
 * injected, the current mode is only read when the terminal is actually used.
 */
@Singleton
class DelegatingTerminalSessionProvider @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val local: TerminalSessionManager,
    private val remote: RemoteTerminalSessionManager
) : TerminalSessionProvider {

    private fun delegate(): TerminalSessionProvider =
        if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) remote else local

    override val tabFinishedEvents: SharedFlow<TabFinishedEvent>
        get() = delegate().tabFinishedEvents

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?
    ): String = delegate().startBackgroundCommand(command, title, notify, sourceSessionId)

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean =
        delegate().sendInput(id, input, appendNewline)

    override fun writeToTab(id: String, text: String): Boolean =
        delegate().writeToTab(id, text)

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean =
        delegate().writeBytesToTab(id, *bytes)

    override fun getTabOutput(id: String): String? = delegate().getTabOutput(id)

    override fun listTabs(): List<TabInfo> = delegate().listTabs()

    override fun closeTab(id: String): Boolean = delegate().closeTab(id)
}
