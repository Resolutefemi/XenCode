package com.xencode.feature.agent.domain.command

import android.content.Context
import com.xencode.R
import com.xencode.feature.agent.domain.skill.Skill
import com.xencode.feature.agent.domain.skill.SkillRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slash command registry: menu merging (built-in commands first + skills after, deduplicated and sorted), send-time resolution (with arguments), and skill prompt construction.
 */
class SlashCommandRegistryTest {

    private val context = mockk<Context>()
    private val skillRepository = mockk<SkillRepository>()

    private fun action(cmdName: String, descRes: Int, takesArgs: Boolean = false) =
        object : ActionCommandHandler {
            override val name = cmdName
            override val descriptionRes = descRes
            override val acceptsArgs = takesArgs
            override fun execute(context: SlashCommandContext, args: String) = Unit
        }

    private fun skill(skillName: String, description: String, instructions: String = "body") =
        Skill(name = skillName, description = description, instructions = instructions)

    private val usage = action("usage", R.string.slash_command_usage_desc)
    private val compress = action("compress", R.string.slash_command_compress_desc)
    private val init = action("init", R.string.slash_command_init_desc)

    private fun registry(vararg handlers: ActionCommandHandler) =
        SlashCommandRegistry(handlers.toSet(), skillRepository, context)

    private fun prepare(registry: SlashCommandRegistry, skills: List<Skill>) {
        every { context.getString(R.string.slash_command_usage_desc) } returns "usage"
        every { context.getString(R.string.slash_command_compress_desc) } returns "compress context"
        every { context.getString(R.string.slash_command_init_desc) } returns "initialize project"
        every { skillRepository.listSkills() } returns skills
        runBlocking { registry.refresh() }
    }

    // ---- Menu merging ----

    @Test
    fun commands_builtinsFirstThenSkills() {
        val registry = registry(usage, compress, init)
        prepare(registry, listOf(skill("pdf-report", "generate PDF report"), skill("aaa", "first")))

        // Built-in commands as one group (alphabetical within the group) first, skills as another group (alphabetical within the group) after; the two groups never interleave.
        assertEquals(
            listOf("compress", "init", "usage", "aaa", "pdf-report"),
            registry.commands.value.map { it.name }
        )
    }

    @Test
    fun commands_builtinWinsOverSameNameSkill() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("usage", "same-name skill")))

        val matched = registry.commands.value.filter { it.name == "usage" }
        assertEquals(1, matched.size)
        assertEquals(SlashCommandKind.ACTION, matched.single().kind)
    }

    @Test
    fun commands_marksKindsAndArgs() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("pdf-report", "generate PDF")))

        val builtin = registry.commands.value.first { it.name == "usage" }
        assertEquals(SlashCommandKind.ACTION, builtin.kind)
        assertEquals(false, builtin.acceptsArgs)

        val skillCmd = registry.commands.value.first { it.name == "pdf-report" }
        assertEquals(SlashCommandKind.SKILL, skillCmd.kind)
        assertTrue(skillCmd.acceptsArgs)
    }

    // ---- Resolution ----

    @Test
    fun resolve_builtinExact() {
        val registry = registry(usage)
        prepare(registry, emptyList())

        val resolved = registry.resolve("/usage")
        assertTrue(resolved is SlashCommandRegistry.ResolvedCommand.Action)
        assertEquals("usage", resolved!!.name)
        assertEquals("", resolved.args)
    }

    @Test
    fun resolve_builtinWithArgsRejectedWhenArgsNotAccepted() {
        val registry = registry(usage)
        prepare(registry, emptyList())

        assertNull(registry.resolve("/usage now"))
    }

    @Test
    fun resolve_argAcceptingBuiltinKeepsArgs() {
        val registry = registry(action("echo", R.string.slash_command_usage_desc, takesArgs = true))
        prepare(registry, emptyList())

        val resolved = registry.resolve("/echo  hello world ")
        assertTrue(resolved is SlashCommandRegistry.ResolvedCommand.Action)
        assertEquals("hello world", resolved!!.args)
    }

    @Test
    fun resolve_skillCaseInsensitiveWithArgs() {
        val registry = registry()
        prepare(registry, listOf(skill("pdf-report", "generate PDF", instructions = "make \$ARGUMENTS")))

        val resolved = registry.resolve("/PDF-Report a4")
        assertTrue(resolved is SlashCommandRegistry.ResolvedCommand.SkillCommand)
        assertEquals("pdf-report", resolved!!.name)
        assertEquals("a4", resolved.args)
    }

    @Test
    fun resolve_unknownOrNonSlash_returnsNull() {
        val registry = registry(usage)
        prepare(registry, listOf(skill("pdf-report", "generate PDF")))

        assertNull(registry.resolve("/nope"))
        assertNull(registry.resolve("hello"))
        assertNull(registry.resolve("/"))
    }

    // ---- Skill prompts ----

    @Test
    fun buildSkillPrompt_replacesPlaceholder() {
        val s = skill("x", "d", instructions = "run with \$ARGUMENTS now")
        assertEquals("run with a4 now", SlashCommandRegistry.buildSkillPrompt(s, "a4"))
    }

    @Test
    fun buildSkillPrompt_appendsArgsWhenNoPlaceholder() {
        val s = skill("x", "d", instructions = "do it")
        assertEquals("do it\n\nAdditional user input: a4", SlashCommandRegistry.buildSkillPrompt(s, "a4"))
    }

    @Test
    fun buildSkillPrompt_noArgsKeepsBody() {
        val s = skill("x", "d", instructions = "do it")
        assertEquals("do it", SlashCommandRegistry.buildSkillPrompt(s, ""))
    }
}
