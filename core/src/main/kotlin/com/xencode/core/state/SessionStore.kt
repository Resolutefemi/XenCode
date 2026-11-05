package com.xencode.core.state

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.xencode.core.provider.ChatClient
import java.io.File

/**
 * Session persistence for the PC line, stored at
 * `<workspace>/.xencode/sessions/<id>.json`.
 *
 * This is the PC counterpart of the Android Room `chat_session`/`message`
 * tables: the model-side history is serialized verbatim (including tool call
 * rounds), so a resumed session continues exactly where it stopped.
 */
class SessionStore(private val workspaceRoot: File) {

    data class SessionMeta(
        val id: String,
        val title: String,
        val provider: String,
        val model: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messageCount: Int
    )

    data class StoredSession(
        val id: String,
        val title: String,
        val provider: String,
        val model: String,
        val createdAt: Long,
        val updatedAt: Long,
        val messages: List<ChatClient.ChatMessage>
    ) {
        fun meta(): SessionMeta = SessionMeta(id, title, provider, model, createdAt, updatedAt, messages.size)
    }

    private val dir: File get() = File(File(workspaceRoot, ".xencode"), "sessions")
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun newId(): String = "s" + java.lang.Long.toString(System.currentTimeMillis(), 36)

    /** Creates or updates a stored session; returns the stored snapshot. */
    fun save(
        id: String,
        title: String,
        provider: String,
        model: String,
        createdAt: Long,
        messages: List<ChatClient.ChatMessage>
    ): StoredSession {
        val existing = load(id)
        val session = StoredSession(
            id = id,
            title = title.ifBlank { existing?.title ?: "session" },
            provider = provider,
            model = model,
            createdAt = if (existing != null) existing.createdAt else createdAt,
            updatedAt = System.currentTimeMillis(),
            messages = messages
        )
        dir.mkdirs()
        // Write-then-move so a crash never truncates an existing session file.
        val target = File(dir, "$id.json")
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(gson.toJson(session), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(gson.toJson(session), Charsets.UTF_8)
            tmp.delete()
        }
        return session
    }

    fun list(): List<SessionMeta> =
        (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { load(it.name.removeSuffix(".json"))?.meta() }
            .sortedByDescending { it.updatedAt }

    fun load(id: String): StoredSession? {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return null
        val f = File(dir, "$id.json")
        if (!f.isFile) return null
        return runCatching { gson.fromJson(f.readText(Charsets.UTF_8), StoredSession::class.java) }.getOrNull()
    }

    fun latest(): StoredSession? = list().firstOrNull()?.let { load(it.id) }

    fun delete(id: String): Boolean {
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return false
        return File(dir, "$id.json").delete()
    }

    /** Derives a short title from the first user message. */
    fun titleFrom(prompt: String): String {
        val clean = prompt.trim().replace('\n', ' ')
        return if (clean.length <= 60) clean.ifBlank { "session" } else clean.take(57) + "..."
    }
}
