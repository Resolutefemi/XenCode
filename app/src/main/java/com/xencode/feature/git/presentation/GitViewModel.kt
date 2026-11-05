package com.xencode.feature.git.presentation

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xencode.R
import com.xencode.core.util.FileLogger
import com.xencode.core.util.LineDiff
import com.xencode.feature.agent.domain.workflow.AgentWorkflow
import com.xencode.feature.git.domain.GitCommandFailureException
import com.xencode.feature.git.domain.GitOutputTooLargeException
import com.xencode.feature.git.domain.GitErrorMessage
import com.xencode.feature.git.domain.GitRepository
import com.xencode.feature.git.domain.model.GitBranch
import com.xencode.feature.git.domain.model.GitCommit
import com.xencode.feature.git.domain.model.GitFileChange
import com.xencode.feature.git.domain.model.GitGraph
import com.xencode.feature.git.domain.model.GitStash
import com.xencode.feature.git.domain.model.GitStatus
import com.xencode.feature.git.domain.model.GitTab
import com.xencode.feature.git.domain.model.GitTag
import com.xencode.feature.git.presentation.component.DiffData
import com.xencode.feature.git.presentation.component.DiffRow
import com.xencode.feature.git.presentation.component.highlightCode
import com.xencode.feature.git.presentation.component.inferSyntaxLanguage
import com.xencode.feature.settings.data.repository.AppThemeMode
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import com.xencode.feature.settings.data.repository.ThemeSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state and actions for the Git page.
 *
 * Entering the page triggers [refresh], which concurrently fetches status/graph/remote then the full
 * branch/tag list async; every write action (stage/commit/pull/push) refreshes automatically and reports
 * back via [GitUiState.toast]. The [GitUiState.busy] guard keeps concurrent writes from trampling each other.
 */
private fun GitGraph.toGitCommits() =
    commits.map { GitCommit(it.hash, it.shortHash, it.author, it.date, it.message) }

@HiltViewModel
class GitViewModel @Inject constructor(
    private val repository: GitRepository,
    @param:ApplicationContext private val context: Context,
    private val themeSettings: ThemeSettingsRepository,
    private val agentWorkflow: AgentWorkflow,
    private val executionModeHolder: ExecutionModeHolder
) : ViewModel() {

    private companion object {
        const val TAG = "GitViewModel"
        /** Diff line cap: beyond it LCS is skipped to avoid O(n·m) memory pressure on mobile. Aligned with FileTools. */
        const val MAX_DIFF_LINES = 2000
        /** Single-line length cap: over-long lines (e.g. minified JSON) blow up Compose Constraints when rendered, so degrade to a notice. */
        const val MAX_DIFF_LINE_LENGTH = 2000
        /** Source markers for the two diff sides; only feed [DiffData.oldRef]/[DiffData.newRef] for troubleshooting, never shown to users. */
        const val REF_WORKTREE = "worktree"
        const val REF_INDEX = "index"
        const val REF_EMPTY = "empty"
    }

    data class GitUiState(
        val loading: Boolean = true,
        val notARepo: Boolean = false,
        val status: GitStatus? = null,
        val branches: List<GitBranch> = emptyList(),
        val commits: List<GitCommit> = emptyList(),
        /** Topology graph view: commits (with parent hashes) + refs + swimlane layout, for LogTab to draw colored branch connectors. */
        val graph: GitGraph = GitGraph.EMPTY,
        val tags: List<GitTag> = emptyList(),
        val tab: GitTab = GitTab.STATUS,
        val busy: Boolean = false,
        val toast: String? = null,
        /** Whether a remote is configured; controls pull/push button availability. */
        val hasRemote: Boolean = false,
        /** Whether a global user.name identity is configured (git config --global); controls the commit button, since committing without one fails. */
        val hasIdentity: Boolean = false,
        /** Hash of the commit shown in the detail sheet; null when closed. */
        val commitDetailHash: String? = null,
        /** Lazily loaded per-commit file lists, cached by hash. */
        val commitFiles: Map<String, List<GitFileChange>> = emptyMap(),
        /** Hash of the commit whose file list is loading. */
        val loadingCommit: String? = null,
        /** Whether older commits are paging in (triggered on scroll to bottom). Does not set busy, so write actions are not blocked. */
        val graphLoadingMore: Boolean = false,
        /** Whether the full branch/tag list has loaded (fetched in the background on entry, not blocking the first screen; if still pending when switching to the BRANCHES tab, keep waiting). */
        val branchesLoaded: Boolean = false,
        /** Whether the full branch/tag list is loading. */
        val branchesLoading: Boolean = false,
        /** Stash list. */
        val stashes: List<GitStash> = emptyList(),
        /** Whether the stash list is loading. */
        val stashesLoading: Boolean = false,
        /** Branch currently being checked out. */
        val checkoutLoading: String? = null,
        /** Expanded untracked directory → its untracked file list. git status collapses new directories into one line; expansion queries on demand. */
        val untrackedDirFiles: Map<String, List<String>> = emptyMap(),
        /** Path of the untracked directory currently being expanded. */
        val untrackedDirLoading: String? = null,
        /** Whether a commit message is being generated. */
        val generatingCommitMessage: Boolean = false,
        /** Whether the full-screen diff page is shown; set true on entry, and while diffData is null the page shows loading. */
        val diffVisible: Boolean = false,
        /** Path of the file being diffed; shown in the top bar while loading (diffData null). */
        val diffPath: String? = null,
        /** Diff view data; when non-null the diff page renders content. */
        val diffData: DiffData? = null
    )

    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()

    /**
     * Whether this is device-root execution mode (DEVICE_ROOT).
     *
     * The device channel runs commands directly in the device's root environment, and most ROMs ship
     * no git binary, so this page used to get stuck at "failed to initialize GIT". Device mode therefore
     * uses "checkpoint snapshot rewind" (com.xencode.feature.agent.domain.checkpoint.CheckpointManager)
     * instead: each conversation turn auto-snapshots files the AI modified/created, and rewinding restores
     * the code to that turn's original state - the same idea as Cursor / Trae's checkpoint rewind.
     */
    val deviceRootMode: StateFlow<Boolean> = executionModeHolder.mode
        .map { it == ExecutionMode.DEVICE_ROOT }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = executionModeHolder.currentMode() == ExecutionMode.DEVICE_ROOT
        )

    init {
        // Decide whether to run git from the current execution mode: the first emission replaces the
        // old init { refresh() }, and when the mode flips in settings (with this page alive) it switches at once, leaving no stale data.
        viewModelScope.launch {
            executionModeHolder.mode.collect { mode ->
                if (mode == ExecutionMode.DEVICE_ROOT) {
                    _state.update { GitUiState(loading = false, notARepo = false) }
                } else {
                    refresh()
                }
            }
        }
    }

    /** Repo snapshot: the result of concurrently fetching status/graph/remote (+ optional identity). Excludes the full branch/tag list -
     * those load asynchronously after [refresh] succeeds ([loadBranches]) so the first screen is not blocked.
     * The graph's ref annotations use local branches only ([repository.localRefsOnly], sub-second); remote/tag annotations are completed by [loadBranches]. */
    private data class RepoSnapshot(
        val status: GitStatus,
        val graph: GitGraph,
        val hasRemote: Boolean,
        val hasIdentity: Boolean = false,
        val untrackedDirFiles: Map<String, List<String>> = emptyMap()
    )

    /** Concurrently fetches the lightweight snapshot: status + graph (local refs) + remote + optional identity. */
    private suspend fun loadSnapshot(includeIdentity: Boolean): RepoSnapshot = coroutineScope {
        val s = async { repository.status() }
        val localRefs = async { repository.localRefsOnly() }
        val r = async { repository.hasRemote() }
        val id = async { if (includeIdentity) repository.getUserName().isNotBlank() else false }
        val g = async { repository.graph(refs = localRefs.await()) }
        val status = s.await()
        RepoSnapshot(status, g.await(), r.await(), id.await(), reloadExpandedUntrackedDirs(status))
    }

    /**
     * Re-queries expanded untracked directories after refresh, preserving their expansion. Only directories still present in [GitStatus.untracked] are kept -
     * once a directory is staged or deleted, its stale file list should not linger.
     */
    private suspend fun reloadExpandedUntrackedDirs(status: GitStatus): Map<String, List<String>> {
        val dirs = _state.value.untrackedDirFiles.keys.filter { it in status.untracked }
        if (dirs.isEmpty()) return emptyMap()
        return coroutineScope {
            dirs.map { dir -> dir to async { repository.untrackedFilesIn(dir) } }
                .associate { (dir, deferred) -> dir to deferred.await() }
        }
    }

    fun setTab(tab: GitTab) {
        _state.update { it.copy(tab = tab) }
        // Fallback: loading starts async on page entry; if it has not started/finished (e.g. re-entering after a failure), try again when switching tabs.
        if (tab == GitTab.BRANCHES && !_state.value.branchesLoaded && !_state.value.branchesLoading) {
            loadBranches()
        }
    }

    /**
     * Loads the full branch/tag list (triggered once [refresh] succeeds on page entry; no need to wait for the BRANCHES tab).
     * A single [repository.loadAllRefs] fetch fills branches/tags and updates the graph's annotations with the full refs.
     * Does not set busy; read-only, never blocks write actions. Skips if already loaded; after write actions, call [refreshBranchesIfLoaded] manually to sync.
     */
    fun loadBranches() {
        if (_state.value.branchesLoading || _state.value.branchesLoaded) return
        _state.update { it.copy(branchesLoading = true) }
        viewModelScope.launch {
            try {
                val allRefs = repository.loadAllRefs()
                // Update graph annotations with the full refs (completing remote branches + tags).
                val graph = repository.graphAppend(_state.value.graph.commits, allRefs.refsByCommit)
                val commits = graph.toGitCommits()
                _state.update {
                    it.copy(branches = allRefs.branches, tags = allRefs.tags, graph = graph, commits = commits, branchesLoading = false, branchesLoaded = true)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to load branch list", e)
                _state.update { it.copy(branchesLoading = false, toast = context.getString(R.string.git_toast_load_branches_failed, e.message)) }
            }
        }
    }

    /**
     * After a write action, refreshes the already-loaded branch/tag list (if loaded). Never fetches on its own when not loaded, preserving lazy-load semantics.
     */
    private fun refreshBranchesIfLoaded() {
        if (!_state.value.branchesLoaded) return
        viewModelScope.launch {
            try {
                val allRefs = repository.loadAllRefs()
                _state.update { it.copy(branches = allRefs.branches, tags = allRefs.tags) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to refresh branch list", e)
            }
        }
    }

    fun refresh() {
        // Device mode: the device environment has no git; this page now shows snapshot-rewind guidance and issues no git commands.
        if (executionModeHolder.currentMode() == ExecutionMode.DEVICE_ROOT) {
            _state.update { GitUiState(loading = false, notARepo = false) }
            return
        }
        if (_state.value.busy) return
        // When the container is not ready, run no git commands; prompt the user to finish initialization on the terminal page instead of wrongly showing "not a git repository".
        repository.notReadyHint()?.let { hint ->
            _state.update { it.copy(loading = false, toast = hint) }
            return
        }
        _state.update { it.copy(loading = true, toast = null) }
        viewModelScope.launch {
            try {
                if (!repository.isRepo()) {
                    _state.update { it.copy(loading = false, notARepo = true) }
                    return@launch
                }
                val snap = loadSnapshot(includeIdentity = true)
                val commits = snap.graph.toGitCommits()
                _state.update {
                    it.copy(loading = false, notARepo = false, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, hasIdentity = snap.hasIdentity, untrackedDirFiles = snap.untrackedDirFiles, branchesLoaded = false, branchesLoading = false, branches = emptyList(), tags = emptyList())
                }
                // Page is open: fetch the full branch/tag list in the background so the user need not wait when switching to the BRANCHES tab.
                loadBranches()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Refresh failed", e)
                _state.update { it.copy(loading = false, toast = context.getString(R.string.git_toast_refresh_failed, e.message)) }
            }
        }
    }

    /** Runs one write action: set busy → run command → refresh → report. Actions are mutually exclusive. */
    private fun runAction(
        @StringRes nameRes: Int,
        action: suspend () -> String,
        onComplete: (suspend () -> Unit)? = null
    ) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, toast = null) }
        viewModelScope.launch {
            val name = context.getString(nameRes)
            val msg = try {
                action()
                context.getString(R.string.git_toast_action_success, name)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "$name failed", e)
                val reason = (e as? GitCommandFailureException)?.output ?: e.message
                context.getString(R.string.git_toast_action_failed, name, GitErrorMessage.friendly(reason ?: ""))
            }
            // Refresh to reflect the new state; refresh on failure too, keeping the UI consistent with the repository.
            try {
                if (repository.isRepo()) {
                    val snap = loadSnapshot(includeIdentity = false)
                    val commits = snap.graph.toGitCommits()
                    _state.update { it.copy(busy = false, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, untrackedDirFiles = snap.untrackedDirFiles, notARepo = false, toast = msg) }
                    refreshBranchesIfLoaded()
                    onComplete?.invoke()
                } else {
                    _state.update { it.copy(busy = false, notARepo = true, toast = msg) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = false, toast = context.getString(R.string.git_toast_action_refresh_failed, msg)) }
            }
        }
    }

    fun stage(path: String) = runAction(R.string.git_stage, { repository.stage(path) })
    fun unstage(path: String) = runAction(R.string.git_unstage, { repository.unstage(path) })
    fun stageAll() = runAction(R.string.git_action_stage_all, { repository.stageAll() })
    fun unstageAll() = runAction(R.string.git_action_unstage_all, { repository.unstageAll() })

    /**
     * Reverts one file's changes. [staged] true restores to the last commit including staged content;
     * otherwise only unstaged changes are reverted (deleted files come back this way). The changes were
     * never committed, so once reverted they are unrecoverable - the UI must double-confirm first.
     */
    fun revertFile(path: String, staged: Boolean) =
        runAction(R.string.git_action_revert, {
            if (staged) repository.revertToHead(path) else repository.revertWorktree(path)
        })

    /** Reverts all unstaged changes; staged content is left untouched. */
    fun revertAllUnstaged() = runAction(R.string.git_action_revert_all, { repository.revertAllWorktree() })

    /** Deletes untracked files or directories. They were never committed, so git cannot restore them after deletion. */
    fun deleteUntracked(path: String) =
        runAction(R.string.git_action_delete_file, { repository.deleteUntracked(path) })

    /**
     * Expands/collapses a collapsed untracked directory (`git status` shows a new directory as one `dir/` line).
     * Expanding queries the untracked files inside on demand so each can be diffed or deleted individually. Does not set busy - a read-only query should not block write actions.
     */
    fun toggleUntrackedDir(dir: String) {
        if (dir in _state.value.untrackedDirFiles) {
            _state.update { it.copy(untrackedDirFiles = it.untrackedDirFiles - dir) }
            return
        }
        if (_state.value.untrackedDirLoading != null) return
        _state.update { it.copy(untrackedDirLoading = dir) }
        viewModelScope.launch {
            val files = try {
                repository.untrackedFilesIn(dir)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to load untracked directory: $dir", e)
                emptyList()
            }
            _state.update {
                it.copy(untrackedDirFiles = it.untrackedDirFiles + (dir to files), untrackedDirLoading = null)
            }
        }
    }
    fun commit(message: String, amend: Boolean = false) =
        runAction(R.string.git_action_commit, { repository.commit(message, amend) })

    /**
     * Calls the AI to generate a commit message from the staged diff.
     */
    fun generateCommitMessage(onResult: (String) -> Unit) {
        if (_state.value.generatingCommitMessage) return
        _state.update { it.copy(generatingCommitMessage = true) }
        viewModelScope.launch {
            try {
                val diff = repository.diffForCommitMessage()
                if (diff.isBlank()) {
                    _state.update { it.copy(generatingCommitMessage = false, toast = context.getString(R.string.git_toast_no_diff_for_commit)) }
                    return@launch
                }
                val message = agentWorkflow.generateCommitMessage(diff)
                _state.update { it.copy(generatingCommitMessage = false) }
                if (!message.isNullOrBlank()) {
                    onResult(message)
                } else {
                    _state.update { it.copy(toast = context.getString(R.string.git_toast_generate_commit_failed)) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Commit message generation error", e)
                _state.update { it.copy(generatingCommitMessage = false, toast = context.getString(R.string.git_toast_generate_commit_failed)) }
            }
        }
    }
    /** Runs `git init` in the current workspace; on success runAction refreshes at the end (notARepo flips false). */
    fun initRepo() = runAction(R.string.git_action_init, { repository.initRepo() })

    /** Clones the remote repository into the current workspace; on success runAction refreshes into the repo state. */
    fun cloneRepo(url: String) = runAction(R.string.git_action_clone, { repository.cloneRepo(url) })
    fun pull() {
        if (!_state.value.hasRemote) {
            _state.update { it.copy(toast = context.getString(R.string.git_toast_no_remote_pull)) }
            return
        }
        runAction(R.string.git_pull, { repository.pull() })
    }
    fun push(onPromptRenameMaster: () -> Unit = {}) {
        if (!_state.value.hasRemote) {
            _state.update { it.copy(toast = context.getString(R.string.git_toast_no_remote_push)) }
            return
        }
        viewModelScope.launch {
            if (repository.isFirstPushOfMaster()) {
                onPromptRenameMaster()
            } else {
                runAction(R.string.git_push, { repository.push() })
            }
        }
    }

    /** The user chose to rename master to main on its first push and push. */
    fun pushRenameMasterToMain() =
        runAction(R.string.git_push, { repository.renameMasterToMainAndPush() })

    /** The user chose to keep master and push directly on its first push. */
    fun pushDirectly() =
        runAction(R.string.git_push, { repository.push() })

    /**
     * Opens a commit's detail sheet. The file list lazy-loads if not yet loaded (without setting [GitUiState.busy],
     * since this is a read-only view and should not block status/branches write actions).
     */
    fun openCommitDetail(hash: String) {
        val current = _state.value
        if (current.commitDetailHash == hash) return
        _state.update { it.copy(commitDetailHash = hash) }
        if (hash in current.commitFiles) return
        _state.update { it.copy(loadingCommit = hash) }
        viewModelScope.launch {
            val files = try {
                repository.commitFiles(hash)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to load commit files: $hash", e)
                emptyList()
            }
            _state.update {
                it.copy(commitFiles = it.commitFiles + (hash to files), loadingCommit = null)
            }
        }
    }

    fun closeCommitDetail() = _state.update { it.copy(commitDetailHash = null) }

    fun consumeToast() = _state.update { it.copy(toast = null) }

    /**
     * Pages in older commits. Called when the UI scrolls to the bottom: with the loaded commits as the
     * anchor, [repository.graphAppend] fetches the next page via `--skip` and recomputes the swimlane layout over the whole set, returning the full graph to replace state.graph. Does not set busy, so write actions are not blocked.
     * Guard: return immediately while loading or when nothing remains. On failure, toast and keep hasMore to allow retry.
     */
    fun loadMoreCommits() {
        val current = _state.value
        if (current.graphLoadingMore || !current.graph.hasMore) return
        _state.update { it.copy(graphLoadingMore = true) }
        viewModelScope.launch {
            try {
                val graph = repository.graphAppend(current.graph.commits, current.graph.refs)
                val commits = graph.toGitCommits()
                _state.update { it.copy(graph = graph, commits = commits, graphLoadingMore = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Failed to load more commits", e)
                _state.update { it.copy(graphLoadingMore = false, toast = context.getString(R.string.git_toast_load_more_failed, e.message)) }
            }
        }
    }

    /**
     * Checks out the given branch or tag. Refreshes full state on success.
     */
    fun checkoutBranch(ref: String, isRemote: Boolean = false) {
        if (_state.value.busy || _state.value.checkoutLoading != null) return
        _state.update { it.copy(checkoutLoading = ref, toast = null) }
        viewModelScope.launch {
            val msg = try {
                repository.checkout(ref, isRemote)
                context.getString(R.string.git_toast_checkout_success, ref)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                FileLogger.e(TAG, "Branch checkout failed", e)
                val reason = (e as? GitCommandFailureException)?.output ?: e.message
                context.getString(R.string.git_toast_checkout_failed, GitErrorMessage.friendly(reason ?: ""))
            }
            try {
                if (repository.isRepo()) {
                    val snap = loadSnapshot(includeIdentity = false)
                    val commits = snap.graph.toGitCommits()
                    _state.update { it.copy(checkoutLoading = null, status = snap.status, commits = commits, graph = snap.graph, hasRemote = snap.hasRemote, notARepo = false, toast = msg) }
                    // The branch list may change after checkout; refresh it if already loaded.
                    refreshBranchesIfLoaded()
                } else {
                    _state.update { it.copy(checkoutLoading = null, notARepo = true, toast = msg) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(checkoutLoading = null, toast = context.getString(R.string.git_toast_action_refresh_failed, msg)) }
            }
        }
    }

    /**
     * Creates a new branch. name is the new branch name; startPoint is the base branch (null/empty → from current HEAD);
     * checkout=true creates and switches. Reuses runAction: set busy → run command → refresh → toast.
     */
    fun createBranch(name: String, startPoint: String?, checkout: Boolean) {
        if (name.isBlank()) return
        runAction(R.string.git_action_create_branch, { repository.createBranch(name, startPoint, checkout) })
    }

    /**
     * Safely deletes a local branch (git branch -d). git errors when unmerged or current, surfaced as a toast via runAction.
     */
    fun deleteBranch(name: String) {
        if (name.isBlank()) return
        runAction(R.string.git_action_delete_branch, { repository.deleteBranch(name) })
    }

    /**
     * Deletes a remote branch (git push --delete). Changes the remote; failures surface as a toast via runAction.
     */
    fun deleteRemoteBranch(ref: String) {
        if (ref.isBlank()) return
        runAction(R.string.git_action_delete_remote_branch, { repository.deleteRemoteBranch(ref) })
    }

    /**
     * Renames a local branch (git branch -m). The current branch can be renamed too. Failures surface as a toast via runAction.
     */
    fun renameBranch(oldName: String, newName: String) {
        if (oldName.isBlank() || newName.isBlank()) return
        runAction(R.string.git_action_rename_branch, { repository.renameBranch(oldName, newName) })
    }

    /**
     * Creates a lightweight tag (git tag <name>) pointing at the current HEAD or a given commit. Failures surface as a toast via runAction.
     */
    fun createTag(name: String, commitHash: String? = null) {
        if (name.isBlank()) return
        runAction(R.string.git_action_create_tag, { repository.createTag(name, commitHash) })
    }

    /**
     * Resets the current branch to the given commit.
     */
    fun resetToCommit(commitHash: String, mode: String = "mixed") {
        if (commitHash.isBlank()) return
        runAction(R.string.git_reset_to_commit, { repository.resetToCommit(commitHash, mode) })
    }

    /**
     * Adds a rule to .gitignore.
     */
    fun addToGitignore(pattern: String) {
        if (pattern.isBlank()) return
        runAction(R.string.git_add_to_gitignore, { repository.addToGitignore(pattern) })
    }

    /**
     * Deletes a local tag (git tag -d). Failures surface as a toast via runAction.
     */
    fun deleteTag(name: String) {
        if (name.isBlank()) return
        runAction(R.string.git_action_delete_tag, { repository.deleteTag(name) })
    }

    /**
     * Merges a branch into the current branch.
     */
    fun mergeBranch(branch: String, noFf: Boolean = false) {
        if (branch.isBlank()) return
        runAction(R.string.git_action_merge, { repository.mergeBranch(branch, noFf) })
    }

    /**
     * Aborts the in-progress merge.
     */
    fun abortMerge() {
        runAction(R.string.git_action_abort_merge, { repository.abortMerge() })
    }

    /**
     * Loads the stash list.
     */
    fun loadStashes() {
        viewModelScope.launch {
            _state.update { it.copy(stashesLoading = true) }
            val list = runCatching { repository.stashList() }.getOrDefault(emptyList())
            _state.update { it.copy(stashes = list, stashesLoading = false) }
        }
    }

    /**
     * Stashes worktree changes.
     */
    fun stashSave(message: String? = null, includeUntracked: Boolean = false) {
        runAction(R.string.git_action_stash, { repository.stashSave(message, includeUntracked) }) {
            loadStashes()
        }
    }

    /**
     * Pops the given stash (applies and deletes).
     */
    fun stashPop(index: String = "stash@{0}") {
        runAction(R.string.git_stash_pop, { repository.stashPop(index) }) {
            loadStashes()
        }
    }

    /**
     * Applies the given stash (keeping the entry).
     */
    fun stashApply(index: String = "stash@{0}") {
        runAction(R.string.git_stash_apply, { repository.stashApply(index) }) {
            loadStashes()
        }
    }

    /**
     * Drops the given stash.
     */
    fun stashDrop(index: String) {
        runAction(R.string.git_stash_drop, { repository.stashDrop(index) }) {
            loadStashes()
        }
    }

    /**
     * Clears all stash entries.
     */
    fun stashClear() {
        runAction(R.string.git_stash_clear, { repository.stashClear() }) {
            loadStashes()
        }
    }

    /**
     * Exits the diff view and clears diff state.
     */
    fun clearDiff() = _state.update { it.copy(diffVisible = false, diffPath = null, diffData = null) }

    /**
     * Loads the diff of a file in a commit (before vs after the change).
 * hash is the commit hash, path the file path. A background thread computes diff + highlighting, then fills diffData.
 */
    fun loadCommitFileDiff(hash: String, path: String) {
        openDiff(path)
        viewModelScope.launch {
            val data = runCatching { computeDiff(path, "${hash}^", hash, repository::showFileContent) }
                .getOrElse { e ->
                    FileLogger.e(TAG, "Failed to load commit-file diff: $path", e)
                    null
                }
            finishDiff(data)
        }
    }

    /**
     * Loads a file's unstaged diff in the worktree (HEAD vs current worktree content).
 * path is the file path. showFileContent("HEAD", path) fetches the repository snapshot; worktreeFileContent(path) fetches the current worktree content.
 */
    fun loadWorktreeDiff(path: String) {
        loadDiff(path, "HEAD", REF_WORKTREE) { ref, p ->
            if (ref == REF_WORKTREE) repository.worktreeFileContent(p)
            else repository.showFileContent(ref, p)
        }
    }

    /** Loads a file's staged diff (HEAD vs index). */
    fun loadStagedDiff(path: String) {
        loadDiff(path, "HEAD", REF_INDEX) { ref, p ->
            if (ref == REF_INDEX) repository.indexFileContent(p)
            else repository.showFileContent(ref, p)
        }
    }

    /**
     * Loads an untracked file's diff: the repository has no version of it, the old side is always empty, and the whole file renders as all-added.
     */
    fun loadUntrackedDiff(path: String) {
        loadDiff(path, REF_EMPTY, REF_WORKTREE) { ref, p ->
            if (ref == REF_WORKTREE) repository.worktreeFileContent(p) else ""
        }
    }

    private fun loadDiff(
        path: String,
        oldRef: String,
        newRef: String,
        contentProvider: suspend (String, String) -> String
    ) {
        openDiff(path)
        viewModelScope.launch {
            val data = runCatching {
                computeDiff(path, oldRef, newRef, contentProvider)
            }.getOrElse { e ->
                FileLogger.e(TAG, "Failed to load diff: $path", e)
                null
            }
            finishDiff(data)
        }
    }

    /** Enters the full-screen diff page (loading inside); ignored if a diff page is already open. */
    private fun openDiff(path: String) {
        if (_state.value.diffVisible) return
        _state.update { it.copy(diffVisible = true, diffPath = path, diffData = null) }
    }

    /** Diff computation finished: fill data on success; close the diff page and toast on failure. */
    private fun finishDiff(data: DiffData?) {
        if (data == null) {
            _state.update { it.copy(diffVisible = false, diffData = null, toast = context.getString(R.string.git_toast_diff_failed)) }
        } else {
            _state.update { it.copy(diffData = data) }
        }
    }

    /**
     * Fetches the old/new file contents, computes the line diff with [LineDiff], syntax-highlights each line, and assembles a [DiffData].
 * Binary files (containing NUL bytes) or oversized files (either side over [MAX_DIFF_LINES]) are degraded.
 * [contentProvider] fetches content by ref, shared by commit diffs and worktree diffs.
 */
    private suspend fun computeDiff(
        path: String,
        oldRef: String,
        newRef: String,
        contentProvider: suspend (String, String) -> String
    ): DiffData = withContext(Dispatchers.Default) {
        val oldContent: String
        val newContent: String
        try {
            oldContent = contentProvider(oldRef, path)
            newContent = contentProvider(newRef, path)
        } catch (e: GitOutputTooLargeException) {
            FileLogger.i(TAG, "File too large, skipping diff content: $path")
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isLarge = true)
        }

        // Binary detection: git show returns mojibake for binary files, so just check for NUL.
        if (oldContent.contains('\u0000') || newContent.contains('\u0000')) {
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isBinary = true)
        }

        val oldLines = oldContent.split('\n')
        val newLines = newContent.split('\n')
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES ||
            oldLines.any { it.length > MAX_DIFF_LINE_LENGTH } ||
            newLines.any { it.length > MAX_DIFF_LINE_LENGTH }
        ) {
            return@withContext DiffData(path, oldRef, newRef, emptyList(), 0, 0, isLarge = true)
        }

        val diffLines = LineDiff.diff(oldContent, newContent)
        val language = inferSyntaxLanguage(path)

        // Highlight the whole old/new file once each to get token spans for the full text; at render time, slice the matching SpanStyle by line offset.
        // The syntax theme follows the current UI theme so light-theme token colors (e.g. = and {}) stay legible in dark mode.
        // Both sides compute in parallel (highlighting dominates diff time); everything already runs on the Default pool, never the main thread.
        val darkMode = currentDarkMode()
        val (oldHighlighted, newHighlighted) = coroutineScope {
            val old = async { highlightCode(oldContent, language, darkMode) }
            val new = async { highlightCode(newContent, language, darkMode) }
            old.await() to new.await()
        }

        // Build line numbers + highlight slices. oldLineOffsets/newLineOffsets are each line's start offset in the full text.
        val oldOffsets = lineOffsets(oldContent)
        val newOffsets = lineOffsets(newContent)

        var oldNum = 0
        var newNum = 0
        val rows = diffLines.map { line ->
            val (oldN, newN) = when (line.type) {
                LineDiff.LineType.CONTEXT -> { oldNum++; newNum++; oldNum to newNum }
                LineDiff.LineType.REMOVE -> { oldNum++; oldNum to null }
                LineDiff.LineType.ADD -> { newNum++; null to newNum }
            }
            // Slice this line's SpanStyle from the highlighted full text. Line index = line number - 1 (0-based).
            val highlighted = when (line.type) {
                LineDiff.LineType.REMOVE -> sliceLineHighlight(oldHighlighted, oldOffsets, oldN?.let { it - 1 })
                LineDiff.LineType.ADD -> sliceLineHighlight(newHighlighted, newOffsets, newN?.let { it - 1 })
                LineDiff.LineType.CONTEXT -> sliceLineHighlight(oldHighlighted, oldOffsets, oldN?.let { it - 1 })
            }
            DiffRow(line.type, line.text, highlighted, oldN, newN)
        }

        val added = rows.count { it.type == LineDiff.LineType.ADD }
        val removed = rows.count { it.type == LineDiff.LineType.REMOVE }
        return@withContext DiffData(path, oldRef, newRef, rows, added, removed)
    }

    /**
     * The actually active dark mode: AUTO follows the system, consistent with the darkTheme logic of AIEditorTheme in MainActivity.
     */
    private suspend fun currentDarkMode(): Boolean {
        val themeMode = themeSettings.themeModeFlow.first()
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (themeMode) {
            AppThemeMode.AUTO -> systemDark
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
        }
    }

    /**
     * Slices one line's SpanStyle out of the fully highlighted AnnotatedString, returning a new AnnotatedString with only that line's text + styles.
 * Returns null (degrading to plain text) when lineIndex is out of range or the highlight is null.
 */
    private fun sliceLineHighlight(
        highlighted: androidx.compose.ui.text.AnnotatedString?,
        offsets: IntArray,
        lineIndex: Int?
    ): androidx.compose.ui.text.AnnotatedString? {
        if (highlighted == null || lineIndex == null || lineIndex < 0 || lineIndex >= offsets.size) return null
        val start = offsets[lineIndex]
        val end = if (lineIndex + 1 < offsets.size) offsets[lineIndex + 1] - 1 else highlighted.length
        if (start >= highlighted.length || end <= start) return null
        return highlighted.subSequence(start, end)
    }

    /** Computes each line's start offset in the full text (plus a virtual offset after the last line = full length). */
    private fun lineOffsets(text: String): IntArray {
        val lines = text.split('\n')
        val offsets = IntArray(lines.size + 1)
        var pos = 0
        for (i in lines.indices) {
            offsets[i] = pos
            pos += lines[i].length + 1 // +1 for the newline
        }
        offsets[lines.size] = pos
        return offsets
    }
}
