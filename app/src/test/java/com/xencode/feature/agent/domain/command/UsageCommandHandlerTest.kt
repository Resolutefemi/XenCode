package com.xencode.feature.agent.domain.command

import com.xencode.R
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The /usage command: menu metadata and execution behavior.
 */
class UsageCommandHandlerTest {

    private val handler = UsageCommandHandler()

    @Test
    fun metadata_valuesAreCorrect() {
        assertEquals("usage", handler.name)
        assertEquals(R.string.slash_command_usage_desc, handler.descriptionRes)
        assertFalse("does not accept arguments by default", handler.acceptsArgs)
    }

    @Test
    fun execute_showsUsage() {
        val context = mockk<SlashCommandContext>(relaxed = true)

        handler.execute(context, "")

        verify { context.showUsage() }
    }
}
