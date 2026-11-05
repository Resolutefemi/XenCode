package com.xencode.feature.workspace.domain

import com.xencode.core.watch.ChangeKind
import com.xencode.core.watch.FileChangeBatch

/**
 * The plan for how a batch of file changes affects the [DirectoryListingCache].
 *
 * @property relistDirs Directories that need re-reading from disk (when the set is empty and [fullRefresh] is false, no tree rebuild is needed).
 * @property dropTrees Directories whose caches must be dropped along with their descendants (deletion — a directory may be deleted and recreated, and stale child listings would lie).
 * @property fullRefresh Set when details were truncated / the batch is unparseable: only the "something changed" signal is guaranteed; callers should clear the whole cache and rebuild.
 * @property gitignoreTouched The root `.gitignore` was modified: callers must drop the parsed ignore-rule cache.
 */
data class BrowseInvalidation(
    val relistDirs: Set<String> = emptySet(),
    val dropTrees: Set<String> = emptySet(),
    val fullRefresh: Boolean = false,
    val gitignoreTouched: Boolean = false
) {
    val isEmpty: Boolean
        get() = !fullRefresh && relistDirs.isEmpty() && dropTrees.isEmpty() && !gitignoreTouched
}

/**
 * Translates a [FileChangeBatch] into a [BrowseInvalidation] (a pure function, easy to unit test).
 *
 * Rules and rationale:
 * - The **parent directory of P** must always be relisted — creating/deleting/renaming a file changes the parent's listing;
 * - **P itself** is relisted too: with single-level inotify semantics, P is a direct child of a watched directory; if P is a directory, its internal changes
 *   arrive as a change on P itself (MODIFIED), and relisting P refreshes the expanded subtree; for file paths it is a cheap no-op;
 * - A **DELETED directory** drops its whole tree from the cache: after the path is deleted, all cached descendants are void, and a same-named directory
 *   rebuilt afterwards may have completely different contents (paste-overwrites and checkouts are like this) — dropping only itself is not enough;
 * - A **truncated batch** has its details truncated ([FileChangeBatch.truncated]); with no per-entry basis for judgment, the only option is a full clear;
 * - When **`<root>/.gitignore`** is touched, the caller is reminded to re-read the ignore rules (the tree filters nodes by them).
 *
 * @param batch One batching window from the watch hub.
 * @param rootPath The workspace container root path (used to tell whether `.gitignore` is the root-level one).
 */
fun planBrowseInvalidation(batch: FileChangeBatch, rootPath: String): BrowseInvalidation {
    if (batch.truncated) return BrowseInvalidation(fullRefresh = true)
    val relist = LinkedHashSet<String>()
    val drop = LinkedHashSet<String>()
    var gitignoreTouched = false
    val gitignorePath = "$rootPath/.gitignore"
    for (change in batch.changes) {
        val p = change.containerPath
        val parent = p.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) relist.add(parent)
        if (p.isNotEmpty()) relist.add(p)
        if (change.kind == ChangeKind.DELETED) drop.add(p)
        if (p == gitignorePath) gitignoreTouched = true
    }
    return BrowseInvalidation(
        relistDirs = relist,
        dropTrees = drop,
        fullRefresh = false,
        gitignoreTouched = gitignoreTouched
    )
}
