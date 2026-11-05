package com.xencode.feature.workspace.domain.remote

class RemoteFileRejectedException(message: String) : Exception(message)

interface RemoteSyncClient {
    suspend fun connect(host: String, port: Int, username: String, auth: RemoteAuth)
    suspend fun disconnect()
    suspend fun listFiles(remotePath: String): List<RemoteFileInfo>
    suspend fun downloadFile(remotePath: String, localPath: String)
    suspend fun uploadFile(localPath: String, remotePath: String)
    suspend fun createDirectory(remotePath: String)
    suspend fun delete(remotePath: String)
    suspend fun isConnected(): Boolean

    /** Atomic reconnect: drops the old connection and re-establishes it. For the sync engine's liveness/failure reconnects,
     *  so no other coroutine can slip in between disconnect and connect and operate on a half-connected state. */
    suspend fun reconnect(host: String, port: Int, username: String, auth: RemoteAuth)

    /** Lightweight liveness probe: whether the connection is still usable. Defaults to reusing the isConnected flag; network protocols may override it with a real round-trip (e.g. SFTP stat / FTP NOOP),
     *  to catch dead links invisible to isConnected, such as half-open TCP. */
    suspend fun ping(): Boolean = isConnected()
}

data class RemoteFileInfo(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

sealed class RemoteAuth {
    data class Password(val password: String): RemoteAuth()
    data class PrivateKey(val privateKeyPath: String, val passphrase: String? = null): RemoteAuth()
}
