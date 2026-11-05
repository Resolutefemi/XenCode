package com.xencode.feature.workspace.domain.repository

import com.xencode.core.security.KeystoreCipher
import com.xencode.core.util.FileLogger
import com.xencode.core.watch.FileChangeHub
import com.xencode.core.watch.WatchFilter
import com.xencode.feature.agent.domain.container.SshHostKeyStore
import com.xencode.feature.agent.domain.container.SshHostKeyVerifier
import com.xencode.feature.agent.domain.container.SshLoginKeyStore
import com.xencode.feature.agent.domain.container.SshPrivateKeyStore
import com.xencode.feature.agent.domain.container.friendlySshError
import com.xencode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.xencode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.xencode.feature.workspace.data.local.entity.RemoteMountEntity
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import com.xencode.feature.workspace.domain.model.RemoteConnection
import com.xencode.feature.workspace.domain.model.RemoteMount
import com.xencode.feature.workspace.domain.model.RemoteProtocol
import com.xencode.feature.workspace.domain.model.SyncConnectionState
import com.xencode.feature.workspace.domain.remote.RemoteAuth
import com.xencode.feature.workspace.domain.remote.SyncEngine
import com.xencode.feature.workspace.domain.remote.ftp.FtpSyncClient
import com.xencode.feature.workspace.domain.remote.sftp.SftpSyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteRepository @Inject constructor(
    private val dao: RemoteConnectionDao,
    private val syncSettings: com.xencode.feature.settings.data.repository.SyncSettingsRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val hostKeyStore: SshHostKeyStore,
    private val hostKeyVerifier: SshHostKeyVerifier,
    private val privateKeyStore: SshPrivateKeyStore,
    private val loginKeyStore: SshLoginKeyStore,
    private val fileChangeHub: FileChangeHub
) {
    private val activeEngines = ConcurrentHashMap<String, SyncEngine>()
    private val activeEngineIds = MutableStateFlow<Set<String>>(emptySet())

    /** Live connection health per mount, written back by SyncEngine.connectionState. */
    private val mountStates = MutableStateFlow<Map<String, SyncConnectionState>>(emptyMap())
    private val stateJobs = ConcurrentHashMap<String, Job>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Auto-connect retry coroutine per mount; cancelled on workspace switch or manual disconnect. */
    private val autoConnectJobs = ConcurrentHashMap<String, Job>()

    /** Local file-change subscription coroutine per mount; cancelled when the mount is disconnected. */
    private val watchJobs = ConcurrentHashMap<String, Job>()

    init {
        // Follows the current workspace: on app startup (workspace ready) and workspace switches, automatically disconnects mounts outside the current workspace
        // and connects the current workspace's mounts whose "auto-connect on app start" is checked (retrying with backoff until success).
        scope.launch {
            workspaceRepository.current.collect { workspace ->
                if (workspace != null) {
                    syncMountsToWorkspace(workspace.path)
                }
            }
        }
    }

    /** Aligns the active mount set to the current workspace. */
    private suspend fun syncMountsToWorkspace(workspacePath: String) {
        val mounts = dao.getAllMountsOnce()
        // 1. Disconnect active mounts that do not belong to the current workspace (including manually connected ones, so after a switch only the current workspace syncs)
        activeEngineIds.value
            .filter { activeId ->
                mounts.firstOrNull { it.id == activeId }?.let { !belongsTo(it, workspacePath) } ?: true
            }
            .forEach { disconnectMount(it) }
        // 2. Auto-connect the current workspace's mounts with autoConnect checked
        mounts.filter { it.autoConnect && belongsTo(it, workspacePath) && it.id !in activeEngineIds.value }
            .forEach { connectMountWithRetry(it.id, workspacePath) }
    }

    private fun belongsTo(mount: RemoteMountEntity, workspacePath: String): Boolean =
        mount.localMountPath == workspacePath || mount.localMountPath.startsWith("$workspacePath/")

    /** Auto-connect with backoff retry: until success, or until the mount no longer belongs to the current workspace / is manually disconnected. */
    private fun connectMountWithRetry(mountId: String, workspacePath: String) {
        autoConnectJobs[mountId]?.cancel()
        autoConnectJobs[mountId] = scope.launch {
            var backoffMs = 30_000L
            while (isActive) {
                val mount = dao.getMountById(mountId) ?: break
                if (!belongsTo(mount, workspacePath)) break
                if (mountId in activeEngineIds.value) break
                val result = connectMount(mountId)
                if (result.isSuccess) break
                FileLogger.w(TAG, "Auto-connect mount ${mount.localMountPath} failed: ${result.exceptionOrNull()?.message}, retrying in ${backoffMs / 1000}s")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(5 * 60_000L)
            }
        }
    }

    private companion object {
        const val TAG = "RemoteRepository"
    }

    fun getConnections(): Flow<List<RemoteConnection>> = dao.getAllConnections().map { list ->
        list.map { it.toDomainModel() }
    }
    
    fun getMounts(): Flow<List<RemoteMount>> = combine(
        dao.getAllMounts(),
        activeEngineIds,
        mountStates
    ) { list, activeIds, states ->
        list.map { mountEntity ->
            val connEntity = dao.getConnectionById(mountEntity.connectionId)
            mountEntity.toDomainModel(connEntity?.toDomainModel()).copy(
                isActive = activeIds.contains(mountEntity.id),
                connectionState = states[mountEntity.id]
            )
        }
    }

    suspend fun addConnection(conn: RemoteConnection, auth: RemoteAuth) {
        val authType = if (auth is RemoteAuth.Password) "PASSWORD" else "PRIVATE_KEY"
        val authData = if (auth is RemoteAuth.Password) auth.password else (auth as RemoteAuth.PrivateKey).privateKeyPath
        val passphrase = if (auth is RemoteAuth.PrivateKey) auth.passphrase else null
        // On edit (updateConnection reuses this method) keep the original creation time so the sort position does not jump
        val existing = dao.getConnectionById(conn.id)
        val entity = RemoteConnectionEntity(
            id = conn.id,
            name = conn.name,
            protocol = conn.protocol,
            host = conn.host,
            port = conn.port,
            username = conn.username,
            authType = authType,
            authData = if (authType == "PASSWORD") KeystoreCipher.encryptString(authData) else authData,
            // The key entry is authoritative for passphrases. The passphrase is mirrored onto the connection only when the key's passphrase has a value;
            // when the key has none, the connection's existing passphrase (filled by the user pre-upgrade) is kept, so editing a connection does not wipe it.
            passphrase = passphrase?.let { KeystoreCipher.encryptString(it) }
                ?: existing?.takeIf { authType == "PRIVATE_KEY" }?.passphrase,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        dao.insertConnection(entity)
    }

    suspend fun updateConnection(conn: RemoteConnection, auth: RemoteAuth) {
        // Will overwrite existing connection ID
        addConnection(conn, auth)
    }

    suspend fun deleteConnection(id: String) {
        val entity = dao.getConnectionById(id)
        if (entity != null) {
            // Associated mounts will cascade delete in DB, but we should disconnect them
            val mounts = dao.getMountsByConnectionId(id)
            // Just disconnect everything from memory to be safe, cascading handles DB
            activeEngines.keys.forEach { mountId -> disconnectMount(mountId) }
            dao.deleteConnection(entity)
        }
    }

    suspend fun addMount(mount: RemoteMount) {
        dao.insertMount(RemoteMountEntity(
            id = mount.id,
            connectionId = mount.connectionId,
            remotePath = mount.remotePath,
            localMountPath = mount.localMountPath,
            autoConnect = mount.autoConnect,
            createdAt = System.currentTimeMillis()
        ))
    }
    
    suspend fun updateMount(mount: RemoteMount) {
        dao.updateMount(RemoteMountEntity(
            id = mount.id,
            connectionId = mount.connectionId,
            remotePath = mount.remotePath,
            localMountPath = mount.localMountPath,
            autoConnect = mount.autoConnect
        ))
    }
    
    suspend fun deleteMount(mountId: String) {
        disconnectMount(mountId)
        val entity = dao.getMountById(mountId)
        if (entity != null) {
            dao.deleteMount(entity)
        }
    }

    suspend fun connectMount(mountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Clean up any old connection first, so repeated/concurrent connects do not leak engines
            activeEngines[mountId]?.shutdown()
            activeEngines.remove(mountId)
            activeEngineIds.update { it - mountId }
            stopStateWatch(mountId)

            val mountEntity = dao.getMountById(mountId) ?: return@withContext Result.failure(Exception("Mount not found"))
            val connEntity = dao.getConnectionById(mountEntity.connectionId) ?: return@withContext Result.failure(Exception("Connection not found"))
            
            val conn = connEntity.toDomainModel()
            val mount = mountEntity.toDomainModel(conn)

            val client = when (conn.protocol) {
                RemoteProtocol.SFTP -> SftpSyncClient(hostKeyVerifier, privateKeyStore)
                RemoteProtocol.FTP -> FtpSyncClient()
            }

            val auth = resolveAuth(connEntity)

            client.connect(conn.host, conn.port, conn.username, auth)
            
            val engine = SyncEngine(
                mount = mount, 
                connection = conn, 
                syncClient = client,
                auth = auth,
                ignoredPatternsStr = syncSettings.ignoredPatterns.value,
                useGitIgnore = syncSettings.useGitIgnore.value,
                maxSyncBatchSize = syncSettings.maxSyncBatchSize.value
            )
            // Default full download removed to avoid overwriting local edits; left to the user to trigger sync manually

            activeEngines[mountId] = engine
            activeEngineIds.update { it + mountId }
            startLocalWatching(mountId, mount, engine)
            startStateWatch(mountId, engine)
            Result.success(Unit)
        } catch (e: Exception) {
            // Mount connections do not pop a confirmation: prompt the user to run a connectivity test on the connection config page to confirm first
            val pending = hostKeyVerifier.consumePending()
            if (pending != null) {
                Result.failure(Exception("Host key not confirmed yet; run a connectivity test on the connection config page to confirm it"))
            } else {
                Result.failure(Exception(friendlySshError(e), e))
            }
        }
    }

    /**
     * Local file changes are watched centrally by the hub service (with pruning and merged batches); this only feeds events into the mount's sync queue;
     * the subscription is cancelled when the mount disconnects, and with no subscribers the hub service holds no handles.
     */
    private fun startLocalWatching(mountId: String, mount: RemoteMount, engine: SyncEngine) {
        watchJobs.remove(mountId)?.cancel()
        watchJobs[mountId] = scope.launch {
            fileChangeHub.watchHostDir(
                hostDir = File(mount.localMountPath),
                recursive = true,
                filter = WatchFilter(
                    ignoredNames = syncSettings.ignoredPatterns.value
                        .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                    followGitignore = syncSettings.useGitIgnore.value
                ),
                fallbackPoll = false
            ).collect { batch ->
                FileLogger.d(TAG, "Sync watcher received changes: mount=$mountId count=${batch.changes.size} truncated=${batch.truncated}")
                for (change in batch.changes) engine.enqueueLocalChange(change.hostPath)
            }
        }
    }

    suspend fun disconnectMount(mountId: String) {
        autoConnectJobs[mountId]?.cancel()
        autoConnectJobs.remove(mountId)
        watchJobs.remove(mountId)?.cancel()
        stopStateWatch(mountId)
        activeEngines[mountId]?.shutdown()
        activeEngines.remove(mountId)
        activeEngineIds.update { it - mountId }
    }

    /** Subscribes to a mount's live connection health and writes it back into [mountStates]. */
    private fun startStateWatch(mountId: String, engine: SyncEngine) {
        mountStates.update { it + (mountId to engine.connectionState.value) }
        stateJobs[mountId]?.cancel()
        stateJobs[mountId] = scope.launch {
            engine.connectionState.collect { state ->
                mountStates.update { it + (mountId to state) }
            }
        }
    }

    private fun stopStateWatch(mountId: String) {
        stateJobs.remove(mountId)?.cancel()
        mountStates.update { it - mountId }
    }

    suspend fun forceUploadMount(mountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val engine = activeEngines[mountId] ?: return@withContext Result.failure(Exception("Connect this mount first"))
            engine.uploadWorkspace()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun forceDownloadMount(mountId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val engine = activeEngines[mountId] ?: return@withContext Result.failure(Exception("Connect this mount first"))
            engine.downloadWorkspace()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Saves the user-confirmed host fingerprint; later connections to the same host are allowed through directly. */
    fun confirmHostKey(host: String, port: Int, fingerprint: String) {
        hostKeyStore.save(host, port, fingerprint)
    }

    /** Tests connectivity: when confirmation is pending (first time / fingerprint changed) it throws [HostKeyConfirmationRequiredException] so the UI can show a confirmation. */
    suspend fun testConnection(
        host: String,
        port: Int,
        username: String,
        auth: RemoteAuth,
        protocol: RemoteProtocol
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val client = when (protocol) {
                RemoteProtocol.SFTP -> SftpSyncClient(hostKeyVerifier, privateKeyStore)
                RemoteProtocol.FTP -> FtpSyncClient()
            }
            client.connect(host, port, username, auth)
            client.disconnect()
            Result.success(Unit)
        } catch (e: Exception) {
            val pending = hostKeyVerifier.consumePending()
            if (pending != null) {
                throw HostKeyConfirmationRequiredException(
                    pending.host, pending.port, pending.keyType, pending.fingerprint, pending.changed
                )
            }
            Result.failure(Exception(friendlySshError(e), e))
        }
    }

    suspend fun listRemoteDirectories(connectionId: String, path: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val connEntity = dao.getConnectionById(connectionId) ?: return@withContext Result.failure(Exception("Connection not found"))
            val conn = connEntity.toDomainModel()
            
            val client = when (conn.protocol) {
                RemoteProtocol.SFTP -> SftpSyncClient(hostKeyVerifier, privateKeyStore)
                RemoteProtocol.FTP -> FtpSyncClient()
            }
            val auth = resolveAuth(connEntity)
            
            client.connect(conn.host, conn.port, conn.username, auth)
            val files = client.listFiles(path).filter { it.isDirectory }.map { it.name }
            client.disconnect()
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Resolves a connection's auth: for key passphrases the key entry's setting wins, falling back to the connection's own stored passphrase. */
    private fun resolveAuth(connEntity: RemoteConnectionEntity): RemoteAuth =
        if (connEntity.authType == "PASSWORD") {
            RemoteAuth.Password(KeystoreCipher.decryptString(connEntity.authData))
        } else {
            val passphrase = loginKeyStore.entries().firstOrNull { it.path == connEntity.authData }?.passphrase
                ?: connEntity.passphrase?.let { KeystoreCipher.decryptString(it) }
            RemoteAuth.PrivateKey(connEntity.authData, passphrase)
        }

    private fun RemoteConnectionEntity.toDomainModel() = RemoteConnection(
        id = id,
        name = name,
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        password = if (authType == "PASSWORD") KeystoreCipher.decryptString(authData) else "",
        authType = if (authType == "PRIVATE_KEY") "key" else "password",
        authData = authData
    )

    private fun RemoteMountEntity.toDomainModel(conn: RemoteConnection?) = RemoteMount(
        id = id,
        connectionId = connectionId,
        remotePath = remotePath,
        localMountPath = localMountPath,
        isActive = isActive,
        autoConnect = autoConnect,
        connection = conn
    )
}

/** Host key awaiting confirmation (first connection or changed fingerprint); thrown by the connectivity test for the UI to confirm. */
class HostKeyConfirmationRequiredException(
    val host: String,
    val port: Int,
    val keyType: String,
    val fingerprint: String,
    val changed: Boolean
) : Exception("SSH host key requires confirmation: $host:$port")
