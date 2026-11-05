package com.xencode.core.util

/**
 * gitignore-style path matching (a moderate implementation, not a full spec parser):
 * - `*.ext`: matches files/directories with that extension at any depth;
 * - `**` wildcard prefix: starts at any depth;
 * - multi-segment patterns (e.g. `build/*.log`): matched at any position in the path
 *   segment sequence;
 * - leading `/` anchoring: with [anchored] = true and no `**/` prefix, the pattern must
 *   match starting at the root path segment; when false, we conservatively treat it as
 *   unanchored (better to over-ignore than to miss an ignore because parsing fell short).
 * Matching works on the full path segment sequence; callers must pass segments relative to
 * the workspace root.
 */
object GitIgnoreMatcher {

    /** Ignored if any pattern matches. */
    fun isIgnored(patterns: List<String>, parts: List<String>, anchored: Boolean = false): Boolean =
        patterns.any { matches(it, parts, anchored) }

    fun matches(pattern: String, parts: List<String>, anchored: Boolean = false): Boolean {
        var p = pattern.trim().trimEnd('/').trimStart('/')
        if (p.isEmpty()) return false
        // Double-star wildcard prefix: starts at any depth (anchored is ignored here — `**/`
        // itself already means unanchored)
        val recursive = p.startsWith("**/")
        if (recursive) p = p.removePrefix("**/")
        if (p.isEmpty()) return false
        // *.ext file pattern: matches file names at any depth
        if (p.startsWith("*.") && '/' !in p) {
            val ext = p.removePrefix("*.")
            return parts.any { it.endsWith(ext) }
        }
        val segs = p.split('/')
        if (segs.size > parts.size) return false
        val starts = if (anchored && !recursive) 0..0 else 0..(parts.size - segs.size)
        return starts.any { start ->
            segs.indices.all { i -> segMatch(segs[i], parts[start + i]) }
        }
    }

    /** Single-segment match: `**` and `*` wildcard segments, and exact segments. */
    private fun segMatch(pattern: String, segment: String): Boolean {
        if (pattern == "**") return true
        if ('*' !in pattern) return pattern == segment
        // Hand-rolled escaping: regex special characters get a backslash; `*` expands
        // directly to `.*`.
        // (Regex.escape in Kotlin 2.x returns the \Q...\E literal form, which can't then be
        // expanded into wildcards via replace)
        val escaped = buildString {
            for (ch in pattern) {
                if (ch == '*') append(".*")
                else if (ch in ".+?()[]{}\\^$|") append('\\').append(ch)
                else append(ch)
            }
        }
        return Regex("^$escaped$").matches(segment)
    }
}
