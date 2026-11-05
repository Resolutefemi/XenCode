package com.xencode.feature.workspace.domain

import java.util.LinkedHashMap

/**
 * Directory listing cache: container path -> snapshot of that directory's most recent **successful listing**.
 *
 * Why it exists: every rebuild of the file tree (expand/collapse/file change/manual refresh) should not have to re-listFiles every expanded
 * directory — a local-mode list is tens to hundreds of stats, ROOT mode is one `su` fork + a stat fork per item,
 * and remote mode is a network round-trip. Collapse a directory then expand it again and there is no reason to hit the disk again.
 *
 * Design conventions (read this first for long-term maintenance):
 * - **Only successful results are cached**: failed listings never enter the cache, so the next rebuild retries them and errors do not stick;
 * - **Invalidation is fully event-driven**: this class has no TTL; correctness relies on the caller invoking
 *   [invalidate]/[invalidateTree] on file changes (see [planBrowseInvalidation]). A missed invalidation only costs a brief
 *   tree-vs-disk inconsistency, so callers must keep a manual full-refresh entry point as a fallback;
 * - **There is a capacity cap**: beyond [maxEntries] the oldest entries are evicted in insertion order, preventing unbounded memory growth when a user expands a whole repository;
 * - **Thread-safe**: internal `synchronized`; reads and writes are fast (nanosecond scale) and never contend with listFiles IO for the lock.
 *
 * @param maxEntries Maximum number of cached directories; eviction is "drop the whole directory", with no cascading to children.
 */
class DirectoryListingCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private data class Snapshot(val entries: List<FileEntry>)

    private val lock = Any()

    /** Insertion-ordered LinkedHashMap: eviction removes the oldest from the head. */
    private val map = LinkedHashMap<String, Snapshot>(16, 0.75f, false)

    /** Returns the entry list on a hit; null on a miss (the caller is responsible for listFiles and backfill). */
    fun get(path: String): List<FileEntry>? = synchronized(lock) {
        map[path]?.entries
    }

    /** Stores a successful listing result; evicts the oldest entry when over capacity. */
    fun put(path: String, entries: List<FileEntry>) = synchronized(lock) {
        map.remove(path)
        map[path] = Snapshot(entries)
        while (map.size > maxEntries) {
            val eldest = map.keys.iterator().next()
            map.remove(eldest)
        }
    }

    /** Invalidates a single directory (its listing is re-read from disk). A no-op if the path is absent. */
    fun invalidate(path: String) = synchronized(lock) {
        if (path.isNotEmpty()) map.remove(path)
    }

    /**
     * Invalidates a directory and all its descendants (used when the directory is deleted/moved): cached `path/...` children are dropped too,
     * so a deleted-then-recreated directory does not show a stale child listing.
     */
    fun invalidateTree(path: String) = synchronized(lock) {
        if (path.isEmpty()) return@synchronized
        map.remove(path)
        val prefix = "$path/"
        map.keys.iterator().let { iter ->
            while (iter.hasNext()) if (iter.next().startsWith(prefix)) iter.remove()
        }
    }

    /** Clears everything (used on workspace switch or manual refresh). */
    fun clear() = synchronized(lock) { map.clear() }

    /** Number of currently cached directories (for diagnostics/tests). */
    fun size(): Int = synchronized(lock) { map.size }

    companion object {
        /** Default capacity: sized to the order of "directories a user actually expands"; 256 directories x hundreds of entries each is no memory pressure. */
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
