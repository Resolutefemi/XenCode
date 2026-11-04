package com.aicode.feature.terminal.presentation.component

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.rememberImeBottomInset
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.domain.RunState
import com.aicode.feature.terminal.domain.TerminalTab
import com.aicode.feature.terminal.domain.font.TerminalFontManager
import com.aicode.feature.terminal.presentation.TerminalViewModel
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.view.TerminalView
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.X
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    onNavigateBack: () -> Unit,
    embedded: Boolean = false
) {
    val prepareState by viewModel.prepareState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val containerInit by viewModel.containerInit.collectAsStateWithLifecycle()
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val activeTabId by viewModel.activeTabId.collectAsStateWithLifecycle()
    val revision by viewModel.revision.collectAsStateWithLifecycle()
    val creatingTab by viewModel.creatingTab.collectAsStateWithLifecycle()
    val terminalSettings by viewModel.terminalSettings.collectAsStateWithLifecycle()
    var showToolsSheet by remember { mutableStateOf(false) }

    val theme = terminalSettings.theme
    val themeBg = Color(theme.background)
    val themeFg = Color(theme.foreground)
    val themeAccent = Color(theme.cursor)
    val chromeBg = Color(shiftArgb(theme.background, 0.12f))

    Scaffold(
        containerColor = themeBg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.terminal_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = chromeBg,
                    titleContentColor = themeFg,
                    navigationIconContentColor = themeFg,
                    actionIconContentColor = themeFg
                ),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            if (embedded) FeatherIcons.X else FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(
                                if (embedded) R.string.common_close else R.string.common_back
                            ),
                            tint = themeFg
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showToolsSheet = true }) {
                        Icon(
                            FeatherIcons.Settings,
                            contentDescription = stringResource(R.string.terminal_settings_title),
                            tint = themeFg
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(bottom = rememberImeBottomInset())
        ) {
            when (val state = prepareState) {
                is TerminalViewModel.PrepareState.Loading -> StatusView(
                    loading = true,
                    message = containerInitMessage(context, containerInit),
                    contentColor = themeFg,
                    accent = themeAccent
                )

                is TerminalViewModel.PrepareState.Error -> StatusView(
                    loading = false,
                    message = stringResource(R.string.terminal_start_failed, state.message),
                    actionLabel = stringResource(R.string.terminal_retry),
                    contentColor = themeFg,
                    accent = themeAccent,
                    onAction = { viewModel.prepare() }
                )

                is TerminalViewModel.PrepareState.Ready -> {
                    @Suppress("UNUSED_EXPRESSION") revision

                    TabBar(
                        tabs = tabs,
                        activeTabId = activeTabId,
                        creating = creatingTab,
                        chromeBg = chromeBg,
                        contentColor = themeFg,
                        accent = themeAccent,
                        onSelect = { viewModel.activate(it) },
                        onClose = { viewModel.closeTab(it) },
                        onNew = { viewModel.newTab() }
                    )

                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        val active = tabs.firstOrNull { it.id == activeTabId }
                        if (active == null) {
                            StatusView(
                                loading = creatingTab,
                                message = stringResource(R.string.terminal_no_open_tabs),
                                actionLabel = stringResource(R.string.common_new_tab),
                                contentColor = themeFg,
                                accent = themeAccent,
                                onAction = { viewModel.newTab() }
                            )
                        } else {
                            key(active.id) {
                                TerminalSurface(
                                    tab = active,
                                    viewModel = viewModel,
                                    settings = terminalSettings
                                )
                            }
                        }
                    }

                    if (activeTabId != null) {
                        ExtraKeysRow(
                            viewModel = viewModel,
                            chromeBg = chromeBg,
                            contentColor = themeFg,
                            accent = themeAccent
                        )
                    }
                }
            }
        }

        if (showToolsSheet) {
            TerminalSettingsSheet(
                settings = terminalSettings,
                onDismiss = { showToolsSheet = false },
                onSelectTheme = { viewModel.setTheme(it) },
                onChangeFontSize = { viewModel.setFontSize(it) },
                onChangeCursorStyle = { viewModel.setCursorStyle(it) },
                onChangeFontPath = { viewModel.setFontPath(it) }
            )
        }
    }
}

/** Material tab row: status dot + title + close; trailing + opens a new tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabBar(
    tabs: List<TerminalTab>,
    activeTabId: String?,
    creating: Boolean,
    chromeBg: Color,
    contentColor: Color,
    accent: Color,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNew: () -> Unit
) {
    val selectedIndex = tabs.indexOfFirst { it.id == activeTabId }.coerceAtLeast(0)
    Surface(color = chromeBg, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (tabs.isEmpty()) {
                Spacer(modifier = Modifier.weight(1f))
            } else {
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    modifier = Modifier.weight(1f),
                    containerColor = chromeBg,
                    contentColor = contentColor,
                    edgePadding = Spacing.md,
                    divider = {},
                    indicator = { tabPositions ->
                        if (selectedIndex in tabPositions.indices) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                                color = accent
                            )
                        }
                    }
                ) {
                    tabs.forEachIndexed { index, tab ->
                        val selected = index == selectedIndex
                        Tab(
                            selected = selected,
                            onClick = { onSelect(tab.id) },
                            selectedContentColor = contentColor,
                            unselectedContentColor = contentColor.copy(alpha = 0.62f)
                        ) {
                            TerminalTabContent(
                                tab = tab,
                                selected = selected,
                                contentColor = contentColor,
                                accent = accent,
                                onClose = { onClose(tab.id) }
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onNew, enabled = !creating, modifier = Modifier.size(40.dp)) {
                if (creating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = accent
                    )
                } else {
                    Icon(
                        FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.common_new_tab),
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalTabContent(
    tab: TerminalTab,
    selected: Boolean,
    contentColor: Color,
    accent: Color,
    onClose: () -> Unit
) {
    val running = tab.runState is RunState.Running
    val dot = when {
        running -> accent
        tab.isBackground -> contentColor.copy(alpha = 0.55f)
        else -> contentColor.copy(alpha = 0.35f)
    }
    Row(
        modifier = Modifier
            .height(40.dp)
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dot)
        )
        Text(
            text = tab.title,
            color = if (selected) contentColor else contentColor.copy(alpha = 0.72f),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                FeatherIcons.X,
                contentDescription = stringResource(R.string.terminal_close_tab),
                tint = contentColor.copy(alpha = if (selected) 0.8f else 0.5f),
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

private fun applyTerminalColors(
    view: TerminalView,
    tab: TerminalTab,
    settings: TerminalSettings
) {
    val preset = settings.theme
    TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_BACKGROUND] = preset.background
    TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_FOREGROUND] = preset.foreground
    TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR] = preset.cursor
    for (i in 0 until minOf(16, preset.ansiColors.size)) {
        TerminalColors.COLOR_SCHEME.mDefaultColors[i] = preset.ansiColors[i]
    }

    tab.session.emulator?.mColors?.let { colors ->
        colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = preset.background
        colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = preset.foreground
        colors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = preset.cursor
        for (i in 0 until minOf(16, preset.ansiColors.size)) {
            colors.mCurrentColors[i] = preset.ansiColors[i]
        }
    }

    (tab.client as? AppTerminalSessionClient)?.cursorStyle = settings.cursorStyle
    tab.session.emulator?.setCursorStyle()

    view.onScreenUpdated()
    view.invalidate()
}

/** Termux TerminalView 的 Compose 包装：渲染与输入全部由该开源组件负责。 */
@Composable
private fun TerminalSurface(
    tab: TerminalTab,
    viewModel: TerminalViewModel,
    settings: TerminalSettings
) {
    val preset = settings.theme
    val bgColor = Color(preset.background)
    // 字号单一事实源：捏合缩放只改这里（预览），手势结束才落盘；设置面板的改动反向同步进来。
    var liveFontSizeSp by remember { mutableIntStateOf(settings.fontSizeSp) }
    LaunchedEffect(settings.fontSizeSp) { liveFontSizeSp = settings.fontSizeSp }

    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        factory = { ctx ->
            val view = TerminalView(ctx, null)
            view.setBackgroundColor(preset.background)
            val density = ctx.resources.displayMetrics.density
            view.setTextSize((liveFontSizeSp * density).toInt())
            view.setTypeface(TerminalFontManager.loadTypeface(ctx, settings.fontPath) ?: Typeface.MONOSPACE)
            view.tag = (liveFontSizeSp * density).toInt() to settings.fontPath
            view.setTerminalViewClient(
                AppTerminalViewClient(
                    context = ctx,
                    viewProvider = { view },
                    modifiers = viewModel.modifiers,
                    currentFontSizeSp = { liveFontSizeSp },
                    onFontSizeCommit = { sp ->
                        liveFontSizeSp = sp
                        viewModel.setFontSize(sp)
                    }
                )
            )
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            tab.view = view
            view.attachSession(tab.session)

            applyTerminalColors(view, tab, settings)
            view.requestFocus()
            view
        },
        update = { view ->
            view.setBackgroundColor(preset.background)
            val density = view.context.resources.displayMetrics.density
            val targetTextSize = (liveFontSizeSp * density).toInt()
            val targetFontKey = targetTextSize to settings.fontPath
            if (view.tag != targetFontKey) {
                view.setTextSize(targetTextSize)
                view.setTypeface(TerminalFontManager.loadTypeface(view.context, settings.fontPath) ?: Typeface.MONOSPACE)
                view.tag = targetFontKey
            }

            applyTerminalColors(view, tab, settings)
        },
        onRelease = { view ->
            if (tab.view === view) tab.view = null
        }
    )
}

@Composable
private fun StatusView(
    loading: Boolean,
    message: String,
    actionLabel: String? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    accent: Color = MaterialTheme.colorScheme.primary,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = accent
            )
            Spacer(Modifier.height(Spacing.md))
        }
        Text(
            text = message,
            color = contentColor.copy(alpha = 0.82f),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Spacing.lg))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = contentColor)
            ) { Text(actionLabel) }
        }
    }
}

/** 把容器初始化进度状态映射为 Loading 阶段展示给用户的文案。 */
private fun containerInitMessage(context: Context, state: ContainerInitState): String = when (state) {
    is ContainerInitState.CleaningOldRootfs ->
        context.getString(R.string.terminal_cleaning_old_container, state.processed)
    is ContainerInitState.ExtractingRootfs ->
        context.getString(R.string.terminal_extracting_env, state.processed)
    is ContainerInitState.InstallingPackages ->
        context.getString(R.string.terminal_installing_packages, state.line ?: "")
    is ContainerInitState.Failed ->
        context.getString(R.string.terminal_preparing_env_failed, state.reason)
    ContainerInitState.Idle, ContainerInitState.Ready ->
        context.getString(R.string.terminal_preparing_env_first_run)
}

/** Extra keys row themed to match the active terminal palette. */
@Composable
private fun ExtraKeysRow(
    viewModel: TerminalViewModel,
    chromeBg: Color,
    contentColor: Color,
    accent: Color
) {
    Surface(
        color = chromeBg,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyChip("ESC", contentColor = contentColor, accent = accent) { viewModel.write("\u001b") }
            KeyChip("TAB", contentColor = contentColor, accent = accent) { viewModel.write("\t") }
            KeyChip("CTRL", active = viewModel.modifiers.ctrl, contentColor = contentColor, accent = accent) {
                viewModel.modifiers.ctrl = !viewModel.modifiers.ctrl
            }
            KeyChip("ALT", active = viewModel.modifiers.alt, contentColor = contentColor, accent = accent) {
                viewModel.modifiers.alt = !viewModel.modifiers.alt
            }

            KeyChip("←", repeatOnHold = true, contentColor = contentColor, accent = accent) { viewModel.write("\u001b[D") }
            KeyChip("↑", repeatOnHold = true, contentColor = contentColor, accent = accent) { viewModel.write("\u001b[A") }
            KeyChip("↓", repeatOnHold = true, contentColor = contentColor, accent = accent) { viewModel.write("\u001b[B") }
            KeyChip("→", repeatOnHold = true, contentColor = contentColor, accent = accent) { viewModel.write("\u001b[C") }

            KeyChip("C-c", contentColor = contentColor, accent = accent) { viewModel.writeBytes(0x03) }
            KeyChip("C-d", contentColor = contentColor, accent = accent) { viewModel.writeBytes(0x04) }
            KeyChip("C-z", contentColor = contentColor, accent = accent) { viewModel.writeBytes(0x1A) }
            KeyChip("C-l", contentColor = contentColor, accent = accent) { viewModel.writeBytes(0x0C) }

            KeyChip("|", contentColor = contentColor, accent = accent) { viewModel.write("|") }
            KeyChip("~", contentColor = contentColor, accent = accent) { viewModel.write("~") }
            KeyChip("/", contentColor = contentColor, accent = accent) { viewModel.write("/") }
            KeyChip("-", contentColor = contentColor, accent = accent) { viewModel.write("-") }
            KeyChip("_", contentColor = contentColor, accent = accent) { viewModel.write("_") }
            KeyChip(">", contentColor = contentColor, accent = accent) { viewModel.write(">") }
            KeyChip(":", contentColor = contentColor, accent = accent) { viewModel.write(":") }
            KeyChip("$", contentColor = contentColor, accent = accent) { viewModel.write("$") }
            KeyChip("\"", contentColor = contentColor, accent = accent) { viewModel.write("\"") }
            KeyChip("'", contentColor = contentColor, accent = accent) { viewModel.write("'") }
        }
    }
}

/** 方向键长按重复节奏：按下后延迟多久开始连续发送，以及每次发送的间隔。 */
private const val KEY_REPEAT_INITIAL_DELAY_MS = 350L
private const val KEY_REPEAT_INTERVAL_MS = 50L

@Composable
private fun KeyChip(
    label: String,
    active: Boolean = false,
    repeatOnHold: Boolean = false,
    contentColor: Color,
    accent: Color,
    onClick: () -> Unit
) {
    val bg = if (active) accent.copy(alpha = 0.22f) else contentColor.copy(alpha = 0.08f)
    val borderColor = if (active) accent else contentColor.copy(alpha = 0.16f)
    val fg = if (active) accent else contentColor

    var pressed by remember { mutableStateOf(false) }
    var sentRepeated by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        sentRepeated = false
        delay(KEY_REPEAT_INITIAL_DELAY_MS)
        while (pressed) {
            onClick()
            sentRepeated = true
            delay(KEY_REPEAT_INTERVAL_MS)
        }
    }

    Box(
        modifier = Modifier
            .height(34.dp)
            .widthChip(label)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(6.dp))
            .then(
                if (repeatOnHold) {
                    Modifier.pointerInput(onClick) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            pressed = true
                            waitForUpOrCancellation()
                            pressed = false
                            if (!sentRepeated) onClick()
                        }
                    }
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.5.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

private fun Modifier.widthChip(label: String): Modifier =
    if (label.length <= 1) this.width(36.dp) else this

/** Lighten or darken an ARGB color toward white/black by [amount] (0..1). */
private fun shiftArgb(argb: Int, amount: Float): Int {
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    val luminance = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
    val t = amount.coerceIn(0f, 1f)
    fun channel(c: Int): Int {
        val shifted = if (luminance > 0.5f) c * (1f - t) else c + (255f - c) * t
        return shifted.toInt().coerceIn(0, 255)
    }
    return (a shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}
