package com.aicode.feature.git.domain

/**
 * Maps raw git command failure output to a user-friendly English hint.
 *
 * git stderr is CLI-oriented English; toasting it as-is is unfriendly. Common
 * failures are pattern-matched; unmatched input falls back to the raw output
 * (keeps diagnostics). Matching uses stable git prefixes, not full-string
 * equality, so paths and branch names can vary.
 */
object GitErrorMessage {

    /**
     * @param raw git stdout+stderr text (i.e. [GitCommandFailureException.output]).
     * @return a friendly hint, or [raw] when nothing matches.
     */
    fun friendly(raw: String): String {
        if (raw.isBlank()) return raw
        // Deleting the currently checked-out branch
        if (raw.contains("Cannot delete branch") && raw.contains("checked out"))
            return "Cannot delete the branch you are on; switch to another branch first"
        // Deleting an unmerged branch
        if (raw.contains("is not fully merged"))
            return "This branch has unmerged commits and cannot be deleted safely"
        // Switching to a missing branch/ref
        if (raw.contains("invalid reference"))
            return "Branch or ref does not exist"
        // Local changes would be overwritten on switch
        if (raw.contains("local changes") && raw.contains("would be overwritten"))
            return "Uncommitted local changes would be overwritten; commit or stash before switching"
        // Remote unavailable / no permission
        if (raw.contains("does not appear to be a git repository") ||
            raw.contains("Could not read from remote repository"))
            return "Remote repository is unavailable or you do not have access"
        // Clone into a non-empty directory
        if (raw.contains("already exists and is not an empty directory"))
            return "The current workspace directory is not empty; cannot clone into it"
        // Auth failed (bad username/password/token or missing)
        if (raw.contains("Authentication failed") ||
            raw.contains("Invalid username or token") ||
            raw.contains("Permission denied") ||
            raw.contains("Password authentication is not supported"))
            return "Remote authentication failed; check credentials (username/password/token)"
        // Repo missing or no access
        if (raw.contains("Repository not found") || raw.contains("repository") && raw.contains("not found"))
            return "Remote repository does not exist or you do not have access"
        // Pull without upstream
        if (raw.contains("no tracking information"))
            return "Current branch has no remote tracking branch; cannot pull"
        // Push with no destination
        if (raw.contains("No configured push destination"))
            return "No remote is configured; cannot push"
        // Push rejected (non-fast-forward / remote has updates)
        if (raw.contains("non-fast-forward") || raw.contains("rejected") && raw.contains("fetch first") ||
            raw.contains("Updates were rejected because the remote contains"))
            return "Push rejected; the remote has newer commits. Pull first"
        // Missing identity (on commit)
        if (raw.contains("Please tell me who you are") || raw.contains("Author identity unknown"))
            return "Commit identity is not set; fill in name and email in Settings"
        // Merge conflict
        if (raw.contains("CONFLICT") || raw.contains("Automatic merge failed"))
            return "Merge produced conflicts; open the status page to resolve them"
        // Uncommitted local changes on merge/pull
        if (raw.contains("Your local changes to the following files would be overwritten by merge"))
            return "Uncommitted local changes; commit or stash before merging"
        // Stash
        if (raw.contains("No local changes to save"))
            return "Nothing to stash"
        return raw
    }
}
