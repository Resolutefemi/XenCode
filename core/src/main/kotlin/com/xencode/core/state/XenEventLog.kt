package com.xencode.core.state

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * The cross-platform sync log, appended at `<workspace>/.xencode/events.ndjson`.
 *
 * Every state transition of the PC line (CLI, desktop, .mjs automation, goal
 * runner) lands here as one JSON line — the schema the Windows/Mobile parity
 * layer streams to the UIs. Consumers tail the file (desktop live feed,
 * `xencode events`) or replay it.
 */
class XenEventLog(private val workspaceRoot: File) {

    data class Event(
        val xencodeEvent: String,
        val at: Long,
        val summary: String?,
        val fields: Map<String, Any?> = emptyMap()
    )

    /** Well-known event types (the protocol vocabulary). */
    object Types {
        const val GOAL_INITIALIZED = "GOAL_INITIALIZED"
        const val NODE_STARTED = "NODE_STARTED"
        const val NODE_COMPLETED = "NODE_COMPLETED"
        const val SPEC_LOCKED = "SPEC_LOCKED"
        const val STATE_SYNC = "STATE_SYNC"
        const val TOOL_CALL = "TOOL_CALL"
        const val TOOL_RESULT = "TOOL_RESULT"
        const val VERIFICATION = "VERIFICATION"
        const val CHECKPOINT = "CHECKPOINT"
        const val DECISION_REQUESTED = "DECISION_REQUESTED"
        const val DECISION_RESOLVED = "DECISION_RESOLVED"
        const val XP_AWARDED = "XP_AWARDED"
        const val SESSION_SAVED = "SESSION_SAVED"
        const val SESSION_RESUMED = "SESSION_RESUMED"
        const val MCP_STATUS = "MCP_STATUS"
        const val MJS_RUN = "MJS_RUN"
        const val COMPACTED = "COMPACTED"
        const val TURN_COMPLETED = "TURN_COMPLETED"
    }

    private val dir: File get() = File(workspaceRoot, ".xencode")
    private val file: File get() = File(dir, "events.ndjson")

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** Appends one event line; returns the rendered line. */
    fun emit(type: String, summary: String? = null, vararg fields: Pair<String, Any?>): String =
        appendLine(Event(type, System.currentTimeMillis(), summary, fields.toMap()))

    fun emit(type: String, summary: String?, fields: Map<String, Any?>): String =
        appendLine(Event(type, System.currentTimeMillis(), summary, fields))

    private fun appendLine(event: Event): String {
        val obj = JsonObject()
        obj.addProperty("xencode_event", event.xencodeEvent)
        obj.addProperty("at", event.at)
        event.summary?.let { obj.addProperty("summary", it) }
        event.fields.forEach { (k, v) ->
            when (v) {
                null -> obj.add(k, com.google.gson.JsonNull.INSTANCE)
                is Number -> obj.addProperty(k, v)
                is Boolean -> obj.addProperty(k, v)
                else -> obj.addProperty(k, v.toString())
            }
        }
        val line = gson.toJson(obj)
        synchronized(this) {
            dir.mkdirs()
            file.appendText(line + "\n", Charsets.UTF_8)
        }
        return line
    }

    /** Last [n] raw lines (oldest first). */
    fun latest(n: Int = 20): List<String> {
        if (!file.isFile) return emptyList()
        return runCatching { file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.takeLast(n) }.getOrDefault(emptyList())
    }

    fun count(): Int = latest(Int.MAX_VALUE).size

    companion object {
        /** Renders an ndjson line for terminals: `HH:mm:ss TYPE summary k=v ...`. */
        fun pretty(line: String): String {
            val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return line
            val ts = obj.get("at")?.asLong?.let {
                val cal = java.util.Calendar.getInstance().apply { timeInMillis = it }
                "%02d:%02d:%02d".format(
                    cal.get(java.util.Calendar.HOUR_OF_DAY),
                    cal.get(java.util.Calendar.MINUTE),
                    cal.get(java.util.Calendar.SECOND)
                )
            } ?: "??:??:??"
            val type = obj.get("xencode_event")?.asString ?: "?"
            val summary = obj.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val extras = obj.entrySet()
                .filter { it.key !in setOf("xencode_event", "at", "summary") }
                .joinToString(" ") { "${it.key}=${it.value.asString.take(40)}" }
            return "$ts $type ${summary.ifBlank { "-" }} $extras".trim()
        }
    }
}
