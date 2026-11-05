package com.xencode.feature.workspace.domain

import net.schmizz.sshj.xfer.FilePermission
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mapping AI paths ↔ real remote paths in remote mode, plus SFTP permission-bit formatting (the pure-logic part of [RemoteSftpFileAccess]).
 */
class RemoteSftpFileAccessMappingTest {

    private val wsRoot = "/data/user/0/com.xencode/files/projects/demo"
    private val home = "/home/dev"

    // ---------- remotePathFor: AI path → real remote path ----------

    @Test
    fun workspace_root_maps_to_configured_root() {
        assertEquals(wsRoot, remotePathFor("~/workspace", wsRoot, home))
        assertEquals(wsRoot, remotePathFor("~/workspace/", wsRoot, home))
        assertEquals(wsRoot, remotePathFor("/home/dev/workspace", wsRoot, home))
        assertEquals(wsRoot, remotePathFor("/home/dev/workspace/", wsRoot, home))
    }

    @Test
    fun workspace_child_maps_under_root() {
        assertEquals("$wsRoot/src/Main.kt", remotePathFor("~/workspace/src/Main.kt", wsRoot, home))
        assertEquals("$wsRoot/app/build.gradle.kts", remotePathFor("/home/dev/workspace/app/build.gradle.kts", wsRoot, home))
        assertEquals("$wsRoot/a/b/c.txt", remotePathFor("~/workspace/a/b/c.txt", wsRoot, home))
    }

    @Test
    fun absolute_path_passes_through() {
        assertEquals("/etc/nginx/nginx.conf", remotePathFor("/etc/nginx/nginx.conf", wsRoot, home))
        assertEquals("/root/.xencode/skills/x/SKILL.md", remotePathFor("/root/.xencode/skills/x/SKILL.md", wsRoot, home))
    }

    @Test
    fun relative_path_hangs_under_root() {
        assertEquals("$wsRoot/src/Main.kt", remotePathFor("src/Main.kt", wsRoot, home))
    }

    @Test
    fun input_is_trimmed() {
        assertEquals(wsRoot, remotePathFor("  ~/workspace  ", wsRoot, home))
    }

    @Test
    fun home_unknown_falls_back_to_tilde_form() {
        // When remoteHome is unavailable, the ~/workspace prefix still maps (wsRoot falls back to the literal ~/workspace)
        assertEquals(wsRoot, remotePathFor("~/workspace", wsRoot, null))
        assertEquals("$wsRoot/foo.txt", remotePathFor("~/workspace/foo.txt", wsRoot, null))
        // ~ paths outside the workspace prefix are not expanded and hang under the root as-is
        assertEquals("$wsRoot/~/other", remotePathFor("~/other", wsRoot, null))
    }

    // ---------- displayPathFor: real remote path → AI-view path ----------

    @Test
    fun display_root_maps_to_container_root() {
        assertEquals("~/workspace", displayPathFor(wsRoot, wsRoot))
        assertEquals("~/workspace/src/Main.kt", displayPathFor("$wsRoot/src/Main.kt", wsRoot))
    }

    @Test
    fun display_other_absolute_path_unchanged() {
        assertEquals("/etc/passwd", displayPathFor("/etc/passwd", wsRoot))
    }

    // ---------- formatPermissions: SFTP permission set → rwx string ----------

    @Test
    fun format_permissions_typical_file() {
        assertEquals(
            "rw-r--r--",
            formatPermissions(
                setOf(FilePermission.USR_R, FilePermission.USR_W, FilePermission.GRP_R, FilePermission.OTH_R)
            )
        )
    }

    @Test
    fun format_permissions_directory() {
        assertEquals(
            "rwxr-xr-x",
            formatPermissions(
                setOf(
                    FilePermission.USR_R, FilePermission.USR_W, FilePermission.USR_X,
                    FilePermission.GRP_R, FilePermission.GRP_X,
                    FilePermission.OTH_R, FilePermission.OTH_X
                )
            )
        )
    }

    @Test
    fun format_permissions_empty() {
        assertEquals("---------", formatPermissions(emptySet()))
    }
}
