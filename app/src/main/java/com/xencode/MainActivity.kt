package com.xencode

import android.content.ClipData
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.xencode.core.theme.AIEditorTheme
import com.xencode.core.theme.AppThemePreset
import com.xencode.core.ui.BetaWatermark
import com.xencode.core.ui.ImageSource
import com.xencode.core.ui.ImageViewerHost
import com.xencode.core.ui.ImageViewerRequest
import com.xencode.core.ui.LocalImageViewer
import com.xencode.core.ui.PAGE_MOTION_MS
import com.xencode.core.ui.VerticalSplitHandle
import com.xencode.core.ui.drawerWidth
import com.xencode.core.ui.isExpandedWidth
import com.xencode.core.ui.pageEnter
import com.xencode.core.ui.pageExit
import com.xencode.core.ui.rememberImageViewerState
import com.xencode.core.ui.rememberViewerDecodeSpec
import com.xencode.feature.agent.presentation.AIAgentViewModel
import com.xencode.feature.agent.presentation.AgentAttachment
import com.xencode.feature.agent.presentation.component.AIChatPanel
import com.xencode.feature.agent.presentation.component.ChatDrawerContent
import com.xencode.feature.agent.presentation.component.chatImageLoader
import com.xencode.feature.agent.presentation.component.openSentAttachment
import com.xencode.feature.editor.presentation.CodeEditorScreen
import com.xencode.feature.editor.presentation.EditorFileTreeHost
import com.xencode.feature.browser.presentation.BrowserScreen
import com.xencode.feature.git.presentation.GitViewModel
import com.xencode.feature.credentials.presentation.component.CredentialScreen
import com.xencode.feature.git.presentation.component.GitScreen
import com.xencode.feature.onboarding.data.OnboardingStatus
import com.xencode.feature.onboarding.domain.OnboardingCoordinator
import com.xencode.feature.onboarding.domain.OnboardingStateHolder
import com.xencode.feature.onboarding.domain.OnboardingStep
import com.xencode.feature.onboarding.presentation.LocalOnboardingTargetRegistry
import com.xencode.feature.onboarding.presentation.OnboardingOverlay
import com.xencode.feature.onboarding.presentation.OnboardingTargetRegistry
import com.xencode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.xencode.feature.settings.data.repository.AppThemeMode
import com.xencode.feature.settings.data.repository.BackgroundSettingsRepository
import com.xencode.feature.settings.data.repository.ScreenOnSettingsRepository
import com.xencode.feature.settings.data.repository.ThemeSettingsRepository
import com.xencode.feature.settings.presentation.SettingsViewModel
import com.xencode.feature.settings.presentation.FetchState
import com.xencode.feature.settings.presentation.UpdateCheckUiState
import com.xencode.feature.settings.presentation.component.githubReleaseUrl
import com.xencode.feature.settings.presentation.component.SettingsScreen
import com.xencode.feature.settings.presentation.component.UpdateCheckDialog
import com.xencode.feature.settings.presentation.component.decodeBackgroundBitmap
import com.xencode.feature.settings.presentation.component.openUrl
import com.xencode.feature.settings.presentation.component.settingsPageBackground
import com.xencode.feature.terminal.domain.TerminalKeepaliveService
import com.xencode.feature.terminal.presentation.TerminalViewModel
import com.xencode.feature.terminal.presentation.component.TerminalScreen
import com.xencode.feature.workspace.presentation.WorkspaceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.xencode.R

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Restores the persistent keep-alive notification in the foreground on cold start (the launch in App.onCreate may be blocked by background FGS restrictions). */
    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    @Inject
    lateinit var themeSettings: ThemeSettingsRepository

    @Inject
    lateinit var backgroundSettings: BackgroundSettingsRepository

    /** Keep-screen-on switch: only holds the screen while the app is foregrounded; the system releases it automatically in the background. */
    @Inject
    lateinit var screenOnSettings: ScreenOnSettingsRepository

    /** Language preference: read synchronously in attachBaseContext to apply the locale; recreate on change. */
    @Inject
    lateinit var languageSettings: com.xencode.feature.settings.data.repository.LanguageSettingsRepository

    /** When the app returns to the foreground, triggers a reconnect in remote mode if SSH dropped. */
    @Inject
    lateinit var remoteSshConnection: com.xencode.feature.agent.domain.container.RemoteSshConnection

    @Inject
    lateinit var executionModeHolder: com.xencode.feature.settings.data.repository.ExecutionModeHolder

    /** Unified dialog bridge for missing git credentials across all ends (UI / AI Bash / interactive terminal): watches the helper's file IPC requests after AIEditorApp starts. */
    @Inject
    lateinit var credentialRequestBridge: com.xencode.feature.credentials.data.CredentialRequestBridge

    /** Persistence for first-run onboarding progress. */
    @Inject
    lateinit var onboardingRepository: com.xencode.feature.onboarding.data.OnboardingRepository

    @Inject
    lateinit var browserManager: com.xencode.feature.agent.domain.tool.browser.BrowserManager

    /** Performance sampler: once enabled manually in the performance panel, attributes samples to modules by route for long-term usage analysis. */
    @Inject
    lateinit var perfMonitor: com.xencode.core.perf.PerfMonitor

    override fun attachBaseContext(newBase: android.content.Context) {
        // Apply the user-selected language synchronously before the Activity is created, so it takes effect on cold starts too.
        // Hilt has not injected yet, so read synchronously from SharedPreferences.
        val tag = newBase.getSharedPreferences("language_prefs_sync", android.content.Context.MODE_PRIVATE)
            .getString("language_tag", null)
        val locale = when {
            tag.isNullOrBlank() -> java.util.Locale.ENGLISH
            com.xencode.core.util.LanguageRegistry.isSupported(tag) -> java.util.Locale.forLanguageTag(tag)
            else -> java.util.Locale.ENGLISH
        }
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    @Suppress("DEPRECATION") // Updates the application resources locale globally; createConfigurationContext cannot replace this
    override fun onCreate(savedInstanceState: Bundle?) {
        // Restore from the cold-start splash theme to the main app theme
        setTheme(R.style.Theme_XenCode)
        // Draw beneath the system status/navigation bars so the app background blends with the system bars (no jarring color blocks).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Offscreen WebView hidden host: mounted after the first frame, keeping its onCreate-time layout passes off the first-paint jank.
        // The WebView itself is only created when the browser page first opens; hiddenHost merely provides the onAttachedToWindow environment, so a 200ms delay is harmless.
        lifecycleScope.launch {
            kotlinx.coroutines.delay(200)
            val hiddenHost = android.widget.FrameLayout(this@MainActivity).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(1, 1)
                alpha = 0.001f
                translationX = -10000f
            }
            findViewById<android.view.ViewGroup>(android.R.id.content)?.addView(hiddenHost)
            browserManager.attachHiddenHost(hiddenHost)
        }
        // Watch language preference changes; update the Application/Activity locale, then recreate.
        lifecycleScope.launch {
            languageSettings.languageFlow.drop(1).distinctUntilChanged().collect { tag ->
                // Also update the Application context's Configuration so non-Activity
                // Resources (e.g. context.getString in a ViewModel) get the correct locale too.
                val app = applicationContext
                val locale = when {
                    tag.isNullOrBlank() -> java.util.Locale.ENGLISH
                    com.xencode.core.util.LanguageRegistry.isSupported(tag) -> java.util.Locale.forLanguageTag(tag)
                    else -> java.util.Locale.ENGLISH
                }
                val config = android.content.res.Configuration(app.resources.configuration)
                config.setLocale(locale)
                app.resources.updateConfiguration(config, app.resources.displayMetrics)
                recreate()
            }
        }
        // Keep screen on: the window flag only holds the screen while the Activity is visible; the system releases it in the background automatically, no manual release needed.
        lifecycleScope.launch {
            screenOnSettings.enabledFlow.collect { enabled ->
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
        // API 30+: switch globally to ADJUST_NOTHING; rememberImeBottomInset() takes over keyboard insets.
        // This must be set uniformly at the Activity level, not saved/restored per composable —
        // during NavHost transitions old and new pages coexist, and an old page's dispose restoring softInputMode triggers a window relayout that whites out the screen.
        if (Build.VERSION.SDK_INT >= 30) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        setContent {
            // -- Deferred first-frame loading --
            // On a cold start the JIT must compile giant Composables like AIChatPanel (1916 lines) and ChatInputBar;
            // compilation allocates ~39MB -> GC storm -> the main thread stalls 150-950ms (measured: 11 frames all janky).
            // Render a lightweight Surface in the splash color first (zero JIT cost), let the JIT finish the hot paths on a background thread, then switch to the heavy content.
            var contentReady by remember { androidx.compose.runtime.mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // Wait until the first frame is rendered (splash visible), then give the JIT ~200ms to compile hot-path Composables on a background thread.
                // Measured: JIT-compiling AIChatPanel+ChatInputBar on a cold start allocates ~39MB and takes ~500ms;
                // 200ms is enough for the JIT to finish most hot methods, so switching to heavy content no longer triggers a main-thread GC storm.
                androidx.compose.runtime.withFrameNanos { }
                kotlinx.coroutines.delay(200)
                contentReady = true
            }

            if (!contentReady) {
                // Lightweight first frame: same color as Theme.XenCode.Splash's windowBackground, for a seamless visual handoff.
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.ui.graphics.Color(0xFFF9FAFA)
                ) {}
            } else {
            val themeMode by themeSettings.themeModeFlow.collectAsStateWithLifecycle(initialValue = AppThemeMode.AUTO)
            val themePresetId by themeSettings.themePresetIdFlow.collectAsStateWithLifecycle(initialValue = null)
            val dynamicColor by themeSettings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                AppThemeMode.AUTO -> systemDarkTheme
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
            }
            val view = LocalView.current
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            LaunchedEffect(darkTheme) {
                browserManager.setAppDarkTheme(darkTheme)
            }

            AIEditorTheme(
                darkTheme = darkTheme,
                preset = AppThemePreset.findById(themePresetId),
                dynamicColor = dynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                        // Global custom background image: layered over every page's content (watermark effect), adjustable opacity.
                        // Does not intercept touches; dialogs (separate windows) are unaffected by the z-order.
                    val bgPath by backgroundSettings.imagePathFlow.collectAsStateWithLifecycle(initialValue = null)
                    val bgAlpha by backgroundSettings.alphaFlow.collectAsStateWithLifecycle(initialValue = BackgroundSettingsRepository.DEFAULT_ALPHA)
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppNavigation(
                            onboardingRepository = onboardingRepository,
                            perfMonitor = perfMonitor
                        )
                        // Global credential dialog: covers every page, so command-line git missing credentials can pop up anywhere.
                        com.xencode.feature.credentials.presentation.component.GlobalCredentialDialogHost(
                            bridge = credentialRequestBridge
                        )
                        if (bgPath != null && bgAlpha > 0f) {
                            val screen = LocalView.current
                            val bitmap by produceState<ImageBitmap?>(initialValue = null, bgPath) {
                                value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    decodeBackgroundBitmap(
                                        bgPath!!,
                                        screen.width.coerceAtLeast(1),
                                        screen.height.coerceAtLeast(1)
                                    )
                                }
                            }
                            bitmap?.let {
                                Image(
                                    bitmap = it,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .alpha(bgAlpha)
                                )
                            }
                        }
                        // Beta-build global watermark, topmost, does not intercept touches.
                        BetaWatermark()
                    }
                }
            }
            } // else (contentReady)
        }

        // We are in the foreground now, so starting a foreground service is definitely allowed: if the user ever enabled persistent keep-alive, restore the notification.
        lifecycleScope.launch {
            if (keepaliveSettings.isEnabled()) {
                TerminalKeepaliveService.enablePersistent(this@MainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning to the foreground in remote mode: if SSH dropped, reconnect immediately instead of waiting for the supervisor poll
        if (executionModeHolder.currentMode() == com.xencode.feature.settings.data.repository.ExecutionMode.REMOTE_SSH) {
            lifecycleScope.launch {
                runCatching { remoteSshConnection.tryReconnectIfDisconnected() }
            }
        }
    }

    override fun onDestroy() {
        browserManager.detachHiddenHost()
        super.onDestroy()
    }

}

/** Default width share of the large-screen right pane, plus drag-resize bounds — both sides keep at least 30% width. */
private const val DEFAULT_PANE_SPLIT = 0.5f
private const val MIN_PANE_SPLIT = 0.3f
private const val MAX_PANE_SPLIT = 0.7f

/**
 * Root navigation container.
 *
 * The [ModalNavigationDrawer] sits **outside** the [NavHost], so the Drawer's lifecycle is independent of page switches.
 *
 * Page transitions uniformly use [pageEnter] / [pageExit].
 *
 * **Note**: the terminal page hosts a native TerminalView; enabling transitions historically caused "white screen when tapping the sidebar right after leaving the terminal" —
 * back then, old and new pages coexisted during the transition, and the old page's dispose flipped softInputMode back to adjustResize, triggering a window relayout,
 * which combined with the Drawer open animation broke the render pipeline. That path is now covered by setting softInputMode uniformly in onCreate and
 * `rememberImeBottomInset`'s empty onDispose; if the white screen reappears, first downgrade the terminal route's transition to None to rule it out.
 *
 * The ViewModel is hoisted to this level so the Drawer content and AIChatPanel share one instance.
 */
/** Navigation route -> performance panel module name. The editor route carries query params, so match by prefix. */
private fun perfModuleOf(route: String?): String = when {
    route == null -> "other"
    route == "chat" -> "chat"
    route.startsWith("editor") -> "editor"
    route == "terminal" -> "terminal"
    route == "browser" -> "browser"
    route == "git" || route == "credentials" -> "git"
    route == "settings" -> "settings"
    else -> "other"
}

@Composable
fun AppNavigation(
    onboardingRepository: com.xencode.feature.onboarding.data.OnboardingRepository,
    perfMonitor: com.xencode.core.perf.PerfMonitor
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Used to determine the current route: Drawer gestures are allowed only on the chat page.
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route

    // Record the current route globally; crash reports use it to locate the crashing screen.
    LaunchedEffect(currentRoute) {
        AIEditorApp.currentRoute = currentRoute
        // Performance samples are attributed to modules by route, for module-level usage analysis in the performance panel.
        perfMonitor.setModule(perfModuleOf(currentRoute))
    }

    // Activity-scoped ViewModel — the Drawer and AIChatPanel share the same instance.
    val agentViewModel: AIAgentViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val workspaceViewModel: WorkspaceViewModel = hiltViewModel()

    // -- First-run onboarding --
    val onboardingStateHolder = remember { OnboardingStateHolder() }
    val onboardingUiState by onboardingStateHolder.state.collectAsStateWithLifecycle()
    val onboardingRegistry = remember { OnboardingTargetRegistry() }
    val onboardingScope = rememberCoroutineScope()
    val onboardingCoordinator = remember {
        OnboardingCoordinator(
            stateHolder = onboardingStateHolder,
            onPersist = { status ->
                onboardingRepository.markStatus(status)
            },
            scope = onboardingScope
        )
    }
    // Launch check: activate onboarding if IN_PROGRESS.
    LaunchedEffect(Unit) {
        val status = onboardingRepository.status()
        onboardingCoordinator.initialize(status ?: OnboardingStatus.IN_PROGRESS)
    }

    // Automatic update check: runs async on entering the home page (when enabled, at most once a day, silent on failure)
    LaunchedEffect(Unit) {
        settingsViewModel.checkUpdate(manual = false)
    }

    // While the sidebar is open, the system back key collapses it first.
    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // Data needed by the sidebar.
    val currentWorkspace by workspaceViewModel.current.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(currentWorkspace) {
        // When the remote connection is not ready, currentWorkspace is null and setWorkspace is not triggered, avoiding an empty path firing session loading
        val path = currentWorkspace?.path ?: return@LaunchedEffect
        agentViewModel.setWorkspace(path)
    }

    val sessions by agentViewModel.sessions.collectAsStateWithLifecycle()
    val currentSessionId by agentViewModel.currentSessionId.collectAsStateWithLifecycle()
    val agentStates by agentViewModel.agentStates.collectAsStateWithLifecycle()
    val awaitingPermissionSessionIds by agentViewModel.awaitingPermissionSessionIds.collectAsStateWithLifecycle()
    val subSessionsByParent by agentViewModel.subSessionsByParent.collectAsStateWithLifecycle()
    val expandedPaths by agentViewModel.expandedPaths.collectAsStateWithLifecycle()
    val expandingPath by agentViewModel.expandingPath.collectAsStateWithLifecycle()
    val fileOpPaths by agentViewModel.fileOpPaths.collectAsStateWithLifecycle()
    val browseState by agentViewModel.browseState.collectAsStateWithLifecycle()
    val browseClipboard by agentViewModel.browseClipboard.collectAsStateWithLifecycle()
    val pasteConflict by agentViewModel.pasteConflict.collectAsStateWithLifecycle()
    val chatSearchQuery by agentViewModel.chatSearchQuery.collectAsStateWithLifecycle()
    val chatSearchState by agentViewModel.chatSearchState.collectAsStateWithLifecycle()

    // -- Export session: SAF file save --
    var pendingExportSessionId by remember { mutableStateOf<String?>(null) }
    var pendingImportParent by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboard.current
    val sessionExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val sessionId = pendingExportSessionId
        if (uri != null && sessionId != null) {
            scope.launch {
                val os = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (os != null) {
                    agentViewModel.exportSession(sessionId, os) { success ->
                        Toast.makeText(
                            context,
                            context.getString(if (success) R.string.chat_export_session_done else R.string.chat_export_session_failed),
                            if (success) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(context, context.getString(R.string.chat_export_session_failed), Toast.LENGTH_LONG).show()
                }
                pendingExportSessionId = null
            }
        } else {
            pendingExportSessionId = null
        }
    }
    val importFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        val parent = pendingImportParent
        pendingImportParent = null
        if (parent != null && uris.isNotEmpty()) {
            agentViewModel.importBrowseFiles(parent, uris, context) { ok ->
                if (!ok) toastFileOpFailed(context, R.string.file_browser_import_failed)
            }
        }
    }
    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val parent = pendingImportParent
        pendingImportParent = null
        if (parent != null && uri != null) {
            agentViewModel.importBrowseFolder(parent, uri, context) { ok ->
                if (!ok) toastFileOpFailed(context, R.string.file_browser_import_failed)
            }
        }
    }

    // -- Large-screen (tablet landscape and up) layout --
    val expanded = isExpandedWidth()
    // The persistent sidebar opens only on the chat page: terminal / editor / settings are all full-screen pages that get cramped by a sidebar.
    // The persistent sidebar depends only on window width: it expands only on the chat page and slides back when switching pages (see sidebarWidth below).
    val permanentDrawer = expanded

    // Refresh the file list once when the sidebar appears: locally it covers missed inotify events; remotely there is no inotify, so it is the only trigger.
    // Cannot simply watch drawerState.isOpen: the large-screen persistent sidebar does not use ModalNavigationDrawer, so drawerState stays Closed forever,
    // which would leave the large-screen remote file tree with only the manual refresh button. Instead use "sidebar visible": on narrow windows check whether the drawer is open,
    // on large screens check whether we are on the chat page (the sidebar expands only there).
    val sidebarVisible = if (permanentDrawer) currentRoute == "chat" else drawerState.isOpen
    LaunchedEffect(sidebarVisible) {
        if (sidebarVisible) agentViewModel.refreshBrowse()
    }

    // Right-pane workbench: on large screens the editor / terminal / Git sit beside the chat; narrow windows still use full-screen routes.
    var paneKind by rememberSaveable { mutableStateOf(WorkbenchPaneKind.NONE) }
    var paneEditorPath by rememberSaveable { mutableStateOf("") }
    var paneEditorLine by rememberSaveable { mutableIntStateOf(0) }
    var paneSplit by rememberSaveable { mutableFloatStateOf(DEFAULT_PANE_SPLIT) }

    // When the right pane is open, back collapses it first. Chat-page only: the right pane is not rendered on other pages, so it must not swallow back events there.
    BackHandler(
        enabled = expanded && currentRoute == "chat" &&
            paneKind != WorkbenchPaneKind.NONE && !drawerState.isOpen
    ) {
        paneKind = WorkbenchPaneKind.NONE
    }

    // Open a file: on wide screens inside the right workbench pane, on narrow screens via the
    // fullscreen editor route.
    val openFile: (String, Int, Boolean) -> Unit = { filePath, line, fromDrawer ->
        if (expanded) {
            paneEditorPath = filePath
            paneEditorLine = line
            paneKind = WorkbenchPaneKind.EDITOR
            // The permanent sidebar does not need closing; with a modal drawer (tablet portrait
            // etc.) the drawer should collapse after a file is picked.
            if (!permanentDrawer) scope.launch { drawerState.close() }
        } else {
            scope.launch { drawerState.close() }
            navController.navigate(
                "editor?path=${android.net.Uri.encode(filePath)}&line=$line&drawer=$fromDrawer"
            ) {
                // Reuse the editor entry when it is already on top so stacked destinations do not
                // pile up duplicated editor screens.
                launchSingleTop = true
            }
        }
    }

    // Tapping a file in the tree: images go to the built-in fullscreen viewer, APKs to the system
    // installer, everything else to the editor.
    val drawerImageViewerState = rememberImageViewerState()
    val drawerDecodeSpec = rememberViewerDecodeSpec()
    val drawerImageLoad = remember(agentViewModel.fileAccess, drawerDecodeSpec) {
        chatImageLoader(agentViewModel.fileAccess, drawerDecodeSpec.maxEdge, drawerDecodeSpec.maxPixels)
    }
    val openTreeEntry: (String) -> Unit = { path ->
        val name = path.trimEnd('/').substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            ext in PREVIEWABLE_IMAGE_EXTS -> drawerImageViewerState.show(
                ImageViewerRequest(listOf(ImageSource.ContainerPath(path)), name)
            )
            ext in INSTALLABLE_APK_EXTS -> scope.launch {
                openSentAttachment(
                    context,
                    AgentAttachment(
                        fileName = name,
                        containerPath = path,
                        localPath = "",
                        mimeType = APK_MIME_TYPE,
                        sizeBytes = 0L,
                        isImage = false
                    ),
                    agentViewModel.fileAccess
                )
            }
            else -> openFile(path, 0, true)
        }
    }

    // Shared file-tree data and actions for the editor's standard navigation drawer. Same sources
    // as the chat sidebar, so both stay in sync no matter where a file is opened from.
    val editorFileTreeHost = EditorFileTreeHost(
        browseState = browseState,
        expandedPaths = expandedPaths,
        expandingPath = expandingPath,
        fileOpPaths = fileOpPaths,
        clipboard = browseClipboard,
        pasteConflict = pasteConflict,
        onToggleExpand = { agentViewModel.toggleExpand(it) },
        onOpenFile = openTreeEntry,
        onRefreshBrowse = { agentViewModel.refreshBrowse() },
        onCreateFile = { parent, name ->
            agentViewModel.createBrowseFile(parent, name) { ok ->
                if (!ok) toastFileOpFailed(context, R.string.file_browser_create_failed)
            }
        },
        onCreateFolder = { parent, name ->
            agentViewModel.createBrowseFolder(parent, name) { ok ->
                if (!ok) toastFileOpFailed(context, R.string.file_browser_create_failed)
            }
        },
        onRenameEntry = { path, newName ->
            agentViewModel.renameBrowseEntry(path, newName) { ok ->
                if (!ok) toastFileOpFailed(context, R.string.file_browser_rename_failed)
            }
        },
        onDeleteEntry = { path ->
            agentViewModel.deleteBrowseEntry(path) { ok ->
                if (!ok) toastFileOpFailed(context, R.string.file_browser_delete_failed)
            }
        },
        onCopyEntry = { path, name -> agentViewModel.copyBrowseEntry(path, name) },
        onCutEntry = { path, name -> agentViewModel.cutBrowseEntry(path, name) },
        onPasteEntry = { targetDir, onResult ->
            agentViewModel.pasteBrowseEntry(targetDir, onResult)
        },
        onPasteOverwrite = { agentViewModel.pasteBrowseEntryOverwrite() },
        onCancelPasteOverwrite = { agentViewModel.clearPasteConflict() },
        onClearClipboard = { agentViewModel.clearBrowseClipboard() },
        onImportFiles = { parent ->
            pendingImportParent = parent
            importFilesLauncher.launch(arrayOf("*/*"))
        },
        onImportFolder = { parent ->
            pendingImportParent = parent
            importFolderLauncher.launch(null)
        },
        onCopyPath = { path ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("file-path", path)))
                Toast.makeText(context, context.getString(R.string.git_toast_path_copied), Toast.LENGTH_SHORT).show()
            }
        }
    )

    // Terminal / Git: on wide screens they toggle inside the workbench pane, on narrow screens
    // they become fullscreen routes.
    val openWorkbench: (WorkbenchPaneKind) -> Unit = { target ->
        if (expanded) {
            paneKind = if (paneKind == target) WorkbenchPaneKind.NONE else target
        } else {
            val route = when (target) {
                WorkbenchPaneKind.TERMINAL -> "terminal"
                WorkbenchPaneKind.GIT -> "git"
                WorkbenchPaneKind.BROWSER -> "browser"
                else -> "chat"
            }
            navController.navigate(route)
        }
    }

    // Sidebar body: the modal drawer and the permanent wide-screen sidebar share it, avoiding a
    // few dozen duplicated parameters in two places.
    val drawerBody: @Composable () -> Unit = {
        ChatDrawerContent(
            sessions = sessions,
            currentSessionId = currentSessionId,
            agentStates = agentStates,
            awaitingPermissionSessionIds = awaitingPermissionSessionIds,
            subSessionsByParent = subSessionsByParent,
            browseState = browseState,
            expandedPaths = expandedPaths,
            expandingPath = expandingPath,
            fileOpPaths = fileOpPaths,
            clipboard = browseClipboard,
            pasteConflict = pasteConflict,
            onToggleExpand = { agentViewModel.toggleExpand(it) },
            onOpenFile = openTreeEntry,
            onRefreshBrowse = { agentViewModel.refreshBrowse() },
            onCreateFile = { parent, name ->
                agentViewModel.createBrowseFile(parent, name) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_create_failed)
                }
            },
            onCreateFolder = { parent, name ->
                agentViewModel.createBrowseFolder(parent, name) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_create_failed)
                }
            },
            onRenameEntry = { path, newName ->
                agentViewModel.renameBrowseEntry(path, newName) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_rename_failed)
                }
            },
            onDeleteEntry = { path ->
                agentViewModel.deleteBrowseEntry(path) { ok ->
                    if (!ok) toastFileOpFailed(context, R.string.file_browser_delete_failed)
                }
            },
            onCopyEntry = { path, name ->
                agentViewModel.copyBrowseEntry(path, name)
            },
            onCutEntry = { path, name ->
                agentViewModel.cutBrowseEntry(path, name)
            },
            onPasteEntry = { targetDir, onResult ->
                agentViewModel.pasteBrowseEntry(targetDir, onResult)
            },
            onClearClipboard = { agentViewModel.clearBrowseClipboard() },
            onImportFiles = { parent ->
                pendingImportParent = parent
                importFilesLauncher.launch(arrayOf("*/*"))
            },
            onImportFolder = { parent ->
                pendingImportParent = parent
                importFolderLauncher.launch(null)
            },
            onCopyPath = { path ->
                scope.launch {
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("file-path", path)))
                    Toast.makeText(context, context.getString(R.string.git_toast_path_copied), Toast.LENGTH_SHORT).show()
                }
            },
            onPasteOverwrite = { agentViewModel.pasteBrowseEntryOverwrite() },
            onCancelPasteOverwrite = { agentViewModel.clearPasteConflict() },
            onSelect = {
                agentViewModel.selectSession(it.id)
                if (!permanentDrawer) scope.launch { drawerState.close() }
            },
            onDelete = { agentViewModel.deleteSession(it.id) },
            onDeleteSessions = { agentViewModel.deleteSessions(it) },
            onRename = { session, title -> agentViewModel.renameSession(session.id, title) },
            onTogglePin = { agentViewModel.togglePinSession(it.id) },
            onExport = { session ->
                pendingExportSessionId = session.id
                val safeTitle = session.title.replace(Regex("[^\\w\\u4e00-\\u9fa5\\-]"), "_")
                sessionExportLauncher.launch("xencode-session-$safeTitle-${System.currentTimeMillis()}.tar.gz")
            },
            onNavigateToBrowser = {
                openWorkbench(WorkbenchPaneKind.BROWSER)
                if (!permanentDrawer) scope.launch { drawerState.close() }
            },
            onNavigateToNetwork = {
                navController.navigate("network")
                if (!permanentDrawer) scope.launch { drawerState.close() }
            },
            onNavigateToSettings = {
                navController.navigate("settings")
                if (!permanentDrawer) scope.launch { drawerState.snapTo(DrawerValue.Closed) }
            },
            searchQuery = chatSearchQuery,
            searchState = chatSearchState,
            onSearchQueryChange = { agentViewModel.updateChatSearchQuery(it) },
            onClearSearch = { agentViewModel.clearChatSearch() },
            onOpenSearchHit = { hit ->
                agentViewModel.openChatSearchHit(hit)
                if (!permanentDrawer) scope.launch { drawerState.close() }
            }
        )
    }

    // Page body (NavHost): on large screens it goes into the right side of the Row; on narrow windows into the modal drawer container.
    val navBody: @Composable () -> Unit = {
        NavHost(
            navController = navController,
            startDestination = "chat",
            // During transitions the exiting page slides left out of its own bounds; without clipping on large screens it would draw over the persistent session sidebar on the left.
            modifier = Modifier.fillMaxSize().clipToBounds(),
            enterTransition = { pageEnter(forward = true) },
            exitTransition = { pageExit(forward = true) },
            popEnterTransition = { pageEnter(forward = false) },
            popExitTransition = { pageExit(forward = false) }
        ) {
            composable("chat") {
                // Override LocalUriHandler inside the chat area: Markdown link taps dispatch through this handler by default.
                // File-path links (no scheme or file://) are intercepted and opened in the editor; the rest (http/https etc.) still go to the system browser.
                val defaultUriHandler = androidx.compose.ui.platform.LocalUriHandler.current
                val fileUriHandler = remember(defaultUriHandler, expanded) {
                    FileAwareUriHandler(defaultUriHandler) { filePath, line ->
                        openFile(filePath, line, false)
                    }
                }
                val paneOpen = expanded && paneKind != WorkbenchPaneKind.NONE
                // Dragging the handle converts pixel displacement into a split ratio, so record the container's measured width.
                var rowWidthPx by remember { mutableIntStateOf(0) }
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { rowWidthPx = it.width }
                ) {
                    Box(modifier = Modifier.weight(if (paneOpen) paneSplit else 1f)) {
                        CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalUriHandler provides fileUriHandler
                        ) {
                            AIChatPanel(
                                viewModel = agentViewModel,
                                settingsViewModel = settingsViewModel,
                                workspaceViewModel = workspaceViewModel,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                showMenuButton = !permanentDrawer,
                                onNavigateToTerminal = { openWorkbench(WorkbenchPaneKind.TERMINAL) },
                                onNavigateToGit = { openWorkbench(WorkbenchPaneKind.GIT) },
                                onNavigateToBrowser = { openWorkbench(WorkbenchPaneKind.BROWSER) },
                                terminalActive = paneOpen && paneKind == WorkbenchPaneKind.TERMINAL,
                                gitActive = paneOpen && paneKind == WorkbenchPaneKind.GIT,
                                browserActive = paneOpen && paneKind == WorkbenchPaneKind.BROWSER,
                                onboardingStep = if (onboardingUiState.active) onboardingUiState.step else null,
                                onSelectModelInOnboarding = {
                                    if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_CHOOSE_MODEL) {
                                        onboardingCoordinator.nextStep()
                                    }
                                },
                                onDismissModelSheetInOnboarding = {
                                    if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_CHOOSE_MODEL) {
                                        onboardingCoordinator.goToStep(OnboardingStep.OPEN_MODEL_PICKER)
                                    }
                                }
                            )
                        }
                    }
                    if (paneOpen) {
                        VerticalSplitHandle(
                            onDragDelta = { dx ->
                                if (rowWidthPx > 0) {
                                    paneSplit = (paneSplit + dx / rowWidthPx)
                                        .coerceIn(MIN_PANE_SPLIT, MAX_PANE_SPLIT)
                                }
                            }
                        )
                        WorkbenchPaneContent(
                            kind = paneKind,
                            editorPath = paneEditorPath,
                            editorLine = paneEditorLine,
                            onClose = { paneKind = WorkbenchPaneKind.NONE },
                            onOpenFile = { path -> openFile(path, 0, false) },
                            modifier = Modifier.weight(1f - paneSplit)
                        )
                    }
                }
            }
            composable("settings") {
                        // Reuse the Activity-scoped settingsViewModel (already created and initialized at the top of MainActivity);
                        // this avoids creating a NavBackStackEntry-scoped instance on entering settings and re-running init plus 9 flow subscriptions,
                        // the main cause of the settings tap in the sidebar feeling laggy.
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                        // On large screens the sidebar is already persistent when returning to the chat page; do not pop the modal drawer again.
                        if (!expanded) scope.launch { drawerState.open() }
                    },
                    onStopAllAndCloseTerminal = { agentViewModel.stopAllAndCloseTerminal() },
                    onRerunOnboarding = {
                        // "Rerun onboarding" from the settings page: clear state back to in_progress, activate onboarding, and return to the chat page.
                        scope.launch {
                            onboardingRepository.resetToInProgress()
                            onboardingCoordinator.resetAndStart()
                            navController.navigate("chat") {
                                popUpTo("chat") { inclusive = false }
                            }
                        }
                    },
                    onboardingStep = onboardingUiState.step.takeIf { onboardingUiState.active },
                    onOnboardingModelAdded = {
                        if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_FETCH_DIALOG) {
                            navController.popBackStack()
                            onboardingCoordinator.nextStep()
                        }
                    },
                    onOnboardingDismissFetchDialog = {
                        if (onboardingUiState.active && onboardingUiState.step == OnboardingStep.SIMULATE_FETCH_DIALOG) {
                            onboardingCoordinator.goToStep(OnboardingStep.PROVIDER_FETCH_MODELS)
                        }
                    }
                )
            }
            composable("terminal") { entry ->
                val terminalViewModel: TerminalViewModel = hiltViewModel()
                TerminalScreen(
                    viewModel = terminalViewModel,
                    onNavigateBack = {
                        // Clicks can still land during the exit animation; do not pop the chat home page that is already current.
                        if (navController.currentBackStackEntry === entry) {
                            navController.popBackStack()
                        }
                    }
                )
            }
            composable("browser") {
                val browserViewModel: com.xencode.feature.browser.presentation.BrowserViewModel = hiltViewModel()
                BrowserScreen(
                    browserManager = browserViewModel.browserManager,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("network") {
                val networkViewModel: com.xencode.feature.network.presentation.NetworkRequestViewModel = hiltViewModel()
                com.xencode.feature.network.presentation.NetworkRequestScreen(
                    viewModel = networkViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("git") {
                val gitViewModel: GitViewModel = hiltViewModel()
                GitScreen(
                    viewModel = gitViewModel,
                    onNavigateToCredentials = { navController.navigate("credentials") },
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFile = { path -> openFile(path, 0, false) }
                )
            }
            composable("credentials") {
                val credentialViewModel: com.xencode.feature.credentials.presentation.CredentialViewModel = hiltViewModel()
                CredentialScreen(
                    viewModel = credentialViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(
                route = "editor?path={path}&line={line}&drawer={drawer}",
                arguments = listOf(
                    navArgument("path") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument("line") {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                    navArgument("drawer") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { entry ->
                // Entering from the sidebar file tree reopens the drawer on back (otherwise every
                // read file would force re-pulling the drawer); from chat links, back returns to
                // chat without reopening the drawer.
                val openDrawerOnBack = entry.arguments?.getBoolean("drawer") ?: false
                CodeEditorScreen(
                    path = entry.arguments?.getString("path").orEmpty(),
                    initialLine = entry.arguments?.getInt("line") ?: 0,
                    fileTreeHost = editorFileTreeHost,
                    onBack = {
                        navController.popBackStack()
                        if (openDrawerOnBack && !expanded) scope.launch { drawerState.open() }
                    }
                )
            }
        }
    }

    CompositionLocalProvider(
        LocalOnboardingTargetRegistry provides onboardingRegistry,
        LocalImageViewer provides drawerImageViewerState
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    // Images opened from the file tree use this viewer; the chat panel has its own, and the two do not interfere.
    ImageViewerHost(state = drawerImageViewerState, load = drawerImageLoad)
    if (expanded) {
        // No ModalNavigationDrawer on large screens: the sidebar is persistent on the left; the drawer's scrim / gestures / anchors are all useless here.
        // The sidebar expands only on the chat page: settings is already a "menu + detail" two-pane layout; adding a session sidebar outside would make three.
        // Width animates rather than toggling, otherwise the right region's width jumps and page switches visibly flicker;
        // the duration matches the page transition so both animations finish together.
        val sidebarWidth by animateDpAsState(
            targetValue = if (currentRoute == "chat") drawerWidth() else 0.dp,
            animationSpec = tween(durationMillis = PAGE_MOTION_MS),
            label = "sidebar-width"
        )
        // Once the sidebar fully collapses the whole subtree leaves composition; use a SaveableStateHolder to retain its internal state,
        // otherwise every return from settings resets the tab to "Sessions" and loses the file tree scroll position.
        val sidebarStateHolder = rememberSaveableStateHolder()
        Row(modifier = Modifier.fillMaxSize()) {
            if (sidebarWidth > 0.dp) {
                Box(
                    modifier = Modifier
                        .width(sidebarWidth)
                        .fillMaxHeight()
                        .clipToBounds()
                ) {
                    // Render content at full width and clip: no re-layout of the sidebar internals during collapse.
                    Box(
                        modifier = Modifier
                            .width(drawerWidth())
                            .fillMaxHeight()
                    ) {
                        sidebarStateHolder.SaveableStateProvider("chat-sidebar") {
                            drawerBody()
                        }
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            Box(modifier = Modifier.weight(1f)) {
                navBody()
            }
        }
    } else {
        ModalNavigationDrawer(
            drawerState = drawerState,
            // Gesture reveal is enabled only on the chat page; disabled elsewhere (but an open drawer can always be closed).
            gesturesEnabled = currentRoute == "chat" || drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(
                    drawerShape = RectangleShape,
                    drawerContainerColor = settingsPageBackground(),
                    drawerTonalElevation = 0.dp,
                    modifier = Modifier.width(drawerWidth())
                ) {
                    drawerBody()
                }
            }
        ) {
            navBody()
        }
    }

    // Update-check dialog (global host: shared by automatic checks and the manual check on the About page, covering all pages)
    val updateCheckState by settingsViewModel.updateCheckState.collectAsStateWithLifecycle()
    if (updateCheckState != UpdateCheckUiState.Idle) {
        val version = remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            }.getOrDefault("unknown")
        }
        UpdateCheckDialog(
            state = updateCheckState,
            currentVersion = version,
            onDismiss = { settingsViewModel.dismissUpdateCheck() },
            onOpenRelease = { tag ->
                openUrl(context, githubReleaseUrl(tag))
                settingsViewModel.dismissUpdateCheck()
            }
        )
    }

    // First-run onboarding overlay (above all routes, persistent at the top level, smooth transitions with zero flicker between pages).
    if (onboardingUiState.active) {
        OnboardingOverlay(
            stateHolder = onboardingStateHolder,
            coordinator = onboardingCoordinator,
            registry = onboardingRegistry,
            onNextStep = {
                val step = onboardingStateHolder.state.value.step
                when (step) {
                    OnboardingStep.OPEN_SIDEBAR -> {
                        scope.launch { drawerState.open() }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.ENTER_SETTINGS -> {
                        navController.navigate("settings")
                        scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.CONFIG_PROVIDER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_ADD -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_CONFIG_INFO -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_FETCH_MODELS -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isEmpty()) {
                            settingsViewModel.saveProvider(current.copy(models = listOf("deepseek-v4-flash")))
                        }
                        navController.popBackStack()
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.OPEN_MODEL_PICKER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_CHOOSE_MODEL -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isNotEmpty()) {
                            agentViewModel.setSessionProviderModel(current.id, current.effectiveModel.ifEmpty { current.models.first() })
                        }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SEND_MESSAGE -> {
                        onboardingCoordinator.complete()
                    }
                }
            },
            onTargetClick = {
                val step = onboardingStateHolder.state.value.step
                when (step) {
                    OnboardingStep.OPEN_SIDEBAR -> {
                        scope.launch { drawerState.open() }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.ENTER_SETTINGS -> {
                        navController.navigate("settings")
                        scope.launch { drawerState.snapTo(DrawerValue.Closed) }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.CONFIG_PROVIDER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_ADD -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_CONFIG_INFO -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.PROVIDER_FETCH_MODELS -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isEmpty()) {
                            settingsViewModel.saveProvider(current.copy(models = listOf("deepseek-v4-flash")))
                        }
                        navController.popBackStack()
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.OPEN_MODEL_PICKER -> {
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SIMULATE_CHOOSE_MODEL -> {
                        val current = settingsViewModel.providers.value.firstOrNull()
                        if (current != null && current.models.isNotEmpty()) {
                            agentViewModel.setSessionProviderModel(current.id, current.effectiveModel.ifEmpty { current.models.first() })
                        }
                        onboardingCoordinator.nextStep()
                    }
                    OnboardingStep.SEND_MESSAGE -> {
                        onboardingCoordinator.complete()
                    }
                }
            }
        )
    }
    }
    }
}

/** Failure toast for write operations on the sidebar files page (shared by create / rename / delete). */
private fun toastFileOpFailed(context: android.content.Context, messageRes: Int) {
    Toast.makeText(context, context.getString(messageRes), Toast.LENGTH_SHORT).show()
}

/**
 * Markdown link handling for the chat area: intercepts "container file path" links and hands them to [onOpenFile] to open the editor;
 * the rest (http/https/mailto and other URLs) go to the system default [delegate] (browser).
 *
 * Convention: the AI emits clickable paths as `[display text](path)`, where the path may carry a `:line` suffix (e.g. `~/workspace/a.kt:42`).
 */
private class FileAwareUriHandler(
    private val delegate: androidx.compose.ui.platform.UriHandler,
    private val onOpenFile: (path: String, line: Int) -> Unit
) : androidx.compose.ui.platform.UriHandler {
    override fun openUri(uri: String) {
        val filePath = asChatFilePath(uri)
        if (filePath == null) {
            delegate.openUri(uri)
            return
        }
        val (path, line) = splitPathAndLine(filePath)
        onOpenFile(path, line)
    }
}

/** Hierarchical schemes containing `//`: `http://`, `https://`, `file://`, `content://`, etc. */
private val HIERARCHICAL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

/**
 * URL-like schemes without `//` must be enumerated in an allowlist: scheme syntax permits `.`, so a file link in the workspace root
 * like `notes.md:12` is syntactically indistinguishable from `mailto:x`; treating "has a colon" as a URL would send
 * such links to the browser where they cannot open.
 */
private val OPAQUE_URL_SCHEME = Regex(
    "^(mailto|tel|sms|smsto|geo|data|market|intent|about|javascript):",
    RegexOption.IGNORE_CASE
)

private val TRAILING_LINE = Regex("^(.*):(\\d+)$")

/** Image formats that open in the built-in viewer when tapped in the file tree (same image detection as sendFile). */
private val PREVIEWABLE_IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

/** Installer package formats that invoke the system installer when tapped in the file tree. */
private val INSTALLABLE_APK_EXTS = setOf("apk", "apks", "xapk")

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"

/** Chat-area link target -> normalized file path; returns null when classified as a URL (non-`file` scheme). */
internal fun asChatFilePath(uri: String): String? {
    val hierarchical = HIERARCHICAL_SCHEME.find(uri)?.groupValues?.get(1)?.lowercase()
    return when {
        hierarchical == "file" -> android.net.Uri.decode(uri.removePrefix("file://"))
        hierarchical != null -> null // http/https/content etc. go to the system
        OPAQUE_URL_SCHEME.containsMatchIn(uri) -> null // mailto/tel etc.
        else -> uri // container-absolute or workspace-relative path, including bare filenames like `notes.md:12`
    }
}

/** Splits off the optional `:line` suffix; the line number is 0 when absent. */
internal fun splitPathAndLine(path: String): Pair<String, Int> {
    val cleaned = path.substringBefore('#').trim()
    val m = TRAILING_LINE.find(cleaned) ?: return cleaned to 0
    return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 0)
}
