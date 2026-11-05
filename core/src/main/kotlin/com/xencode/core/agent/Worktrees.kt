package com.xencode.core.agent

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Isolated git worktrees for parallel goal swimlanes and `.mjs` automation.
 *
 * Worktrees are created OUTSIDE the repository tree (system temp dir) so the
 * user's `git status` and XenCode checkpoints never see them; `git worktree
 * list` still tracks them, and [remove] prunes cleanly.
 */
object Worktrees {

    private fun git(root: File, vararg args: String, timeoutSec: Long = 30): Pair<Int, String>? = runCatching {
        val proc = ProcessBuilder(listOf("git") + args)
            .directory(root)
            .redirectErrorStream(true)
            .start()
        val buf = java.io.ByteArrayOutputStream()
        proc.inputStream.use { input ->
            val chunk = ByteArray(4 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (buf.size() > 10_000) break
                buf.write(chunk, 0, n)
            }
        }
        proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        proc.exitValue() to buf.toString(Charsets.UTF_8).trim()
    }.getOrNull()

    fun isGitRepo(root: File): Boolean =
        git(root, "rev-parse", "--is-inside-work-tree")?.second == "true"

    data class Worktree(val id: String, val path: String, val branch: String)

    /** Lists registered worktrees (id = branch suffix after `xencode/`). */
    fun list(root: File): List<Worktree> {
        val out = git(root, "worktree", "list", "--porcelain")?.second ?: return emptyList()
        return out.lines().filter { it.startsWith("worktree ") }
            .mapNotNull { line ->
                val path = line.removePrefix("worktree ").trim()
                val branch = git(File(path), "rev-parse", "--abbrev-ref", "HEAD")?.second?.trim() ?: return@mapNotNull null
                if (branch.startsWith("xencode/")) Worktree(branch.removePrefix("xencode/"), path, branch) else null
            }
    }

    /**
     * Creates a worktree on branch `xencode/<id>` in a fresh temp directory.
     * @return the worktree, or null with [lastError] set when git failed.
     */
    fun create(root: File, id: String): Worktree? {
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "-")
        if (safeId.isBlank()) { lastError = "invalid worktree id"; return null }
        val tmp = runCatching { java.nio.file.Files.createTempDirectory("xencode-worktree-").toFile() }.getOrNull()
        if (tmp == null) { lastError = "cannot create temp dir"; return null }
        val res = git(root, "worktree", "add", tmp.absolutePath, "-b", "xencode/$safeId")
        if (res == null || res.first != 0) {
            lastError = "git worktree add failed: ${(res?.second ?: "").take(200)}"
            runCatching { tmp.deleteRecursively() }
            return null
        }
        lastError = null
        return Worktree(safeId, tmp.absolutePath, "xencode/$safeId")
    }

    fun remove(root: File, id: String): Boolean {
        val wt = list(root).firstOrNull { it.id == id } ?: run {
            lastError = "no worktree with id `$id`"
            return false
        }
        val rm = git(root, "worktree", "remove", "--force", wt.path)
        git(root, "branch", "-D", wt.branch)
        if (rm == null || rm.first != 0) {
            lastError = "git worktree remove failed: ${(rm?.second ?: "").take(200)}"
            return false
        }
        lastError = null
        return true
    }

    var lastError: String? = null
        private set
}
