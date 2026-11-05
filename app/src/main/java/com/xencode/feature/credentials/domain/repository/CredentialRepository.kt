package com.xencode.feature.credentials.domain.repository

import com.xencode.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.flow.Flow

/**
 * Git credential repository. Credentials are injected by matching [GitCredential.host]: before push/pull, the current remote's host is parsed
 * and [findForHost] fetches the corresponding account's token.
 */
interface CredentialRepository {
    fun getAll(): Flow<List<GitCredential>>

    /** Prefers the default credential, falling back to any entry for that host. Used by the injection chain. */
    suspend fun findForHost(host: String): GitCredential?

    suspend fun save(credential: GitCredential)

    suspend fun delete(id: String)
}
