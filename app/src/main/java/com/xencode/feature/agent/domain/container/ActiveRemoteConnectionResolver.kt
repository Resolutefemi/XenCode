package com.xencode.feature.agent.domain.container

import com.xencode.core.security.KeystoreCipher
import com.xencode.feature.settings.data.repository.ContainerSettingsRepository
import com.xencode.feature.settings.data.repository.normalizeRemoteWorkspacePath
import com.xencode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.xencode.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.xencode.feature.workspace.domain.remote.RemoteAuth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolver for the remote connection config of the currently active container profile.
 *
 * Replaces the old execution_mode_prefs copy of connection parameters: a change of the active profile, a change of profile
 * contents (editing the remote path or rebinding the channel), or a change of the connection table (host/port/credentials)
 * all trigger re-resolution, producing a [RemoteConnectionConfig] that can be handed straight to [RemoteSshConnection.connect],
 * so connection edits take effect immediately without toggling the container off and back on.
 *
 * Authentication is resolved solely by the connection's own authType: password connections resolve to the password, key
 * connections to the private key path and passphrase, staying consistent with the workspace-sync SFTP channel.
 */
@Singleton
class ActiveRemoteConnectionResolver @Inject constructor(
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val dao: RemoteConnectionDao,
    private val loginKeyStore: SshLoginKeyStore
) {
    /** Connection config resolved from the active profile; null for a local profile, a missing channel, or a deleted connection. */
    val activeConfigFlow: Flow<RemoteConnectionConfig?> = combine(
        containerSettingsRepository.activeProfileIdFlow,
        containerSettingsRepository.customProfilesFlow,
        dao.getAllConnections()
    ) { activeId, profiles, connections ->
        resolve(activeId, profiles, connections)
    }.distinctUntilChanged()

    private fun resolve(
        activeId: String,
        profiles: List<ContainerProfile>,
        connections: List<RemoteConnectionEntity>
    ): RemoteConnectionConfig? {
        val profile = profiles.firstOrNull { it.id == activeId }
            ?: ContainerProfile.BUILTIN_ALPINE.takeIf { it.id == activeId }
            ?: return null
        val ssh = profile.rootfsSource as? RootfsSource.RemoteSsh ?: return null
        val conn = connections.firstOrNull { it.id == ssh.connectionId } ?: return null
        val auth = if (conn.authType.equals("PASSWORD", ignoreCase = true)) {
            RemoteAuth.Password(KeystoreCipher.decryptString(conn.authData))
        } else {
            val passphrase = loginKeyStore.entries().firstOrNull { it.path == conn.authData }?.passphrase
                ?: conn.passphrase?.let { KeystoreCipher.decryptString(it) }
            RemoteAuth.PrivateKey(
                privateKeyPath = conn.authData,
                passphrase = passphrase
            )
        }
        return RemoteConnectionConfig(
            host = conn.host,
            port = conn.port,
            username = conn.username,
            auth = auth,
            remoteWorkspacePath = normalizeRemoteWorkspacePath(ssh.remoteWorkspacePath, conn.username)
        )
    }
}
