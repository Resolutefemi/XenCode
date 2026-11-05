package com.xencode.feature.agent.domain.subagent

import com.xencode.core.util.FileLogger
import com.xencode.feature.workspace.domain.FileAccessProvider

/** Sub-agent definition source: `*.md` files under one directory, one agent per file. */
interface AgentDefinitionSource {
    fun listDefinitions(): List<AgentDefinition>
}

/** Directory scan: takes only top-level `*.md`, so skill directories and other unrelated content are never mistaken for agent definitions. */
internal object AgentDefinitionDirectoryScanner {
    private const val TAG = "AgentDefinitionScanner"

    /**
     * Scans top-level `*.md` under [root], one definition per file. Returns an empty list when the directory does not exist;
     * on scan errors (IO failure, remote workspace not connected, etc.) it degrades to an empty list and logs, never throwing upward.
     */
    fun scan(provider: FileAccessProvider, root: String): List<AgentDefinition> = runCatching {
        if (!provider.isDirectory(root)) return@runCatching emptyList()
        val base = root.trimEnd('/')
        val paths = provider.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .map { "$base/${it.name}" }
        if (paths.isEmpty()) return@runCatching emptyList()
        // One batched read (the ROOT channel fetches the whole batch in one su round trip), replacing the N subprocesses of per-file parse.
        val contents = provider.readFiles(paths)
        paths.mapNotNull { path -> contents[path]?.let { AgentDefinitionParser.parseText(it, path) } }
            .sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "Failed to scan sub-agent definition directory: $root", e)
        emptyList()
    }
}
