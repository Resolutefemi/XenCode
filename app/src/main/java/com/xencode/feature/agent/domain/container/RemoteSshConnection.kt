package com.xencode.feature.agent.domain.container

import com.xencode.core.util.FileLogger
import com.xencode.feature.workspace.domain.remote.RemoteAuth
import com.xencode.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.userauth.UserAuthException
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import net.schmizz.sshj.userauth.password.PasswordUtils
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteSshConnection"

/**
 * Shared SSH connection manager: holds two independent sshj [SSHClient] instances — an exec channel for [RemoteSshEngine] to run commands,
 * and an SFTP channel for [RemoteSftpFileAccess] to read/write files. The two are isolated, so an SFTP buffer overflow cannot take down the command channel.
 *
 * Connection config (host/port/username/auth) is passed in by the caller at [connect] time. After a disconnect, the next [connect]
 * re-establishes it; the SFTP channel is created lazily on demand (see [sftp]). Exec-side operations are serialized ([mutex]); the SFTP side is
 * serialized by [RemoteSftpFileAccess] (sshj's SFTPClient is not thread-safe).
 */
@Singleton
class RemoteSshConnection @Inject constructor(
    private val hostKeyStore: SshHostKeyStore,
    private val hostKeyVerifier: SshHostKeyVerifier,
    private val privateKeyStore: SshPrivateKeyStore,
    private val containerInstaller: ContainerInstaller
) {

    @Volatile
    private var sshClient: SSHClient? = null

    /**
     * Dedicated SFTP channel: uses its own SSH transport, separate from exec. sshj's SFTP has intermittent buffer
     * overflows (hierynomus/sshj#461); sharing one transport lets a crash take down Bash/terminal too. Kept separate,
     * only the file channel is affected — recovery falls back to [invalidateSftp] + rebuilding on the next [sftp].
     */
    @Volatile
    private var sftpSshClient: SSHClient? = null

    @Volatile
    private var sftpClient: SFTPClient? = null

    private val mutex = Mutex()
    private val sftpLock = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    /** Connection state flow for the UI indicator and for workspace init to await connection readiness. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Callback after a successful reconnect, used to reload the workspace. Registered by [startSupervisor]. */
    private var onReconnected: (suspend () -> Unit)? = null
    private var supervisorJob: Job? = null

    /** Real home path on the remote server (queried from $HOME after connecting and cached), for file tools to expand ~. */
    @Volatile
    var remoteHome: String? = null
        private set

    /** Snapshot of the current connection config, used for reconnect and path mapping. */
    @Volatile
    var config: RemoteConnectionConfig? = null
        private set

    suspend fun connect(config: RemoteConnectionConfig) = mutex.withLock {
        if (isConnected() && this.config == config) return@withLock
        disconnectInternal()
        this.config = config
        _connectionState.value = ConnectionState.CONNECTING
        try {
            withContext(Dispatchers.IO) {
                val client = newSshClient(config)
                sshClient = client
                // Query the real remote home (root uses /root, regular users /home/xxx) so file tools can expand ~
                runCatching {
                    val s = client.startSession()
                    val cmd = s.exec("echo \$HOME")
                    remoteHome = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream))
                        .readText().trim().ifEmpty { null }
                    s.close()
                }
                // Register a transport disconnect listener: push state immediately on disconnect instead of waiting for the supervisor poll
                client.transport.setDisconnectListener { reason, info ->
                    FileLogger.w(TAG, "SSH disconnected: $reason, $info")
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                FileLogger.i(TAG, "SSH connected to ${config.host}:${config.port} as ${config.username}")
            }
            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.FAILED
            throw e
        }
    }

    fun removeHostKey(host: String, port: Int) {
        hostKeyStore.remove(host, port)
    }

    fun savedHostKeys(): Map<String, String> = hostKeyStore.entries()
    /** No-arg connect: reconnect with the last saved config. */
    suspend fun connect() {
        val cfg = config ?: throw IllegalStateException("SSH connection is not configured")
        connect(cfg)
    }

    suspend fun disconnect() = mutex.withLock {
        disconnectInternal()
    }

    private fun disconnectInternal() {
        closeSftpInternal()
        runCatching { sshClient?.disconnect() }
        sshClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** Close and clear the dedicated SFTP channel; the next [sftp] rebuilds it from the current config. */
    private fun closeSftpInternal() {
        runCatching { sftpClient?.close() }
        runCatching { sftpSshClient?.disconnect() }
        sftpClient = null
        sftpSshClient = null
    }

    /** Establish an SSH transport and complete auth and keep-alive (one each for exec and SFTP). */
    private fun newSshClient(config: RemoteConnectionConfig): SSHClient = SSHClient().apply {
        addHostKeyVerifier(hostKeyVerifier)
        connect(config.host, config.port)
        when (val auth = config.auth) {
            is RemoteAuth.Password -> authPassword(config.username, auth.password)
            is RemoteAuth.PrivateKey -> {
                val pem = privateKeyStore.readPem(auth.privateKeyPath)
                val passwordFinder = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                authPublickey(config.username, loadKeys(pem, null, passwordFinder))
            }
        }
        // Enable the SSH keep-alive heartbeat to prevent idle-timeout disconnects
        runCatching {
            connection.keepAlive?.let {
                it.setKeepAliveInterval(30)
                it.start()
            }
        }
    }

    fun isConnected(): Boolean =
        sshClient?.isConnected == true && sshClient?.isAuthenticated == true

    /** Open an exec session to run a command. The caller is responsible for closing the returned Command. */
    fun startExecSession(command: String): Session.Command {
        val client = sshClient ?: throw IllegalStateException("SSH is not connected")
        val session = client.startSession()
        val cmd = session.exec(command)
        // Send EOF to the remote process's stdin immediately: all callers only read output. Otherwise a remote program that
        // decides behavior on whether stdin is readable (e.g. rg without a path argument) treats the channel as pending input and blocks until the command times out.
        runCatching { cmd.outputStream.close() }
        return cmd
    }

    /**
     * Open an exec session but **keep stdin writable**, for sending large content via stdin (avoids command-line
     * arguments exceeding the remote ARG_MAX). The caller must write and close [Session.Command.outputStream], read output, and close the session.
     */
    fun startExecSessionWithStdin(command: String): Session.Command {
        val client = sshClient ?: throw IllegalStateException("SSH is not connected")
        return client.startSession().exec(command)
    }

    /** Open a new Session for the caller to allocate a PTY and start a shell. The caller closes the Session. */
    fun startShellSession(): Session {
        val client = sshClient ?: throw IllegalStateException("SSH is not connected")
        return client.startSession()
    }

    /**
     * Get the dedicated SFTP client (lazily establishes a second SSH connection, isolated from exec). Callers must not
     * close it — it is managed centrally by [disconnect]. A stale connection (not connected/authenticated) is discarded and rebuilt, covering self-healing after an SFTP channel crash.
     */
    suspend fun sftp(): SFTPClient = sftpLock.withLock {
        val cfg = config ?: throw IllegalStateException("SSH is not configured")
        sftpClient
            ?.takeIf { sftpSshClient?.isConnected == true && sftpSshClient?.isAuthenticated == true }
            ?.let { return@withLock it }
        closeSftpInternal()
        val client = withContext(Dispatchers.IO) { newSshClient(cfg) }
        sftpSshClient = client
        val sftp = withContext(Dispatchers.IO) { client.newSFTPClient() }
        sftpClient = sftp
        sftp
    }

    /** Discard the current SFTP channel (called after a channel error); the next [sftp] rebuilds it. */
    suspend fun invalidateSftp() = sftpLock.withLock { closeSftpInternal() }

    /** If configured but not connected, immediately try one reconnect. Returns whether it ended up connected. Triggered actively when the app returns to the foreground. */
    suspend fun tryReconnectIfDisconnected(): Boolean {
        val cfg = config ?: return false
        if (isConnected()) return true
        _connectionState.value = ConnectionState.CONNECTING
        return runCatching { connect(cfg) }
            .onSuccess {
                FileLogger.i(TAG, "SSH reconnected (foreground triggered)")
                runCatching { onReconnected?.invoke() }
            }
            .onFailure {
                FileLogger.w(TAG, "SSH reconnect failed (foreground triggered)", it)
                _connectionState.value = ConnectionState.FAILED
            }
            .isSuccess
    }

    /**
     * Start the connection supervisor coroutine: in remote mode it probes periodically and auto-reconnects on
     * disconnect (exponential backoff). After a successful reconnect it triggers the [onReconnected] callback (for workspace reload)
     * and maintains [connectionState]. Idempotent: repeated calls never start multiple supervisors. Only active in remote mode. */
    fun startSupervisor(scope: CoroutineScope, onReconnected: suspend () -> Unit) {
        this.onReconnected = onReconnected
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            var backoffMs = 5000L
            val maxBackoffMs = 30000L
            while (isActive) {
                delay(backoffMs)
                val cfg = config ?: continue
                if (isConnected()) {
                    backoffMs = 15000 // Connection healthy; lengthen the probe interval (fallback only — disconnects get instant notice via DisconnectListener)
                    continue
                }
                // Connection lost; try to reconnect
                tryReconnectIfDisconnected()
                // Exponential backoff after a failed reconnect
                if (!isConnected()) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
                } else {
                    backoffMs = 15000
                }
            }
        }
    }

    /**
     * Update the ~/workspace symlink to point at the currently selected workspace's remote path, so that when the AI
     * uses ~/workspace/... paths, Bash commands (pwd etc.) reach the correct workspace directory. Call after the workspace is selected/initialized.
     *
     * Skipped when `~/workspace` is already a real directory (the case where the workspace root itself is configured as
     * `~/workspace`): `ln` would then create the link **inside** that directory, forming a self-referential loop that breaks the whole workspace; when skipped, the command path falls back to the real path.
     */
    suspend fun updateWorkspaceSymlink(workspacePath: String) {
        val client = sshClient ?: return
        val ws = workspacePath.trimEnd('/')
        if (ws.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val session = client.startSession()
                val cmd = session.exec(
                    "if [ -d ~/workspace ] && [ ! -L ~/workspace ]; then echo skip; " +
                        "else ln -sfn '$ws' ~/workspace 2>/dev/null; echo done; fi"
                )
                val out = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream)).readText().trim()
                session.close()
                if (out == "skip") {
                    FileLogger.w(TAG, "~/workspace is already a real directory; skipping the symlink (the workspace root may be configured as ~/workspace)")
                }
            }.onFailure { FileLogger.w(TAG, "Failed to update workspace symlink: $ws", it) }
        }
    }

    /**
     * Sync built-in docs to the remote ~/.xencode/docs/, so in remote mode the AI can consult the setup
     * docs under ~/.xencode/docs/ just like in local mode. Fully overwritten after every connect/reconnect,
     * so remote docs update together with app upgrades. Docs are plain text; exec + printf is enough, no SFTP needed.
     *
     * @param docs Path relative to docs/ (may include subdirectories, e.g. guide/terminal.md) → file content.
     */
    suspend fun uploadDocs(docs: Map<String, String>) {
        if (docs.isEmpty()) return
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val destDir = home.trimEnd('/') + "/.xencode/docs"
        withContext(Dispatchers.IO) {
            runCatching {
                // Create all subdirectories first and remove old .md files: reorganizing docs across versions changes paths, and overwrite-only would leave stale files behind
                val subDirs = docs.keys.mapNotNull { key ->
                    key.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
                }.distinct()
                val mkdirArgs = (listOf(destDir) + subDirs.map { "$destDir/$it" }).joinToString(" ") { "'$it'" }
                val prepSession = client.startSession()
                prepSession.exec("mkdir -p $mkdirArgs; find '$destDir' -type f -name '*.md' -delete").join()
                prepSession.close()
                for ((name, content) in docs) {
                    val dest = "$destDir/$name"
                    val escaped = content.replace("\\", "\\\\").replace("'", "'\\\"'\"'")
                    val session = client.startSession()
                    session.exec("printf %s '$escaped' > '$dest'").join()
                    session.close()
                }
                FileLogger.i(TAG, "Synced ${docs.size} docs to remote $destDir")
            }.onFailure { FileLogger.w(TAG, "Failed to sync docs to remote", it) }
        }
    }

    /**
     * Extract app built-in assets (subagent definitions, panel sample scripts, etc.) to the remote `~/.xencode/<subdir>/`.
     * Only fills in files missing on the server and **never overwrites existing ones** (preserving user edits); scripts get the executable bit.
     * Called after connect/reconnect like [uploadDocs] — in remote mode the global agents/scripts are taken from the remote side,
     * and the built-in defaults must exist remotely to work out of the box. Binary-safe (base64 transfer).
     */
    suspend fun uploadBuiltinFiles(
        destSubdir: String,
        files: Map<String, ByteArray>,
        executable: Boolean = false
    ) {
        if (files.isEmpty()) return
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val destDir = home.trimEnd('/') + "/.xencode/" + destSubdir.trim('/')
        withContext(Dispatchers.IO) {
            runCatching {
                val mk = client.startSession()
                mk.exec("mkdir -p '$destDir'").join()
                mk.close()
                for ((name, bytes) in files) {
                    val dest = "$destDir/$name"
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    val chmod = if (executable) " chmod +x '$dest';" else ""
                    val session = client.startSession()
                    session.exec("if [ ! -e '$dest' ]; then printf %s '$b64' | base64 -d > '$dest';$chmod fi").join()
                    session.close()
                }
                FileLogger.i(TAG, "Extracted ${files.size} built-in files to remote $destDir")
            }.onFailure { FileLogger.w(TAG, "Failed to extract built-in files to remote: $destSubdir", it) }
        }
    }

    /**
     * Inject the app's git credentials into the remote server (only called when the user enables "auto-inject"):
     * writes `~/.xencode/git-credentials` (store format) + `~/.xencode/gitconfig` + `~/.xencode/gitconfig.credential`.
     *
     * How it works: [RemoteSshEngine] injects `GIT_CONFIG_GLOBAL=$HOME/.xencode/gitconfig` when running commands;
     * `[include] path=~/.gitconfig` keeps the server user's own global config (identity etc.), and `[includeIf "gitdir:<workspace-root>/"]`
     * restricts the store helper to repositories under the workspace root — it does not affect the user's manual git on the server, nor repositories in other directories.
     *
     * @param credentials List of credentials to inject (one per host).
     * @param workspaceRoot Remote workspace root path (the directory the AI's ~/workspace maps to), scoping the injection.
     */
    suspend fun uploadGitCredentialConfig(credentials: List<GitCredential>, workspaceRoot: String) {
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val xencodeDir = home.trimEnd('/') + "/.xencode"
        val wsRoot = workspaceRoot.trimEnd('/').ifEmpty { return }
        withContext(Dispatchers.IO) {
            runCatching {
                val mkdirSession = client.startSession()
                mkdirSession.exec("mkdir -p '$xencodeDir'").join()
                mkdirSession.close()

                val creds = credentials.joinToString("\n") { c ->
                    "https://${enc(c.username)}:${enc(c.token)}@${c.host}"
                }.let { if (it.isNotEmpty()) "$it\n" else "" }
                writeRemoteFile(client, "$xencodeDir/git-credentials", encodeCreds(creds))

                // Upload the credential helper script (extracted locally by ContainerInstaller) and configure it as the only helper;
                // the server has no app, so AICODE_CRED_NO_PROMPT makes a miss exit empty immediately instead of waiting for a dialog.
                containerInstaller.extractCredentialHelper()
                val helperScript = java.io.File(containerInstaller.xencodeDir, "git-credential-xencode").readText()
                writeRemoteFile(client, "$xencodeDir/git-credential-xencode", helperScript)
                val chmodSession = client.startSession()
                chmodSession.exec("chmod +x '$xencodeDir/git-credential-xencode'").join()
                chmodSession.close()

                val gitconfig = buildString {
                    append("[include]\n")
                    append("    path = ~/.gitconfig\n")
                    append("[includeIf \"gitdir:$wsRoot/\"]\n")
                    append("    path = ~/.xencode/gitconfig.credential\n")
                }
                writeRemoteFile(client, "$xencodeDir/gitconfig", gitconfig)

                val credentialConfig = buildString {
                    append("[credential]\n")
                    append("    helper = !AICODE_CRED_NO_PROMPT=1 $xencodeDir/git-credential-xencode\n")
                }
                writeRemoteFile(client, "$xencodeDir/gitconfig.credential", credentialConfig)
                FileLogger.i(TAG, "Injected git credentials to remote $xencodeDir (scoped to $wsRoot/)")
            }.onFailure { FileLogger.w(TAG, "Failed to inject git credentials to remote", it) }
        }
    }

    /**
     * Undo the remote injection: delete the three config/credential files managed by the app.
     * When `GIT_CONFIG_GLOBAL` points to a nonexistent file, git silently skips it — the injection stops working and the server user's config is unaffected.
     */
    suspend fun removeGitCredentialConfig() {
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val xencodeDir = home.trimEnd('/') + "/.xencode"
        withContext(Dispatchers.IO) {
            runCatching {
                val session = client.startSession()
                session.exec("rm -f '$xencodeDir/gitconfig' '$xencodeDir/gitconfig.credential' '$xencodeDir/git-credentials' '$xencodeDir/git-credential-xencode' 2>/dev/null; echo done").join()
                session.close()
                FileLogger.i(TAG, "Revoked the remote git credential injection")
            }.onFailure { FileLogger.w(TAG, "Failed to revoke the remote git credential injection", it) }
        }
    }

    /** Write a text file to the remote via exec + printf (content is raw; single quotes/backslashes are escaped internally). */
    private suspend fun writeRemoteFile(client: SSHClient, dest: String, content: String) {
        if (content.isEmpty()) {
            // Empty content: truncate directly to avoid the quote ambiguity of printf %s ''
            val session = client.startSession()
            session.exec("printf '' > '$dest'").join()
            session.close()
            return
        }
        val escaped = content.replace("\\", "\\\\").replace("'", "'\\\"'\"'")
        val session = client.startSession()
        session.exec("printf %s '$escaped' > '$dest'").join()
        session.close()
    }

    private fun enc(part: String): String = java.net.URLEncoder.encode(part, "UTF-8")

    /** Encoding consistent with the app side: base64 then reverse the whole string (obfuscation, not encryption). */
    private fun encodeCreds(plain: String): String =
        android.util.Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP).reversed()
}

/** Remote SSH connection state, for the UI indicator and workspace-init sequencing. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/** Translate SSH connection exceptions into friendly messages for the tool layer to echo to the AI/user. */
fun friendlySshError(e: Throwable): String {
    val msg = e.message.orEmpty()
    return when {
        e is KeyDecryptionFailedException ->
            "SSH auth failed: private-key passphrase is wrong or the key format is unsupported ($msg)"
        e is UserAuthException ->
            "SSH auth failed: username, password, or key was rejected ($msg)"
        "ECONNREFUSED" in msg || "Connection refused" in msg ->
            "Cannot reach the SSH server (connection refused). Confirm the host is up and the port is correct"
        "UnknownHost" in msg || "unknown host" in msg ->
            "Cannot reach the SSH server (unknown host). Check the hostname"
        "Auth fail" in msg || "authentication" in msg ->
            "SSH auth failed. Check the username and password"
        "Network is unreachable" in msg ->
            "Network is unreachable. Check the connection"
        "SocketTimeout" in msg || "timed out" in msg ->
            "Timed out connecting to the SSH server. Check the network or server status"
        "SSH not connected" in msg || "SSH is not connected" in msg ->
            "SSH is not connected. Wait for it to recover or check Settings"
        else -> "SSH connection failed: ${e.message ?: "unknown error"}"
    }
}

/** Remote SSH connection config. */
data class RemoteConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: RemoteAuth,
    /** Workspace root path on the remote server (the AI's ~/workspace maps to this path). */
    val remoteWorkspacePath: String
)
