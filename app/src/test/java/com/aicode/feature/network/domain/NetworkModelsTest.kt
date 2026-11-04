package com.aicode.feature.network.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkModelsTest {

    @Test
    fun normalizeRequestUrl_addsHttpsWhenMissing() {
        assertEquals("https://example.com", normalizeRequestUrl("example.com"))
        assertEquals("https://example.com/path", normalizeRequestUrl("  example.com/path  "))
    }

    @Test
    fun normalizeRequestUrl_keepsExistingScheme() {
        assertEquals("http://example.com", normalizeRequestUrl("http://example.com"))
        assertEquals("https://example.com", normalizeRequestUrl("HTTPS://example.com"))
    }

    @Test
    fun normalizeRequestUrl_keepsBlank() {
        assertEquals("", normalizeRequestUrl("   "))
    }

    @Test
    fun prettyPrintJson_formatsObjectAndArray() {
        val objectJson = prettyPrintJson("""{"b":1,"a":2}""")
        assertTrue(objectJson.contains("\n"))
        assertTrue(objectJson.contains("\"b\""))

        val arrayJson = prettyPrintJson("""[1,2]""")
        assertTrue(arrayJson.contains("\n"))
        assertTrue(arrayJson.contains("1"))
    }

    @Test
    fun prettyPrintJson_leavesPlainText() {
        assertEquals("not json", prettyPrintJson("not json"))
    }

    @Test
    fun headersToJson_serializesDuplicateNamesAsArray() {
        val json = headersToJson(
            listOf(
                NetworkHeader("Accept", "text/html"),
                NetworkHeader("Set-Cookie", "a=1"),
                NetworkHeader("Set-Cookie", "b=2")
            )
        )
        assertTrue(json.contains("\"Accept\""))
        assertTrue(json.contains("\"Set-Cookie\""))
        assertTrue(json.contains("a=1"))
        assertTrue(json.contains("b=2"))
    }

    @Test
    fun bodyToTableRows_parsesObject() {
        val rows = bodyToTableRows("""{"name":"ai","ok":true}""")
        assertEquals(2, rows.size)
        assertEquals(setOf("name", "ok"), rows.map { it.name }.toSet())
    }

    @Test
    fun bodyToTableRows_parsesArray() {
        val rows = bodyToTableRows("""["a","b"]""")
        assertEquals(listOf("[0]", "[1]"), rows.map { it.name })
        assertEquals(listOf("a", "b"), rows.map { it.value })
    }

    @Test
    fun defaultViewMode_matchesTab() {
        assertEquals(ResponseViewMode.TABLE, defaultViewMode(ResponseTab.HEADERS))
        assertEquals(ResponseViewMode.JSON, defaultViewMode(ResponseTab.BODY))
        assertNull(defaultViewMode(ResponseTab.METRICS))
    }

    @Test
    fun jsonToHeaders_readsObjectAndArrays() {
        val rows = jsonToHeaders("""{"Accept":"text/html","X-Test":["a","b"]}""")
        assertEquals(
            listOf(
                NetworkHeader("Accept", "text/html"),
                NetworkHeader("X-Test", "a"),
                NetworkHeader("X-Test", "b")
            ),
            rows
        )
    }

    @Test
    fun mergeQueryIntoUrl_appendsParameters() {
        val merged = mergeQueryIntoUrl(
            "https://example.com/path",
            """{"q":"hello","page":"2"}"""
        )
        assertTrue(merged.contains("q=hello"))
        assertTrue(merged.contains("page=2"))
    }

    @Test
    fun authHeaders_buildsBearerAndBasic() {
        val bearer = authHeaders("""{"type":"bearer","token":"abc"}""")
        assertEquals(listOf(NetworkHeader("Authorization", "Bearer abc")), bearer)

        val basic = authHeaders("""{"type":"basic","username":"u","password":"p"}""")
        assertEquals(1, basic.size)
        assertEquals("Authorization", basic[0].name)
        assertTrue(basic[0].value.startsWith("Basic "))
    }

    @Test
    fun jsonToTreeNodes_buildsSlimTree() {
        val nodes = jsonToTreeNodes("""{"user":{"id":1},"tags":["a"]}""")
        assertEquals(listOf("user", "tags"), nodes?.map { it.key })
        assertEquals("{1}", nodes?.first { it.key == "user" }?.preview)
        assertEquals("[1]", nodes?.first { it.key == "tags" }?.preview)
    }

    @Test
    fun jsonToTreeNodes_returnsNullForInvalidJson() {
        assertNull(jsonToTreeNodes("not json"))
    }

    @Test
    fun parseTimeoutSeconds_clampsRange() {
        assertEquals(30, parseTimeoutSeconds(""))
        assertEquals(1, parseTimeoutSeconds("0"))
        assertEquals(120, parseTimeoutSeconds("999"))
        assertEquals(15, parseTimeoutSeconds("15"))
    }

    @Test
    fun isBlankJson_treatsEmptyObjectAsBlank() {
        assertTrue(isBlankJson(DEFAULT_JSON_OBJECT))
        assertTrue(isBlankJson("{}"))
        assertTrue(isBlankJson("[]"))
        assertTrue(!isBlankJson("""{"q":"1"}"""))
    }

    @Test
    fun jsonToTableRows_readsObject() {
        val rows = jsonToTableRows("""{"q":"hello","ok":true}""")
        assertEquals(listOf("q", "ok"), rows.map { it.key })
        assertEquals("hello", rows[0].value)
        assertEquals("true", rows[1].value)
        assertTrue(rows.all { it.enabled })
    }

    @Test
    fun tableRowsRoundTrip_skipsDisabledAndBlankKeys() {
        val json = tableRowsToJson(
            listOf(
                TableInputRow(key = "q", value = "hello", enabled = true),
                TableInputRow(key = "page", value = "2", enabled = false),
                TableInputRow(key = "", value = "x", enabled = true),
                TableInputRow(key = "ok", value = "true", enabled = true)
            )
        )
        val rows = jsonToTableRows(json)
        assertEquals(listOf("q", "ok"), rows.map { it.key })
        assertEquals("hello", rows.first { it.key == "q" }.value)
        assertTrue(json.contains("\"ok\""))
        assertTrue(!json.contains("page"))
    }

    @Test
    fun safeViewModes_fallBackOnUnknown() {
        assertEquals(JsonViewMode.JSON, safeJsonViewMode("nope"))
        assertEquals(JsonViewMode.TABLE, safeJsonViewMode(JsonViewMode.TABLE.name))
        assertEquals(ResponseViewMode.JSON, safeResponseViewMode(null))
        assertEquals(ResponseViewMode.TABLE, safeResponseViewMode(ResponseViewMode.TABLE.name))
    }

    @Test
    fun prettyPrintJson_skipsHugePayload() {
        val huge = "x".repeat(MAX_JSON_PRETTY_CHARS + 8)
        assertEquals(huge, prettyPrintJson(huge))
    }
}
