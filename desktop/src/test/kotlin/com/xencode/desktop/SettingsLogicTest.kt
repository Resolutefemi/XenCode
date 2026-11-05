package com.xencode.desktop

import com.xencode.core.provider.PcConfig
import com.xencode.core.provider.ProviderConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-logic tests for the Settings pane: add-provider validation (delegated
 * to [ProviderConfig.validate], one field at a time), optional number parsing,
 * config mutations (add / set-active / remove) and probe rendering. Nothing
 * here touches ~/.xencode or the network.
 */
class SettingsLogicTest {

    private val deepseek = ProviderConfig(
        name = "DeepSeek",
        kind = "openai",
        baseUrl = "https://api.deepseek.com/v1",
        apiKey = "sk-test",
        model = "deepseek-chat"
    )

    // ------------------------------------------------------------------
    // Add-provider form validation
    // ------------------------------------------------------------------

    @Test
    fun blankFormFlagsEveryWireField() {
        val errors = ProviderFormState().fieldErrors(existingNames = emptyList())
        assertEquals("Provider name is empty", errors[ProviderField.NAME])
        assertEquals("baseUrl is empty", errors[ProviderField.BASE_URL])
        assertEquals(
            "apiKey is empty (set one even for local endpoints, e.g. 'none')",
            errors[ProviderField.API_KEY]
        )
        assertEquals("model is empty", errors[ProviderField.MODEL])
        assertEquals(4, errors.size, "got: $errors")
    }

    @Test
    fun validFormHasNoErrorsAndParsesNumbers() {
        val form = ProviderFormState(
            name = "Groq",
            kind = "openai",
            baseUrl = "https://api.groq.com/openai/v1",
            apiKey = "gsk_x",
            model = "llama-3.3-70b-versatile",
            temperature = "0.4",
            maxTokens = "2048"
        )
        assertTrue(form.fieldErrors(existingNames = listOf("DeepSeek")).isEmpty())
        val provider = form.buildProvider()
        assertEquals("Groq", provider.name)
        assertEquals(0.4, provider.temperature!!, 1e-9)
        assertEquals(2048, provider.maxTokens!!)
    }

    @Test
    fun duplicateNamesAreRejectedCaseInsensitively() {
        val form = ProviderFormState(
            name = "deepseek",
            baseUrl = "https://api.deepseek.com/v1",
            apiKey = "sk",
            model = "deepseek-chat"
        )
        assertEquals(
            "A provider named \"deepseek\" already exists",
            form.fieldErrors(existingNames = listOf("DeepSeek"))[ProviderField.NAME]
        )
    }

    @Test
    fun badBaseUrlSurfacesValidateMessage() {
        val form = ProviderFormState(
            name = "X",
            baseUrl = "ftp://nope",
            apiKey = "k",
            model = "m"
        )
        assertEquals(
            "baseUrl must start with http(s)://",
            form.fieldErrors(existingNames = emptyList())[ProviderField.BASE_URL]
        )
    }

    @Test
    fun kindMustBeAWireProtocol() {
        val form = ProviderFormState(
            name = "X",
            kind = "google",
            baseUrl = "https://x.test/v1",
            apiKey = "k",
            model = "m"
        )
        assertEquals(
            "kind must be openai, anthropic or gemini",
            form.fieldErrors(existingNames = emptyList())[ProviderField.KIND]
        )
    }

    @Test
    fun optionalNumbersMustParseWhenPresent() {
        assertNull(temperatureError(""))
        assertNull(temperatureError(" 1.0 "))
        assertEquals("Temperature must be a number, e.g. 0.7", temperatureError("hot"))
        assertEquals("Temperature must be between 0.0 and 2.0", temperatureError("3.5"))
        assertNull(maxTokensError(""))
        assertNull(maxTokensError("8192"))
        assertEquals("Max tokens must be a positive whole number, e.g. 4096", maxTokensError("-5"))
        assertEquals("Max tokens must be a positive whole number, e.g. 4096", maxTokensError("many"))

        val form = ProviderFormState(
            name = "X",
            baseUrl = "https://x.test/v1",
            apiKey = "k",
            model = "m",
            temperature = "hot",
            maxTokens = "-5"
        )
        val errors = form.fieldErrors(existingNames = emptyList())
        assertEquals("Temperature must be a number, e.g. 0.7", errors[ProviderField.TEMPERATURE])
        assertEquals("Max tokens must be a positive whole number, e.g. 4096", errors[ProviderField.MAX_TOKENS])
    }

    // ------------------------------------------------------------------
    // Config mutations
    // ------------------------------------------------------------------

    @Test
    fun addingTheFirstProviderMakesItActive() {
        val next = withAddedProvider(PcConfig(), deepseek)
        assertEquals(1, next.providers.size)
        assertEquals("DeepSeek", next.active)
        assertEquals("DeepSeek", effectiveActiveName(next))
    }

    @Test
    fun addingAnotherProviderKeepsTheActiveOne() {
        val anthropic = deepseek.copy(name = "Anthropic", kind = "anthropic")
        val config = PcConfig(providers = listOf(deepseek), active = "DeepSeek")
        val next = withAddedProvider(config, anthropic)
        assertEquals(2, next.providers.size)
        assertEquals("DeepSeek", next.active)
    }

    @Test
    fun effectiveActiveFallsBackToTheFirstProvider() {
        val config = PcConfig(providers = listOf(deepseek, deepseek.copy(name = "B")))
        assertNull(config.active)
        assertEquals("DeepSeek", effectiveActiveName(config))
    }

    @Test
    fun removingTheActiveProviderPromotesTheNextOne() {
        val config = PcConfig(providers = listOf(deepseek, deepseek.copy(name = "B")), active = "DeepSeek")
        val next = withRemovedProvider(config, "DeepSeek")
        assertEquals(listOf("B"), next.providers.map { it.name })
        assertEquals("B", next.active)
        assertEquals("B", effectiveActiveName(next))
    }

    @Test
    fun removingTheLastProviderClearsActive() {
        val next = withRemovedProvider(PcConfig(providers = listOf(deepseek), active = "DeepSeek"), "DeepSeek")
        assertTrue(next.providers.isEmpty())
        assertNull(next.active)
        assertNull(effectiveActiveName(next))
    }

    @Test
    fun setActiveSwitchesTheRadio() {
        val config = PcConfig(providers = listOf(deepseek, deepseek.copy(name = "B")), active = "DeepSeek")
        val next = withActiveProvider(config, "B")
        assertEquals("B", next.active)
        assertEquals("B", effectiveActiveName(next))
    }

    // ------------------------------------------------------------------
    // Probe rendering
    // ------------------------------------------------------------------

    @Test
    fun probeDescriptionsShowFirstDeltaOrFailure() {
        assertEquals("Connected — first reply: \"ready\"", ConnectionProbe.Connected("ready").describe())
        assertTrue(ConnectionProbe.Connected("ready").ok)

        assertEquals("Failed: HTTP 401: bad key", ConnectionProbe.Failed("HTTP 401: bad key").describe())
        assertTrue(!ConnectionProbe.Failed("HTTP 401: bad key").ok)

        assertTrue(!ConnectionProbe.NoReply.ok)
        assertTrue(ConnectionProbe.NoReply.describe().contains("timeout"))
    }
}
