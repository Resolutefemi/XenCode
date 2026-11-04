package com.aicode.feature.git.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Friendly mapping of git failure output ([GitErrorMessage.friendly]).
 * One case per pattern-matching branch; inputs stay close to real git stderr.
 * Unmatched input is returned as-is (keeps diagnostic text).
 */
class GitErrorMessageTest {

    // ── delete / switch branch ──────────────────────────────────

    @Test
    fun cannotDeleteBranch_checkedOut_returnsSwitchHint() {
        val raw = "error: Cannot delete branch 'main' checked out at '/root/workspace'"
        assertEquals("Cannot delete the branch you are on; switch to another branch first", GitErrorMessage.friendly(raw))
    }

    @Test
    fun branchNotFullyMerged_returnsSafeDeleteHint() {
        val raw = "error: The branch 'feat/old' is not fully merged.\n" +
            "If you are sure you want to delete it, run 'git branch -D feat/old'."
        assertEquals("This branch has unmerged commits and cannot be deleted safely", GitErrorMessage.friendly(raw))
    }

    @Test
    fun invalidReference_returnsNotFoundHint() {
        val raw = "fatal: invalid reference: nope"
        assertEquals("Branch or ref does not exist", GitErrorMessage.friendly(raw))
    }

    @Test
    fun localChangesWouldBeOverwritten_returnsStashHint() {
        val raw = "error: Your local changes to the following files would be overwritten by checkout:\n" +
            "\tapp/src/Main.kt\nPlease commit your changes or stash them before you switch branches."
        assertEquals("Uncommitted local changes would be overwritten; commit or stash before switching", GitErrorMessage.friendly(raw))
    }

    // ── remote unavailable / no permission ──────────────────────

    @Test
    fun notGitRepository_returnsRemoteUnavailable() {
        val raw = "fatal: 'origin' does not appear to be a git repository"
        assertEquals("Remote repository is unavailable or you do not have access", GitErrorMessage.friendly(raw))
    }

    @Test
    fun couldNotReadFromRemote_returnsRemoteUnavailable() {
        val raw = "fatal: Could not read from remote repository.\n\nPlease make sure you have the correct access rights and the repository exists."
        assertEquals("Remote repository is unavailable or you do not have access", GitErrorMessage.friendly(raw))
    }

    // ── auth failed ─────────────────────────────────────────────

    @Test
    fun authenticationFailed_returnsCredentialHint() {
        val raw = "fatal: Authentication failed for 'https://github.com/user/repo.git/'"
        assertEquals("Remote authentication failed; check credentials (username/password/token)", GitErrorMessage.friendly(raw))
    }

    @Test
    fun invalidUsernameOrToken_returnsCredentialHint() {
        val raw = "fatal: Invalid username or token."
        assertEquals("Remote authentication failed; check credentials (username/password/token)", GitErrorMessage.friendly(raw))
    }

    @Test
    fun permissionDenied_returnsCredentialHint() {
        // Real cases often also include "Could not read from remote repository", which would
        // hit the earlier "remote unavailable" branch (friendly matches in order). Keep only
        // Permission denied itself here.
        val raw = "git@github.com: Permission denied (publickey)."
        assertEquals("Remote authentication failed; check credentials (username/password/token)", GitErrorMessage.friendly(raw))
    }

    @Test
    fun passwordAuthenticationNotSupported_returnsCredentialHint() {
        val raw = "remote: Password authentication is not supported for GitHub."
        assertEquals("Remote authentication failed; check credentials (username/password/token)", GitErrorMessage.friendly(raw))
    }

    // ── repo missing ────────────────────────────────────────────

    @Test
    fun repositoryNotFound_returnsRepoMissingHint() {
        val raw = "remote: Repository not found.\nfatal: repository 'https://github.com/user/nope.git/' not found"
        assertEquals("Remote repository does not exist or you do not have access", GitErrorMessage.friendly(raw))
    }

    @Test
    fun repositoryAndNotFoundCombined_returnsRepoMissingHint() {
        val raw = "fatal: repository 'https://git.example.com/team/deleted.git' not found"
        assertEquals("Remote repository does not exist or you do not have access", GitErrorMessage.friendly(raw))
    }

    // ── pull / push ─────────────────────────────────────────────

    @Test
    fun noTrackingInformation_returnsPullHint() {
        val raw = "There is no tracking information for the current branch.\n" +
            "Please specify which branch you want to rebase against."
        assertEquals("Current branch has no remote tracking branch; cannot pull", GitErrorMessage.friendly(raw))
    }

    @Test
    fun noConfiguredPushDestination_returnsRemoteHint() {
        val raw = "fatal: No configured push destination.\n" +
            "Either specify the URL from the command-line or configure a remote repository using\n" +
            "\n    git remote add <name> <url>\n\nand then push using the remote name"
        assertEquals("No remote is configured; cannot push", GitErrorMessage.friendly(raw))
    }

    @Test
    fun nonFastForward_returnsPullFirstHint() {
        val raw = "To https://github.com/user/repo.git\n" +
            " ! [rejected]        main -> main (non-fast-forward)\n" +
            "error: failed to push some refs to 'https://github.com/user/repo.git'"
        assertEquals("Push rejected; the remote has newer commits. Pull first", GitErrorMessage.friendly(raw))
    }

    @Test
    fun rejectedFetchFirst_returnsPullFirstHint() {
        val raw = " ! [rejected]        main -> main (fetch first)"
        assertEquals("Push rejected; the remote has newer commits. Pull first", GitErrorMessage.friendly(raw))
    }

    @Test
    fun updatesRejectedRemoteContains_returnsPullFirstHint() {
        val raw = "hint: Updates were rejected because the remote contains work that you do not\n" +
            "hint: have locally. This is usually caused by another repository pushing\n" +
            "hint: to the same ref."
        assertEquals("Push rejected; the remote has newer commits. Pull first", GitErrorMessage.friendly(raw))
    }

    // ── commit identity ─────────────────────────────────────────

    @Test
    fun pleaseTellMeWhoYouAre_returnsIdentityHint() {
        val raw = "*** Please tell me who you are.\n\nRun\n\n  git config --global user.email \"you@example.com\"\n  git config --global user.name \"Your Name\""
        assertEquals("Commit identity is not set; fill in name and email in Settings", GitErrorMessage.friendly(raw))
    }

    @Test
    fun authorIdentityUnknown_returnsIdentityHint() {
        val raw = "fatal: Author identity unknown"
        assertEquals("Commit identity is not set; fill in name and email in Settings", GitErrorMessage.friendly(raw))
    }

    // ── no match / empty ────────────────────────────────────────

    @Test
    fun noMatch_returnsRaw() {
        val raw = "error: pathspec 'missing.txt' did not match any file(s) known to git"
        assertEquals(raw, GitErrorMessage.friendly(raw))
    }

    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals("", GitErrorMessage.friendly(""))
    }

    @Test
    fun blankInput_returnsRawAsIs() {
        val raw = "  \n\t "
        assertEquals(raw, GitErrorMessage.friendly(raw))
    }
}
