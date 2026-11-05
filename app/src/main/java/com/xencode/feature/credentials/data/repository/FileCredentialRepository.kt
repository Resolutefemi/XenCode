package com.xencode.feature.credentials.data.repository

import android.content.Context
import android.util.Base64
import com.xencode.core.util.FileLogger
import com.xencode.feature.credentials.domain.model.GitCredential
import com.xencode.feature.credentials.domain.repository.CredentialRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-backed implementation of git credentials (source of truth = `filesDir/xencode/git-credentials`, i.e. `/root/.xencode/git-credentials` inside the container).
 *
 * The file uses the standard git-credential-store format: one `https://user:token@host` line per host (saving the same host overwrites the old value),
 * read directly by the container/remote server's `credential.helper=store`; the UI and git share the same copy.
 * Cached in memory as a StateFlow; write operations (save/delete) flush after an atomic write to disk.
 */
@Singleton
class FileCredentialRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : CredentialRepository {

    private companion object {
        const val TAG = "FileCredentialRepo"
        const val XENCODE_DIR = "xencode"
        const val CREDENTIALS_NAME = "git-credentials"
    }

    private val credentialsFile: File get() = File(File(context.filesDir, XENCODE_DIR), CREDENTIALS_NAME)

    private val mutex = Mutex()

    private val _credentials = MutableStateFlow(parse(credentialsFile))
    override fun getAll(): Flow<List<GitCredential>> = _credentials

    override suspend fun findForHost(host: String): GitCredential? {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return null
        return _credentials.value.firstOrNull { it.host == normalized }
    }

    override suspend fun save(credential: GitCredential) = mutex.withLock {
        val host = credential.host.trim().lowercase()
        if (host.isEmpty()) return@withLock
        val updated = _credentials.value
            .filterNot { it.host == host } +
            GitCredential(id = host, host = host, username = credential.username.trim(), token = credential.token)
        writeFile(updated)
        _credentials.value = updated
        FileLogger.i(TAG, "Saved credential host=$host user=${credential.username}")
    }

    override suspend fun delete(id: String) = mutex.withLock {
        val updated = _credentials.value.filterNot { it.id == id }
        writeFile(updated)
        _credentials.value = updated
        FileLogger.i(TAG, "Deleted credential id=$id")
    }

    /** Parses the git-credentials file; lines with malformed formats are skipped. */
    private fun parse(file: File): List<GitCredential> {
        if (!file.isFile) return emptyList()
        return runCatching {
            // The file content is an encoded string ("base64 then fully reversed"); on decode failure (legacy plaintext) treat it as raw text.
            val content = tryDecode(file.readText()) ?: file.readText()
            content.lines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                // https://user:token@host
                val at = trimmed.lastIndexOf('@')
                if (at < 0) return@mapNotNull null
                val host = trimmed.substring(at + 1).trim().lowercase()
                val cred = trimmed.substring(0, at)
                val rest = cred.substringAfter("://", "")
                val sep = rest.indexOf(':')
                if (sep < 0) return@mapNotNull null
                val username = URLDecoder.decode(rest.substring(0, sep), "UTF-8")
                val token = URLDecoder.decode(rest.substring(sep + 1), "UTF-8")
                if (host.isEmpty() || username.isEmpty()) null
                else GitCredential(id = host, host = host, username = username, token = token)
            }
        }.getOrElse {
            FileLogger.w(TAG, "Failed to parse git-credentials: ${it.message}")
            emptyList()
        }
    }

    /** Writes the file in git-credentials format (.tmp then rename, so git never reads a half-written file). */
    private fun writeFile(list: List<GitCredential>) {
        val sb = StringBuilder()
        list.sortedBy { it.host }.forEach { c ->
            sb.append("https://")
                .append(enc(c.username)).append(':').append(enc(c.token))
                .append('@').append(c.host)
                .append('\n')
        }
        credentialsFile.parentFile?.mkdirs()
        val tmp = File(credentialsFile.parentFile, "${credentialsFile.name}.tmp")
        val encoded = encode(sb.toString())
        tmp.writeText(encoded)
        if (credentialsFile.exists()) credentialsFile.delete()
        if (!tmp.renameTo(credentialsFile)) {
            credentialsFile.writeText(encoded)
            tmp.delete()
        }
    }

    private fun enc(part: String): String = URLEncoder.encode(part, "UTF-8")

    /** Encoding: base64 then fully reversed, so the file isn't directly readable plaintext (obfuscation, not encryption). */
    private fun encode(plain: String): String =
        Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).reversed()

    /** Decodes an encoded string; returns null when it isn't in this format (e.g. legacy plaintext). */
    private fun tryDecode(text: String): String? = runCatching {
        String(Base64.decode(text.reversed(), Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    /** Migrates the legacy plaintext credentials file to the encoded format (idempotent). */
    suspend fun migrateToEncoded() = mutex.withLock {
        if (!credentialsFile.isFile) return@withLock
        val raw = runCatching { credentialsFile.readText() }.getOrNull() ?: return@withLock
        if (tryDecode(raw) != null) return@withLock
        writeFile(_credentials.value)
        FileLogger.i(TAG, "Migrated plaintext git credentials to the encoded format")
    }
}