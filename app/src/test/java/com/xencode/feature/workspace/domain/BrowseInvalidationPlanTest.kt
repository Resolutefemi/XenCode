package com.xencode.feature.workspace.domain

import com.xencode.core.watch.ChangeKind
import com.xencode.core.watch.ChangeRoot
import com.xencode.core.watch.FileChange
import com.xencode.core.watch.FileChangeBatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseInvalidationPlanTest {

    private val root = "/workspace"

    private fun change(path: String, kind: ChangeKind) =
        FileChange(ChangeRoot.WORKSPACE, "/host$path", path, kind)

    @Test
    fun createFile_invalidatesParentDirectory() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace/src/main.kt", ChangeKind.CREATED))), root
        )
        assertFalse(plan.fullRefresh)
        assertTrue("/workspace/src" in plan.relistDirs)
        assertTrue(plan.dropTrees.isEmpty())
    }

    @Test
    fun modifyFile_invalidatesSelfAndParentDirectory() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace/src/main.kt", ChangeKind.MODIFIED))), root
        )
        assertTrue("/workspace/src" in plan.relistDirs)
        assertTrue("/workspace/src/main.kt" in plan.relistDirs)
        assertTrue(plan.dropTrees.isEmpty())
    }

    @Test
    fun deleteDirectory_dropsEntireTree() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace/build", ChangeKind.DELETED))), root
        )
        assertTrue("/workspace/build" in plan.dropTrees)
        assertTrue("/workspace" in plan.relistDirs)
    }

    @Test
    fun truncatedBatch_requiresFullRefresh() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(emptyList(), truncated = true), root
        )
        assertTrue(plan.fullRefresh)
    }

    @Test
    fun rootGitignoreChanged_suggestsRuleReload() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace/.gitignore", ChangeKind.MODIFIED))), root
        )
        assertTrue(plan.gitignoreTouched)
    }

    @Test
    fun nestedGitignoreDoesNotCountAsRoot() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace/sub/.gitignore", ChangeKind.MODIFIED))), root
        )
        assertFalse(plan.gitignoreTouched)
    }

    @Test
    fun emptyBatch_emptyPlan() {
        val plan = planBrowseInvalidation(FileChangeBatch(emptyList()), root)
        assertTrue(plan.isEmpty)
    }

    @Test
    fun changeOnRootPathOnlyInvalidatesRoot() {
        val plan = planBrowseInvalidation(
            FileChangeBatch(listOf(change("/workspace", ChangeKind.MODIFIED))), root
        )
        assertEquals(setOf("/workspace"), plan.relistDirs)
    }
}
