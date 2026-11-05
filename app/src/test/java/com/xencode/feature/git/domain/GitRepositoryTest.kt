package com.xencode.feature.git.domain

import com.xencode.feature.agent.domain.container.CommandEngine
import com.xencode.feature.agent.domain.container.CommandResult
import com.xencode.feature.git.domain.model.GitBranch
import com.xencode.feature.git.domain.model.GitCommit
import com.xencode.feature.git.domain.model.GitFileChange
import com.xencode.feature.git.domain.model.GitGraphRef
import com.xencode.feature.git.domain.model.GitStatus
import com.xencode.feature.git.domain.model.GitTag
import com.xencode.feature.git.domain.model.GraphCommit
import com.xencode.feature.workspace.data.repository.WorkspaceRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GitRepository] output parsing logic (CommandEngine and WorkspaceRepository mocked out).
 * Every case's engine output is built to mirror real git command output (porcelain / for-each-ref /
 * diff-tree / ls-files / log), focusing on parsing: Chinese paths kept unescaped, branch tracking info,
 * refs assembly, pagination, and commit message shell escaping.
 * Pure JVM, no Android APIs touched (git execution itself is mocked).
 */
class GitRepositoryTest {

    private val workPath = "/root/workspace"

    /** Builds a repo returning the given output; a non-zero [exitCode] covers the gitChecked failure path. */
    private fun createRepo(output: String, exitCode: Int? = 0): GitRepository {
        val engine = mockk<GitCommandEngine>()
        coEvery { engine.runCommandSyncUnbounded(any(), any(), any()) } answers {
            // status() additionally probes MERGE_HEAD to detect merge state; that probe must return empty or it is misjudged as mid-merge
            if (invocation.args[0].toString().contains("MERGE_HEAD")) CommandResult("", 0)
            else CommandResult(output, exitCode)
        }
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns workPath
        return GitRepository(engine, workspace)
    }

    // ── status(): porcelain v1 -b parsing ───────────────────────────

    @Test
    fun status_parsesBranchTrackingAndChanges() = runTest {
        val repo = createRepo(
            "## feature/foo...origin/feature/foo [ahead 2, behind 3]\n" +
                " M src/Main.kt\n" +
                "A  src/New.kt\n" +
                "?? untracked/file.txt"
        )
        val status = repo.status()
        assertEquals(
            GitStatus(
                branch = "feature/foo",
                ahead = 2,
                behind = 3,
                staged = listOf(GitFileChange("src/New.kt", "A", staged = true)),
                unstaged = listOf(GitFileChange("src/Main.kt", "M", staged = false)),
                untracked = listOf("untracked/file.txt"),
                upstream = "origin/feature/foo",
                isDetached = false
            ),
            status
        )
        assertTrue(status.hasChanges)
    }

    @Test
    fun status_noUpstream_upstreamNull() = runTest {
        val repo = createRepo("## main")
        val status = repo.status()
        assertEquals("main", status.branch)
        assertNull(status.upstream)
        assertEquals(0, status.ahead)
        assertEquals(0, status.behind)
        assertFalse(status.hasChanges)
    }

    @Test
    fun status_detachedHead_branchIsHead() = runTest {
        val repo = createRepo("## HEAD (no branch)")
        val status = repo.status()
        assertTrue(status.isDetached)
        assertEquals("HEAD", status.branch)
    }

    @Test
    fun status_chinesePath_keptUnescaped() = runTest {
        // With core.quotepath=false, Chinese paths are output verbatim and must not be escaped to \NNN
        val repo = createRepo(" M 中文目录/文件.txt\n?? 中文未跟踪.txt\nA  新建/目录/测试.txt")
        val status = repo.status()
        assertEquals(listOf(GitFileChange("中文目录/文件.txt", "M", staged = false)), status.unstaged)
        assertEquals(listOf("中文未跟踪.txt"), status.untracked)
        assertEquals(listOf(GitFileChange("新建/目录/测试.txt", "A", staged = true)), status.staged)
    }

    @Test
    fun status_renamedPath_usesNewPath() = runTest {
        val repo = createRepo("R  old.txt -> newdir/renamed.txt")
        val status = repo.status()
        assertEquals(listOf(GitFileChange("newdir/renamed.txt", "R", staged = true)), status.staged)
        assertTrue(status.unstaged.isEmpty())
    }

    @Test
    fun status_bothModified_addedToStagedAndUnstaged() = runTest {
        val repo = createRepo("MM app/src/X.kt")
        val status = repo.status()
        assertEquals(listOf(GitFileChange("app/src/X.kt", "M", staged = true)), status.staged)
        assertEquals(listOf(GitFileChange("app/src/X.kt", "M", staged = false)), status.unstaged)
    }

    @Test
    fun status_quotedPath_unquoted() = runTest {
        // Paths with spaces are quoted in porcelain and must be unquoted
        val repo = createRepo(" M \"a b.txt\"")
        val status = repo.status()
        assertEquals(listOf(GitFileChange("a b.txt", "M", staged = false)), status.unstaged)
    }

    // ── branches() / loadAllRefs(): for-each-ref parsing ────────────

    @Test
    fun loadAllRefs_parsesBranchesRemotesAndTags() = runTest {
        val repo = createRepo(
            "main\u001f7f2a1b3c4d5e\u001f*\u001frefs/heads/main\u001forigin/main\u001f[ahead 1, behind 2]\n" +
                "feature\u001f9a8b7c6d5e4f\u001f\u001frefs/heads/feature\u001f\u001f\n" +
                "origin/feature\u001f3c2d1e0f9a8b\u001f\u001frefs/remotes/origin/feature\u001f\u001f\n" +
                "v1.0\u001f7f2a1b3c4d5e\u001f\u001frefs/tags/v1.0\u001f\u001f"
        )
        val refs = repo.loadAllRefs()
        assertEquals(
            listOf(
                GitBranch("main", current = true, remote = false, upstream = "origin/main", ahead = 1, behind = 2),
                GitBranch("feature", current = false, remote = false),
                GitBranch("origin/feature", current = false, remote = true)
            ),
            refs.branches
        )
        assertEquals(listOf(GitTag("v1.0", "7f2a1b3")), refs.tags)
        assertEquals(
            "main",
            refs.refsByCommit.getValue("7f2a1b3c4d5e")[0].name
        )
        // Both the local branch and the tag on the same hash are kept; the HEAD marker lands only on the local branch
        val refsOnHash = refs.refsByCommit.getValue("7f2a1b3c4d5e")
        assertEquals(
            listOf(
                GitGraphRef("main", isBranch = true, isCurrent = true, isRemote = false),
                GitGraphRef("v1.0", isBranch = false, isCurrent = false, isRemote = false)
            ),
            refsOnHash
        )
        assertEquals(GitGraphRef("origin/feature", isBranch = true, isCurrent = false, isRemote = true),
            refs.refsByCommit.getValue("3c2d1e0f9a8b")[0])
    }

    @Test
    fun loadAllRefs_tagsReversedByRefname() = runTest {
        // for-each-ref outputs v0.9 first in refname ascending order; after parsing it must be reversed to [v1.0, v0.9]
        val repo = createRepo(
            "v0.9\u001f1a2b3c4d5e6f\u001f\u001frefs/tags/v0.9\u001f\u001f\n" +
                "v1.0\u001f7f2a1b3c4d5e\u001f\u001frefs/tags/v1.0\u001f\u001f"
        )
        val refs = repo.loadAllRefs()
        assertEquals(listOf("v1.0", "v0.9"), refs.tags.map { it.name })
        assertEquals("7f2a1b3", refs.tags[0].shortHash)
    }

    @Test
    fun loadAllRefs_blankOutput_returnsEmpty() = runTest {
        val empty = createRepo("").loadAllRefs()
        assertTrue(empty.branches.isEmpty())
        assertTrue(empty.tags.isEmpty())
        assertTrue(empty.refsByCommit.isEmpty())
    }

    @Test
    fun loadAllRefs_fatalOutput_returnsEmpty() = runTest {
        val fatal = createRepo("fatal: Not a git repository").loadAllRefs()
        assertTrue(fatal.branches.isEmpty() && fatal.tags.isEmpty() && fatal.refsByCommit.isEmpty())
    }

    @Test
    fun branches_delegatesToLoadAllRefs() = runTest {
        val repo = createRepo(
            "main\u001f7f2a1b3c4d5e\u001f*\u001frefs/heads/main\u001f\u001f\n" +
                "origin/main\u001f7f2a1b3c4d5e\u001f\u001frefs/remotes/origin/main\u001f\u001f"
        )
        assertEquals(listOf("main", "origin/main"), repo.branches().map { it.name })
    }

    // ── log(limit): %x1f-separated parsing ────────────────────────────────

    @Test
    fun log_parsesFields() = runTest {
        val repo = createRepo(
            "a1b2c3d4\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix: issue\n" +
                "e5f6a7b8\u001fe5f6a7\u001fBob\u001f3 days ago\u001ffeat: new feature"
        )
        val commits = repo.log(limit = 50)
        assertEquals(
            listOf(
                GitCommit("a1b2c3d4", "a1b2c3", "Alice", "2 days ago", "fix: issue"),
                GitCommit("e5f6a7b8", "e5f6a7", "Bob", "3 days ago", "feat: new feature")
            ),
            commits
        )
    }

    @Test
    fun log_messageWithPipe_keptWhole() = runTest {
        // Commit message containing '|': the delimiter is 0x1f, so the message must be kept whole
        val repo = createRepo("a1b2c3d4\u001fa1b2c3\u001fAlice\u001f2 days ago\u001ffix: merge | conflict")
        val commits = repo.log(limit = 50)
        assertEquals("fix: merge | conflict", commits.single().message)
    }

    @Test
    fun log_blankOutput_returnsEmpty() = runTest {
        assertTrue(createRepo("").log(limit = 50).isEmpty())
    }

    @Test
    fun log_fatalOutput_returnsEmpty() = runTest {
        assertTrue(createRepo("fatal: your current branch 'main' does not have any commits yet").log(limit = 50).isEmpty())
    }

    @Test
    fun log_shortLine_skipped() = runTest {
        // Lines with fewer than 5 segments (missing message) are dropped entirely
        val repo = createRepo("a1b2c3d4\u001fa1b2c3\u001fAlice\u001f2 days ago")
        assertTrue(repo.log(limit = 50).isEmpty())
    }

    // ── commitFiles(hash): diff-tree --name-status parsing ──────────

    @Test
    fun commitFiles_parsesNameStatus() = runTest {
        val repo = createRepo(
            "M\tapp/src/Main.kt\n" +
                "A\tapp/src/New.kt\n" +
                "D\tapp/src/Old.kt\n" +
                "A\tdocs/file.txt"
        )
        val files = repo.commitFiles("a1b2c3d4")
        assertEquals(
            listOf(
                GitFileChange("app/src/Main.kt", "M", staged = false),
                GitFileChange("app/src/New.kt", "A", staged = false),
                GitFileChange("app/src/Old.kt", "D", staged = false),
                GitFileChange("docs/file.txt", "A", staged = false)
            ),
            files
        )
    }

    @Test
    fun commitFiles_quotedPath_unquoted() = runTest {
        val repo = createRepo("M\t\"a b.txt\"")
        val files = repo.commitFiles("a1b2c3d4")
        assertEquals(listOf(GitFileChange("a b.txt", "M", staged = false)), files)
    }

    @Test
    fun commitFiles_noTabLines_skipped() = runTest {
        // fatal lines / blank lines have no tab and are skipped → empty list (commit has no changes or the command failed)
        val repo = createRepo("fatal: ambiguous argument 'x': unknown revision or path not in the working tree.\n")
        assertTrue(repo.commitFiles("badhash").isEmpty())
    }

    // ── untrackedFilesIn(dir): ls-files --others parsing ────────────

    @Test
    fun untrackedFilesIn_parsesLines() = runTest {
        val repo = createRepo(
            "newDir/1.txt\n" +
                "newDir/2.txt\n" +
                "docs/new-file.txt\n" +
                "\n"
        )
        val files = repo.untrackedFilesIn("newDir")
        assertEquals(listOf("newDir/1.txt", "newDir/2.txt", "docs/new-file.txt"), files)
    }

    @Test
    fun untrackedFilesIn_blankOrFatal_returnsEmpty() = runTest {
        assertTrue(createRepo("").untrackedFilesIn("dir").isEmpty())
        assertTrue(createRepo("fatal: not a git repository").untrackedFilesIn("dir").isEmpty())
    }

    // ── graph() / graphAppend(): log + refs → GitGraph ───────────

    @Test
    fun graph_blankOutput_returnsEmptyGraph() = runTest {
        val graph = createRepo("").graph()
        assertTrue(graph.commits.isEmpty())
        assertTrue(graph.refs.isEmpty())
        assertFalse(graph.hasMore)
    }

    @Test
    fun graph_parsesCommitsAndFiltersRefs() = runTest {
        val repo = createRepo(
            "aaaa1111\u001faaaa1111\u001fAlice\u001f2 days ago\u001ffix: table | semicolons\u001fbbbb2222\u001fsingle-line body\n" +
                "bbbb2222\u001fbbbb2222\u001fBob\u001f3 days ago\u001ffeat: init\u001f"
        )
        val refs = mapOf(
            "aaaa1111" to listOf(GitGraphRef("main", isBranch = true, isCurrent = true, isRemote = false)),
            // Hashes that were not loaded should be filtered out
            "zzzz9999" to listOf(GitGraphRef("other", isBranch = true, isCurrent = false, isRemote = false))
        )
        val graph = repo.graph(limit = 100, refs = refs)
        assertEquals(2, graph.commits.size)
        assertEquals("aaaa1111", graph.commits[0].hash)
        assertEquals("single-line body", graph.commits[0].body)
        assertEquals(listOf("bbbb2222"), graph.commits[0].parents)
        assertEquals(setOf("aaaa1111"), graph.refs.keys)
        assertFalse(graph.hasMore)
        // Lanes: a single chain stays in column 0
        assertEquals(mapOf("aaaa1111" to 0, "bbbb2222" to 0), graph.lanes)
    }

    @Test
    fun graph_smallBatch_hasMoreTrue() = runTest {
        // Returned count reaches the limit → older commits remain to load
        val repo = createRepo("aaaa1111\u001faaaa1111\u001fAlice\u001f2 days ago\u001ffix\u001f")
        assertTrue(repo.graph(limit = 1).hasMore)
    }

    @Test
    fun graphAppend_mergesExistingAndDedupes() = runTest {
        val existing = listOf(
            GraphCommit("aaaa1111", "aaaa1111", "Alice", "d", "newer", parents = listOf("bbbb2222"))
        )
        // Simulates the extreme case where a new batch still contains already-loaded commits (--skip avoids this in theory; defensive dedup)
        val repo = createRepo(
            "aaaa1111\u001faaaa1111\u001fAlice\u001f2 days ago\u001fnewer\u001fbbbb2222\u001f\n" +
                "bbbb2222\u001fbbbb2222\u001fBob\u001f3 days ago\u001folder\u001f"
        )
        val graph = repo.graphAppend(existing, emptyMap(), limit = 100)
        assertEquals(listOf("aaaa1111", "bbbb2222"), graph.commits.map { it.hash })
    }

    @Test
    fun graphAppend_existingNonEmpty_logFatal_buildsFromExisting() = runTest {
        // When the appended batch fails, keep the loaded commits and recompute the layout; do not degrade to EMPTY
        val existing = listOf(
            GraphCommit("aaaa1111", "aaaa1111", "Alice", "d", "newer", parents = emptyList())
        )
        val graph = createRepo("fatal: not a git repository").graphAppend(existing, emptyMap(), limit = 100)
        assertEquals(listOf("aaaa1111"), graph.commits.map { it.hash })
        assertFalse(graph.hasMore)
    }

    @Test
    fun graphAppend_mergeCommit_computesMergeLanes() = runTest {
        val repo = createRepo(
            "m1m1m1\u001fm1m1m1\u001fAlice\u001f2 days ago\u001fMerge branch 'feat'\u001fp1p1p1 p2p2p2\u001f\n" +
                "p1p1p1\u001fp1p1p1\u001fBob\u001f3 days ago\u001ffeat: x\u001fr0r0r0\u001f\n" +
                "p2p2p2\u001fp2p2p2\u001fCarol\u001f3 days ago\u001ffeat: y\u001fr0r0r0\u001f\n" +
                "r0r0r0\u001fr0r0r0\u001fDan\u001f4 days ago\u001froot\u001f"
        )
        val graph = repo.graph(limit = 100)
        assertTrue(graph.commits[0].isMerge)
        assertEquals(mapOf("m1m1m1" to 0, "p1p1p1" to 0, "p2p2p2" to 1, "r0r0r0" to 0), graph.lanes)
        assertTrue(graph.edges.any { it.isMergeIn && it.fromLane == 0 && it.toLane == 1 })
        assertEquals(1, graph.maxLane)
    }

    @Test
    fun graph_fork_branchesToSeparateLanes() = runTest {
        val repo = createRepo(
            "c1c1c1\u001fc1c1c1\u001fAlice\u001f2 days ago\u001fbranch A\u001fr0r0r0\u001f\n" +
                "c2c2c2\u001fc2c2c2\u001fBob\u001f2 days ago\u001fbranch B\u001fr0r0r0\u001f\n" +
                "r0r0r0\u001fr0r0r0\u001fDan\u001f4 days ago\u001froot\u001f"
        )
        val graph = repo.graph(limit = 100)
        assertEquals(mapOf("c1c1c1" to 0, "c2c2c2" to 1, "r0r0r0" to 0), graph.lanes)
        assertEquals(1, graph.maxLane)
    }

    // ── commit(): message shell escaping and failure paths ───────────────────────

    @Test
    fun commit_messageWithPipe_shellSingleQuoted() = runTest {
        val engine = mockk<GitCommandEngine>()
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns workPath
        val slot = slot<String>()
        coEvery { engine.runCommandSyncUnbounded(capture(slot), any(), any()) } returns CommandResult("", 0)
        val repo = GitRepository(engine, workspace)

        repo.commit("fix: table | semicolon; parens(1)")
        // '|' is a shell pipe, so the whole message must be passed single-quoted
        assertEquals("git -c core.quotepath=false commit -m 'fix: table | semicolon; parens(1)'", slot.captured)
    }

    @Test
    fun commit_messageWithSingleQuote_escaped() = runTest {
        val engine = mockk<GitCommandEngine>()
        val workspace = mockk<WorkspaceRepository>()
        every { workspace.currentPath() } returns workPath
        val slot = slot<String>()
        coEvery { engine.runCommandSyncUnbounded(capture(slot), any(), any()) } returns CommandResult("", 0)
        val repo = GitRepository(engine, workspace)

        repo.commit("it's fixed")
        // Embedded single quote closes-escapes-reopens: it'\''s fixed
        assertEquals("git -c core.quotepath=false commit -m 'it'\\''s fixed'", slot.captured)
    }

    @Test
    fun commit_nonZeroExit_throwsGitCommandFailureException() = runTest {
        val repo = createRepo("fatal: commit failed", exitCode = 1)
        val e = runCatching { repo.commit("msg") }.exceptionOrNull()
        assertTrue(e is GitCommandFailureException)
        assertEquals("fatal: commit failed", (e as GitCommandFailureException).output)
    }

    @Test
    fun commit_nonZeroExitWithBlankOutput_throwsWithExitCodeMessage() = runTest {
        val repo = createRepo("", exitCode = 1)
        val e = runCatching { repo.commit("msg") }.exceptionOrNull()
        assertTrue(e is GitCommandFailureException)
        assertEquals("git exited with code 1", e?.message)
    }
}