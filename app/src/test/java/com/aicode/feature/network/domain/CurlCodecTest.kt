package com.aicode.feature.network.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurlCodecTest {

    @Test
    fun parseCurlCommand_readsMethodHeadersAndJsonBody() {
        val result = parseCurlCommand(
            """
            curl -X POST 'https://example.com/v1/items?q=one' \
              -H 'Accept: application/json' \
              -H 'X-Token: abc' \
              --data-raw '{"name":"ai"}' \
              -L -k
            """.trimIndent()
        )
        val parsed = (result as CurlParseResult.Success).parsed
        assertEquals(HttpMethod.POST, parsed.method)
        assertTrue(parsed.url.startsWith("https://example.com/v1/items"))
        assertTrue(parsed.queryJson.contains("\"q\""))
        assertTrue(parsed.headersJson.contains("Accept"))
        assertTrue(parsed.bodyJson.contains("\"name\""))
        assertTrue(parsed.followRedirects)
        assertTrue(!parsed.sslVerify)
    }

    @Test
    fun parseCurlCommand_readsBasicAuthAndGetData() {
        val result = parseCurlCommand(
            """curl -u user:secret -G --data 'q=hello' https://example.com/search"""
        )
        val parsed = (result as CurlParseResult.Success).parsed
        assertEquals(HttpMethod.GET, parsed.method)
        assertTrue(parsed.authJson.contains("basic"))
        assertTrue(parsed.authJson.contains("user"))
        assertTrue(parsed.queryJson.contains("hello"))
        assertTrue(isBlankJson(parsed.bodyJson))
    }

    @Test
    fun parseCurlCommand_rejectsEmptyAndMissingUrl() {
        assertTrue(parseCurlCommand("   ") is CurlParseResult.Failure)
        assertTrue(parseCurlCommand("not a curl") is CurlParseResult.Failure)
        assertTrue(parseCurlCommand("curl --request GET") is CurlParseResult.Failure)
    }

    @Test
    fun exportCurlCommand_includesMethodUrlHeadersAndBody() {
        val curl = exportCurlCommand(
            method = HttpMethod.PUT,
            url = "example.com/item",
            queryJson = """{"id":"9"}""",
            headersJson = """{"Accept":"application/json"}""",
            bodyJson = """{"ok":true}""",
            authJson = DEFAULT_AUTH_JSON,
            settings = NetworkClientSettings(followRedirects = true, sslVerify = false)
        )
        assertTrue(curl.startsWith("curl"))
        assertTrue(curl.contains("--request PUT"))
        assertTrue(curl.contains("https://example.com/item"))
        assertTrue(curl.contains("Accept"))
        assertTrue(curl.contains("--data-raw"))
        assertTrue(curl.contains("--location"))
        assertTrue(curl.contains("--insecure"))
    }

    @Test
    fun tokenizeCurl_handlesQuotesAndLineContinuations() {
        val tokens = tokenizeCurl("""curl -H "X-Test: a b" \ --data '{"k":"v"}' https://x.test""")
        assertEquals("curl", tokens.first())
        assertTrue(tokens.contains("X-Test: a b"))
        assertTrue(tokens.contains("""{"k":"v"}"""))
        assertTrue(tokens.contains("https://x.test"))
    }
}
