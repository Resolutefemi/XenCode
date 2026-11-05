package com.xencode.core.watch

import com.xencode.core.util.GitIgnoreMatcher
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The watched root a change belongs to: the workspace, the AI config directory (`~/.xencode`), or another host directory specified by the subscriber. */
enum class ChangeRoot { WORKSPACE, XENCODE, OTHER }

/** The type of a single change. Multiple changes to the same path within one batch window are merged into one. */
enum class ChangeKind { CREATED, MODIFIED, DELETED }

/**
 * A single file change. [containerPath] is the container-view path (what the AI /
 * terminal sees), ready for consumers to use directly; consumers that match by host path
 * (e.g. the sync engine) use [hostPath].
 */
data class FileChange(
    val root: ChangeRoot,
    val hostPath: String,
    val containerPath: String,
    val kind: ChangeKind
)

/**
 * Changes collected within one batch window. [changes] is de-duplicated by path (each path
 * appears once, kinds merged); [truncated] = true means the details exceeded the window cap
 * and were truncated — only the "something changed in this scope" signal is guaranteed.
 */
data class FileChangeBatch(
    val changes: List<FileChange>,
    val truncated: Boolean = false
)

/**
 * Subscriber-side filter rules: only affects "whether recursion enters a directory" and
 * "whether events inside it are reported".
 * The check applies to parent path segments only, excluding the last one — additions/removals
 * of the pruned directory itself are still reported, since only the parent's listing can see
 * it.
 *
 * Deliberately contains no built-in ignore list: pruning derives entirely from the
 * subscriber ([ignoredNames] / [extraPatterns]) and the subscription root's .gitignore
 * ([followGitignore]).
 */
data class WatchFilter(
    val ignoredNames: Set<String> = emptySet(),
    val extraPatterns: List<String> = emptyList(),
    val followGitignore: Boolean = true
) {
    companion object {
        val DEFAULT = WatchFilter()
    }
}

/** Directory pruning check (pure logic, easy to unit-test). */
class IgnoreRules internal constructor(
    private val ignoredNames: Set<String>,
    private val patterns: List<String>
) {
    /** [relativeParts] are path segments relative to the subscription root (callers pass only the parent segments, excluding the last one). */
    fun isIgnoredDir(relativeParts: List<String>): Boolean {
        if (relativeParts.isEmpty()) return false
        if (ignoredNames.isNotEmpty() && relativeParts.any { it in ignoredNames }) return true
        return patterns.isNotEmpty() && GitIgnoreMatcher.isIgnored(patterns, relativeParts)
    }

    companion object {
        fun of(filter: WatchFilter, gitignorePatterns: List<String>): IgnoreRules =
            IgnoreRules(filter.ignoredNames, gitignorePatterns + filter.extraPatterns)
    }
}

/** For UI subscriptions that only need the "something changed" signal; the batch window has already merged a directory's burst of changes into one. */
fun Flow<FileChangeBatch>.asDirtySignal(): Flow<Unit> = map { }

/** Converts a host absolute path into path segments relative to [root]. */
internal fun relativePartsOf(root: File, path: File): List<String> {
    val rootPath = root.absolutePath.trimEnd(File.separatorChar)
    val abs = path.absolutePath
    // The prefix may be followed by `/` (Android/Linux) or `\` (Windows JVM, e.g. unit
    // tests); strip both
    val rel = if (abs == rootPath) "" else abs.removePrefix(rootPath).trimStart('/', '\\')
    return rel.split(File.separatorChar, '/').filter { it.isNotEmpty() }
}

/** Reads the subscription root's .gitignore (dropping blank lines, comments, and trailing slashes); returns empty if missing or unreadable. */
internal fun readGitignorePatterns(root: File): List<String> {
    val file = File(root, ".gitignore")
    if (!file.isFile) return emptyList()
    return runCatching {
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removeSuffix("/") }
    }.getOrDefault(emptyList())
}

/**
 * Single-level snapshot of a directory: child name → marker. Files record mtime+size;
 * directories record only their type (a directory's own mtime changes with its internal
 * additions/removals, and including it in the comparison would only duplicate the events of
 * the subdirectory itself).
 */
internal fun snapshotDir(dir: File): Map<String, String> {
    val children = dir.listFiles() ?: return emptyMap()
    val map = HashMap<String, String>(children.size)
    for (child in children) {
        map[child.name] =
            if (child.isDirectory) "d" else "f:${child.lastModified()}:${child.length()}"
    }
    return map
}

/** Compares two snapshots, producing child name → change kind. */
internal fun diffSnapshot(
    prev: Map<String, String>,
    now: Map<String, String>
): List<Pair<String, ChangeKind>> {
    if (prev.isEmpty() && now.isEmpty()) return emptyList()
    val out = ArrayList<Pair<String, ChangeKind>>()
    for ((name, value) in now) {
        val old = prev[name]
        when {
            old == null -> out += name to ChangeKind.CREATED
            old != value -> out += name to ChangeKind.MODIFIED
        }
    }
    for (name in prev.keys) {
        if (name !in now) out += name to ChangeKind.DELETED
    }
    return out
}

/** Merge rules for multiple successive changes to the same path within one window. */
internal fun mergeKind(prev: ChangeKind, next: ChangeKind): ChangeKind = when {
    prev == next -> prev
    (prev == ChangeKind.CREATED && next == ChangeKind.DELETED) ||
        (prev == ChangeKind.DELETED && next == ChangeKind.CREATED) -> ChangeKind.MODIFIED
    else -> next
}