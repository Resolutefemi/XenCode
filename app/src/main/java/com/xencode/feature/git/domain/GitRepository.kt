package com.xencode.feature.git.domain

import com.xencode.core.util.FileLogger
import com.xencode.feature.agent.domain.container.CommandEngine
import com.xencode.feature.git.domain.model.GitBranch
import com.xencode.feature.git.domain.model.GitCommit
import com.xencode.feature.git.domain.model.GitFileChange
import com.xencode.feature.git.domain.model.GitGraph
import com.xencode.feature.git.domain.model.GitGraphRef
import com.xencode.feature.git.domain.model.GraphCommit
import com.xencode.feature.git.domain.model.GitStash
import com.xencode.feature.git.domain.model.GitStatus
import com.xencode.feature.git.domain.model.GitTag
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GitRepository"

/**
 * Runs git commands inside the container and parses their output.
 *
 * Commands are dispatched via [GitCommandEngine] - it guarantees git always runs in a complete environment
 * that has the git binary (local container or remote Linux; the device ROOT channel auto-falls back to the local container because Android ships no git).
 * It bypasses the agent toolchain / permission engine - the Git page is user-initiated. All output is parsed into pure domain models.
 *
 * Commands are assembled into a single `git ...` string for `/bin/sh -c` with each argument escaped
 * via [shellQuote], so format strings containing `|`, `%(...)`, and paths with spaces all pass through safely.
 *
 * Every command carries `-c core.quotepath=false`: by default git octal-escapes non-ASCII bytes in paths
 * (a CJK filename becomes `\344\270\255\346\226\207`); turning that off emits raw UTF-8, so CJK directories/filenames no longer garble.
 */
@Singleton
class GitRepository @Inject constructor(
    private val engine: GitCommandEngine,
    private val workspaceRepository: WorkspaceRepository
) {
    private companion object {
        /** Commits loaded per page of the topology graph. The first batch and each "load more" fetch this many; the rest load on scroll to bottom. */
        const val GRAPH_PAGE_SIZE = 100

        /** Byte cap per side of a file the diff may read: beyond it, content is not fetched and it degrades to "file too large". */
        const val MAX_DIFF_FILE_BYTES = 2L * 1024 * 1024
    }
    /**
     * Runs a read-only `git` subcommand and returns the combined stdout+stderr text. Only for read-only commands (status/branches/log/remote etc.):
     * those are parsed tolerantly from output, so a non-zero git exit code cannot mislead the UI (it just parses as empty). Credentials are injected
     * automatically via the credential file by `credential.helper=store` (see [com.xencode.feature.credentials.data.repository.FileCredentialRepository]);
     * no per-command `http.extraHeader` is added here.
     * Each argument is single-quote escaped via [shellQuote], so values with spaces/special characters (commit messages, paths with spaces) pass safely.
     */
    private suspend fun git(
        vararg args: String
    ): String = gitRaw(args)

    /**
     * Runs a `git` **write** subcommand, judging success by exit code: non-zero (a real failure) throws
     * [GitCommandFailureException] carrying git's output text, so [com.xencode.feature.git.presentation.GitViewModel.runAction]
     * can honestly show "failure + reason" instead of falsely reporting success. Typical cases: committing without an identity,
     * unauthorized push, merge conflicts. A missing exit code (timeout/exception) also throws as failure to avoid silent success.
     */
    private suspend fun gitChecked(
        vararg args: String
    ): String = gitCheckedWithTimeout(CommandEngine.DEFAULT_TIMEOUT_MS, *args)

    private suspend fun gitCheckedWithTimeout(
        timeoutMs: Long,
        vararg args: String
    ): String {
        // Run unbounded: diff/file content can far exceed the 40k-character cap on the AI tool path,
        // and a truncation placeholder would mix into the diff stream and be rendered as fake diff lines.
        val result = engine.runCommandSyncUnbounded(buildGitCommand(args), workspaceRepository.currentPath(), timeoutMs)
        if (result.outputTruncated) throw GitOutputTooLargeException()
        if (result.exitCode == 0) return result.output
        throw GitCommandFailureException(result.output.ifBlank { "git exited with code ${result.exitCode}" })
    }

    /** Assembles and runs the command (exit code unchecked); shared by [git] and [gitChecked]. */
    private suspend fun gitRaw(args: Array<out String>): String {
        val result = engine.runCommandSyncUnbounded(buildGitCommand(args), workspaceRepository.currentPath())
        if (result.outputTruncated) throw GitOutputTooLargeException()
        return result.output
    }

    /** Builds the single command string handed to `/bin/sh -c`, escaping each argument with [shellQuote]. */
    private fun buildGitCommand(args: Array<out String>): String = buildString {
        append("git -c core.quotepath=false")
        args.forEach { append(' '); append(shellQuote(it)) }
    }

    /** Whether the current workspace is inside a git work tree. Returns false rather than throwing on errors like SSH being disconnected, keeping the UI from crashing. */
    suspend fun isRepo(): Boolean {
        return runCatching { git("rev-parse", "--is-inside-work-tree").trim() == "true" }
            .getOrElse { false }
    }

    /** Whether the container is ready to run git commands; when not ready, returns guidance text (for the Git page to prompt the user to initialize on the terminal page before refreshing). */
    fun notReadyHint(): String? = engine.notReadyHint()

    /**
     * Initializes a git repository in the current workspace.
     *
     * The default branch prefers the modern standard `main` (Git 2.28+ supports `git init -b main`);
     * if the environment's git is too old for `-b`, fall back to `git init` then point the uninitialized HEAD at `refs/heads/main`.
     */
    suspend fun initRepo(): String {
        return runCatching { gitChecked("init", "-b", "main") }
            .getOrElse {
                val out = gitChecked("init")
                runCatching { git("symbolic-ref", "HEAD", "refs/heads/main") }
                out
            }
    }

    /** Clones the remote repository into the current workspace root (`git clone <url> .`). Success judged by exit code; failure throws [GitCommandFailureException]. */
    suspend fun cloneRepo(url: String): String =
        gitCheckedWithTimeout(600_000L, "clone", url.trim(), ".")

    /** Whether at least one remote is configured (`git remote` output non-empty). Used to gate pull/push. */
    suspend fun hasRemote(): Boolean = git("remote").trim().isNotEmpty()

    /** Aggregated `git status` view. */
    suspend fun status(): GitStatus {
        val raw = git("status", "--porcelain=v1", "-b")
        val lines = raw.split('\n').map { it.removeSuffix("\r") }

        var branch = "(unknown)"
        var upstream: String? = null
        var isDetached = false
        var ahead = 0
        var behind = 0
        val staged = mutableListOf<GitFileChange>()
        val unstaged = mutableListOf<GitFileChange>()
        val untracked = mutableListOf<String>()
        val conflicted = mutableListOf<GitFileChange>()

        for (line in lines) {
            if (line.isBlank()) continue
            if (line.startsWith("## ")) {
                val header = line.removePrefix("## ")
                isDetached = header.startsWith("HEAD (no branch)")
                val tracking = header.substringBefore(" [")
                if (isDetached) {
                    branch = "HEAD"
                } else {
                    branch = tracking.substringBefore("...").ifBlank { tracking }
                    upstream = tracking.substringAfter("...", "").ifBlank { null }
                }
                val bracket = header.substringAfter(" [", "")
                if (bracket.isNotBlank()) {
                    bracket.removeSuffix("]").split(",").forEach { tok ->
                        val t = tok.trim()
                        val n = t.filter { it.isDigit() }.toIntOrNull() ?: 0
                        if (t.startsWith("ahead")) ahead = n
                        else if (t.startsWith("behind")) behind = n
                    }
                }
                continue
            }
            if (line.length < 3) continue
            val x = line[0]
            val y = line[1]
            val rawPath = line.substring(3)
            // Renames look like "old -> new"; show the new path.
            val path = unquoteGitPath(rawPath.substringAfter(" -> ").trim())

            if (x == '?' && y == '?') {
                untracked.add(path)
                continue
            }
            // Unmerged conflicts: UU, AA, DD, AU, UD, UA, DU, etc.
            val isConflict = x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D')
            if (isConflict) {
                conflicted.add(GitFileChange(path, "$x$y", staged = false))
                continue
            }
            if (x != ' ' && x != '?') {
                staged.add(GitFileChange(path, x.toString(), staged = true))
            }
            if (y != ' ' && y != '?') {
                unstaged.add(GitFileChange(path, y.toString(), staged = false))
            }
        }
        val isMerging = hasMergeHead() || conflicted.isNotEmpty()
        return GitStatus(branch, ahead, behind, staged, unstaged, untracked, conflicted, isMerging, upstream, isDetached)
    }

    /** Local + remote branch list, with the current branch highlighted. */
    suspend fun branches(): List<GitBranch> = loadAllRefs().branches

    /**
     * Fetches only the local-branch refs-by-commit map (`for-each-ref refs/heads`, sub-second even on large repos),
     * so the first-screen lightweight snapshot's graph can mark the current branch position. Remote-branch and tag annotations are deferred to the full BRANCHES tab load.
     */
    suspend fun localRefsOnly(): Map<String, List<GitGraphRef>> = withContext(Dispatchers.Default) {
        val raw = runCatching {
            git("for-each-ref", "--format=%(refname:short)\u001f%(objectname)\u001f%(HEAD)\u001f%(refname)", "refs/heads")
        }.getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext emptyMap()
        val result = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            val parts = l.split('\u001f')
            if (parts.size < 4) continue
            val isHead = parts[2].trim() == "*"
            result.getOrPut(parts[1]) { mutableListOf() }
                .add(GitGraphRef(parts[0], isBranch = true, isCurrent = isHead, isRemote = false))
        }
        result
    }

    /** The most recent [limit] commits. */
    suspend fun log(limit: Int = 50): List<GitCommit> {
        val raw = git("log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s", "-n", limit.toString())
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.removeSuffix("\r").split('\u001f')
            if (parts.size < 5) null
            else GitCommit(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
    }

    /**
     * Topology graph view: commits (with parent hashes) + refs (branches/tags) + swimlane layout.
     *
     * `git log --pretty=format:...%P` appends the parent hash list after the usual fields (space-separated; several means a merge);
     * `git for-each-ref` fetches all branches/tags at once with their target commit hashes and HEAD marker. After parsing commits and refs,
     * the pure Kotlin swimlane assignment algorithm runs on [Dispatchers.Default] (see [GitGraphBuilder]) to produce [GitGraph].
     * Failure (not a repo / no commits) returns [GitGraph.EMPTY] instead of throwing - the UI shows the empty state from the empty graph.
     *
     * The first batch loads [GRAPH_PAGE_SIZE] commits; [hasMore] is judged by whether this batch reached the page size, and the UI on scroll-to-bottom
     * triggers [graphAppend] for the next page. If fewer commits than the page size return, hasMore=false and the UI stops offering load-more.
     *
     * [refs] is the refs-by-commit map passed in from outside (from [loadAllRefs]), so graph does not re-fetch all refs itself.
     * When empty, graph annotates no refs (first-screen lightweight snapshot case: local branches only; remote/tag annotations deferred to the BRANCHES tab).
     */
    suspend fun graph(
        limit: Int = GRAPH_PAGE_SIZE,
        refs: Map<String, List<GitGraphRef>> = emptyMap()
    ): GitGraph =
        graphAppend(emptyList(), refs, limit)

    /**
     * Loads the next page of commits and recomputes the swimlane layout over the whole set.
     *
     * Fetches the next batch via `git log --skip=<existingCommits.size> -n <limit>`, merges it with [existingCommits]
     * into the full list, then recomputes the layout with [GitGraphBuilder] over everything - lane assignment depends on the
     * global child-parent order, and a parent may point across batches to an already-loaded commit; computing only the new batch
     * would clash column numbers with the old layout and break edges, so the whole graph must be recomputed. Cost is O(loaded count),
     * milliseconds for pure in-memory Kotlin math on mobile - acceptable.
     *
     * Returns the complete [GitGraph] with all loaded commits (old + new); the ViewModel replaces state.graph wholesale. The UI's
     * LazyColumn recomposes only new rows without flicker thanks to `item(key=...)`. [hasMore] = the new batch reached the page size (otherwise history's end).
     */
    suspend fun graphAppend(
        existingCommits: List<GraphCommit>,
        refs: Map<String, List<GitGraphRef>> = emptyMap(),
        limit: Int = GRAPH_PAGE_SIZE
    ): GitGraph = withContext(Dispatchers.Default) {
        val skip = existingCommits.size
        val logRaw = runCatching { git("log", "--pretty=format:%H%x1f%h%x1f%an%x1f%ar%x1f%s%x1f%P%x1f%b", "--skip", skip.toString(), "-n", limit.toString()) }
            .getOrDefault("")
        if (logRaw.isBlank() || logRaw.startsWith("fatal:")) {
            return@withContext if (existingCommits.isEmpty()) GitGraph.EMPTY
            else GitGraphBuilder.buildGraph(existingCommits, refs, hasMore = false)
        }

        val newCommits = GitGraphBuilder.parseGraphCommits(logRaw)
        if (newCommits.isEmpty() && existingCommits.isEmpty()) return@withContext GitGraph.EMPTY

        // Merge and dedupe: the new batch should theoretically not overlap the loaded ones (--skip skips the loaded count), but dedupe defensively by hash, preserving order.
        val existingHashes = existingCommits.mapTo(HashSet()) { it.hash }
        val merged = existingCommits + newCommits.filter { it.hash !in existingHashes }
        GitGraphBuilder.buildGraph(merged, refs, hasMore = newCommits.size >= limit)
    }

    /**
     * Fetches all branches/tags and their target commit hashes in one call, parsed into three views: the [AllRefs.branches] list,
     * the [AllRefs.tags] list, and the [AllRefs.refsByCommit] map. `branches()`, `listTags()`, and the graph's ref annotation share this
     * single call, eliminating the previous three separate `for-each-ref`/`tag` commands (8-20s each on large repos).
     *
     * `%(refname:short)` is the short name, `%(objectname)` the full target hash, `%(HEAD)` marks the current branch (outputs `*` or empty).
     * Local branches (refs/heads) get isRemote=false, remote branches (refs/remotes) isRemote=true, tags (refs/tags) isBranch=false.
     * Tags are sorted by refname descending (plain string compare, no object reads), avoiding the 8s cost of `--sort=-creatordate` walking every tag object.
     */
    data class AllRefs(
        val branches: List<GitBranch>,
        val tags: List<GitTag>,
        val refsByCommit: Map<String, List<GitGraphRef>>
    )

    suspend fun loadAllRefs(): AllRefs = withContext(Dispatchers.Default) {
        val raw = runCatching {
            git(
                "for-each-ref",
                "--format=%(refname:short)\u001f%(objectname)\u001f%(HEAD)\u001f%(refname)\u001f%(upstream:short)\u001f%(upstream:track)",
                "refs/heads", "refs/remotes", "refs/tags"
            )
        }.getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext AllRefs(emptyList(), emptyList(), emptyMap())
        val branches = mutableListOf<GitBranch>()
        val tags = mutableListOf<GitTag>()
        val refsByCommit = mutableMapOf<String, MutableList<GitGraphRef>>()
        for (line in raw.split('\n')) {
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) continue
            val parts = l.split('\u001f')
            if (parts.size < 4) continue
            val name = parts[0]
            val hash = parts[1]
            val isHead = parts[2].trim() == "*"
            val refname = parts[3]
            val upstream = parts.getOrNull(4)?.ifBlank { null }
            val track = parts.getOrNull(5).orEmpty()
            val ahead = Regex("ahead (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val behind = Regex("behind (\\d+)").find(track)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val isRemote = refname.startsWith("refs/remotes")
            val isBranch = refname.startsWith("refs/heads") || isRemote
            refsByCommit.getOrPut(hash) { mutableListOf() }
                .add(GitGraphRef(name, isBranch, isHead && !isRemote, isRemote))
            if (isBranch) {
                branches.add(GitBranch(name, current = isHead && !isRemote, remote = isRemote, upstream = upstream, ahead = ahead, behind = behind))
            } else {
                // Tags: objectname shortened to the first 7 hash chars, matching the original listTags behavior.
                tags.add(GitTag(name, hash.take(7)))
            }
        }
        // for-each-ref sorts by refname by default (local branches, remote branches, tags each in order); reversing tags matches the original behavior.
        tags.reverse()
        AllRefs(branches, tags, refsByCommit)
    }

    /**
     * Files changed in a commit. `diff-tree --root` handles parent-less root commits; `--no-renames` makes
     * renames degrade into "delete old + add new", so taking the first status character reuses [com.xencode.feature.git.domain.model.GitFileChange].
     * An empty list means the commit changed no files (e.g. an empty commit).
     */
    suspend fun commitFiles(hash: String): List<GitFileChange> {
        val raw = git("diff-tree", "--no-commit-id", "-r", "--root", "--name-status", "--no-renames", hash)
        return withContext(Dispatchers.Default) {
            raw.lineSequence().mapNotNull { line ->
                val l = line.removeSuffix("\r").trim()
                val tab = l.indexOf('\t')
                if (l.isBlank() || tab < 0) null
                else {
                    val status = l.substring(0, tab).trim()
                    val path = unquoteGitPath(l.substring(tab + 1).trim())
                    GitFileChange(path, status, staged = false)
                }
            }.toList()
        }
    }

    suspend fun stage(path: String) = gitChecked("add", "--", path)
    suspend fun unstage(path: String) = gitChecked("reset", "HEAD", "--", path)
    suspend fun stageAll() = gitChecked("add", "-A")
    suspend fun unstageAll() = gitChecked("reset")
    suspend fun commit(message: String, amend: Boolean = false) =
        if (amend) gitChecked("commit", "--amend", "-m", message)
        else gitChecked("commit", "-m", message)

    /**
     * Diff text used for AI-generated commit messages.
     * Prefers the staged diff (`git diff --cached`); if the staging area is empty, falls back to the worktree diff (`git diff`).
     */
    suspend fun diffForCommitMessage(): String {
        val staged = gitRaw(arrayOf("diff", "--cached", "--no-color", "--no-ext-diff"))
        if (staged.isNotBlank() && !staged.startsWith("fatal:")) return staged
        val worktree = gitRaw(arrayOf("diff", "--no-color", "--no-ext-diff"))
        return if (!worktree.startsWith("fatal:")) worktree else ""
    }

    /**
     * Reverts a single file's unstaged changes: restores the worktree to the staged content (`git checkout -- <path>`),
     * bringing deleted files back. Uses `checkout` rather than `restore` - the latter needs git 2.23+,
     * and the server's git under remote SSH mode may be older.
     */
    suspend fun revertWorktree(path: String): String = gitChecked("checkout", "--", path)

    /**
     * Reverts all changes to a single file (staged + unstaged), back to the last commit: first `reset` moves
     * the index back to HEAD, then the worktree is restored from the index. If the file is absent in HEAD (a new
     * file), reset already returned it to untracked, so checkout is skipped - otherwise git fails with `pathspec did not match`.
     * A repo with no commits yet has no HEAD to compare against; only `rm --cached` can return it to untracked.
     */
    suspend fun revertToHead(path: String): String {
        if (!hasHead()) return gitChecked("rm", "-q", "--cached", "--", path)
        gitChecked("reset", "-q", "HEAD", "--", path)
        return if (existsInHead(path)) gitChecked("checkout", "--", path) else ""
    }

    /**
     * Reverts all unstaged changes. The pathspec `:/` means the repository root, so it covers the whole
     * repo even when the workspace is a subdirectory (`--  .` only affects the current directory). Staged content is untouched.
     */
    suspend fun revertAllWorktree(): String = gitChecked("checkout", "--", ":/")

    /**
     * Deletes untracked files or directories (`git clean -f -d`). `-d` covers new directories that status
     * collapses into one line; `-x` is omitted so files ignored by .gitignore stay untouched. These files never entered version control, so git cannot restore them after deletion.
     */
    suspend fun deleteUntracked(path: String): String = gitChecked("clean", "-f", "-d", "--", path)

    /**
     * Untracked files under an untracked directory. `git status` collapses a new directory into one `dir/` line
     * by default without listing its files; the UI calls this on demand when expanding, avoiding `-uall` listing thousands of entries at once in large directories.
     */
    suspend fun untrackedFilesIn(dir: String): List<String> {
        val raw = git("ls-files", "--others", "--exclude-standard", "--", dir)
        if (raw.isBlank() || raw.startsWith("fatal:")) return emptyList()
        return raw.split('\n').mapNotNull { it.removeSuffix("\r").trim().ifBlank { null } }
            .map { unquoteGitPath(it) }
    }

    /** Whether the repo has any commits (HEAD resolvable). In an empty repo `reset HEAD` / `ls-tree HEAD` both fail. */
    private suspend fun hasHead(): Boolean =
        runCatching { git("rev-parse", "--verify", "HEAD").trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /** Whether the given path exists in the HEAD commit. */
    private suspend fun existsInHead(path: String): Boolean =
        runCatching { git("ls-tree", "HEAD", "--", path).trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /**
     * Pull: runs `git pull` directly; credentials are injected automatically by the container's `credential.helper`
     * chain - `store` hits existing credentials instantly, and on a miss the custom helper triggers an app dialog via
     * file IPC and git resumes (see [CredentialRequestBridge]). No host-credential precheck here: all three ends
     * (UI/terminal/AI) share one helper fallback. A missing remote or real failure throws via [gitChecked]; toasted above.
     */
    suspend fun pull(): String = gitChecked("pull")

    /**
     * Push: `git push` when an upstream exists; with none, it runs `git push --set-upstream <remote> <branch>` to create
     * the association on first push, mimicking the Win/Mac git clients' auto-upstream-on-first-push behavior so users never
     * hit the raw `fatal: has no upstream branch` error. The remote is the first from `git remote` (first by default with
     * several; none is already gated by hasRemote above); the branch comes from `git rev-parse --abbrev-ref HEAD`. Credentials are still injected by the container's credential.helper chain as fallback.
     */
    suspend fun push(): String {
        val hasUpstream = runCatching { git("rev-parse", "--abbrev-ref", "@{upstream}").trim() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "HEAD" && !it.startsWith("fatal") } != null
        if (hasUpstream) return gitChecked("push")
        val remote = git("remote").split('\n').firstOrNull { it.removeSuffix("\r").isNotBlank() }?.removeSuffix("\r")?.trim()
            ?: throw GitCommandFailureException("No remote repository configured")
        val branch = git("rev-parse", "--abbrev-ref", "HEAD").removeSuffix("\r").trim()
            .takeIf { it.isNotBlank() && it != "HEAD" }
            ?: throw GitCommandFailureException("Cannot determine the current branch (detached HEAD)")
        return gitChecked("push", "--set-upstream", remote, branch)
    }

    /**
     * Whether this is the first push of the master branch (current branch is master with no upstream tracking yet).
     * Lets the UI dialog ask the user whether to rename it to the modern, conventional main branch.
     */
    suspend fun isFirstPushOfMaster(): Boolean {
        val hasUpstream = runCatching { git("rev-parse", "--abbrev-ref", "@{upstream}").trim() }
            .getOrDefault("")
            .takeIf { it.isNotBlank() && it != "HEAD" && !it.startsWith("fatal") } != null
        if (hasUpstream) return false
        val branch = git("rev-parse", "--abbrev-ref", "HEAD").removeSuffix("\r").trim()
        return branch == "master"
    }

    /** Renames the current master branch to main and performs the first push. */
    suspend fun renameMasterToMainAndPush(): String {
        renameBranch("master", "main")
        return push()
    }

    /** Local tag list, newest first. */
    suspend fun listTags(): List<GitTag> = loadAllRefs().tags

    /**
     * Creates a new branch. name is the new branch name; startPoint is the base branch (null/empty → from current HEAD);
     * checkout=true creates and switches (`git checkout -b`), otherwise it only creates (`git branch`).
     * A missing start point or invalid branch name throws [GitCommandFailureException] from [gitChecked] by exit code; the upper layer toasts it.
     */
    suspend fun createBranch(name: String, startPoint: String?, checkout: Boolean): String {
        return if (checkout) {
            if (startPoint.isNullOrBlank()) gitChecked("checkout", "-b", name)
            else gitChecked("checkout", "-b", name, startPoint)
        } else {
            if (startPoint.isNullOrBlank()) gitChecked("branch", name)
            else gitChecked("branch", name, startPoint)
        }
    }

    /**
     * Safely deletes a local branch (`git branch -d`): only branches merged into their upstream are deleted;
     * otherwise git errors and [gitChecked] throws [GitCommandFailureException] by exit code, toasted above. The current branch cannot be deleted (git blocks it).
     */
    suspend fun deleteBranch(name: String): String = gitChecked("branch", "-d", name)

    /**
     * Renames a local branch (`git branch -m <old> <new>`). The current branch can be renamed too: a single-argument
     * `git branch -m <new>` renames it; here the two-argument form is used, with the caller guaranteeing a non-empty
     * oldName. Invalid or existing names throw via [gitChecked]; toasted above.
     */
    suspend fun renameBranch(oldName: String, newName: String): String =
        gitChecked("branch", "-m", oldName, newName)

    /**
     * Creates a lightweight tag (`git tag <name>`) pointing at the current HEAD or a given commit.
     */
    suspend fun createTag(name: String, commitHash: String? = null): String =
        if (commitHash.isNullOrBlank()) gitChecked("tag", name)
        else gitChecked("tag", name, commitHash)

    /**
     * Resets the current branch to the given commit.
     * [mode] can be "soft" or "mixed".
     */
    suspend fun resetToCommit(commitHash: String, mode: String = "mixed"): String =
        gitChecked("reset", "--$mode", commitHash)

    /**
     * Appends an ignore rule to the workspace's .gitignore file.
     * Creates and appends whether or not .gitignore exists; runs through the engine to support both local and remote modes.
     */
    suspend fun addToGitignore(pattern: String): String {
        val normalized = pattern.trim()
        if (normalized.isBlank()) return ""
        // Check whether .gitignore already has this rule first
        val checkCmd = "grep -F -x -- ${shellQuote(normalized)} .gitignore"
        val checkResult = engine.runCommandSyncUnbounded(checkCmd, workspaceRepository.currentPath())
        if (checkResult.exitCode == 0) return normalized // Already present; no need to append again

        // Otherwise append safely via printf (auto-creating the file if absent)
        val appendCmd = "printf '%s\\n' ${shellQuote(normalized)} >> .gitignore"
        val appendResult = engine.runCommandSyncUnbounded(appendCmd, workspaceRepository.currentPath())
        if (appendResult.exitCode != 0) {
            throw GitCommandFailureException(appendResult.output.ifBlank { "Failed to write .gitignore" })
        }
        return normalized
    }

    /**
     * Deletes a local tag (`git tag -d <name>`). A missing tag throws [GitCommandFailureException] from [gitChecked] by exit code; the upper layer toasts it.
     */
    suspend fun deleteTag(name: String): String = gitChecked("tag", "-d", name)

    /**
     * Deletes a remote branch (`git push <remote> --delete <branch>`). The ref looks like `origin/feature`, split into remote and branch name;
     * without a remote prefix it falls back to `origin`. This changes the remote; failure throws [GitCommandFailureException] from [gitChecked].
     */
    suspend fun deleteRemoteBranch(ref: String): String {
        val remote = ref.substringBefore('/', "origin")
        val branch = ref.substringAfter('/', ref)
        return gitChecked("push", remote, "--delete", branch)
    }

    /** Whether an unfinished merge is in progress (MERGE_HEAD exists). */
    private suspend fun hasMergeHead(): Boolean =
        runCatching { git("rev-parse", "-q", "--verify", "MERGE_HEAD").trim() }
            .getOrDefault("")
            .let { it.isNotBlank() && !it.startsWith("fatal") }

    /**
     * Checks out the given branch or tag. branch may be a local branch, remote branch, or tag name.
     * A remote branch creates a local tracking branch via `git checkout -b <local> <remote>`, stripping the remote prefix (e.g. origin/).
     */
    suspend fun checkout(branch: String, isRemote: Boolean): String {
        return if (isRemote) {
            val localName = branch.substringAfter('/', branch)
            gitChecked("checkout", "-b", localName, "--track", branch)
        } else {
            gitChecked("checkout", branch)
        }
    }

    /**
     * Merges the given branch into the current branch.
     * [branch] is the target branch name.
     * When [noFf] is true, a merge commit is forced even if a fast-forward is possible (`--no-ff`).
     * Conflicts or a dirty worktree throw [GitCommandFailureException] from [gitChecked].
     */
    suspend fun mergeBranch(branch: String, noFf: Boolean = false): String {
        return if (noFf) gitChecked("merge", "--no-ff", branch)
        else gitChecked("merge", branch)
    }

    /**
     * Aborts the in-progress merge (`git merge --abort`).
     */
    suspend fun abortMerge(): String = gitChecked("merge", "--abort")

    /**
     * Lists all stash entries.
     */
    suspend fun stashList(): List<GitStash> = withContext(Dispatchers.Default) {
        val raw = runCatching { git("stash", "list", "--pretty=format:%gd%x1f%s%x1f%ar") }
            .getOrDefault("")
        if (raw.isBlank() || raw.startsWith("fatal:")) return@withContext emptyList()
        raw.split('\n').mapNotNull { line ->
            val l = line.removeSuffix("\r").trim()
            if (l.isBlank()) return@mapNotNull null
            val parts = l.split('\u001f')
            if (parts.size < 3) null
            else GitStash(index = parts[0], message = parts[1], date = parts[2])
        }
    }

    /**
     * Stashes worktree changes.
     * [message] is an optional description; [includeUntracked] true includes untracked files (`-u`).
     */
    suspend fun stashSave(message: String? = null, includeUntracked: Boolean = false): String {
        val args = mutableListOf("stash", "push")
        if (includeUntracked) args.add("-u")
        if (!message.isNullOrBlank()) {
            args.add("-m")
            args.add(message.trim())
        }
        return gitChecked(*args.toTypedArray())
    }

    /**
     * Pops the given stash (applies and deletes), defaulting to the most recent `stash@{0}`.
     */
    suspend fun stashPop(index: String = "stash@{0}"): String =
        gitChecked("stash", "pop", index)

    /**
     * Applies the given stash (keeping the entry), defaulting to the most recent `stash@{0}`.
     */
    suspend fun stashApply(index: String = "stash@{0}"): String =
        gitChecked("stash", "apply", index)

    /**
     * Drops the given stash, defaulting to `stash@{0}`.
     */
    suspend fun stashDrop(index: String = "stash@{0}"): String =
        gitChecked("stash", "drop", index)

    /**
     * Clears all stash entries.
     */
    suspend fun stashClear(): String = gitChecked("stash", "clear")

    /**
     * Writes the commit identity, **project-level first**: writes local when the current workspace
     * (~/workspace/.git/config) already has a project-level identity; otherwise writes global (container
     * `GIT_CONFIG_GLOBAL=/root/.xencode/.gitconfig`, persistently mounted, survives rootfs upgrades) as default.
     * Blank values skip their entry; the UI and terminal read the same value, matching git's own resolution order.
     */
    suspend fun setUserIdentity(name: String, email: String) {
        if (name.isNotBlank()) gitChecked("config", if (hasLocalConfig("user.name")) "--local" else "--global", "user.name", name)
        if (email.isNotBlank()) gitChecked("config", if (hasLocalConfig("user.email")) "--local" else "--global", "user.email", email)
    }

    /** Whether the current workspace has a project-level (`--local`) value configured. Returns false outside a git repo or when unset. */
    private suspend fun hasLocalConfig(key: String): Boolean =
        runCatching { git("config", "--local", "--get", key).trim() }.getOrDefault("").isNotBlank()

    /** Reads the user.name git actually resolves right now (git's order: local→global→system), for UI echo and the commit button's blank check. Returns an empty string on failure. */
    suspend fun getUserName(): String =
        runCatching { git("config", "--get", "user.name").trim() }.getOrDefault("").removeSuffix("\r")

    /** Reads the user.email git actually resolves right now (local→global→system), for UI echo and the edit field's initial value. Returns an empty string on failure. */
    suspend fun getUserEmail(): String =
        runCatching { git("config", "--get", "user.email").trim() }.getOrDefault("").removeSuffix("\r")

    /**
     * Writes the repository URL remote.origin.url, **project-level only**: remote.origin.url is one repo's remote
     * address and, unlike identity defaults like user.name/email, must never go to global. Once in global, any later
     * `git clone` makes git resolve both the global and clone-written local origin.url, so fetch uses the stale global
     * value and push shows multiple entries pointing at different repos (reproduced in testing).
     *
     * So this always writes `--local` (the workspace must be a git repo); a non-repo throws [GitCommandFailureException],
     * with no global fallback. It also cleans up any leftover global remote.origin.url from old versions, removing the pollution.
     */
    suspend fun setRepoUrl(url: String) {
        if (url.isNotBlank()) {
            gitChecked("config", "--local", "remote.origin.url", url)
        } else {
            // A blank value deletes local remote.origin.url; git config --unset returns non-zero for a missing key without affecting other config
            runCatching { gitChecked("config", "--local", "--unset", "remote.origin.url") }
        }
        // Clear a leftover global remote.origin.url (historical mistake) to avoid polluting later git clone and other commands.
        runCatching { git("config", "--global", "--unset", "remote.origin.url") }
    }

    /** Reads the remote.origin.url git actually resolves right now (local→global→system), for UI echo and the edit field's initial value. Returns an empty string on failure. */
    suspend fun getRepoUrl(): String =
        runCatching { git("config", "--get", "remote.origin.url").trim() }.getOrDefault("").removeSuffix("\r")

    /**
     * Reads a file's full content under the given ref (commit/branch/tag) (`git show <ref>:<path>`).
     * Used for commit-file diffs: compares `<hash>^:<path>` (before) with `<hash>:<path>` (after).
     * When the file is absent under the ref (e.g. the first commit adding it), git errors with `fatal:`;
     * detecting that returns an empty string, which the upper layer treats as "added/deleted" and renders the whole file as all-added or all-deleted.
     */
    suspend fun showFileContent(ref: String, path: String): String {
        if (refBlobSize("$ref:$path") > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val out = git("show", "$ref:$path")
        // git show writes fatal to stderr for a missing path, and runCommandSync merges stdout+stderr.
        // A fatal prefix means the file is absent in that version; return an empty string so the diff treats it as all-added/all-deleted.
        return if (out.startsWith("fatal:") || out.startsWith("error:")) "" else out
    }

    /** Byte size of a file under the given ref (`<ref>:<path>` / `:<path>`); returns 0 when unavailable (missing/invalid ref). */
    private suspend fun refBlobSize(spec: String): Long =
        runCatching { git("cat-file", "-s", spec).trim().toLong() }.getOrDefault(0L)

    /**
     * Reads the file's current worktree content. Used for worktree-change diffs: compared against `HEAD:<path>` to show unstaged changes.
     * Returns an empty string when the file is missing or reading fails.
     *
     * In local mode the workspace is inside the app's private directory, so reading with [java.io.File] is fastest;
     * in remote SSH mode [WorkspaceRepository.currentPath] is a remote absolute path that a local File cannot read (it would silently
     * become an empty string and the diff would show the whole file deleted), so it falls back to `cat` on the execution backend.
     */
    suspend fun worktreeFileContent(path: String): String {
        val local = withContext(Dispatchers.IO) {
            runCatching {
                java.io.File(workspaceRepository.currentPath(), path)
                    .takeIf { it.isFile }
                    ?.also { if (it.length() > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException() }
                    ?.readText()
            }.getOrElse { e ->
                if (e is GitOutputTooLargeException) throw e
                null
            }
        }
        if (local != null) return local
        val result = engine.runCommandSyncUnbounded(
            "cat -- ${shellQuote(path)}",
            workspaceRepository.currentPath()
        )
        if (result.outputTruncated) throw GitOutputTooLargeException()
        return if (result.exitCode == 0) result.output else ""
    }

    /** Reads the file's current staged content (index). Returns an empty string when nothing is staged. */
    suspend fun indexFileContent(path: String): String {
        if (refBlobSize(":$path") > MAX_DIFF_FILE_BYTES) throw GitOutputTooLargeException()
        val out = git("show", ":$path")
        return if (out.startsWith("fatal:") || out.startsWith("error:")) "" else out
    }

    /**
     * Single-quote escapes one shell argument. Characters outside the "safe set" (spaces, `|`, `$`, backticks,
     * `*`, etc.) wrap the whole thing in single quotes; embedded single quotes become `'\''` (close-escape-reopen).
     * Format strings (`|`, `%(...)`), spaced paths, and commit messages all pass safely. `|` is a shell pipe - **never** safe.
     */
    private fun shellQuote(arg: String): String {
        if (arg.isEmpty()) return "''"
        if (arg.all { it.isLetterOrDigit() || it in "_.@/:=+,%-" }) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}

/**
 * porcelain v1 / ls-files / diff-tree wrap paths containing quotes, backslashes, spaces, or control
 * characters in quotes with C-style escapes (e.g. `"a\"b.txt"`, `"a\tb.txt"`, octal `\NNN`); this reverses that to recover the real path.
 * Unquoted plain paths are returned as-is.
 *
 * Octal escapes are **per byte**: a CJK character's three bytes become three `\NNN` segments, and
 * `toChar()` per segment yields only three Latin-1 characters (mojibake), so consecutive `\NNN` are accumulated into a byte sequence and decoded as UTF-8 as a whole.
 */
internal fun unquoteGitPath(raw: String): String {
    if (!raw.startsWith("\"")) return raw
    val inner = raw.removeSurrounding("\"")
    val sb = StringBuilder(inner.length)
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (c != '\\' || i + 1 >= inner.length) {
            sb.append(c)
            i += 1
            continue
        }
        if (inner[i + 1] in '0'..'7') {
            val bytes = mutableListOf<Byte>()
            while (i + 1 < inner.length && inner[i] == '\\' && inner[i + 1] in '0'..'7') {
                val octal = inner.substring(i + 1, minOf(i + 4, inner.length)).takeWhile { it in '0'..'7' }
                if (octal.length != 3) break
                bytes.add(octal.toInt(8).toByte())
                i += 4
            }
            if (bytes.isEmpty()) {
                sb.append(c)
                i += 1
            } else {
                sb.append(String(bytes.toByteArray(), Charsets.UTF_8))
            }
            continue
        }
        val decoded = when (inner[i + 1]) {
            'a' -> '\u0007'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'v' -> '\u000B'
            '"' -> '"'
            '\\' -> '\\'
            else -> null
        }
        if (decoded == null) {
            sb.append(c)
            i += 1
        } else {
            sb.append(decoded)
            i += 2
        }
    }
    return sb.toString()
}
