package com.xencode.feature.git.domain.model

/**
 * Git domain models. All fields are parsed by [com.xencode.feature.git.domain.GitRepository] from
 * the output of `git status --porcelain` / `git log` / `git for-each-ref`. Pure data, no Android dependencies.
 */

/** One file's change: path + git status XY two-character status code + whether staged (decided by the X column). */
data class GitFileChange(
    val path: String,
    /** One of "M"/"A"/"D"/"R"/"??", from the porcelain output. */
    val statusCode: String,
    val staged: Boolean
)

data class GitCommit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val date: String,
    val message: String
)

data class GitBranch(
    val name: String,
    val current: Boolean,
    val remote: Boolean,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0
)

data class GitTag(
    val name: String,
    val shortHash: String
)

data class GitStash(
    val index: String,
    val message: String,
    val date: String
)

/** Aggregated `git status` view: branch tracking info + grouped file changes. */
data class GitStatus(
    val branch: String,
    val ahead: Int,
    val behind: Int,
    val staged: List<GitFileChange>,
    val unstaged: List<GitFileChange>,
    val untracked: List<String>,
    val conflicted: List<GitFileChange> = emptyList(),
    val isMerging: Boolean = false,
    val upstream: String? = null,
    val isDetached: Boolean = false
) {
    val hasChanges: Boolean
        get() = staged.isNotEmpty() || unstaged.isNotEmpty() || untracked.isNotEmpty() || conflicted.isNotEmpty()
}

enum class GitTab { STATUS, BRANCHES, LOG }
