package com.xencode.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Workspace precedence: --workspace beats the config default beats the CWD.
 */
class WorkspaceResolveTest {

    @Test
    fun cliArgWins() {
        assertEquals("/tmp/ws", resolveWorkspace("/tmp/ws", "/cfg/default", "/cwd"))
    }

    @Test
    fun configDefaultWhenNoArg() {
        assertEquals("/cfg/default", resolveWorkspace(null, "/cfg/default", "/cwd"))
    }

    @Test
    fun cwdWhenNothingElseSet() {
        assertEquals("/cwd", resolveWorkspace(null, null, "/cwd"))
        assertEquals("/cwd", resolveWorkspace(null, "", "/cwd"))
    }

    @Test
    fun blankArgFallsThrough() {
        assertEquals("/cfg/default", resolveWorkspace("   ", "/cfg/default", "/cwd"))
        assertEquals("/cwd", resolveWorkspace("   ", "   ", "/cwd"))
    }

    @Test
    fun argMayBeRelative() {
        // Raw pass-through: canonicalization happens later, once, in loadRuntime.
        assertEquals("relative/path", resolveWorkspace("relative/path", "/cfg/default", "/cwd"))
    }
}
