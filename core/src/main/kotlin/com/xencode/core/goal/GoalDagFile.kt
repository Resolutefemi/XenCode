package com.xencode.core.goal

import com.google.gson.Gson
import java.io.File

/**
 * Loads/saves `.xencode/GOAL_DAG.json` under a workspace root. The JSON field names
 * are the cross-platform contract (Android reads/writes the same file); see
 * GoalDag.kt for the twin-implementation note.
 */
object GoalDagFile {

    private val gson = Gson()

    const val FILE_NAME = ".xencode/GOAL_DAG.json"

    fun filePathFor(workspaceRoot: String): String =
        workspaceRoot.trimEnd('/') + "/" + FILE_NAME

    fun load(workspaceRoot: String): GoalDag {
        val f = File(filePathFor(workspaceRoot))
        if (!f.isFile) return empty()
        return runCatching {
            gson.fromJson(f.readText(Charsets.UTF_8), DagJson::class.java)?.toDomain() ?: empty()
        }.getOrElse { empty() }
    }

    fun save(workspaceRoot: String, dag: GoalDag): Boolean = runCatching {
        val f = File(filePathFor(workspaceRoot))
        f.parentFile?.mkdirs()
        // Write-then-move so a crash never leaves a half-written DAG behind.
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(gson.toJson(DagJson.from(dag)), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(gson.toJson(DagJson.from(dag)), Charsets.UTF_8)
            tmp.delete()
        }
        true
    }.getOrElse { false }

    fun empty(): GoalDag = GoalDag(nodes = emptyList(), nextId = 1, updatedAt = System.currentTimeMillis())

    // ---- Gson mirrors (the on-disk schema; also produced by the Android app) ----

    private data class DagJson(val format: Int, val nextId: Int, val updatedAt: Long, val nodes: List<NodeJson>?) {
        fun toDomain() = GoalDag(
            format = format,
            nextId = nextId,
            updatedAt = updatedAt,
            nodes = (nodes ?: emptyList()).map { it.toDomain() }
        )

        companion object {
            fun from(g: GoalDag) = DagJson(g.format, g.nextId, g.updatedAt, g.nodes.map { NodeJson.from(it) })
        }
    }

    private data class NodeJson(
        val id: String,
        val title: String,
        val detail: String?,
        val dependsOn: List<String>?,
        val status: String,
        val notes: List<String>?,
        val createdAt: Long,
        val updatedAt: Long
    ) {
        fun toDomain() = GoalNode(
            id = id,
            title = title,
            detail = detail ?: "",
            dependsOn = dependsOn ?: emptyList(),
            status = runCatching { GoalStatus.valueOf(status) }.getOrDefault(GoalStatus.PENDING),
            notes = notes ?: emptyList(),
            createdAt = createdAt,
            updatedAt = updatedAt
        )

        companion object {
            fun from(n: GoalNode) = NodeJson(
                n.id, n.title, n.detail, n.dependsOn, n.status.name, n.notes, n.createdAt, n.updatedAt
            )
        }
    }
}
