package com.xencode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/** [normalizeRemoteWorkspacePath]: null and legacy default values normalize to [DEFAULT_REMOTE_WORKSPACE_ROOT]. */
class RemoteWorkspacePathNormalizationTest {

    @Test
    fun blank_falls_back_to_default() {
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath(null, "root"))
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath("", "root"))
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath("   ", "root"))
    }

    @Test
    fun legacy_hardcoded_default_is_normalized() {
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath("/home/root/workspace", "root"))
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath("/home/dev/workspace", "dev"))
    }

    @Test
    fun tilde_workspace_is_normalized() {
        // ~/workspace is a path taken by a symlink and cannot serve as the workspace root
        assertEquals(DEFAULT_REMOTE_WORKSPACE_ROOT, normalizeRemoteWorkspacePath("~/workspace", "root"))
    }

    @Test
    fun explicit_path_is_kept() {
        assertEquals("/data/projects", normalizeRemoteWorkspacePath("/data/projects", "root"))
        assertEquals("~/proj", normalizeRemoteWorkspacePath("~/proj", "root"))
        // A non-matching username must not be misjudged as the user's own default
        assertEquals("/home/dev/workspace", normalizeRemoteWorkspacePath("/home/dev/workspace", "root"))
    }
}
