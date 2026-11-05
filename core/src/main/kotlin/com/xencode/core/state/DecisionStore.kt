package com.xencode.core.state

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Interactive decision cards, persisted at `<workspace>/.xencode/decisions.json`.
 *
 * Any component that needs a human call (permission asks, 3-strike pivots,
 * `.mjs` `sync.requestUserDecision`) creates an OPEN card; the CLI
 * (`xencode decide approve|reject`) and the desktop banner resolve it.
 */
class DecisionStore(private val workspaceRoot: File) {

    enum class Status { OPEN, RESOLVED }

    data class Card(
        val id: String,
        val title: String,
        val body: String,
        val options: List<String>,
        val status: Status = Status.OPEN,
        val choice: String? = null,
        val createdAt: Long,
        val resolvedAt: Long? = null
    )

    private val file: File get() = File(File(workspaceRoot, ".xencode"), "decisions.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun loadAll(): MutableList<Card> = runCatching {
        val arr = gson.fromJson(file.readText(Charsets.UTF_8), Array<Card>::class.java)
        (arr ?: emptyArray()).toMutableList()
    }.getOrElse { mutableListOf() }

    private fun saveAll(cards: List<Card>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(gson.toJson(cards), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(gson.toJson(cards), Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun create(title: String, body: String, options: List<String> = listOf("approve", "reject")): Card {
        val card = Card(
            id = "d" + System.currentTimeMillis().toString(36),
            title = title.take(200),
            body = body.take(2000),
            options = if (options.isEmpty()) listOf("approve", "reject") else options.map { it.trim().lowercase() },
            createdAt = System.currentTimeMillis()
        )
        saveAll(loadAll() + card)
        return card
    }

    fun resolve(id: String, choice: String): Card? {
        val cards = loadAll()
        val idx = cards.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val card = cards[idx]
        if (card.status == Status.RESOLVED) return null // already decided — no flip-flops
        val normalized = choice.trim().lowercase()
        if (normalized !in card.options) return null
        val resolved = card.copy(status = Status.RESOLVED, choice = normalized, resolvedAt = System.currentTimeMillis())
        cards[idx] = resolved
        saveAll(cards)
        return resolved
    }

    fun open(): List<Card> = loadAll().filter { it.status == Status.OPEN }

    fun list(): List<Card> = loadAll().sortedByDescending { it.createdAt }
}
