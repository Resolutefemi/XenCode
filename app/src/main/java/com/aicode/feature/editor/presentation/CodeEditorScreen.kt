package com.aicode.feature.editor.presentation

import android.graphics.Typeface
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DismissibleDrawerSheet
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.drawerWidth
import com.aicode.feature.editor.data.EditorSettings
import com.aicode.feature.editor.domain.TextMateSetup
import com.aicode.feature.editor.presentation.component.EditorFilesDrawer
import com.aicode.feature.editor.presentation.component.MarkdownPreviewWebView
import com.aicode.feature.editor.presentation.component.XmlPreviewPane
import com.aicode.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Code
import compose.icons.feathericons.Eye
import compose.icons.feathericons.Menu
import compose.icons.feathericons.Save
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.X
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.ScrollEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.text.UndoManager
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Full-screen code editor with syntax highlight, undo/redo, a symbol bar, save and a standard
 * navigation drawer for the project file tree. Picking a file in the drawer swaps the content
 * directly — there is no tab bar. Save overwrites in place; there is no external
 * concurrent-write check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    path: String,
    onBack: () -> Unit,
    initialLine: Int = 0,
    embedded: Boolean = false,
    fileTreeHost: EditorFileTreeHost? = null,
    viewModel: CodeEditorViewModel = hiltViewModel()
) {
    val activePath = path
    LaunchedEffect(activePath) { viewModel.load(activePath) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dirtyPaths by viewModel.dirtyPaths.collectAsStateWithLifecycle()

    val editorRef = remember { mutableStateOf<CodeEditor?>(null) }
    // Document the editor in [editorRef] currently belongs to. Set by the surface factory,
    // cleared on release; save/snapshot code must not assume it matches [activePath] — while a
    // document switch is settling, the view may still be the previous document's.
    val boundPath = remember { mutableStateOf<String?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val baselineText = remember { mutableStateOf("") }
    var pendingSaveText by remember { mutableStateOf("") }
    var pendingExit by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var previewMode by remember { mutableStateOf(false) }
    val isMarkdown = remember(activePath) {
        activePath.substringAfterLast('.', "").lowercase() in setOf("md", "markdown")
    }
    val isXml = remember(activePath) {
        activePath.substringAfterLast('.', "").lowercase() == "xml"
    }
    var editorBackground by remember { mutableStateOf<Color?>(null) }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Chat deep-link jump target. Latched per (path, line) so re-navigation never replays it.
    var jumpLine by remember { mutableIntStateOf(0) }
    var lastJumpArgs by remember { mutableStateOf<Pair<String, Int>?>(null) }

    LaunchedEffect(activePath) {
        canUndo = false
        canRedo = false
        dirty = activePath in dirtyPaths
        pendingExit = false
        jumpLine = 0
        previewMode = false
    }

    LaunchedEffect(path, initialLine, activePath) {
        if (initialLine > 0 && activePath == path && lastJumpArgs != path to initialLine) {
            lastJumpArgs = path to initialLine
            jumpLine = initialLine
        }
    }

    val context = LocalContext.current
    val savedText = stringResource(R.string.editor_save_success)
    val saveFailedText = stringResource(R.string.editor_save_failed)
    LaunchedEffect(Unit) {
        viewModel.saveEvents.collect { result ->
            when (result) {
                is SaveResult.Success -> {
                    baselineText.value = pendingSaveText
                    dirty = false
                    Toast.makeText(context, savedText, Toast.LENGTH_SHORT).show()
                    if (pendingExit) {
                        pendingExit = false
                        onBack()
                    }
                }
                is SaveResult.Error -> {
                    pendingExit = false
                    Toast.makeText(
                        context,
                        result.detail ?: saveFailedText,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun snapshotActive() {
        val editor = editorRef.value ?: return
        // Route by the editor's own document, never by activePath: at dispose time after a
        // document switch the dying view may outlive the activePath change, and guessing the
        // path would write one document's text into the other's draft.
        val path = boundPath.value ?: return
        viewModel.captureDraft(path, editor.text.toString())
    }

    fun requestSave() {
        // Use the live view only when it truly belongs to the active document; otherwise fall
        // back to the draft captured earlier (draft or disk content). Either way the saved text
        // always comes from the active document's buffer, never from a stale editor.
        val live = editorRef.value
        val text = if (live != null && boundPath.value == activePath) {
            live.text.toString()
        } else {
            viewModel.draftOf(activePath) ?: return
        }
        pendingSaveText = text
        viewModel.save(text, activePath)
        if (live != null && boundPath.value == activePath) {
            viewModel.captureDraft(activePath, text)
        }
    }

    fun handleBack() {
        snapshotActive()
        if (dirty) {
            showUnsavedDialog = true
        } else onBack()
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    BackHandler(enabled = !showSettings && !previewMode && !drawerState.isOpen) { handleBack() }
    BackHandler(enabled = showSettings) { showSettings = false }

    val drawerContent: @Composable () -> Unit = {
        if (fileTreeHost != null) {
            DismissibleDrawerSheet(
                drawerContainerColor = settingsPageBackground(),
                drawerTonalElevation = 0.dp,
                modifier = Modifier
                    .width(drawerWidth())
                    .fillMaxHeight()
            ) {
                EditorFilesDrawer(
                    browseState = fileTreeHost.browseState,
                    expandedPaths = fileTreeHost.expandedPaths,
                    expandingPath = fileTreeHost.expandingPath,
                    fileOpPaths = fileTreeHost.fileOpPaths,
                    clipboard = fileTreeHost.clipboard,
                    onToggleExpand = fileTreeHost.onToggleExpand,
                    onOpenFile = { filePath ->
                        // The picked file simply replaces the current content. The buffer cache
                        // in the view model re-serves the unsaved draft when returning to a
                        // previously opened file in this session.
                        snapshotActive()
                        fileTreeHost.onOpenFile(filePath)
                        scope.launch { drawerState.close() }
                    },
                    onRefreshBrowse = fileTreeHost.onRefreshBrowse,
                    onCreateFile = fileTreeHost.onCreateFile,
                    onCreateFolder = fileTreeHost.onCreateFolder,
                    onRenameEntry = fileTreeHost.onRenameEntry,
                    onDeleteEntry = fileTreeHost.onDeleteEntry,
                    onCopyEntry = fileTreeHost.onCopyEntry,
                    onCutEntry = fileTreeHost.onCutEntry,
                    onPasteEntry = fileTreeHost.onPasteEntry,
                    onClearClipboard = fileTreeHost.onClearClipboard,
                    onImportFiles = fileTreeHost.onImportFiles,
                    onImportFolder = fileTreeHost.onImportFolder,
                    onCopyPath = fileTreeHost.onCopyPath
                )
            }
        }
    }

    val editorBody: @Composable () -> Unit = {
        Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Column {
                    TopAppBar(
                        title = {},
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        ),
                    navigationIcon = {
                        if (embedded) {
                            // Wide-screen workbench pane closes with an X, matching the other
                            // pane contents; the file tree still lives in the chat sidebar.
                            IconButton(onClick = { handleBack() }) {
                                Icon(
                                    FeatherIcons.X,
                                    contentDescription = stringResource(R.string.common_close)
                                )
                            }
                        } else {
                            // Fullscreen route: opens the project files drawer. While the drawer
                            // is open the icon morphs into an X (quarter-turn rotation plus
                            // crossfade, Material motion) and tapping it closes the drawer again.
                            IconButton(onClick = {
                                scope.launch {
                                    if (drawerState.targetValue == DrawerValue.Open) {
                                        drawerState.close()
                                    } else {
                                        drawerState.open()
                                    }
                                }
                            }) {
                                val drawerOpen = drawerState.targetValue == DrawerValue.Open
                                val rotation by animateFloatAsState(
                                    targetValue = if (drawerOpen) DRAWER_ICON_ROTATE_ANGLE else 0f,
                                    animationSpec = tween(DRAWER_ICON_MORPH_MS),
                                    label = "drawer-icon-rotation"
                                )
                                Crossfade(
                                    // targetValue flips the moment open()/close() is called, so
                                    // the morph starts with the drawer motion, not after settling.
                                    targetState = drawerOpen,
                                    animationSpec = tween(DRAWER_ICON_FADE_MS),
                                    label = "drawer-icon-swap"
                                ) { open ->
                                    Icon(
                                        if (open) FeatherIcons.X else FeatherIcons.Menu,
                                        contentDescription = stringResource(
                                            if (open) {
                                                R.string.common_close
                                            } else {
                                                R.string.editor_open_files_drawer
                                            }
                                        ),
                                        modifier = Modifier.graphicsLayer { rotationZ = rotation }
                                    )
                                }
                            }
                        }
                    },
                        actions = {
                            val editable = state is EditorUiState.Success
                            if (isXml && editable) {
                                // Layout render preview: swaps the editor body for a phone-framed
                                // snapshot of the current text (see XmlPreviewPane below).
                                IconButton(onClick = { previewMode = !previewMode }) {
                                    Icon(
                                        if (previewMode) FeatherIcons.Code else FeatherIcons.Smartphone,
                                        contentDescription = stringResource(R.string.editor_xml_preview)
                                    )
                                }
                            }
                            if (isMarkdown && editable) {
                                IconButton(onClick = { previewMode = !previewMode }) {
                                    Icon(
                                        if (previewMode) FeatherIcons.Code else FeatherIcons.Eye,
                                        contentDescription = stringResource(
                                            if (previewMode) R.string.editor_md_show_source
                                            else R.string.editor_md_preview
                                        )
                                    )
                                }
                            }
                            IconButton(
                                onClick = { editorRef.value?.undo() },
                                enabled = editable && canUndo
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Undo,
                                    contentDescription = stringResource(R.string.editor_undo)
                                )
                            }
                            IconButton(
                                onClick = { editorRef.value?.redo() },
                                enabled = editable && canRedo
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Redo,
                                    contentDescription = stringResource(R.string.editor_redo)
                                )
                            }
                            IconButton(
                                onClick = { requestSave() },
                                enabled = editable && dirty && !saving
                            ) {
                                if (saving) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        FeatherIcons.Save,
                                        contentDescription = stringResource(R.string.common_save)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showSettings = true },
                                enabled = editable
                            ) {
                                Icon(
                                    FeatherIcons.Settings,
                                    contentDescription = stringResource(R.string.editor_settings)
                                )
                            }
                        }
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                }
            },
            bottomBar = {
                if (state is EditorUiState.Success && !previewMode) {
                    EditorSymbolBar(
                        backgroundColor = editorBackground ?: MaterialTheme.colorScheme.background,
                        onInsert = { editorRef.value?.commitText(it) },
                        onIndent = { editorRef.value?.commitText("\t") },
                        onMoveLeft = { editorRef.value?.let { moveCursorHorizontally(it, forward = false) } },
                        onMoveRight = { editorRef.value?.let { moveCursorHorizontally(it, forward = true) } }
                    )
                }
            }
        ) { padding ->
            val content = Modifier
                .fillMaxSize()
                .padding(padding)
            when (val s = state) {
                is EditorUiState.Loading -> CenterBox(content) { CircularProgressIndicator() }
                is EditorUiState.Success if s.path == activePath -> Box(modifier = content) {
                    // Recreate the editor view per document (switch or reload); picking another
                    // file swaps content, so the view keyed by the document identity is rebuilt
                    // intentionally. Hash + length keep the per-frame key comparison O(1).
                    key(s.path, s.contentHash, s.content.length) {
                        EditorSurface(
                            state = s,
                            path = activePath,
                            modifier = Modifier.fillMaxSize(),
                            editorRef = editorRef,
                            boundPath = boundPath,
                            settings = settings,
                            baselineText = baselineText,
                            initialLine = jumpLine,
                            onBackgroundResolved = { editorBackground = it },
                            onContentChanged = { undo, redo, changed ->
                                canUndo = undo
                                canRedo = redo
                                dirty = changed
                                // O(1) dirty flag only; stringify/copy happens once at document
                                // switch or dispose (snapshotActive), never per keystroke.
                                viewModel.markDirty(activePath, changed)
                            },
                            onSnapshot = { snapshotActive() }
                        )
                    }
                    if (previewMode) {
                        val previewText = remember(previewMode) {
                            editorRef.value?.text?.toString() ?: s.content
                        }
                        if (isXml) {
                            // Layout snapshot from the live editor text; refresh re-reads the editor.
                            XmlPreviewPane(
                                path = activePath,
                                editorTextProvider = { editorRef.value?.text?.toString() ?: s.content },
                                viewModel = viewModel,
                                onExitPreview = { previewMode = false },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            )
                        } else {
                            MarkdownPreviewWebView(
                                text = previewText,
                                path = activePath,
                                onExitPreview = { previewMode = false },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            )
                        }
                    }
                }
                // Success for a document that is not the active tab: the new file's read has
                // not completed yet. Keep the old text off the screen — binding it under the
                // new path would flash the wrong content and taint the new tab's draft.
                is EditorUiState.Success -> CenterBox(content) { CircularProgressIndicator() }
                is EditorUiState.TooLarge -> CenterBox(content) {
                    HintText(
                        stringResource(
                            R.string.editor_file_too_large,
                            s.sizeBytes / 1024 / 1024
                        )
                    )
                }
                is EditorUiState.Binary -> CenterBox(content) {
                    HintText(stringResource(R.string.editor_binary_unsupported))
                }
                is EditorUiState.Error -> CenterBox(content) {
                    HintText(s.detail ?: stringResource(R.string.editor_load_failed))
                }
            }
        }

        if (showUnsavedDialog) {
            AlertDialog(
                onDismissRequest = { showUnsavedDialog = false },
                title = { Text(stringResource(R.string.editor_unsaved_title)) },
                text = { Text(stringResource(R.string.editor_unsaved_message)) },
                confirmButton = {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        pendingExit = true
                        requestSave()
                    }) { Text(stringResource(R.string.editor_save_and_exit)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showUnsavedDialog = false
                        onBack()
                    }) { Text(stringResource(R.string.editor_dont_save)) }
                }
            )
        }

        fileTreeHost?.pasteConflict?.let { (_, targetPath) ->
            AlertDialog(
                onDismissRequest = fileTreeHost.onCancelPasteOverwrite,
                title = { Text(stringResource(R.string.file_browser_paste_conflict_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.file_browser_paste_conflict_message,
                            targetPath.substringAfterLast('/')
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = fileTreeHost.onPasteOverwrite) {
                        Text(stringResource(R.string.common_overwrite))
                    }
                },
                dismissButton = {
                    TextButton(onClick = fileTreeHost.onCancelPasteOverwrite) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }

        if (showSettings) {
            EditorSettingsScreen(onBack = { showSettings = false })
        }
        }
    }

    if (fileTreeHost != null) {
        DismissibleNavigationDrawer(
            drawerState = drawerState,
            // Hamburger-only opening: edge swipes fight with code editing gestures and stall the
            // editor. Closing still works via system back (see the BackHandlers above).
            gesturesEnabled = false,
            drawerContent = drawerContent
        ) {
            editorBody()
        }
    } else {
        editorBody()
    }
}

/**
 * One editor document surface.
 *
 * [initialLine] is a chat deep-link target (center-jump, 1-based); without it the file simply
 * opens at the top. The factory only runs once per document because the call site keys this
 * composable by the document identity.
 */
@Composable
private fun EditorSurface(
    state: EditorUiState.Success,
    path: String,
    modifier: Modifier,
    editorRef: MutableState<CodeEditor?>,
    boundPath: MutableState<String?>,
    settings: EditorSettings,
    baselineText: MutableState<String>,
    initialLine: Int = 0,
    onBackgroundResolved: (Color) -> Unit,
    onContentChanged: (canUndo: Boolean, canRedo: Boolean, dirty: Boolean) -> Unit,
    onSnapshot: () -> Unit
) {
    // Match the rendered app theme, not the system setting; the theme is switchable in-app.
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val themeSurface = MaterialTheme.colorScheme.surface
    // sora parses syntax on a background thread, so the first frame is never highlighted. A short
    // fade-in turns the plain-text-then-sudden-colors flash into a smooth reveal. Keys use the
    // content hash: full-string equality per frame would stall large files.
    var revealed by remember(state.contentHash) { mutableStateOf(false) }
    LaunchedEffect(state.contentHash) { revealed = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (revealed) 1f else 0f,
        animationSpec = tween(durationMillis = HIGHLIGHT_REVEAL_MS),
        label = "editor-reveal"
    )

    // Once the user has scrolled by themselves, no more automatic positioning. The view is created
    // once, so the flag lives in an instance-stable State (the factory closure captures the first
    // instance); document switches reset it via the effect below.
    val scrolledByUser = remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.graphicsLayer { alpha = contentAlpha },
        factory = { ctx ->
            TextMateSetup.applyTheme(dark)
            // sora merges consecutive input within its window into a single undo record, one undo
            // would swallow a whole paragraph; keep the window short for discrete undo steps.
            UndoManager.setMergeTimeLimit(UNDO_MERGE_WINDOW_MS)
            CodeEditor(ctx).apply {
                // sora's getScrollMaxY() skips verticalExtraSpaceFactor when LayoutParams is null
                // or WRAP_CONTENT, losing the bottom overscroll room set below. AndroidView adds
                // child views with the default WRAP_CONTENT, so set MATCH_PARENT explicitly.
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isEditable = true
                typefaceText = Typeface.MONOSPACE
                setTextSize(settings.fontSizeSp.toFloat())
                setWordwrap(settings.wordWrap)
                // Disable the cursor move animation: the current-line highlight jumps in place
                // instead of sliding line by line.
                isCursorAnimationEnabled = false
                // Fixed left gap for line numbers so digits do not hug the edge.
                setLineNumberMarginLeft(spToPx(ctx, LINE_NUMBER_MARGIN_SP))
                // Keep overscroll room below the last line: extra viewport = half a screen (sora
                // default, pinned explicitly).
                verticalExtraSpaceFactor = VERTICAL_EXTRA_SPACE_FACTOR
                isBlockLineEnabled = settings.showIndentGuide
                nonPrintablePaintingFlags =
                    nonPrintableFlags(settings.showWhitespace, settings.showWrapArrow)
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                applyThemedBackground(this, themeSurface)
                applyLineNumberBackground(this, dark)
                state.scopeName?.let { scope ->
                    setEditorLanguage(TextMateLanguage.create(scope, false))
                }
                // setText before subscribing: the initial content must not mark the file dirty.
                setText(state.content)
                val editorView = this
                subscribeAlways(ContentChangeEvent::class.java) {
                    // UndoManager mutates the text first and moves the stack pointers after, so
                    // canUndo/canRedo read in the same frame are one step stale (redo key dark
                    // after undo, undo key lit at stack bottom). Defer one frame to be accurate.
                    editorView.post {
                        val baseline = baselineText.value
                        val text = editorView.text
                        onContentChanged(
                            editorView.canUndo(),
                            editorView.canRedo(),
                            text.length != baseline.length || text.toString() != baseline
                        )
                    }
                }
                subscribeAlways(ScrollEvent::class.java) { event ->
                    // After size changes sora clamps scroll through touchHandler with the same
                    // CAUSE_USER_DRAG but identical start/end offsets. A fresh editor emits a
                    // burst of those zero-move events; matching cause alone would mistake them
                    // for user scrolls and abandon line positioning.
                    val moved = event.startY != event.endY || event.startX != event.endX
                    if (moved &&
                        (event.cause == ScrollEvent.CAUSE_USER_DRAG ||
                            event.cause == ScrollEvent.CAUSE_USER_FLING)
                    ) {
                        scrolledByUser.value = true
                    }
                }
                editorRef.value = this
                boundPath.value = path
            }
        },
        onRelease = { released ->
            // Only clear when it is still this surface's binding: a replacement editor may have
            // been created already, and nuking its reference would break it.
            if (editorRef.value === released) editorRef.value = null
            if (boundPath.value == path) boundPath.value = null
            released.release()
        }
    )

    // Capture the document exactly when this surface leaves composition (tab switch, screen
    // exit, activity recreation). Compose disposes later-registered effects first, so this runs
    // while the editor is still alive and before AndroidView releases it.
    DisposableEffect(Unit) {
        onDispose { onSnapshot() }
    }

    LaunchedEffect(state.contentHash) { baselineText.value = state.content }

    // Re-apply colors on theme switch instead of recreating the editor, keeping unsaved content
    // and the undo stack intact.
    val editor = editorRef.value
    LaunchedEffect(dark, themeSurface, editor) {
        editor ?: return@LaunchedEffect
        TextMateSetup.applyTheme(dark)
        editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
        applyThemedBackground(editor, themeSurface)
        applyLineNumberBackground(editor, dark)
        editor.invalidate()
        onBackgroundResolved(Color(editor.colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)))
    }
    LaunchedEffect(settings.fontSizeSp, editor) {
        editor?.setTextSize(settings.fontSizeSp.toFloat())
    }
    LaunchedEffect(settings.wordWrap, editor) {
        editor?.setWordwrap(settings.wordWrap)
    }
    // Positioning after the document view is (re)created.
    //
    // initialLine > 0 means a chat deep link: jump to the target line and center it (1-based here,
    // 0-based inside sora). Otherwise the file opens at the top, no auto positioning.
    //
    // The jump must wait for the first layout: ensurePositionVisible computes scroll from
    // getHeight(), while onSizeChanged clamps offsets against the new size and word wrap rebuilds
    // the whole layout — a jump made at zero size is wiped by the first layout pass, so the file
    // would open at the top instead.
    //
    // Font size and word wrap come from DataStore and usually arrive after the first frame;
    // setTextSize / setWordwrap rebuild the layout as well, hence they are effect keys too. After
    // the user scrolls by hand (scrolledByUser), no more automatic repositioning.
    LaunchedEffect(state.contentHash, initialLine) { scrolledByUser.value = false }
    LaunchedEffect(
        editor,
        state.contentHash,
        initialLine,
        settings.wordWrap,
        settings.fontSizeSp
    ) {
        val editorView = editor ?: return@LaunchedEffect
        if (scrolledByUser.value || initialLine <= 0) return@LaunchedEffect
        val targetLine = initialLine.coerceAtLeast(1)
        var frames = 0
        while ((editorView.width == 0 || editorView.height == 0) &&
            frames < LINE_JUMP_LAYOUT_WAIT_FRAMES
        ) {
            withFrameNanos { }
            frames++
        }
        if (editorView.width == 0 || editorView.height == 0 || scrolledByUser.value) {
            return@LaunchedEffect
        }
        val line = (targetLine - 1).coerceIn(0, (editorView.lineCount - 1).coerceAtLeast(0))
        // After jumping, verify the target line really settled where expected; retry when a
        // layout rebuild (late settings, finished syntax parsing, re-wrap) wiped it — a single
        // positioning pass is not enough.
        var attempt = 0
        while (attempt < LINE_JUMP_MAX_ATTEMPTS && !scrolledByUser.value) {
            editorView.setSelection(line, 0)
            val desired = centerScrollOffset(editorView, line)
            if (desired != null) {
                scrollToOffset(editorView, 0, desired)
            } else {
                // Without layout info, fall back to the official make-visible call; no animation,
                // so a layout rebuild cannot interrupt it halfway.
                editorView.ensurePositionVisible(line, 0, true)
            }
            repeat(LINE_JUMP_VERIFY_FRAMES) { withFrameNanos { } }
            val settled = if (desired != null) {
                abs(editorView.offsetY - desired) <= editorView.rowHeight
            } else {
                runCatching {
                    val layout = editorView.layout
                    line in layout.getLineNumberForRow(editorView.firstVisibleRow)..
                        layout.getLineNumberForRow(editorView.lastVisibleRow)
                }.getOrDefault(true)
            }
            if (settled) break
            attempt++
        }
    }
    LaunchedEffect(settings.showIndentGuide, editor) {
        editor?.isBlockLineEnabled = settings.showIndentGuide
    }
    LaunchedEffect(settings.showWhitespace, settings.showWrapArrow, editor) {
        editor?.nonPrintablePaintingFlags =
            nonPrintableFlags(settings.showWhitespace, settings.showWrapArrow)
    }
}

@Composable
private fun EditorSymbolBar(
    backgroundColor: Color,
    onInsert: (String) -> Unit,
    onIndent: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit
) {
    Surface(
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SymbolKey(onClick = onMoveLeft) {
                Icon(
                    FeatherIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.editor_cursor_left)
                )
            }
            SymbolKey(onClick = onMoveRight) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = stringResource(R.string.editor_cursor_right)
                )
            }
            SymbolKey(onClick = onIndent) {
                Text(
                    text = stringResource(R.string.editor_indent),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            EDITOR_SYMBOLS.forEach { symbol ->
                SymbolKey(onClick = { onInsert(symbol) }) {
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SymbolKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(Spacing.sm))
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun CenterBox(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = Spacing.xl)
    )
}

/**
 * Mix the themed surface color into the editor background.
 *
 * TextMate theme backgrounds (Dark+ #1E1E1E, Light+ plain white) are tuned to VSCode's neutral
 * tones; used as-is they clash with themes carrying a color temperature. Keep a tenth of the
 * original color to preserve the editing area's base tone.
 */
private fun applyThemedBackground(editor: CodeEditor, themeSurface: Color) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    scheme.setColor(
        EditorColorScheme.WHOLE_BACKGROUND,
        lerp(base, themeSurface, EDITOR_THEME_TINT).toArgb()
    )
}

/** Slight brightness gap between the line-number gutter and the editing area; theme-aware. */
private fun applyLineNumberBackground(editor: CodeEditor, dark: Boolean) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    val target = if (dark) Color.White else Color.Black
    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, lerp(base, target, 0.08f).toArgb())
}

/** sp to px, for sora APIs that take pixel values such as the line-number left margin. */
private fun spToPx(context: android.content.Context, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

/** Compose sora's non-printable drawing flags from the whitespace / wrap-arrow toggles. */
private fun nonPrintableFlags(showWhitespace: Boolean, showWrapArrow: Boolean): Int {
    var flags = 0
    if (showWhitespace) {
        flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
            CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
            CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
            CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
            CodeEditor.FLAG_DRAW_LINE_SEPARATOR
    }
    if (showWrapArrow) {
        flags = flags or CodeEditor.FLAG_DRAW_SOFT_WRAP
    }
    return flags
}

/** Symbol bar arrow: move the cursor one character horizontally, wrapping across line edges. */
private fun moveCursorHorizontally(editor: CodeEditor, forward: Boolean) {
    val cursor = editor.cursor
    val line = cursor.leftLine
    val column = cursor.leftColumn
    if (forward) {
        val lineLength = editor.text.getColumnCount(line)
        when {
            column < lineLength -> editor.setSelection(line, column + 1)
            line < editor.lineCount - 1 -> editor.setSelection(line + 1, 0)
        }
    } else {
        when {
            column > 0 -> editor.setSelection(line, column - 1)
            line > 0 -> editor.setSelection(line - 1, editor.text.getColumnCount(line - 1))
        }
    }
}

/** Symbols offered by the bottom quick bar; tapping inserts at the cursor. */
private val EDITOR_SYMBOLS = listOf(
    "{", "}", "(", ")", "[", "]", "<", ">",
    "=", "+", "-", "*", "/", "\\",
    ";", ":", ",", ".", "_", "\"", "'", "`",
    "|", "&", "!", "?", "@", "#", "\$", "%"
)

/** Content fade-in duration: leaves room for background syntax parsing without feeling slow. */
private const val HIGHLIGHT_REVEAL_MS = 200

/**
 * Undo merge window: input spaced further apart starts a new undo record, giving discrete undo
 * steps. sora default is 8000ms.
 */
private const val UNDO_MERGE_WINDOW_MS = 500L

/** Left padding reserved for line numbers, in sp. */
private const val LINE_NUMBER_MARGIN_SP = 2f

/**
 * Hamburger-to-X drawer button morph: the icon turns a quarter rotation while the two glyphs
 * crossfade, reading as a single element rotating from a hamburger into a close icon and back.
 */
private const val DRAWER_ICON_MORPH_MS = 220
private const val DRAWER_ICON_FADE_MS = 120
private const val DRAWER_ICON_ROTATE_ANGLE = 90f

/**
 * Vertical extra viewport factor (share of editor height, in [0,1]) used for bottom overscroll.
 */
private const val VERTICAL_EXTRA_SPACE_FACTOR = 0.5f

/** Ratio that pulls the editor background toward the themed surface color. */
private const val EDITOR_THEME_TINT = 0.9f

/**
 * Max frames to wait for the editor's first layout when opening with a line number, avoiding an
 * endless wait while width/height stay zero.
 */
private const val LINE_JUMP_LAYOUT_WAIT_FRAMES = 30

/** Retry limit for verifying the target line settled, and frames waited before each check. */
private const val LINE_JUMP_MAX_ATTEMPTS = 8
private const val LINE_JUMP_VERIFY_FRAMES = 3

/**
 * Vertical scroll needed to center the target line (clamped to the scrollable range, hugging the
 * edges near file ends). Returns null while layout is unavailable; the caller then falls back to
 * the official make-visible call.
 */
private fun centerScrollOffset(editor: CodeEditor, line: Int): Int? = runCatching {
    // getCharLayoutOffset [0] is the bottom y of the row containing the line.
    val rowBottom = editor.layout.getCharLayoutOffset(line, 0)[0]
    val rowTop = rowBottom - editor.rowHeight
    (rowTop - (editor.height - editor.rowHeight) / 2f).toInt()
        .coerceIn(0, editor.scrollMaxY.coerceAtLeast(0))
}.getOrNull()

/** Land on an absolute scroll offset with no animation, mirroring sora's internal no-anim branch. */
private fun scrollToOffset(editor: CodeEditor, offsetX: Int, offsetY: Int) {
    val scroller = editor.scroller
    scroller.startScroll(
        editor.offsetX,
        editor.offsetY,
        offsetX - editor.offsetX,
        offsetY - editor.offsetY,
        0
    )
    scroller.abortAnimation()
    editor.invalidate()
}
