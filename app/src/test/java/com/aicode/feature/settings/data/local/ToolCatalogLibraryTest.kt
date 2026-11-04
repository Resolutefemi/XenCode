package com.aicode.feature.settings.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogLibraryTest {

    @Test
    fun parse_validCatalog() {
        val text = """
            [
              {
                "name": "readFile",
                "title": "Read file",
                "description": "Reads a file.",
                "category": "files",
                "permission": "auto",
                "parameters": [
                  {
                    "name": "path",
                    "type": "string",
                    "required": true,
                    "description": "File path"
                  },
                  {
                    "name": "start_line",
                    "type": "integer",
                    "required": false,
                    "description": "Start line"
                  }
                ]
              }
            ]
        """.trimIndent()
        val entries = ToolCatalogLibrary.parse(text)
        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("readFile", entry.name)
        assertEquals("Read file", entry.title)
        assertEquals("Reads a file.", entry.description)
        assertEquals("files", entry.category)
        assertEquals("auto", entry.permission)
        assertEquals(2, entry.parameters.size)
        assertTrue(entry.parameters[0].required)
        assertTrue(!entry.parameters[1].required)
    }

    @Test
    fun parse_invalidJsonReturnsEmpty() {
        assertTrue(ToolCatalogLibrary.parse("not json").isEmpty())
        assertTrue(ToolCatalogLibrary.parse("{\"unexpected\": true}").isEmpty())
    }

    @Test
    fun parse_defaultsApplied() {
        val text = """[{"name":"x","title":"X"}]"""
        val entries = ToolCatalogLibrary.parse(text)
        assertEquals(1, entries.size)
        assertEquals("extensions", entries[0].category)
        assertEquals("ask", entries[0].permission)
        assertTrue(entries[0].parameters.isEmpty())
    }

    @Test
    fun parse_unknownFieldsIgnored() {
        val text = """[{"name":"x","title":"X","futureField":123,"permission":"ask"}]"""
        val entries = ToolCatalogLibrary.parse(text)
        assertEquals(1, entries.size)
        assertEquals("ask", entries[0].permission)
        assertEquals("extensions", entries[0].category)
    }
}
