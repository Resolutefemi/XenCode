package com.xencode.feature.terminal.domain

import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/**
 * Run state of a terminal tab. Finished stays in the list without removal, so the user/AI can review the output.
 */
sealed interface RunState {
    data object Running : RunState
    data class Finished(val exitCode: Int) : RunState
}

/** Number of terminal output lines carried by the background task finished notification. */
const val TAIL_LINES = 50

/** Take the last n lines of a terminal transcript; null input returns null. */
fun String?.takeTailLines(n: Int): String? =
    this?.lines()?.takeLast(n)?.joinToString("\n")

/**
 * Event emitted when a background command finishes, for the ViewModel to subscribe to and notify the AI.
 */
data class TabFinishedEvent(
    val tabId: String,
    val title: String,
    val command: String?,
    val exitCode: Int,
    /** Session id that started this background command; the callback routes back to that original session instead of the user's current one. */
    val sourceSessionId: String?,
    /** Snapshot of the last [TAIL_LINES] lines of the terminal transcript at completion. Events may be cached until the session is idle before being merged and sent, and the tab may already be closed by then, so it is captured up front at emit time. */
    val tailOutput: String? = null
)

/**
 * A terminal tab: session + render view + metadata.
 *
 * [view] is filled back by Compose after creating the [TerminalView]; switching tabs reuses the same session and remounts the view.
 * [client]'s viewProvider always reads [view], so no matter how the view is rebuilt, output is flushed to the currently mounted view.
 *
 * Local and remote terminal modes share this type — the only difference is the [com.termux.terminal.SessionBackend] behind [session]
 * (local SubprocessBackend forks a process, remote is an SSH shell stream).
 */
class TerminalTab(
    val id: String,
    title: String,
    val session: TerminalSession,
    val isBackground: Boolean,
    val command: String?,
    val notifyOnExit: Boolean = false,
    /** Session id that started this background command; null for interactive tabs. The callback routes back to that original session. */
    val sourceSessionId: String? = null,
    val client: com.termux.terminal.TerminalSessionClient? = null,
    runState: RunState
) {
    var title: String = title
        internal set

    @Volatile
    var view: TerminalView? = null

    var runState: RunState = runState
        internal set

    /**
     * Whether a finished event has already been sent to the AI. Both the fallback monitor and onFinished can trigger completion,
     * and this flag guarantees a single callback per command, avoiding duplicate notifications on late process exit.
     */
    @Volatile
    var finishedNotified: Boolean = false
}

/**
 * Terminal tab summary, used by the AI's terminal tool when listing tabs (carries no runtime objects like session/view).
 */
data class TabInfo(
    val id: String,
    val title: String,
    val isBackground: Boolean,
    val running: Boolean,
    val command: String?
)
