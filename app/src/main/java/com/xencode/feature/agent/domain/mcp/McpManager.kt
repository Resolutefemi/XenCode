package com.xencode.feature.agent.domain.mcp

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.ContainerProfile
import com.xencode.feature.agent.domain.container.LinuxContainerEngine
import com.xencode.feature.agent.domain.tool.ToolRegistry
import com.xencode.feature.settings.data.repository.ContainerSettingsRepository
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

data class McpServerStatus(
    val name: String,
    val state: State,
    val toolCount: Int = 0,
    val error: String? = null
) {
    enum class State { CONNECTING, CONNECTED, FAILED, DISABLED }
}

// reloadMutex serializes reconnects, avoiding concurrent register/unregister races from rapid taps in Settings.
@Singleton
class McpManager @Inject constructor(
    private val configRepository: McpConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val okHttpClient: OkHttpClient,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val containerSettingsRepository: ContainerSettingsRepository
) {
    private companion object {
        const val TAG = "McpManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloadMutex = Mutex()

    private val activeClients = mutableMapOf<String, McpClient>()
    private val registeredToolNames = mutableSetOf<String>()

    private val _statuses = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val statuses: StateFlow<List<McpServerStatus>> = _statuses.asStateFlow()

    fun start() {
        // Auto-reload on current workspace switch (the first frame emits the current value immediately, i.e. reconnect at startup;
        // on a project switch both the merged config and the stdio tools' project path change, so connections must be rebuilt).
        scope.launch {
            workspaceRepository.current.collectLatest {
                reload()
            }
        }
        // Config file edited directly from outside (in-container or by hand): auto-reload within seconds so add/remove/enable/disable take effect immediately.
        scope.launch {
            configRepository.externalChanges.collect {
                FileLogger.i(TAG, "Detected external MCP config change; auto-reloading")
                reload()
            }
        }
        // Container profile switch: stdio server processes run on the old container's rootfs and must be rebuilt to use the new container;
        // HTTP servers don't depend on the container and are unaffected. drop(1) skips the startup first frame (already handled by reload).
        scope.launch {
            containerSettingsRepository.activeProfileIdFlow.drop(1).collect {
                reloadStdioServers()
            }
        }
        // Default container change: in remote mode stdio servers run on the default container, so they also need rebuilding.
        scope.launch {
            containerSettingsRepository.defaultContainerIdFlow.drop(1).collect {
                if (currentActiveProfile().mode == ExecutionMode.REMOTE_SSH) {
                    reloadStdioServers()
                }
            }
        }
    }

    suspend fun reload() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers()
        FileLogger.i(TAG, "Reloading MCP config, ${servers.size} servers total")

        teardown()

        if (servers.isEmpty()) {
            _statuses.value = emptyList()
            return@withLock
        }

        // Mark all servers "connecting/disabled" first so the UI gets immediate feedback.
        _statuses.value = servers.map { cfg ->
            McpServerStatus(
                name = cfg.name,
                state = if (cfg.enabled) McpServerStatus.State.CONNECTING else McpServerStatus.State.DISABLED
            )
        }

        // Connect all enabled servers in parallel; each fails independently.
        val results = withContext(Dispatchers.IO) {
            servers.filter { it.enabled }.map { cfg ->
                async { connectOne(cfg) }
            }.awaitAll()
        }

        // Merge disabled entries with connection results, keeping the original order.
        val byName = results.associateBy { it.name }
        _statuses.value = servers.map { cfg ->
            byName[cfg.name] ?: McpServerStatus(cfg.name, McpServerStatus.State.DISABLED)
        }
    }

    private suspend fun connectOne(cfg: McpServerConfig): McpServerStatus {
        val t0 = System.currentTimeMillis()
        return try {
            FileLogger.i(TAG, "[${cfg.name}] connecting (${if (cfg.isStdio) "stdio" else "HTTP"})")
            val transport = if (cfg.isStdio) {
                // stdio servers run on the "runtime container": the current container in local mode, the default container in remote SSH mode.
                // If the container is not ready, do not auto-initialize — fail directly and point to the terminal page to finish setup.
                val runtimeProfile = resolveMcpRuntimeProfile()
                containerEngine.notReadyHintFor(runtimeProfile)?.let {
                    throw IllegalStateException(it)
                }
                StdioTransport(
                    serverName = cfg.name,
                    engine = containerEngine,
                    program = cfg.command!!,
                    programArgs = cfg.args,
                    projectPath = workspaceRepository.currentPath(),
                    extraEnv = cfg.env,
                    runtimeProfile = runtimeProfile
                )
            } else {
                StreamableHttpTransport(
                    endpoint = cfg.url.orEmpty(),
                    client = okHttpClient,
                    extraHeaders = cfg.headers
                )
            }
            val client = McpClient(serverName = cfg.name, transport = transport)
            client.connect()

            val tools = client.tools.map { McpTool(client, it) }
            val enabledTools = tools.filter { it.remoteName !in cfg.disabledTools }
            synchronized(activeClients) {
                activeClients[cfg.name] = client
                enabledTools.forEach { tool ->
                    toolRegistry.register(tool.name, tool)
                    registeredToolNames.add(tool.name)
                }
            }
            FileLogger.i(TAG, "[${cfg.name}] connected, registered ${enabledTools.size}/${tools.size} tools (${System.currentTimeMillis() - t0}ms)")
            McpServerStatus(cfg.name, McpServerStatus.State.CONNECTED, toolCount = enabledTools.size)
        } catch (e: Exception) {
            FileLogger.e(TAG, "[${cfg.name}] connection failed (${System.currentTimeMillis() - t0}ms)", e)
            McpServerStatus(cfg.name, McpServerStatus.State.FAILED, error = e.message)
        }
    }

    fun getServerTools(serverName: String): List<McpToolDescriptor> {
        return synchronized(activeClients) {
            activeClients[serverName]?.tools ?: emptyList()
        }
    }

    /**
     * Reconnect just one server (used by save/enable-toggle/edit refresh); other servers' connections and registered tools are unaffected.
     * Silently returns when no config with that name exists.
     */
    suspend fun reloadServer(name: String) = reloadMutex.withLock {
        val cfg = configRepository.getEffectiveServers().firstOrNull { it.name == name } ?: return@withLock
        teardownServer(name)
        if (_statuses.value.none { it.name == name }) {
            _statuses.value = _statuses.value + McpServerStatus(name, McpServerStatus.State.CONNECTING)
        }
        if (!cfg.enabled) {
            _statuses.value = _statuses.value.map {
                if (it.name == name) McpServerStatus(name, McpServerStatus.State.DISABLED) else it
            }
            return@withLock
        }
        reconnectOne(cfg)
    }

    /**
     * Reconnect only servers that are currently unconnected (connected ones are skipped); fallback for new sessions: manageMcp add/remove only edits config without reconnecting,
     * telling the user it "takes effect next session"; this method lets a new session actually connect the unconnected servers without disturbing connected tools.
     */
    suspend fun reconnectUnconnected() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers().filter { it.enabled }
        for (cfg in servers) {
            val connected = synchronized(activeClients) { activeClients.containsKey(cfg.name) }
            if (connected) continue
            reconnectOne(cfg)
        }
    }

    /**
     * Asynchronous fallback reconnect for unconnected servers (does not block the caller). Creating a new session should not wait for MCP:
     * a stdio server's first run can hang long on dependency downloads or handshake timeouts, and waiting synchronously would make "new session" look frozen.
     */
    fun reconnectUnconnectedAsync() {
        scope.launch { reconnectUnconnected() }
    }

    private suspend fun reconnectOne(cfg: McpServerConfig) {
        if (_statuses.value.none { it.name == cfg.name }) {
            _statuses.value = _statuses.value + McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING)
        }
        _statuses.value = _statuses.value.map {
            if (it.name == cfg.name) McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING) else it
        }
        val result = withContext(Dispatchers.IO) { connectOne(cfg) }
        _statuses.value = _statuses.value.map { if (it.name == cfg.name) result else it }
    }

    /** Resolve the runtime container for a stdio server: the current profile in local mode, the default container in remote SSH mode. */
    private suspend fun resolveMcpRuntimeProfile(): ContainerProfile {
        val active = currentActiveProfile()
        return if (active.mode == ExecutionMode.REMOTE_SSH) resolveDefaultContainerProfile() else active
    }

    /** Currently active profile: resolved by id from the configured list, falling back to the built-in Alpine when absent. */
    private suspend fun currentActiveProfile(): ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** Default container: the configured id in local PRoot mode; falls back to built-in Alpine when missing/deleted/remote. */
    private suspend fun resolveDefaultContainerProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * Rebuild all stdio servers after container-related config changes (HTTP doesn't depend on the container, left alone).
     * Old processes are pinned to the old container's rootfs and must be torn down before the new container can start them; one server failing doesn't affect the others.
     */
    private suspend fun reloadStdioServers() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers().filter { it.enabled && it.isStdio }
        if (servers.isEmpty()) return@withLock
        FileLogger.i(TAG, "Container config changed; rebuilding ${servers.size} stdio MCP servers")
        for (cfg in servers) {
            teardownServer(cfg.name)
            reconnectOne(cfg)
        }
    }

    /** When removing a server, only disconnect it and unregister its tools; other servers are unaffected. */
    suspend fun removeServer(name: String) = reloadMutex.withLock {
        teardownServer(name)
        _statuses.value = _statuses.value.filterNot { it.name == name }
    }

    private fun teardownServer(name: String) {
        synchronized(activeClients) {
            val client = activeClients.remove(name) ?: return
            runCatching { client.close() }
            client.tools.forEach { tool ->
                toolRegistry.unregister(tool.name)
                registeredToolNames.remove(tool.name)
            }
        }
    }

    private fun teardown() {
        synchronized(activeClients) {
            registeredToolNames.forEach { toolRegistry.unregister(it) }
            registeredToolNames.clear()
            activeClients.values.forEach { runCatching { it.close() } }
            activeClients.clear()
        }
    }
}
