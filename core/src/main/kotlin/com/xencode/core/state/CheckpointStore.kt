package com.xencode.core.state

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Time-machine checkpoints: atomic git commits taken before risky work, with a
 * ledger at `<workspace>/.xencode/checkpoints.json` and restore by id.
 *
 * The `.xencode/` directory itself is never staged, so agent state (sessions,
 * events, ledgers) stays out of the commit history. When the workspace is not a
 * git repository, checkpoint creation degrades to a no-op (clearly reported).
 */
class CheckpointStore(private val workspaceRoot: File) {

    data class Checkpoint(
        val id: String,
        val label: String,
        val summary: String,
        val commit: String?,
        val createdAt: Long,
        val filesChanged: Int
    )

    private val ledgerFile: File get() = File(File(workspaceRoot, ".xencode"), "checkpoints.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun isGitRepo(): Boolean {
        val proc = git("rev-parse", "--is-inside-work-tree")
        return proc != null && proc.second.trim() == "true"
    }

    /**
     * Stages everything except `.xencode/` and creates an atomic commit.
     * @return the new checkpoint, or null when there was nothing to commit /
     *         git is unavailable (see [lastError]).
     */
    fun create(label: String, summary: String): Checkpoint? {
        if (!isGitRepo()) {
            lastError = "not a git repository"
            return null
        }
        git("add", "-A", "--", ".", ":(exclude).xencode")
        val status = git("status", "--porcelain", "--", ".", ":(exclude).xencode") ?: run {
            lastError = "git status failed"
            return null
        }
        if (status.second.isBlank()) {
            lastError = "no changes to checkpoint"
            return null
        }
        val commitOk = git("commit", "-m", "xencode-checkpoint($label): $summary")
        if (commitOk == null || commitOk.first != 0) {
            lastError = "git commit failed: ${(commitOk?.second ?: "").take(200)}"
            return null
        }
        val hash = git("rev-parse", "--short", "HEAD")?.second?.trim()
        val files = status.second.lineSequence().count()
        lastError = null
        val cp = Checkpoint("c" + System.currentTimeMillis().toString(36), label, summary, hash, System.currentTimeMillis(), files)
        persist(cp)
        return cp
    }

    fun list(): List<Checkpoint> = runCatching {
        val type = object : TypeToken<List<Checkpoint>>() {}.type
        gson.fromJson<List<Checkpoint>>(ledgerFile.readText(Charsets.UTF_8), type) ?: emptyList()
    }.getOrElse { emptyList() }

    /** Restores the workspace to the commit recorded by checkpoint [id]. */
    fun restore(id: String): String? {
        val cp = list().firstOrNull { it.id == id } ?: return "no checkpoint with id `$id`"
        if (cp.commit == null) return "checkpoint `${id}` has no commit hash"
        val reset = git("reset", "--hard", cp.commit)
        if (reset == null || reset.first != 0) return "git reset failed: ${(reset?.second ?: "").take(200)}"
        return null // success
    }

    /** `git diff --stat` between a checkpoint commit and the current tree. */
    fun diff(id: String): String? {
        val cp = list().firstOrNull { it.id == id } ?: return "no checkpoint with id `$id`"
        if (cp.commit == null) return "checkpoint `${id}` has no commit hash"
        val diff = git("diff", "--stat", cp.commit) ?: return "git diff failed"
        return diff.second.ifBlank { "(no differences)" }
    }

    /** Last failure reason for display; null after a successful create. */
    var lastError: String? = null
        private set

    private fun persist(cp: Checkpoint) {
        val all = list() + cp
        ledgerFile.parentFile?.mkdirs()
        ledgerFile.writeText(gson.toJson(all), Charsets.UTF_8)
    }

    /** @return (exitCode, combinedOutput) or null when git could not run. */
    private fun git(vararg args: String): Pair<Int, String>? = runCatching {
        val proc = ProcessBuilder(listOf("git") + args)
            .directory(workspaceRoot)
            .redirectErrorStream(true)
            .start()
        val buf = java.io.ByteArrayOutputStream()
        proc.inputStream.use { input ->
            val chunk = ByteArray(4 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                if (buf.size() > 20_000) break
                buf.write(chunk, 0, n)
            }
        }
        proc.waitFor(30, TimeUnit.SECONDS)
        proc.exitValue() to buf.toString(Charsets.UTF_8)
    }.getOrNull()
}
