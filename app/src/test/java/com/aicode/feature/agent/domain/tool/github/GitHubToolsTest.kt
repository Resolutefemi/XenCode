package com.aicode.feature.agent.domain.tool.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolsTest {

    // ── parseRemoteUrl ──

    @Test
    fun parseRemoteUrl_https() {
        assertEquals("owner" to "repo", GitHubApi.parseRemoteUrl("https://github.com/owner/repo.git"))
    }

    @Test
    fun parseRemoteUrl_https_withoutGitSuffix() {
        assertEquals("owner" to "repo", GitHubApi.parseRemoteUrl("https://github.com/owner/repo"))
    }

    @Test
    fun parseRemoteUrl_https_extraSegments() {
        assertEquals(
            "owner" to "repo",
            GitHubApi.parseRemoteUrl("https://github.com/owner/repo/pull/123?diff=split")
        )
    }

    @Test
    fun parseRemoteUrl_scpSyntax() {
        assertEquals("owner" to "repo", GitHubApi.parseRemoteUrl("git@github.com:owner/repo.git"))
    }

    @Test
    fun parseRemoteUrl_sshSyntax() {
        assertEquals("owner" to "repo", GitHubApi.parseRemoteUrl("ssh://git@github.com/owner/repo.git"))
    }

    @Test
    fun parseRemoteUrl_gitProtocol() {
        assertEquals("owner" to "repo", GitHubApi.parseRemoteUrl("git://github.com/owner/repo.git"))
    }

    @Test
    fun parseRemoteUrl_caseInsensitiveHost() {
        assertEquals("Owner" to "Repo", GitHubApi.parseRemoteUrl("https://GitHub.com/Owner/Repo.git"))
    }

    @Test
    fun parseRemoteUrl_nonGithubHostReturnsNull() {
        assertNull(GitHubApi.parseRemoteUrl("https://gitlab.com/owner/repo.git"))
        assertNull(GitHubApi.parseRemoteUrl("git@gitlab.com:owner/repo.git"))
    }

    @Test
    fun parseRemoteUrl_invalidInputs() {
        assertNull(GitHubApi.parseRemoteUrl(""))
        assertNull(GitHubApi.parseRemoteUrl("https://github.com/only-one-segment"))
        assertNull(GitHubApi.parseRemoteUrl("not a url"))
    }

    // ── arg helpers ──

    @Test
    fun argString_trimsAndAcceptsOnlyNonBlank() {
        val args = mapOf(
            "a" to Json.parseToJsonElement("\"  value  \""),
            "b" to Json.parseToJsonElement("\"   \"")
        )
        assertEquals("value", GitHubApi.argString(args, "a"))
        assertNull(GitHubApi.argString(args, "b"))
        assertNull(GitHubApi.argString(args, "missing"))
    }

    @Test
    fun argInt_parsesDigits() {
        val args = mapOf("n" to Json.parseToJsonElement("42"))
        assertEquals(42, GitHubApi.argInt(args, "n"))
        assertNull(GitHubApi.argInt(args, "missing"))
    }

    // ── summarizers ──

    @Test
    fun summarizeIssue_selectsRelevantFields() {
        val raw = """
            {
              "number": 7,
              "title": "Crash on start",
              "state": "open",
              "body": "long body",
              "user": {"login": "alice"},
              "labels": [{"name": "bug"}, {"name": "ui"}],
              "comments": 2,
              "created_at": "2026-01-01T00:00:00Z",
              "updated_at": "2026-01-02T00:00:00Z",
              "html_url": "https://github.com/owner/repo/issues/7"
            }
        """.trimIndent()
        val summarized = GitHubApi.summarizeIssue(Json.parseToJsonElement(raw).jsonObject)
        assertEquals(7, summarized["number"]?.toString()?.toInt())
        assertEquals("\"Crash on start\"", summarized["title"].toString())
        assertEquals(false, summarized["is_pull_request"]?.toString()?.toBoolean())
        assertEquals("\"alice\"", summarized["user"].toString())
        assertTrue(summarized["labels"].toString().contains("bug"))
    }

    @Test
    fun summarizePull_extractsHeadAndBase() {
        val raw = """
            {
              "number": 12,
              "title": "Add feature",
              "state": "open",
              "draft": false,
              "merged": false,
              "mergeable": true,
              "body": "desc",
              "user": {"login": "bob"},
              "head": {"ref": "feat", "sha": "abc123"},
              "base": {"ref": "main"},
              "changed_files": 3,
              "html_url": "https://github.com/owner/repo/pull/12"
            }
        """.trimIndent()
        val summarized = GitHubApi.summarizePull(Json.parseToJsonElement(raw).jsonObject)
        assertEquals("\"feat\"", summarized["head_ref"].toString())
        assertEquals("\"abc123\"", summarized["head_sha"].toString())
        assertEquals("\"main\"", summarized["base_ref"].toString())
        assertEquals(3, summarized["changed_files"]?.toString()?.toInt())
    }

    @Test
    fun summarizeIssue_defaultsForMissingFields() {
        val summarized = GitHubApi.summarizeIssue(Json.parseToJsonElement("{}").jsonObject)
        assertEquals(-1, summarized["number"]?.toString()?.toInt())
        assertEquals(false, summarized["is_pull_request"]?.toString()?.toBoolean())
        assertEquals("\"\"", summarized["user"].toString())
    }

    // ── excerpt / error mapping ──

    @Test
    fun excerpt_truncatesLongText() {
        assertEquals("", GitHubApi.excerpt(null))
        assertEquals("", GitHubApi.excerpt("   "))
        assertEquals("short", GitHubApi.excerpt("short"))
        val long = "x".repeat(500)
        val result = GitHubApi.excerpt(long, max = 400)
        assertTrue(result.startsWith("x".repeat(400)))
        assertTrue(result.endsWith("[truncated]"))
    }

    @Test
    fun error_buildsActionableMessages() {
        val e401 = GitHubApi.error(401, "{\"message\":\"Bad credentials\"}")
        assertTrue(e401.message.contains("401"))
        assertEquals("GITHUB_HTTP_401", e401.code)

        val e422 = GitHubApi.error(422, "{\"message\":\"Validation Failed\"}")
        assertTrue(e422.message.contains("Validation Failed"))
        assertEquals("GITHUB_HTTP_422", e422.code)

        val e404 = GitHubApi.error(404, "{}")
        assertTrue(e404.message.contains("404"))
    }
}
