package com.aicode.feature.network.domain

import com.aicode.feature.agent.domain.container.ContainerInstaller
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkHistoryStore @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    val historyDir: File
        get() = File(containerInstaller.aicodeDir, HISTORY_DIR)

    fun list(): List<NetworkHistoryEntry> {
        val dir = historyDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file: File -> file.isFile && file.name.endsWith(".json") }
            .orEmpty()
            .mapNotNull { file -> readFile(file) }
            .sortedByDescending { it.createdAt }
    }

    fun save(entry: NetworkHistoryEntry): NetworkHistoryEntry {
        val dir = historyDir.apply { mkdirs() }
        val file = File(dir, "${entry.id}.json")
        file.writeText(prettyJson.encodeToString(NetworkHistoryEntry.serializer(), entry))
        return entry
    }

    fun saveSnapshot(
        method: HttpMethod,
        url: String,
        queryJson: String,
        headersJson: String,
        bodyJson: String,
        authJson: String,
        settings: NetworkClientSettings
    ): NetworkHistoryEntry {
        val entry = NetworkHistoryEntry(
            id = UUID.randomUUID().toString().take(12),
            createdAt = System.currentTimeMillis(),
            method = method.name,
            url = url,
            queryJson = queryJson,
            headersJson = headersJson,
            bodyJson = bodyJson,
            authJson = authJson,
            followRedirects = settings.followRedirects,
            sslVerify = settings.sslVerify,
            timeoutSeconds = settings.timeoutSeconds
        )
        return save(entry)
    }

    fun deleteAll(): Long {
        val dir = historyDir
        if (!dir.isDirectory) return 0L
        var freed = 0L
        dir.listFiles()?.forEach { child ->
            val size = if (child.isFile) child.length() else 0L
            if (child.deleteRecursively()) freed += size
        }
        return freed
    }

    private fun readFile(file: File): NetworkHistoryEntry? = runCatching {
        prettyJson.decodeFromString(NetworkHistoryEntry.serializer(), file.readText())
    }.getOrNull()

    companion object {
        const val HISTORY_DIR = "network-history"
    }
}
