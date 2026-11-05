package com.xencode.core.tools

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * The shared TODO list, persisted at `<workspace>/.xencode/TODO.json`.
 *
 * One list per workspace, seen by both the agent (todo_write / todo_read tools)
 * and the user (`xencode todo`, desktop TODO panel) — the same contract the
 * Android app implements for its in-chat todo widget.
 */
class TodoStore(private val workspaceRoot: File) {

    data class TodoItem(
        val content: String? = null,
        val status: String? = "pending",
        val priority: String? = "medium"
    ) {
        // Nullable fields + normalized getters: Gson (reflection, no constructors)
        // leaves absent fields null, so every read must be null-safe.
        val normalizedStatus: String
            get() = when (status?.lowercase()) {
                "completed", "done" -> "completed"
                "active", "in_progress", "in progress" -> "active"
                else -> "pending"
            }
        val normalizedPriority: String
            get() = priority?.lowercase()?.takeIf { it in setOf("high", "medium", "low") } ?: "medium"
    }

    private val file: File get() = File(File(workspaceRoot, ".xencode"), "TODO.json")

    fun read(): List<TodoItem> = runCatching {
        val type = object : TypeToken<List<TodoItem>>() {}.type
        Gson().fromJson<List<TodoItem>>(file.readText(Charsets.UTF_8), type) ?: emptyList()
    }.getOrElse { emptyList() }

    fun write(items: List<TodoItem>) {
        file.parentFile?.mkdirs()
        file.writeText(GsonBuilder().setPrettyPrinting().create().toJson(items), Charsets.UTF_8)
    }

    fun add(content: String, priority: String = "medium"): List<TodoItem> {
        val next = read() + TodoItem(content.trim(), "pending", priority)
        write(next)
        return next
    }

    /** Marks the first item whose content contains [needle] as completed. */
    fun complete(needle: String): List<TodoItem>? {
        val items = read()
        val idx = items.indexOfFirst { it.content?.contains(needle, ignoreCase = true) == true && it.normalizedStatus != "completed" }
        if (idx < 0) return null
        val next = items.toMutableList()
        next[idx] = items[idx].copy(status = "completed")
        write(next)
        return next
    }

    fun clear(): List<TodoItem> {
        write(emptyList())
        return emptyList()
    }

    companion object {
        private val GLYPH = mapOf("completed" to "x", "active" to ">", "pending" to " ")

        fun render(items: List<TodoItem>): String =
            if (items.isEmpty()) "(todo list is empty)"
            else items.joinToString("\n") {
                "[${GLYPH[it.normalizedStatus] ?: ' '}] (${it.normalizedPriority}) ${it.content ?: "(untitled)"}"
            }
    }

    fun render(): String = render(read())
}
