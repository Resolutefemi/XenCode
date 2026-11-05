package com.xencode.feature.terminal.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xencode.core.util.FileLogger
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.roundToInt

/**
 * Virtual modifier key state driven by the extra key row (Esc/Ctrl/Alt etc.).
 *
 * [TerminalView] calls back `readControlKey()` and friends while dispatching keys/characters to read these flags,
 * enabling Ctrl/Alt combos (e.g. Ctrl-C) that a phone soft keyboard lacks.
 *
 * The flags are held in Compose observable state: the extra key row renders a "pressed" highlight from them so users can see whether
 * Ctrl/Alt is currently armed. All reads and writes happen on the UI thread (key dispatch, taps, and consume all run on the main thread), so this is safe.
 */
class TerminalKeyModifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)
    var shift by mutableStateOf(false)
    var fn by mutableStateOf(false)

    /** One-shot modifier keys: auto-reset after a character is emitted (matching the tap-once instinct of a physical keyboard). */
    fun consume() {
        ctrl = false
        alt = false
        shift = false
        fn = false
    }
}

/**
 * [TerminalSessionClient]: called back when the session emits events such as output/title/finished.
 * Its main duty is to hand screen refreshes over to [TerminalView] and bridge the system clipboard.
 */
class AppTerminalSessionClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?,
    private val onFinished: (TerminalSession) -> Unit,
    var cursorStyle: Int = 0
) : TerminalSessionClient {

    private companion object { const val TAG = "TerminalSession" }

    override fun onTextChanged(changedSession: TerminalSession) {
        viewProvider()?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) { /* Title not shown for now */ }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty()) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            session?.write(bytes, 0, bytes.size)
        }
    }

    override fun onBell(session: TerminalSession) { /* Ignore the bell */ }
    override fun onColorsChanged(session: TerminalSession) { viewProvider()?.onScreenUpdated() }
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = cursorStyle

    override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        FileLogger.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
}

/**
 * [TerminalViewClient]: view-layer callbacks for input/gestures/zoom.
 * Implemented with sensible defaults, delegating virtual modifier keys like Ctrl/Alt to [TerminalKeyModifiers].
 */
class AppTerminalViewClient(
    private val context: Context,
    private val viewProvider: () -> TerminalView?,
    private val modifiers: TerminalKeyModifiers,
    private val currentFontSizeSp: () -> Int,
    private val onFontSizeCommit: (Int) -> Unit
) : TerminalViewClient {

    private companion object {
        const val TAG = "TerminalView"
        const val MIN_FONT_SP = 5
        const val MAX_FONT_SP = 22
    }

    private var baseFontSizeSp = 0
    private var accumulatedScale = 1f
    private var lastFontSizeSp = 0

    override fun onScaleBegin() {
        baseFontSizeSp = currentFontSizeSp()
        accumulatedScale = 1f
        lastFontSizeSp = baseFontSizeSp
    }

    // Pinch: accumulate incremental multipliers into a target font size (clamped to 10-22), returning the equivalent preview scale for the view to zoom its canvas.
    // The font size is not changed here and the PTY is not resized (to avoid flooding the shell with SIGWINCH and garbling content);
    // the single resize at the final font size happens in onScaleEnd when the gesture ends.
    override fun onScale(scale: Float): Float {
        accumulatedScale *= scale
        val target = (baseFontSizeSp * accumulatedScale).roundToInt().coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        lastFontSizeSp = target
        return if (baseFontSizeSp > 0) target.toFloat() / baseFontSizeSp else 1f
    }

    override fun onScaleEnd() {
        if (lastFontSizeSp != baseFontSizeSp) onFontSizeCommit(lastFontSizeSp)
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        val view = viewProvider() ?: return
        view.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    // Returning false means the input field uses InputType.TYPE_NULL (the Termux upstream default). That way most IMEs like Gboard deliver
    // arrows/backspace etc. as real KeyEvents, which TerminalView can then map to cursor movement sequences; returning true would take the
    // "character input" path (VISIBLE_PASSWORD), where the IME swallows arrows as internal cursor movement so they never reach the terminal.
    override fun shouldEnforceCharBasedInput(): Boolean = false
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = modifiers.ctrl
    override fun readAltKey(): Boolean = modifiers.alt
    override fun readShiftKey(): Boolean = modifiers.shift
    override fun readFnKey(): Boolean = modifiers.fn

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        // Character already emitted; reset the one-shot modifier keys
        modifiers.consume()
        return false
    }

    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        FileLogger.e(tag ?: TAG, message ?: "", e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
}
