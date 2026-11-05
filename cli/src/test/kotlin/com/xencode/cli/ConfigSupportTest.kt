package com.xencode.cli

import com.xencode.core.provider.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Config helpers: key masking, --header parsing, and the ProviderConfig.validate
 * wiring used by `xencode config add` before anything is persisted.
 */
class ConfigSupportTest {

    // ---- masking ----

    @Test
    fun maskKeyShowsFirstSixCharsPlusEllipsis() {
        assertEquals("sk-pro...", maskKey("sk-project-abcdef123456"))
        assertEquals("none...", maskKey("none")) // short local-endpoint placeholder, fully shown
        assertEquals("abcdef...", maskKey("abcdefghij"))
        assertEquals("...", maskKey(""))
    }

    // ---- header parsing ----

    @Test
    fun headerPairsHappyPath() {
        val (map, err) = parseHeaderPairs(listOf("HTTP-Referer=https://my.site", "XTitle=My App"))
        assertNull(err)
        assertEquals(mapOf("HTTP-Referer" to "https://my.site", "XTitle" to "My App"), map)
    }

    @Test
    fun headerPairsRejectMissingEqualsEmptyKeyAndDuplicates() {
        val noEquals = parseHeaderPairs(listOf("bare"))
        assertTrue(noEquals.second!!.contains("--header expects k=v form"))
        val emptyKey = parseHeaderPairs(listOf("=value"))
        assertTrue(emptyKey.second!!.contains("--header expects k=v form"))
        val emptyValue = parseHeaderPairs(listOf("k="))
        assertTrue(emptyValue.second!!.contains("--header expects k=v form"))
        val dup = parseHeaderPairs(listOf("k=1", "k=2"))
        assertTrue(dup.second!!.contains("duplicate --header key"))
        // Blank entries are skipped rather than rejected.
        val (map, err2) = parseHeaderPairs(listOf("", "   ", "a=b"))
        assertNull(err2)
        assertEquals(mapOf("a" to "b"), map)
    }

    // ---- provider validation wiring ----

    private fun provider(
        name: String = "zai",
        kind: String = "openai",
        baseUrl: String = "https://api.z.ai/api/v1",
        apiKey: String = "sk-test",
        model: String = "glm-4.6",
        temperature: Double? = null,
        maxTokens: Int? = null,
        headers: Map<String, String> = emptyMap()
    ): ProviderConfig = buildProvider(name, kind, baseUrl, apiKey, model, temperature, maxTokens, headers)

    @Test
    fun validOpenAiStylePasses() {
        assertNull(provider().validate())
    }

    @Test
    fun validAnthropicStylePasses() {
        assertNull(provider(kind = "anthropic", baseUrl = "https://api.anthropic.com", model = "claude-sonnet-4-5").validate())
    }

    @Test
    fun buildProviderTrimsAndNormalizesKind() {
        val p = provider(name = "  zai ", kind = " OpenAI ", baseUrl = " https://api.z.ai ", apiKey = " k ", model = " m ")
        assertEquals("zai", p.name)
        assertEquals("openai", p.kind)
        assertEquals("https://api.z.ai", p.baseUrl)
        assertEquals("k", p.apiKey)
        assertEquals("m", p.model)
        assertNull(p.validate())
    }

    @Test
    fun validateRejectsBadKindUrlKeyAndModel() {
        assertTrue(provider(kind = "grok").validate()!!.contains("kind must be openai, anthropic or gemini"))
        assertTrue(provider(kind = "gemini").validate() == null) // first-class since the Gemini protocol landed
        assertTrue(provider(baseUrl = "ftp://x").validate()!!.contains("baseUrl must start with http(s)://"))
        assertTrue(provider(baseUrl = " ").validate()!!.contains("baseUrl is empty"))
        assertTrue(provider(apiKey = "").validate()!!.contains("apiKey is empty"))
        assertTrue(provider(model = "").validate()!!.contains("model is empty"))
        assertTrue(provider(name = "").validate()!!.contains("provider name is empty"))
    }

    @Test
    fun duplicateNamesAreDetectedCaseInsensitively() {
        val existing = listOf(provider(name = "zai"))
        assertTrue(isDuplicateName(existing, "zai"))
        assertTrue(isDuplicateName(existing, "  ZAI "))
        assertFalse(isDuplicateName(existing, "openrouter"))
    }

    @Test
    fun extraHeadersSurviveTheRoundTrip() {
        val (headers, err) = parseHeaderPairs(listOf("HTTP-Referer=https://my.site", "XTitle=My App"))
        assertNull(err)
        val p = provider(headers = headers)
        assertEquals(mapOf("HTTP-Referer" to "https://my.site", "XTitle" to "My App"), p.extraHeaders)
        assertNull(p.validate()) // extra headers never block validation
    }
}
