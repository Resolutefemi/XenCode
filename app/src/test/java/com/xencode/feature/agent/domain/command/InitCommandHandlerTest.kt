package com.xencode.feature.agent.domain.command

import com.xencode.R
import com.xencode.feature.agent.domain.prompt.SystemPromptProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The /init command: menu metadata, prompt body source, argument substitution, and execution behavior.
 */
class InitCommandHandlerTest {

    private val promptProvider = mockk<SystemPromptProvider>()
    private val handler = InitCommandHandler(promptProvider)

    @Test
    fun metadata_valuesAreCorrect() {
        assertEquals("init", handler.name)
        assertEquals(R.string.slash_command_init_desc, handler.descriptionRes)
        assertTrue("accepts arguments to carry user focus areas", handler.acceptsArgs)
    }

    @Test
    fun promptName_pointsToAgentFragment() {
        assertEquals("agent/init.md", InitCommandHandler.PROMPT_NAME)
    }

    @Test
    fun execute_substitutesArguments() {
        every { promptProvider.resolvePrompt(InitCommandHandler.PROMPT_NAME) } returns "focus: \$ARGUMENTS"
        val context = mockk<SlashCommandContext>(relaxed = true)

        handler.execute(context, "test")

        verify { context.initProject("focus: test") }
    }

    @Test
    fun execute_withoutArgs_replacesPlaceholderWithEmpty() {
        every { promptProvider.resolvePrompt(InitCommandHandler.PROMPT_NAME) } returns "focus: \$ARGUMENTS"
        val context = mockk<SlashCommandContext>(relaxed = true)

        handler.execute(context, "")

        verify { context.initProject("focus: ") }
    }
}
