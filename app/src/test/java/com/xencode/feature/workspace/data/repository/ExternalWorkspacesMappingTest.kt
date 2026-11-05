package com.xencode.feature.workspace.data.repository

import com.xencode.feature.workspace.domain.model.WorkspaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalWorkspacesMappingTest {

    private fun record(name: String, path: String) =
        WorkspaceRepository.ExternalWorkspaceRecord(name, path, "content://tree/$path")

    @Test
    fun existingDirectory_isAvailable() {
        val ws = WorkspaceRepository.mapExternalWorkspaces(
            listOf(record("proj", "/storage/emulated/0/proj")),
            isDir = { it == "/storage/emulated/0/proj" }
        )
        assertEquals(1, ws.size)
        assertTrue(ws[0].available)
        assertEquals(WorkspaceType.EXTERNAL_LOCAL, ws[0].type)
        assertEquals("/storage/emulated/0/proj", ws[0].path)
    }

    @Test
    fun missingDirectory_isUnavailableButKept() {
        val ws = WorkspaceRepository.mapExternalWorkspaces(
            listOf(record("proj", "/storage/emulated/0/proj")),
            isDir = { false }
        )
        // Disconnected records stay in the list (grayed out rather than removed) so the user can review and remove them
        assertEquals(1, ws.size)
        assertFalse(ws[0].available)
    }

    @Test
    fun mixedDirectories_keepOrderAndFlags() {
        val ws = WorkspaceRepository.mapExternalWorkspaces(
            listOf(record("a", "/storage/emulated/0/a"), record("b", "/storage/emulated/0/b")),
            isDir = { it.endsWith("/a") }
        )
        assertEquals(listOf("a", "b"), ws.map { it.name })
        assertTrue(ws[0].available)
        assertFalse(ws[1].available)
    }

    @Test
    fun directoryRecovered_becomesAvailable() {
        var exists = false
        val ws = WorkspaceRepository.mapExternalWorkspaces(
            listOf(record("proj", "/storage/emulated/0/proj")),
            isDir = { exists }
        )
        assertFalse(ws[0].available)
        // Once the directory recovers, mapping again should mark it available
        exists = true
        val again = WorkspaceRepository.mapExternalWorkspaces(
            listOf(record("proj", "/storage/emulated/0/proj")),
            isDir = { exists }
        )
        assertTrue(again[0].available)
    }

    @Test
    fun emptyRecords_mapsToEmpty() {
        assertTrue(WorkspaceRepository.mapExternalWorkspaces(emptyList(), isDir = { true }).isEmpty())
    }

    @Test
    fun uniqueName_bareWhenFree() {
        assertEquals("proj", WorkspaceRepository.uniqueName("proj", setOf("other")))
    }

    @Test
    fun uniqueName_appendsSuffixOnConflict() {
        assertEquals("proj (2)", WorkspaceRepository.uniqueName("proj", setOf("proj")))
        assertEquals("proj (3)", WorkspaceRepository.uniqueName("proj", setOf("proj", "proj (2)")))
    }

    @Test
    fun uniqueName_emptyBaseFallsBack() {
        // Consistent with the Repository call site: the caller guarantees a non-blank name
        assertEquals("", WorkspaceRepository.uniqueName("", emptySet()))
    }
}
