package com.xencode.feature.agent.domain.skill

import com.xencode.core.util.FileLogger
import com.xencode.feature.workspace.domain.FileAccessProvider

/**
 * Shared scanning logic for directory-based skill sources: recursively finds SKILL.md / CLAUDE.md,
 * parsing each directory containing an instruction file into one Skill.
 *
 * Directories are accessed via [FileAccessProvider] with container paths, one code path for local and remote (SSH).
 */
object SkillDirectoryScanner {
    private const val TAG = "SkillDirectoryScanner"

    /** Allow some nesting depth (e.g. repo/skills/my-skill/SKILL.md). */
    private const val MAX_DEPTH = 4
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /**
     * Characters read for head matching: a frontmatter is only a few lines (name/description/required_tools),
     * so 8K is plenty; an over-long frontmatter truncated past its name falls back to the full read, never a misjudge.
     */
    private const val FRONTMATTER_HEAD_CHARS = 8 * 1024

    /** Whether it is an instruction file name (SKILL.md / CLAUDE.md, case-insensitive). */
    private fun isInstructionFile(relative: String): Boolean {
        val name = relative.substringAfterLast('/')
        return name.equals(SKILL_FILE, ignoreCase = true) || name.equals(CLAUDE_FILE, ignoreCase = true)
    }

    /**
     * Scans all valid skills under [root], sorted by name.
     * Returns an empty list when the directory does not exist; on scan errors (IO failure, remote workspace not connected, etc.) it degrades to an empty list and logs,
     * never throwing upward — a skill scan failure should not crash callers (workspace switch, AI requests).
     */
    fun scan(provider: FileAccessProvider, root: String): List<Skill> = runCatching {
        val picked = instructionFiles(provider, root)
        if (picked.isEmpty()) return@runCatching emptyList()

        // One batched read (the ROOT channel fetches the whole batch in one su round trip), replacing hundreds of subprocesses from per-file readFile.
        val contents = provider.readFiles(picked.map { it.first })
        picked.mapNotNull { (filePath, dirPath) ->
            val text = contents[filePath] ?: return@mapNotNull null
            SkillParser.parseText(text, dirPath.substringAfterLast('/').ifBlank { dirPath })
                .copy(dirPath = dirPath)
        }.sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "Failed to scan skill directory: $root", e)
        emptyList()
    }

    /**
     * Precisely locates and reads a single skill's body (for loadSkill): reads only the **head** of each candidate file to match the frontmatter
     * name, then reads the full file on a hit. Compared to [scan]'s "read every skill in full", the cost drops from
     * "recursive listing + full-body batch read" to "recursive listing + one batched head read + one full read".
     *
     * Matching semantics match [scan] exactly: a frontmatter missing name falls back to the directory name; SKILL.md wins over CLAUDE.md in the same directory.
     * When all head matches miss, fall back to [scan]'s full matching — with a frontmatter truncated for length, or a skill that truly does not exist,
     * the result still matches a full scan (when the skill does not exist, this cost buys exactly an accurate "not found" verdict).
     */
    fun locateAndRead(provider: FileAccessProvider, root: String, name: String): String? = runCatching {
        val picked = instructionFiles(provider, root)
        if (picked.isEmpty()) return@runCatching null

        // Batched head read: the frontmatter sits at the file head, 8K is plenty; the ROOT channel fetches the whole batch in one su round trip.
        val heads = provider.readFileHeads(picked.map { it.first }, FRONTMATTER_HEAD_CHARS)
        for ((filePath, dirPath) in picked) {
            val head = heads[filePath] ?: continue
            val meta = SkillParser.parseText(head, dirPath.substringAfterLast('/').ifBlank { dirPath })
            if (!meta.name.equals(name, ignoreCase = true)) continue
            // Hit: read the full text and re-parse with the real frontmatter (the head may have truncated the body and name field)
            val text = provider.readFiles(listOf(filePath))[filePath] ?: provider.readFile(filePath)
            return@runCatching SkillParser.parseText(text, meta.name).instructions.trim()
        }
        // No head match: fall back to a full scan (which also yields the accurate verdict when the skill does not exist)
        scan(provider, root).firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions
    }.getOrElse { e ->
        FileLogger.w(TAG, "Failed to locate skill: $root/$name", e)
        null
    }

    /**
     * Recursively lists instruction files under [root], returning `(file container path, directory container path)`;
     * when a directory has both SKILL.md and CLAUDE.md, only SKILL.md is taken, same as [scan].
     */
    private fun instructionFiles(provider: FileAccessProvider, root: String): List<Pair<String, String>> {
        val base = root.trimEnd('/')
        // The recursive results already carry file names (like "repo/skills/my-skill/SKILL.md"), so pick skill files straight from them,
        // with no per-directory listFiles re-query — on a real device's ROOT channel that would fork one extra su process per skill.
        return provider.listFilesRecursive(root, MAX_DEPTH)
            .map { it.removePrefix("./") }
            .filter { isInstructionFile(it) }
            .groupBy { it.substringBeforeLast('/', "") }
            .values.mapNotNull { files ->
                val chosen = files.firstOrNull { it.substringAfterLast('/').equals(SKILL_FILE, ignoreCase = true) }
                    ?: files.firstOrNull { it.substringAfterLast('/').equals(CLAUDE_FILE, ignoreCase = true) }
                    ?: return@mapNotNull null
                val dirRel = chosen.substringBeforeLast('/', "")
                val dirPath = if (dirRel.isEmpty()) base else "$base/$dirRel"
                "$base/$chosen" to dirPath
            }
    }
}
