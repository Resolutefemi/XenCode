package com.xencode.feature.agent.domain.goal

import com.google.gson.Gson
import com.xencode.feature.workspace.domain.FileAccessProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for the workspace Goal DAG: a single `.xencode/GOAL_DAG.json` in the
 * active workspace root. Reads and writes go through [FileAccessProvider], so the
 * file transparently follows the execution channel (local / device-root / remote SSH),
 * exactly like `.xencode/AGENTS.md`.
 *
 * The file is small (bounded by goal count) and written atomically-enough for a
 * single-writer model: the app is the only writer, and /goal mutations are
 * serialized on the caller's coroutine.
 */
@Singleton
class GoalDagRepository @Inject constructor(
    private val fileAccess: FileAccessProvider
) {
    private val gson = Gson()

    fun filePathFor(workspaceRoot: String): String =
        workspaceRoot.trimEnd('/') + "/" + FILE_NAME

    /** Loads the DAG for [workspaceRoot]; a missing or unreadable file yields an empty graph. */
    fun load(workspaceRoot: String): GoalDag? {
        if (workspaceRoot.isBlank()) return null
        val path = filePathFor(workspaceRoot)
        if (!fileAccess.exists(path)) return emptyGraph()
        return runCatching {
            val parsed = gson.fromJson(fileAccess.readFile(path), GoalDagJson::class.java)
            parsed?.toDomain() ?: emptyGraph()
        }.getOrElse { emptyGraph() }
    }

    /** Persists [graph]; creates `.xencode/` on demand. Returns false when the write fails. */
    fun save(workspaceRoot: String, graph: GoalDag): Boolean {
        if (workspaceRoot.isBlank()) return false
        val root = workspaceRoot.trimEnd('/')
        val dir = "$root/.xencode"
        if (!fileAccess.exists(dir)) {
            runCatching { fileAccess.mkdirs(dir) }
        }
        return runCatching {
            fileAccess.writeFile(filePathFor(root), gson.toJson(GoalDagJson.from(graph)), overwrite = true)
            true
        }.getOrElse { false }
    }

    fun emptyGraph(): GoalDag = GoalDag(nodes = emptyList(), nextId = 1, updatedAt = System.currentTimeMillis())

    private companion object {
        const val FILE_NAME = ".xencode/GOAL_DAG.json"
    }
}

/** Gson-friendly mirror of [GoalDag]; field names are the on-disk contract
 *  (also read by the CLI / desktop clients — do not rename casually). */
private data class GoalDagJson(
    val format: Int,
    val nextId: Int,
    val updatedAt: Long,
    val nodes: List<GoalNodeJson>?
) {
    fun toDomain(): GoalDag = GoalDag(
        format = format,
        nextId = nextId,
        updatedAt = updatedAt,
        nodes = (nodes ?: emptyList()).map { it.toDomain() }
    )

    companion object {
        fun from(g: GoalDag): GoalDagJson = GoalDagJson(
            format = g.format,
            nextId = g.nextId,
            updatedAt = g.updatedAt,
            nodes = g.nodes.map { GoalNodeJson.from(it) }
        )
    }
}

private data class GoalNodeJson(
    val id: String,
    val title: String,
    val detail: String?,
    val dependsOn: List<String>?,
    val status: String,
    val notes: List<String>?,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): GoalNode = GoalNode(
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
        fun from(n: GoalNode): GoalNodeJson = GoalNodeJson(
            id = n.id,
            title = n.title,
            detail = n.detail,
            dependsOn = n.dependsOn,
            status = n.status.name,
            notes = n.notes,
            createdAt = n.createdAt,
            updatedAt = n.updatedAt
        )
    }
}
