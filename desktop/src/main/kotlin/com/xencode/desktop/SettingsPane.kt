package com.xencode.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xencode.core.provider.ChatClient
import com.xencode.core.provider.PcConfig
import com.xencode.core.provider.PcConfigStore
import com.xencode.core.provider.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// ---------------------------------------------------------------------------
// Pure helpers. No Compose dependency below this line, so the add-provider
// validation, the config mutations and the probe rendering are unit-tested
// without a UI (see SettingsLogicTest).
// ---------------------------------------------------------------------------

/** Add-provider form fields, used as keys of the per-field error map. */
enum class ProviderField { NAME, KIND, BASE_URL, API_KEY, MODEL, TEMPERATURE, MAX_TOKENS }

/** Values known to satisfy [ProviderConfig.validate]; used to isolate one field at a time. */
private const val PROBE_NAME = "probe"
private const val PROBE_KIND = "openai"
private const val PROBE_URL = "https://probe.invalid/v1"
private const val PROBE_KEY = "probe-key"
private const val PROBE_MODEL = "probe-model"

/**
 * Editable state of the add-provider form. Every field stays a raw string so
 * the UI never has to guess what an invalid number looked like; parsing and
 * validation happen in [buildProvider]/[fieldErrors].
 */
data class ProviderFormState(
    val name: String = "",
    val kind: String = "openai",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val temperature: String = "",
    val maxTokens: String = ""
) {
    fun buildProvider(): ProviderConfig = ProviderConfig(
        name = name.trim(),
        kind = kind.trim().lowercase(),
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        temperature = temperature.trim().toDoubleOrNull(),
        maxTokens = maxTokens.trim().toIntOrNull()
    )

    /**
     * Field-level validation. Wire-level rules are delegated to
     * [ProviderConfig.validate] (one field isolated at a time, with all other
     * fields replaced by known-good probe values, so each field surfaces its
     * own real error message); duplicates and number parsing are checked here.
     * An empty map means the form is ready to save.
     */
    fun fieldErrors(existingNames: List<String>): Map<ProviderField, String> {
        val errors = LinkedHashMap<ProviderField, String>()
        val trimmedName = name.trim()
        when {
            trimmedName.isEmpty() -> errors[ProviderField.NAME] = "Provider name is empty"
            existingNames.any { it.equals(trimmedName, ignoreCase = true) } ->
                errors[ProviderField.NAME] = "A provider named \"$trimmedName\" already exists"
        }
        val candidate = buildProvider()
        for (field in listOf(
            ProviderField.NAME, ProviderField.KIND, ProviderField.BASE_URL,
            ProviderField.API_KEY, ProviderField.MODEL
        )) {
            if (field in errors) continue
            isolatedError(candidate, field)?.let { errors[field] = it }
        }
        temperatureError(temperature)?.let { errors[ProviderField.TEMPERATURE] = it }
        maxTokensError(maxTokens)?.let { errors[ProviderField.MAX_TOKENS] = it }
        return errors
    }

    /** [ProviderConfig.validate] message for one field, with all other fields known-good. */
    private fun isolatedError(candidate: ProviderConfig, field: ProviderField): String? {
        val probed = when (field) {
            ProviderField.NAME -> candidate.copy(kind = PROBE_KIND, baseUrl = PROBE_URL, apiKey = PROBE_KEY, model = PROBE_MODEL)
            ProviderField.KIND -> candidate.copy(name = PROBE_NAME, baseUrl = PROBE_URL, apiKey = PROBE_KEY, model = PROBE_MODEL)
            ProviderField.BASE_URL -> candidate.copy(name = PROBE_NAME, kind = PROBE_KIND, apiKey = PROBE_KEY, model = PROBE_MODEL)
            ProviderField.API_KEY -> candidate.copy(name = PROBE_NAME, kind = PROBE_KIND, baseUrl = PROBE_URL, model = PROBE_MODEL)
            ProviderField.MODEL -> candidate.copy(name = PROBE_NAME, kind = PROBE_KIND, baseUrl = PROBE_URL, apiKey = PROBE_KEY)
            else -> return null
        }
        return probed.validate()
    }
}

/** Optional temperature must parse to a number in [0.0, 2.0]; blank means "not set". */
internal fun temperatureError(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    val v = t.toDoubleOrNull() ?: return "Temperature must be a number, e.g. 0.7"
    return if (v < 0.0 || v > 2.0) "Temperature must be between 0.0 and 2.0" else null
}

/** Optional max tokens must parse to a positive whole number; blank means "not set". */
internal fun maxTokensError(raw: String): String? {
    val t = raw.trim()
    if (t.isEmpty()) return null
    val v = t.toIntOrNull() ?: return "Max tokens must be a positive whole number, e.g. 4096"
    return if (v <= 0) "Max tokens must be a positive whole number, e.g. 4096" else null
}

/** Name the config treats as active: the explicit choice, or the first provider. */
internal fun effectiveActiveName(config: PcConfig): String? =
    config.active ?: config.providers.firstOrNull()?.name

internal fun withActiveProvider(config: PcConfig, name: String): PcConfig =
    config.copy(active = name)

/** The first provider added to an empty config also becomes the active one. */
internal fun withAddedProvider(config: PcConfig, provider: ProviderConfig): PcConfig =
    config.copy(providers = config.providers + provider, active = config.active ?: provider.name)

/** Removing the active provider promotes the first remaining one (or clears active). */
internal fun withRemovedProvider(config: PcConfig, name: String): PcConfig {
    val remaining = config.providers.filterNot { it.name == name }
    val active = when {
        remaining.isEmpty() -> null
        config.active == null || config.active == name -> remaining.first().name
        else -> config.active
    }
    return config.copy(providers = remaining, active = active)
}

/** Outcome of the "Test connection" probe: first streamed delta, failure, or silence. */
sealed class ConnectionProbe {
    data class Connected(val firstDelta: String) : ConnectionProbe()
    data class Failed(val message: String) : ConnectionProbe()
    object NoReply : ConnectionProbe()
}

internal val ConnectionProbe.ok: Boolean get() = this is ConnectionProbe.Connected

internal fun ConnectionProbe.describe(): String = when (this) {
    is ConnectionProbe.Connected -> "Connected — first reply: \"$firstDelta\""
    is ConnectionProbe.Failed -> "Failed: $message"
    ConnectionProbe.NoReply -> "No data within the probe timeout (the endpoint may be slow, blocked or unreachable)."
}

/**
 * Runs the "Test connection" probe: a tiny prompt streamed through the shared
 * [ChatClient] on [Dispatchers.IO]; the first text delta proves the endpoint
 * works end to end. One client for the whole app because core's ChatClient
 * exposes no close(), so reusing a single engine keeps repeated probes from
 * piling up idle connections.
 */
object ConnectionProbes {

    const val PROMPT = "Reply with the single word: ready"
    const val TIMEOUT_MS = 30_000L

    private val client by lazy { ChatClient() }

    suspend fun probe(config: ProviderConfig, timeoutMs: Long = TIMEOUT_MS): ConnectionProbe =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                client.streamChat(
                    config,
                    listOf(ChatClient.ChatMessage(role = "user", content = PROMPT))
                ).mapNotNull { event ->
                    when (event) {
                        is ChatClient.ChatEvent.TextDelta ->
                            event.text.trim().takeIf { it.isNotEmpty() }
                                ?.let { ConnectionProbe.Connected(it) }
                        is ChatClient.ChatEvent.Failed -> ConnectionProbe.Failed(event.message)
                        else -> null
                    }
                }.firstOrNull()
            } ?: ConnectionProbe.NoReply
        }
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

/** One-line feedback strip; red for errors, primary-tinted for success. */
private data class StatusLine(val message: String, val error: Boolean = false)

/**
 * Settings pane: provider management for `~/.xencode/pc-config.json` (via
 * [PcConfigStore] through [DesktopAppState.saveConfig]) — provider list with
 * masked keys, set-active radio, remove, add-provider form with per-field
 * errors, per-provider "Test connection" probe — plus the default workspace.
 */
@Composable
fun SettingsPane(appState: DesktopAppState) {
    val config by appState.config.collectAsState()
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<StatusLine?>(null) }
    var probing by remember { mutableStateOf<String?>(null) }
    var probeResult by remember { mutableStateOf<Pair<String, ConnectionProbe>?>(null) }

    fun persist(next: PcConfig, success: StatusLine) {
        if (appState.saveConfig(next)) {
            status = success
        } else {
            status = StatusLine(
                "Could not write ${PcConfigStore.defaultPath()} — check that the directory is writable.",
                error = true
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Stored in ${PcConfigStore.defaultPath()} — the same file the CLI reads.",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        status?.let { StatusCard(it) }

        Text(
            "Providers",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (config.providers.isEmpty()) {
            Text(
                "No providers yet. Add one below — any OpenAI-compatible endpoint works, plus the native Anthropic API.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        config.providers.forEach { provider ->
            ProviderCard(
                provider = provider,
                active = effectiveActiveName(config) == provider.name,
                anyProbeRunning = probing != null,
                thisOneProbing = probing == provider.name,
                probe = probeResult?.takeIf { it.first == provider.name }?.second,
                onSetActive = {
                    persist(
                        withActiveProvider(config, provider.name),
                        StatusLine("${provider.name} is now the active provider.")
                    )
                },
                onRemove = {
                    persist(
                        withRemovedProvider(config, provider.name),
                        StatusLine("Removed provider ${provider.name}.")
                    )
                },
                onTest = {
                    if (probing == null) {
                        probing = provider.name
                        probeResult = null
                        scope.launch {
                            val result = ConnectionProbes.probe(provider)
                            probing = null
                            probeResult = provider.name to result
                        }
                    }
                }
            )
        }

        AddProviderCard(
            existingNames = config.providers.map { it.name },
            onAdd = { form ->
                val provider = form.buildProvider()
                val wasEmpty = config.providers.isEmpty()
                if (appState.saveConfig(withAddedProvider(config, provider))) {
                    status = if (wasEmpty) {
                        StatusLine("Added provider ${provider.name} — it is now the active one.")
                    } else {
                        StatusLine("Added provider ${provider.name}.")
                    }
                    true
                } else {
                    status = StatusLine(
                        "Could not write ${PcConfigStore.defaultPath()} — check that the directory is writable.",
                        error = true
                    )
                    false
                }
            }
        )

        WorkspaceCard(
            config = config,
            onSave = { path ->
                val trimmed = path.trim()
                val next = config.copy(defaultWorkspace = trimmed.ifBlank { null })
                if (appState.saveConfig(next)) {
                    val resolved = DesktopAppState.resolveWorkspace(next)
                    appState.setWorkspace(resolved)
                    status = StatusLine("Workspace saved — the agent now works in $resolved")
                    true
                } else {
                    status = StatusLine(
                        "Could not write ${PcConfigStore.defaultPath()} — check that the directory is writable.",
                        error = true
                    )
                    false
                }
            }
        )

        AgentSafetyCard(workspace = appState.workspace.collectAsState().value)
        McpCard(workspace = appState.workspace.collectAsState().value)
        HooksCard(workspace = appState.workspace.collectAsState().value)
    }
}

@Composable
private fun StatusCard(status: StatusLine) {
    Surface(
        color = if (status.error) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            status.message,
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (status.error) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    active: Boolean,
    anyProbeRunning: Boolean,
    thisOneProbing: Boolean,
    probe: ConnectionProbe?,
    onSetActive: () -> Unit,
    onRemove: () -> Unit,
    onTest: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                RadioButton(selected = active, onClick = if (active) null else onSetActive)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (active) "${provider.name} — active" else provider.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${provider.kind} · ${provider.model}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        provider.baseUrl,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onTest, enabled = !anyProbeRunning) {
                    if (thisOneProbing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text("Test connection")
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
            OutlinedTextField(
                value = Formatters.maskApiKey(provider.apiKey),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text("API key (masked)") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth()
            )
            probe?.let { result ->
                Text(
                    text = result.describe(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun AddProviderCard(
    existingNames: List<String>,
    onAdd: (ProviderFormState) -> Boolean
) {
    var form by remember { mutableStateOf(ProviderFormState()) }
    var showErrors by remember { mutableStateOf(false) }
    val errors = form.fieldErrors(existingNames)

    fun fieldError(field: ProviderField): String? = if (showErrors) errors[field] else null

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Add a provider",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            OutlinedTextField(
                value = form.name,
                onValueChange = { form = form.copy(name = it) },
                label = { Text("Name") },
                singleLine = true,
                isError = fieldError(ProviderField.NAME) != null,
                supportingText = fieldError(ProviderField.NAME)
                    ?.let { message -> { Text(message, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth()
            )
            KindField(
                kind = form.kind,
                error = fieldError(ProviderField.KIND),
                onKindChange = { form = form.copy(kind = it) }
            )
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = { form = form.copy(baseUrl = it) },
                label = { Text("Base URL (e.g. https://api.openai.com/v1)") },
                singleLine = true,
                isError = fieldError(ProviderField.BASE_URL) != null,
                supportingText = fieldError(ProviderField.BASE_URL)
                    ?.let { message -> { Text(message, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.apiKey,
                onValueChange = { form = form.copy(apiKey = it) },
                label = { Text("API key ('none' is fine for local endpoints)") },
                singleLine = true,
                isError = fieldError(ProviderField.API_KEY) != null,
                supportingText = fieldError(ProviderField.API_KEY)
                    ?.let { message -> { Text(message, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = form.model,
                onValueChange = { form = form.copy(model = it) },
                label = { Text("Model (e.g. gpt-4o-mini)") },
                singleLine = true,
                isError = fieldError(ProviderField.MODEL) != null,
                supportingText = fieldError(ProviderField.MODEL)
                    ?.let { message -> { Text(message, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                OutlinedTextField(
                    value = form.temperature,
                    onValueChange = { form = form.copy(temperature = it) },
                    label = { Text("Temperature (optional)") },
                    singleLine = true,
                    isError = fieldError(ProviderField.TEMPERATURE) != null,
                    supportingText = fieldError(ProviderField.TEMPERATURE)
                        ?.let { message -> { Text(message, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = form.maxTokens,
                    onValueChange = { form = form.copy(maxTokens = it) },
                    label = { Text("Max tokens (optional)") },
                    singleLine = true,
                    isError = fieldError(ProviderField.MAX_TOKENS) != null,
                    supportingText = fieldError(ProviderField.MAX_TOKENS)
                        ?.let { message -> { Text(message, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        if (errors.isEmpty()) {
                            if (onAdd(form)) {
                                form = ProviderFormState()
                                showErrors = false
                            }
                        } else {
                            showErrors = true
                        }
                    }
                ) { Text("Add provider") }
                if (showErrors && errors.isNotEmpty()) {
                    Text(
                        "Fix the fields marked in red to save this provider.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** Kind selector: read-only text field plus a dropdown with the three wire protocols. */
@Composable
private fun KindField(kind: String, error: String?, onKindChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(
            value = kind,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Kind") },
            isError = error != null,
            supportingText = error
                ?.let { message -> { Text(message, color = MaterialTheme.colorScheme.error) } },
            trailingIcon = {
                IconButton(onClick = { expanded = true }) {
                    Text(if (expanded) "▴" else "▾", fontSize = 16.sp)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("openai", "anthropic", "gemini").forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onKindChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun WorkspaceCard(config: PcConfig, onSave: (String) -> Boolean) {
    // Re-seed the draft whenever the persisted value changes (e.g. after saving).
    var draft by remember(config.defaultWorkspace) { mutableStateOf(config.defaultWorkspace ?: "") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Default workspace",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Absolute path the chat agent works in. Leave empty to use your home directory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Workspace path") },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onSave(draft) },
                    enabled = draft.trim() != (config.defaultWorkspace ?: "")
                ) { Text("Save workspace") }
            }
        }
    }
}

/**
 * Agent-safety card: permission mode (read-only / default / yolo) plus the
 * per-rule globs (allow / ask / deny), persisted to
 * `<workspace>/.xencode/permissions.json` — the same file the CLI reads.
 */
@Composable
private fun AgentSafetyCard(workspace: String) {
    val store = remember(workspace) { com.xencode.core.tools.PermissionStore(java.io.File(workspace)) }
    var cfg by remember(workspace) { mutableStateOf(store.load()) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Agent safety (workspace)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Tool permission policy for this workspace: read-only blocks every mutation, default asks before destructive commands, yolo allows everything (still sandboxed). Rules: tool:<glob> or cmd:<glob>, e.g. cmd:git push*, deny wins over ask wins over allow.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("read-only", "default", "yolo").forEach { mode ->
                    val selected = cfg.mode.lowercase() == mode
                    OutlinedButton(
                        onClick = { cfg = cfg.copy(mode = mode) },
                        colors = if (selected) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) else ButtonDefaults.outlinedButtonColors()
                    ) { Text(mode) }
                }
            }
            listOf(
                "allow" to "Allow rules (one per line)",
                "ask" to "Ask rules (decision card)",
                "deny" to "Deny rules (hard block)"
            ).forEach { (field, label) ->
                var value by remember(workspace, field) {
                    mutableStateOf(cfg.let { c -> when (field) { "allow" -> c.allow; "ask" -> c.ask; else -> c.deny } }.joinToString("\n"))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.fillMaxWidth()
                )
                // Keep the draft in sync for the save button.
                LaunchedEffect(value) {
                    val lines = value.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    cfg = when (field) {
                        "allow" -> cfg.copy(allow = lines)
                        "ask" -> cfg.copy(ask = lines)
                        else -> cfg.copy(deny = lines)
                    }
                }
            }
            Button(
                onClick = { store.save(cfg) },
                enabled = true
            ) { Text("Save agent safety") }
        }
    }
}

/**
 * MCP card: edit `<workspace>/.xencode/mcp.json` by table (name / command /
 * args), persisted through [com.xencode.core.mcp.McpRegistry].
 */
@Composable
private fun McpCard(workspace: String) {
    val registry = remember(workspace) { com.xencode.core.mcp.McpRegistry(java.io.File(workspace)) }
    var servers by remember(workspace) { mutableStateOf(registry.loadConfig()) }
    var newName by remember { mutableStateOf("") }
    var newCommand by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "MCP servers (workspace)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Model Context Protocol servers over stdio; their tools appear to the agent as mcp_<server>_<tool>. Env and full JSON live in .xencode/mcp.json.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (servers.isEmpty()) {
                Text("No servers configured yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            servers.forEach { (name, cfg) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            cfg.command + if (cfg.args.isEmpty()) "" else " " + cfg.args.joinToString(" "),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = {
                        servers = servers - name
                        registry.saveConfig(servers)
                    }) { Text("Remove") }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = newCommand,
                    onValueChange = { newCommand = it },
                    label = { Text("Command (args after space)") },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    modifier = Modifier.weight(2f)
                )
                Button(
                    enabled = newName.isNotBlank() && newCommand.isNotBlank(),
                    onClick = {
                        val parts = newCommand.trim().split(Regex("\\s+"))
                        val cfg = com.xencode.core.mcp.McpRegistry.ServerConfig(command = parts.first(), args = parts.drop(1))
                        servers = servers + (newName.trim() to cfg)
                        registry.saveConfig(servers)
                        newName = ""
                        newCommand = ""
                    }
                ) { Text("Add") }
            }
        }
    }
}

/**
 * Hooks card: pre/post tool-use shell hooks, persisted to
 * `<workspace>/.xencode/hooks.json`. Pre-hooks block the tool call when they
 * exit non-zero.
 */
@Composable
private fun HooksCard(workspace: String) {
    val store = remember(workspace) { com.xencode.core.tools.HookStore(java.io.File(workspace)) }
    var pre by remember(workspace) { mutableStateOf(store.load().preToolUse.joinToString("\n") { "${it.matcher} = ${it.command}" }) }
    var post by remember(workspace) { mutableStateOf(store.load().postToolUse.joinToString("\n") { "${it.matcher} = ${it.command}" }) }

    fun parse(lines: String) = lines.lines().mapNotNull { line ->
        val idx = line.indexOf('=')
        if (idx <= 0) null
        else com.xencode.core.tools.Hooks.Hook(
            matcher = line.substring(0, idx).trim().ifBlank { "*" },
            command = line.substring(idx + 1).trim()
        ).takeIf { it.command.isNotBlank() }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "Tool hooks (workspace)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "One per line: <tool glob> = <shell command>. Pre-tool hooks run first (non-zero exit blocks the call, stdout becomes the reason); post hooks get XENCODE_HOOK_TOOL/ARGS/RESULT in the environment.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = pre,
                onValueChange = { pre = it },
                label = { Text("Pre-tool hooks") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = post,
                onValueChange = { post = it },
                label = { Text("Post-tool hooks") },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                store.save(com.xencode.core.tools.Hooks.Config(preToolUse = parse(pre), postToolUse = parse(post)))
            }) { Text("Save hooks") }
        }
    }
}
