package com.xencode.feature.workspace.domain

import com.xencode.feature.agent.domain.device.DeviceRootEngine
import java.io.File

/**
 * Script generation and output parsing for [DeviceRootFileAccess] directory listings (pure functions, no Android dependency, unit-testable).
 *
 * Why the rewrite: the old implementation was "one su fork + one stat fork per item" — a directory of 100 entries meant ~100 process creations,
 * so expanding the file tree on a large directory took seconds. Now a single `find -printf` fetches all attributes, cutting process creations from N+1 to 1.
 *
 * Output protocol (records separated by `\0`, fields by `\t`): `type \t size \t mtime \t mode \t name`.
 * `\0` was chosen because filenames may contain tabs or even newlines (the old line+tab splitting parsed such names wrongly —
 * a known limitation, fixed here as well); name is the last segment, and tabs inside it are re-joined by the parser.
 *
 * Fallback: `find -printf` depends on toybox/GNU find (toybox on Android 7+ already supports it). On the rare build that lacks it,
 * the command exits non-zero and the caller falls back to [legacyScript] (old per-item stat semantics) — same behavior, just back on the slow path.
 */
internal object DeviceRootListing {

    /** find-based script: a failed cd keeps the old exit-2 convention (NoSuchFileException). */
    fun findScript(real: String): String =
        "cd ${DeviceRootEngine.shellQuote(real)} 2>/dev/null || exit 2; " +
            "find -L . -mindepth 1 -maxdepth 1 -printf '%y\\t%s\\t%T@\\t%m\\t%f\\0'"

    /** Legacy script (fallback): cd then glob, stat each item for size|time|permissions. */
    fun legacyScript(real: String): String =
        "cd ${DeviceRootEngine.shellQuote(real)} 2>/dev/null || exit 2; " +
            "for f in * .[!.]* ..?*; do " +
            "[ -e \"\$f\" ] || [ -L \"\$f\" ] || continue; " +
            "if [ -d \"\$f\" ]; then t=d; elif [ -f \"\$f\" ]; then t=f; else t=o; fi; " +
            "sm=\$(stat -c '%s|%Y|%A' \"\$f\" 2>/dev/null); " +
            "printf '%s\\t%s\\t%s\\n' \"\$t\" \"\${sm:-0|0|-}\" \"\$f\"; " +
            "done"

    /**
     * Parses the output of [findScript]. [real] is the directory's real-device absolute path, used to fill in [FileEntry.localFile].
     * Malformed records (missing fields, empty name) are skipped; missing stat fields default to 0 and permissions to "rwx" (same defaults as the old implementation).
     */
    fun parseFindOutput(output: String, real: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        for (record in output.split('\u0000')) {
            if (record.isEmpty()) continue
            val p1 = record.indexOf('\t')
            val p2 = if (p1 >= 0) record.indexOf('\t', p1 + 1) else -1
            val p3 = if (p2 >= 0) record.indexOf('\t', p2 + 1) else -1
            val p4 = if (p3 >= 0) record.indexOf('\t', p3 + 1) else -1
            if (p1 < 0 || p2 < 0 || p3 < 0 || p4 < 0) continue
            val type = record.substring(0, p1)
            val size = record.substring(p1 + 1, p2).toLongOrNull() ?: 0L
            // %T@ looks like "1729165800.0123456789": keep whole seconds only (millisecond precision is enough for the file tree and file details)
            val mtime = record.substring(p2 + 1, p3).substringBefore('.').toLongOrNull() ?: 0L
            val mode = record.substring(p3 + 1, p4)
            val name = record.substring(p4 + 1)
            if (name.isEmpty()) continue
            out += FileEntry(
                name = name,
                isDirectory = type == "d",
                size = size,
                lastModified = mtime * 1000,
                localFile = File(real, name),
                permissions = permissionsFromOctal(mode)
            )
        }
        return out
    }

    /**
     * Parses the output of [legacyScript] (old format: `type \t size|mtime|perm \t name`, line-separated).
     * name may contain tabs (the old implementation re-joined them via subList); the same semantics are kept here.
     */
    fun parseLegacyOutput(output: String, real: String): List<FileEntry> {
        return output.lineSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val name = parts.subList(2, parts.size).joinToString("\t")
                if (name.isEmpty()) return@mapNotNull null
                val meta = parts[1].split('|')
                FileEntry(
                    name = name,
                    isDirectory = parts[0] == "d",
                    size = meta.getOrNull(0)?.toLongOrNull() ?: 0L,
                    lastModified = (meta.getOrNull(1)?.toLongOrNull() ?: 0L) * 1000,
                    localFile = File(real, name),
                    permissions = meta.getOrNull(2)?.takeIf { it.length >= 4 }?.substring(1, 4) ?: "rwx"
                )
            }
            .toList()
    }

    /**
     * Converts the octal permission string from `stat`/`find` (e.g. "644", "40755") into the owner's rwx triplet (e.g. "rw-").
     * Only the top bit of the last three digits (owner) is taken, matching the old semantics of `stat -c %A` then taking the first three chars of `-rwx`;
     * setuid/sticky and group/other bits are meaningless for the file tree and file details, so they are not shown.
     * Returns "rwx" when unparseable (root-view default of read/write/execute).
     */
    fun permissionsFromOctal(octal: String): String {
        val digits = octal.filter { it.isDigit() }.takeLast(3)
        if (digits.length < 3 || digits.any { it !in '0'..'7' }) return "rwx"
        val v = digits[0] - '0'
        return buildString {
            append(if (v and 4 != 0) 'r' else '-')
            append(if (v and 2 != 0) 'w' else '-')
            append(if (v and 1 != 0) 'x' else '-')
        }
    }
}
