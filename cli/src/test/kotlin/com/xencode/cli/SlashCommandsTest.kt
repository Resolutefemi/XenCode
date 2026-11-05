package com.xencode.cli

import com.xencode.core.agent.SlashCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Slash-command parsing shared by the CLI REPL and the desktop chat pane. */
class SlashCommandsTest {

    @Test
    fun parse_extractsNameAndArg() {
        val p = SlashCommands.parse("/todo add write the docs")!!
        assertEquals("/todo", p.name)
        assertEquals("add write the docs", p.arg)
        assertTrue(p.hasArg)

        val bare = SlashCommands.parse("/HELP")!!
        assertEquals("/help", bare.name)
        assertEquals("", bare.arg)
        assertFalse(bare.hasArg)
    }

    @Test
    fun parse_nonSlashAndDegenerateForms() {
        assertNull(SlashCommands.parse("just a message"))
        assertNull(SlashCommands.parse("   "))
        assertNull(SlashCommands.parse("/"))
    }

    @Test
    fun help_listsEveryKnownCommand() {
        val help = SlashCommands.help()
        SlashCommands.COMMANDS.forEach { (cmd, _) ->
            assertTrue(help.contains(cmd), "missing $cmd in help")
        }
        assertTrue(help.contains("/decide"))
        assertTrue(help.contains("/radar"))
    }

    @Test
    fun goalParse_runVerb() {
        assertEquals(GoalRequest.Run(2), parseGoalArgs(listOf("run")))
        assertEquals(GoalRequest.Run(4), parseGoalArgs(listOf("run", "--parallel", "4")))
        assertEquals(GoalRequest.Run(3), parseGoalArgs(listOf("run", "parallel", "3")))
        assertEquals(GoalRequest.Run(8), parseGoalArgs(listOf("run", "--parallel", "99"))) // clamped
        assertEquals(GoalRequest.Run(1), parseGoalArgs(listOf("run", "--parallel", "0"))) // clamped
        // other verbs untouched
        assertEquals(GoalRequest.Listing, parseGoalArgs(listOf("list")))
        assertTrue(parseGoalArgs(listOf("add", "title", "after", "g1")) is GoalRequest.Add)
    }
}
