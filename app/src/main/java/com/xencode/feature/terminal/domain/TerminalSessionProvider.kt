package com.xencode.feature.terminal.domain

import kotlinx.coroutines.flow.SharedFlow

/**
 * Terminal session backend abstraction: decouples "where the terminal session runs" from the hard-coded local Termux PTY.
 *
 * [TerminalSessionTool] (the AI's terminal tool) depends on this interface instead of a concrete implementation,
 * and the DI delegating layer forwards to the right implementation per the current execution mode:
 * - [TerminalSessionManager]: local Termux PTY (forks a proot process);
 * - `RemoteTerminalSessionManager`: remote SSH shell channel.
 */
interface TerminalSessionProvider {

    /** Event emitted when a background command finishes, for the ViewModel to subscribe to and notify the AI. */
    val tabFinishedEvents: SharedFlow<TabFinishedEvent>

    /** Run a command in the background (e.g. `npm run dev`) and return a unique tabId. */
    suspend fun startBackgroundCommand(
        command: String,
        title: String? = null,
        notify: Boolean = false,
        sourceSessionId: String? = null
    ): String

    /** Send input to a tab by id and press enter to run it. Returns whether the tab was found and is still active. */
    fun sendInput(id: String, input: String, appendNewline: Boolean = true): Boolean

    /** Write raw text to a tab by id, without appending a newline. */
    fun writeToTab(id: String, text: String): Boolean

    /** Write raw bytes to a tab by id (control characters, e.g. Ctrl-C=0x03). */
    fun writeBytesToTab(id: String, vararg bytes: Int): Boolean

    /** Read terminal content by id (emulator screen buffer). Returns null when there is no such tab. */
    fun getTabOutput(id: String): String?

    /** List summaries of all tabs. */
    fun listTabs(): List<TabInfo>

    /** Close and destroy a tab. Returns whether it succeeded. */
    fun closeTab(id: String): Boolean
}
