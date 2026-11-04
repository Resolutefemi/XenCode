package com.aicode.feature.network.presentation

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.network.domain.MAX_EDITOR_CHARS
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.UndoManager
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun JsonCodeEditor(
    value: String,
    onValueChange: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    languageScope: String = JSON_SCOPE
) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<CodeEditor?>(null) }
    val lastLocal = remember { mutableStateOf(value) }
    val currentOnValueChange = rememberUpdatedState(onValueChange)
    val currentLanguage = rememberUpdatedState(languageScope)
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val themeSurface = MaterialTheme.colorScheme.surface
    val nestedScroll = rememberNestedScrollInteropConnection()

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            TextMateSetup.ensureInitialized(context.applicationContext)
            runCatching { TextMateLanguage.create(JSON_SCOPE, false).destroy() }
            runCatching { TextMateLanguage.create(SHELL_SCOPE, false).destroy() }
        }
        ready = true
    }

    val changeHandler = remember {
        Handler(Looper.getMainLooper())
    }
    DisposableEffect(Unit) {
        onDispose { changeHandler.removeCallbacksAndMessages(null) }
    }

    if (!ready) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        return
    }

    AndroidView(
        modifier = modifier.nestedScroll(nestedScroll),
        factory = { ctx ->
            TextMateSetup.applyTheme(dark)
            UndoManager.setMergeTimeLimit(500L)
            CodeEditor(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isEditable = !readOnly
                typefaceText = Typeface.MONOSPACE
                setTextSize(13f)
                setWordwrap(false)
                isCursorAnimationEnabled = false
                setLineNumberMarginLeft(spToPx(ctx, 2f))
                verticalExtraSpaceFactor = 0.18f
                isBlockLineEnabled = true
                isNestedScrollingEnabled = true
                overScrollMode = View.OVER_SCROLL_NEVER
                isHorizontalScrollBarEnabled = true
                isVerticalScrollBarEnabled = true
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                applyThemedBackground(this, themeSurface)
                applyLineNumberBackground(this, dark)
                setEditorLanguage(safeLanguage(currentLanguage.value))
                setText(value.take(MAX_EDITOR_CHARS))
                lastLocal.value = value
                if (!readOnly) {
                    val editorView = this
                    subscribeAlways(ContentChangeEvent::class.java) {
                        val text = runCatching { editorView.text.toString() }.getOrDefault(lastLocal.value)
                        lastLocal.value = text
                        changeHandler.removeCallbacksAndMessages(null)
                        changeHandler.postDelayed({
                            currentOnValueChange.value?.invoke(text)
                        }, 48L)
                    }
                }
                editor = this
            }
        },
        update = { view ->
            view.isEditable = !readOnly
            view.isNestedScrollingEnabled = true
        },
        onRelease = {
            changeHandler.removeCallbacksAndMessages(null)
            it.clearFocus()
            editor = null
            it.release()
        }
    )

    LaunchedEffect(dark, themeSurface, editor) {
        val current = editor ?: return@LaunchedEffect
        TextMateSetup.applyTheme(dark)
        current.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        applyThemedBackground(current, themeSurface)
        applyLineNumberBackground(current, dark)
        current.invalidate()
    }

    LaunchedEffect(languageScope, editor) {
        val current = editor ?: return@LaunchedEffect
        current.setEditorLanguage(safeLanguage(languageScope))
    }

    LaunchedEffect(value, editor) {
        val current = editor ?: return@LaunchedEffect
        val editorText = runCatching { current.text.toString() }.getOrDefault(lastLocal.value)
        if (value != lastLocal.value && value != editorText) {
            current.setText(value.take(MAX_EDITOR_CHARS))
            lastLocal.value = value
        }
    }
}

private fun safeLanguage(scope: String): TextMateLanguage =
    runCatching { TextMateLanguage.create(scope, false) }
        .getOrElse { TextMateLanguage.create(JSON_SCOPE, false) }

private fun applyThemedBackground(editor: CodeEditor, themeSurface: Color) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    scheme.setColor(
        EditorColorScheme.WHOLE_BACKGROUND,
        lerp(base, themeSurface, 0.9f).toArgb()
    )
}

private fun applyLineNumberBackground(editor: CodeEditor, dark: Boolean) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    val target = if (dark) Color.White else Color.Black
    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, lerp(base, target, 0.08f).toArgb())
}

private fun spToPx(context: android.content.Context, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

internal const val JSON_SCOPE = "source.json"
internal const val SHELL_SCOPE = "source.shell"
