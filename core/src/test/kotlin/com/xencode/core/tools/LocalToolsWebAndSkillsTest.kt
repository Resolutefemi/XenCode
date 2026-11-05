package com.xencode.core.tools

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * web_fetch against a local JDK HttpServer (loopback allowed via the test
 * flag) + the SSRF guard + load_skill wiring. No external network involved.
 */
class LocalToolsWebAndSkillsTest {

    private fun tools(ws: File) = LocalTools(ws, allowLoopbackHttp = true)

    private fun serve(body: String, contentType: String): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}/page"
    }

    @Test
    fun webFetchStripsHtmlAndClips() {
        val ws = createTempDirectory(prefix = "xencode-web").toFile()
        val (server, url) = serve(
            "<html><head><title>t</title><style>.x{color:red}</style></head>" +
                "<body><script>evil()</script><h1>Welcome</h1><p>First&nbsp;paragraph &amp; more</p>" +
                "<p>Second</p></body></html>",
            "text/html; charset=utf-8"
        )
        try {
            val out = tools(ws).execute("web_fetch", """{"url":"$url"}""")
            assertTrue(out.contains("Welcome"), out)
            assertTrue(out.contains("First paragraph & more"), out)
            assertTrue(out.contains("Second"), out)
            assertTrue(!out.contains("<"), "no tags expected: $out")
            assertTrue(!out.contains("evil()"), "script must be dropped: $out")
        } finally {
            server.stop(0)
            ws.deleteRecursively()
        }
    }

    @Test
    fun webFetchBlocksPrivateAndLoopbackTargetsByDefault() {
        val ws = createTempDirectory(prefix = "xencode-web2").toFile()
        try {
            // Without the test flag, loopback is blocked outright.
            val strict = LocalTools(ws)
            assertTrue(strict.execute("web_fetch", """{"url":"http://127.0.0.1:1/x"}""").startsWith("ERROR: blocked"))
            // Non-http schemes and private ranges are blocked even for tests.
            val t = tools(ws)
            assertTrue(t.execute("web_fetch", """{"url":"file:///etc/passwd"}""").startsWith("ERROR: blocked"))
            assertTrue(t.execute("web_fetch", """{"url":"http://10.1.2.3/x"}""").startsWith("ERROR: blocked"))
            assertTrue(t.execute("web_fetch", """{"url":"http://192.168.1.4/x"}""").startsWith("ERROR: blocked"))
            assertTrue(t.execute("web_fetch", """{"url":"ftp://example.com/x"}""").startsWith("ERROR: blocked"))
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun loadSkillListsAndLoadsWorkspaceSkills() {
        val ws = createTempDirectory(prefix = "xencode-skill-tool").toFile()
        try {
            val skills = File(File(ws, ".xencode"), "skills")
            File(File(skills, "greet"), "SKILL.md").apply { parentFile.mkdirs() }.writeText(
                "---\nname: greet\ndescription: Greets the user politely\n---\nSay hello and offer help.\n"
            )
            val t = tools(ws)
            val listed = t.execute("load_skill", "{}")
            assertTrue(listed.contains("greet"), listed)
            assertTrue(listed.contains("Greets the user politely"), listed)
            val body = t.execute("load_skill", """{"name":"greet"}""")
            assertEquals("Say hello and offer help.", body)
            assertTrue(t.execute("load_skill", """{"name":"nope"}""").startsWith("ERROR: no skill named"))
        } finally {
            ws.deleteRecursively()
        }
    }

    @Test
    fun systemPromptListsSkillsWhenPresent() {
        val ws = createTempDirectory(prefix = "xencode-skill-prompt").toFile()
        try {
            val skills = File(File(ws, ".xencode"), "skills")
            File(File(skills, "release"), "SKILL.md").apply { parentFile.mkdirs() }.writeText(
                "---\ndescription: Cuts a release step by step\n---\n1. bump version"
            )
            val prompt = com.xencode.core.prompt.SystemPromptBuilder.build(ws.path, listOf("read_file"))
            assertTrue(prompt.contains("## Skills"), prompt)
            assertTrue(prompt.contains("release: Cuts a release step by step"), prompt)
            assertTrue(prompt.contains("load_skill"), prompt)
        } finally {
            ws.deleteRecursively()
        }
    }
}
