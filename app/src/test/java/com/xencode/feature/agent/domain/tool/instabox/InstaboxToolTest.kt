package com.xencode.feature.agent.domain.tool.instabox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstaboxToolTest {

    @Test
    fun cleanAction_acceptsCanonicalNames() {
        assertEquals("run_command", InstaboxTool.cleanAction("run_command"))
        assertEquals("files_list", InstaboxTool.cleanAction("files_list"))
        assertEquals("desktop_status", InstaboxTool.cleanAction("desktop_status"))
    }

    @Test
    fun cleanAction_normalizesHyphensAndCase() {
        assertEquals("run_command", InstaboxTool.cleanAction("  Run-Command  "))
        assertEquals("files_read", InstaboxTool.cleanAction("FILES-READ"))
    }

    @Test
    fun cleanAction_resolvesAliases() {
        assertEquals("run_code", InstaboxTool.cleanAction("exec"))
        assertEquals("run_command", InstaboxTool.cleanAction("shell"))
        assertEquals("files_list", InstaboxTool.cleanAction("ls"))
        assertEquals("files_read", InstaboxTool.cleanAction("read"))
        assertEquals("files_write", InstaboxTool.cleanAction("write"))
        assertEquals("kill", InstaboxTool.cleanAction("destroy"))
        assertEquals("pause", InstaboxTool.cleanAction("suspend"))
        assertEquals("desktop_stream", InstaboxTool.cleanAction("vnc"))
    }

    @Test
    fun cleanAction_rejectsUnknown() {
        assertNull(InstaboxTool.cleanAction(null))
        assertNull(InstaboxTool.cleanAction(""))
        assertNull(InstaboxTool.cleanAction("not_a_real_action"))
    }

    @Test
    fun machinePath_mapsHomeAndRelative() {
        assertEquals(InstaboxTool.HOME, InstaboxTool.machinePath(""))
        assertEquals(InstaboxTool.HOME, InstaboxTool.machinePath("~"))
        assertEquals(InstaboxTool.HOME, InstaboxTool.machinePath("."))
        assertEquals("${InstaboxTool.HOME}/notes.txt", InstaboxTool.machinePath("~/notes.txt"))
        assertEquals("${InstaboxTool.HOME}/work/a.py", InstaboxTool.machinePath("work/a.py"))
        assertEquals("/tmp/out", InstaboxTool.machinePath("/tmp/out"))
    }

    @Test
    fun readOnlyActions_areKnown() {
        val expected = setOf(
            "info", "list", "files_list", "files_read", "files_exists",
            "list_ports", "desktop_status", "metrics"
        )
        assertEquals(expected, InstaboxTool.READ_ONLY_ACTIONS)
        assertTrue(InstaboxTool.READ_ONLY_ACTIONS.all { it in InstaboxTool.ACTIONS })
    }
}
