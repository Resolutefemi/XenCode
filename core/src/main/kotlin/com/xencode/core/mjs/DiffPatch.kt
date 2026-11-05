package com.xencode.core.mjs

/**
 * Minimal unified-diff applier for the `.mjs` `fs.applyPatch` binding.
 *
 * Understands the hunks git/diff produce for a single file:
 * ```
 * --- a/path
 * +++ b/path
 * @@ -l,s +l,s @@
 *  context
 * -removed
 * +added
 * ```
 * Context and removed lines must match the current file exactly (small fuzz
 * search like GNU patch); a stale patch is rejected with a precise reason and
 * never corrupts the file. `\ No newline at end of file` markers are ignored.
 */
object DiffPatch {

    private sealed class Token {
        data class Ctx(val line: String) : Token()
        data class Del(val line: String) : Token()
        data class Add(val line: String) : Token()
    }

    private class Hunk(val oldStart: Int, val tokens: List<Token>) {
        val removedCount: Int get() = tokens.count { it is Token.Del }
        val addedCount: Int get() = tokens.count { it is Token.Add }
        val firstOldIndex: Int? get() = tokens.indexOfFirst { it !is Token.Add }.takeIf { it >= 0 }
    }

    sealed class Result {
        data class Applied(val hunks: Int, val linesChanged: Int) : Result()
        data class Rejected(val reason: String) : Result()
    }

    private fun parse(diff: String): List<Hunk> {
        val hunks = ArrayList<Hunk>()
        var tokens: MutableList<Token>? = null
        var oldStart = 0
        fun flush(list: MutableList<Token>?) {
            if (list != null) hunks.add(Hunk(oldStart, list.toList()))
        }
        for (raw in diff.lines()) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("@@") -> {
                    flush(tokens)
                    tokens = null
                    val m = Regex("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@").find(line) ?: continue
                    oldStart = m.groupValues[1].toInt()
                    tokens = mutableListOf()
                }
                line.startsWith("---") || line.startsWith("+++") || line.startsWith("diff ") ||
                    line.startsWith("index ") || line.startsWith("\\") -> continue
                else -> {
                    val t = tokens ?: continue
                    when {
                        line.startsWith("+") -> t.add(Token.Add(line.substring(1)))
                        line.startsWith("-") -> t.add(Token.Del(line.substring(1)))
                        line.startsWith(" ") -> t.add(Token.Ctx(line.substring(1)))
                        line.isEmpty() -> t.add(Token.Ctx(""))
                    }
                }
            }
        }
        flush(tokens)
        return hunks
    }

    /** Applies [diff] to [current]; returns the patched text or a rejection. */
    fun applyToString(current: String, diff: String): Pair<String, Result> {
        val hunks = parse(diff)
        if (hunks.isEmpty()) return current to Result.Rejected("no @@ hunks found in patch")
        val lines = current.split('\n').toMutableList()
        var offset = 0
        var linesChanged = 0

        for (hunk in hunks) {
            if (hunk.tokens.all { it is Token.Add }) {
                // Pure insertion: place at oldStart-1+offset (== position after which to insert).
                var insertAt = hunk.oldStart - 1 + offset
                if (insertAt < 0) insertAt = 0
                if (insertAt > lines.size) insertAt = lines.size
                hunk.tokens.asReversed().forEach { t -> if (t is Token.Add) lines.add(insertAt, t.line) }
                offset += hunk.addedCount
                linesChanged += hunk.addedCount
                continue
            }

            // Expected start of the hunk's old text in the file.
            val expected = hunk.oldStart - 1 + offset
            val span = hunk.tokens.count { it !is Token.Add }

            // Verify with a fuzz window around the expected position.
            var found = -1
            search@ for (probe in expected - 3..expected + 3) {
                if (probe < 0 || probe + span > lines.size) continue
                var i = probe
                for (t in hunk.tokens) {
                    when (t) {
                        is Token.Ctx -> if (lines[i] != t.line) continue@search
                        is Token.Del -> if (lines[i] != t.line) continue@search
                        is Token.Add -> Unit
                    }
                    if (t !is Token.Add) i++
                }
                found = probe
                break
            }
            if (found < 0) {
                return current to Result.Rejected(
                    "hunk at old line ${hunk.oldStart} does not match the file (expected context/removed lines not found around line ${expected + 1})"
                )
            }

            // Splice: rebuild the region [found, found+span) from the tokens.
            val rebuilt = ArrayList<String>(hunk.tokens.size)
            for (t in hunk.tokens) when (t) {
                is Token.Ctx -> rebuilt.add(t.line)
                is Token.Del -> Unit
                is Token.Add -> rebuilt.add(t.line)
            }
            repeat(span) { lines.removeAt(found) }
            rebuilt.asReversed().forEach { lines.add(found, it) }
            linesChanged += hunk.removedCount + hunk.addedCount
            offset += hunk.addedCount - hunk.removedCount
        }
        return lines.joinToString("\n") to Result.Applied(hunks.size, linesChanged)
    }
}
