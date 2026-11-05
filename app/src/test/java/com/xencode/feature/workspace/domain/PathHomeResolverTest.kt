package com.xencode.feature.workspace.domain

import android.content.Context
import com.xencode.feature.agent.domain.container.RemoteSshConnection
import com.xencode.feature.settings.data.repository.ExecutionMode
import com.xencode.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * PathHomeResolver: home source selection (remote/device-root/local/fallback) and ~ prefix expansion.
 * ExecutionModeHolder uses the real implementation (pure in-memory, no Android dependencies), RemoteSshConnection is a mockk controlling remoteHome,
 * and Context is a mockk controlling filesDir (the home for device ROOT mode).
 */
class PathHomeResolverTest {

    private val remoteSshConnection: RemoteSshConnection = mockk()

    /** Under device ROOT mode the home is the app's private filesDir (expected values follow the platform path separator; on Windows it becomes `C:\data\...`). */
    private val filesDirFile = File("/data/user/0/com.xencode")
    private val filesDirPath = filesDirFile.absolutePath
    private val context: Context = mockk {
        every { filesDir } returns filesDirFile
    }

    private fun newResolver(mode: ExecutionMode): PathHomeResolver {
        val holder = ExecutionModeHolder().apply { setMode(mode) }
        return PathHomeResolver(context, holder, remoteSshConnection)
    }

    // ---------- home(): source selection and fallback ----------

    @Test
    fun home_localMode_containerHomeUnknown_fallsBackToRoot() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        every { remoteSshConnection.remoteHome } returns null
        resolver.containerHome = null
        assertEquals("/root", resolver.home())
    }

    @Test
    fun home_localMode_returnsContainerHome() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        // Even when a remote home exists, local mode does not use it
        every { remoteSshConnection.remoteHome } returns "/home/remote"
        resolver.containerHome = "/data/container-home"
        assertEquals("/data/container-home", resolver.home())
    }

    @Test
    fun home_remoteMode_remoteHomeUnknown_fallsBackToRoot() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns null
        // Even when a local containerHome exists, remote mode does not use it
        resolver.containerHome = "/data/container-home"
        assertEquals("/root", resolver.home())
    }

    @Test
    fun home_remoteMode_returnsRemoteHome() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns "/home/dev"
        assertEquals("/home/dev", resolver.home())
    }

    @Test
    fun home_deviceRootMode_returnsAppFilesDir() {
        val resolver = newResolver(ExecutionMode.DEVICE_ROOT)
        // Device-root mode ignores container/remote home
        every { remoteSshConnection.remoteHome } returns "/home/dev"
        resolver.containerHome = "/root"
        assertEquals(filesDirPath, resolver.home())
        assertEquals("$filesDirPath/.xencode", resolver.xencodeRoot())
        assertEquals("$filesDirPath/workspace", resolver.expandHome("~/workspace"))
    }

    // ---------- xencodeRoot(): execution-environment AI config root ----------

    @Test
    fun xencodeRoot_localMode() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root"
        assertEquals("/root/.xencode", resolver.xencodeRoot())
    }

    @Test
    fun xencodeRoot_remoteMode_usesRemoteHome() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns "/home/dev/"
        assertEquals("/home/dev/.xencode", resolver.xencodeRoot())
    }

    // ---------- expandHome(): ~ expansion ----------

    @Test
    fun expandHome_tildeOnly_returnsHome() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        every { remoteSshConnection.remoteHome } returns null
        resolver.containerHome = "/root"
        assertEquals("/root", resolver.expandHome("~"))
    }

    @Test
    fun expandHome_tildeSlash_appendsToHome() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root"
        assertEquals("/root/workspace", resolver.expandHome("~/workspace"))
        assertEquals("/root/.xencode/skills", resolver.expandHome("~/.xencode/skills"))
    }

    @Test
    fun expandHome_homeEndsWithSlash_noDoubleSlash() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root/"
        assertEquals("/root/x", resolver.expandHome("~/x"))
    }

    @Test
    fun expandHome_remoteMode_usesRemoteHome() {
        val resolver = newResolver(ExecutionMode.REMOTE_SSH)
        every { remoteSshConnection.remoteHome } returns "/home/dev/"
        assertEquals("/home/dev/workspace", resolver.expandHome("~/workspace"))
    }

    @Test
    fun expandHome_otherPaths_unchanged() {
        val resolver = newResolver(ExecutionMode.LOCAL_PROOT)
        resolver.containerHome = "/root"
        assertEquals("/etc/hosts", resolver.expandHome("/etc/hosts"))
        assertEquals("src/Main.kt", resolver.expandHome("src/Main.kt"))
        assertEquals("", resolver.expandHome(""))
        assertEquals("~~/x", resolver.expandHome("~~/x"))
    }
}
