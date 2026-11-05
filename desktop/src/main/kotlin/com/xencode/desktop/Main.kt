package com.xencode.desktop

// Desktop Window API. Since Compose Multiplatform 1.9 the desktop windowing
// composables live in androidx.compose.ui.window (the old
// androidx.compose.desktop.ui.window package no longer ships in the 1.9 jars).
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xencode.core.provider.PcConfig
import com.xencode.core.provider.PcConfigStore
import com.xencode.core.provider.ProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Dimension

/**
 * App-wide state holder: the loaded config (~/.xencode/pc-config.json), the
 * selected destination and the active workspace. Plain class + StateFlows, no
 * Android lifecycle machinery. Composables read it via collectAsState().
 */
class DesktopAppState {

    private val initialConfig: PcConfig = PcConfigStore.load()

    private val _config = MutableStateFlow(initialConfig)
    val config: StateFlow<PcConfig> = _config.asStateFlow()

    private val _destination = MutableStateFlow(Destination.CHAT)
    val destination: StateFlow<Destination> = _destination.asStateFlow()

    private val _workspace = MutableStateFlow(resolveWorkspace(initialConfig))
    val workspace: StateFlow<String> = _workspace.asStateFlow()

    fun activeProvider(): ProviderConfig? = config.value.activeProvider()

    fun navigate(destination: Destination) {
        _destination.value = destination
    }

    fun setWorkspace(path: String) {
        _workspace.value = path
    }

    /** Persists [next]; the in-memory config is only updated when the write succeeded. */
    fun saveConfig(next: PcConfig): Boolean {
        val saved = PcConfigStore.save(next)
        if (saved) _config.value = next
        return saved
    }

    /** One controller per (provider, workspace); null while no provider is configured. */
    fun buildController(provider: ProviderConfig?, workspace: String): DesktopAgentController? =
        provider?.let { DesktopAgentController(it, workspace) }

    companion object {
        /** Config default if set, otherwise the user home directory. */
        fun resolveWorkspace(config: PcConfig): String =
            config.defaultWorkspace?.trim()?.takeIf { it.isNotEmpty() }
                ?: (System.getProperty("user.home") ?: ".")
    }
}

/** Left navigation rail destinations. Glyphs are plain unicode so no icon library is needed. */
enum class Destination(val label: String, val glyph: String) {
    CHAT("Chat", "❯"),
    GOALS("Goals", "◆"),
    PROGRESS("Progress", "★"),
    SETTINGS("Settings", "⚙")
}

fun main() = application {
    val appState = remember { DesktopAppState() }
    Window(
        onCloseRequest = ::exitApplication,
        title = "XenCode",
        // Runtime window/taskbar icon, from src/main/resources (the packaging
        // icons in desktop/icons are handled by the nativeDistributions block).
        // painterResource(String) is deprecated in Compose 1.9 in favor of the
        // compose-resources library; it is kept here on purpose because it reads
        // straight from the JVM classpath, which is exactly the src/main/resources
        // layout this module ships.
        icon = painterResource("xencode-icon-256.png"),
        state = rememberWindowState(width = 1280.dp, height = 800.dp)
    ) {
        // Keep the shell window from being shrunk below the design minimum.
        // AWT mutation: run inside SideEffect so it happens after composition.
        SideEffect {
            window.minimumSize = Dimension(1100, 720)
        }
        XenCodeTheme {
            XenCodeApp(appState)
        }
    }
}

@Composable
fun XenCodeApp(appState: DesktopAppState) {
    val config by appState.config.collectAsState()
    val workspace by appState.workspace.collectAsState()
    val destination by appState.destination.collectAsState()
    val provider = config.activeProvider()

    // Recreate the agent (and its conversation) whenever the provider or workspace changes.
    // The controller lives here, outside the switch below, so switching panes keeps the chat.
    val controller = remember(provider, workspace) { appState.buildController(provider, workspace) }
    DisposableEffect(controller) {
        onDispose { controller?.dispose() }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XenCodeNavRail(
            destination = destination,
            onSelect = { appState.navigate(it) }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (destination) {
                    Destination.CHAT -> ChatPane(
                        controller = controller,
                        workspace = workspace,
                        onNavigateToSettings = { appState.navigate(Destination.SETTINGS) }
                    )
                    Destination.GOALS -> GoalsPane(appState)
                    Destination.PROGRESS -> ProgressPane(appState)
                    Destination.SETTINGS -> SettingsPane(appState)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            StatusBar(appState = appState, controller = controller)
        }
    }
}

@Composable
private fun XenCodeNavRail(destination: Destination, onSelect: (Destination) -> Unit) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Column(
                modifier = Modifier.padding(top = 20.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("⚡", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "XenCode",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    ) {
        Destination.entries.forEach { d ->
            NavigationRailItem(
                selected = destination == d,
                onClick = { onSelect(d) },
                icon = { Text(d.glyph, fontSize = 18.sp) },
                label = { Text(d.label, fontSize = 11.sp) }
            )
        }
    }
}

/** Bottom bar: active provider + model + workspace (and a live streaming dot). */
@Composable
private fun StatusBar(appState: DesktopAppState, controller: DesktopAgentController?) {
    val config by appState.config.collectAsState()
    val workspace by appState.workspace.collectAsState()
    val busy = controller?.busy?.collectAsState()?.value ?: false
    val provider = config.activeProvider()

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                if (busy) "● streaming" else "●",
                fontSize = 11.sp,
                color = if (busy) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                "Provider: ${provider?.name ?: "none"}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Model: ${provider?.model ?: "-"}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "Workspace: $workspace",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
