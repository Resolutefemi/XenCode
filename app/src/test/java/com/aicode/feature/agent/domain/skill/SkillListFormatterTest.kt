package com.aicode.feature.agent.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillListFormatterTest {

    private fun skill(name: String, description: String = "desc") =
        Skill(name = name, description = description, instructions = "body")

    @Test
    fun emptyList_returnsEmptyString() {
        assertEquals("", SkillListFormatter.format(emptyList()))
    }

    @Test
    fun zeroBudget_returnsEmptyString() {
        assertEquals("", SkillListFormatter.format(listOf(skill("a")), charBudget = 0))
    }

    @Test
    fun smallList_keepsFullEntries() {
        val skills = listOf(skill("a", "甲"), skill("b", "乙"))
        val out = SkillListFormatter.format(skills, charBudget = 1000)
        assertTrue(out.contains("- a: 甲"))
        assertTrue(out.contains("- b: 乙"))
        assertFalse(out.contains("omitted due to prompt budget"))
        assertEquals(2, out.lines().size)
    }

    @Test
    fun blankDescription_usesPlaceholder() {
        val out = SkillListFormatter.format(listOf(skill("a", "")), charBudget = 1000)
        assertTrue(out.contains("- a: (no description)"))
    }

    @Test
    fun budgetExhausted_degradesToNameOnly_thenOmitsWithNotice() {
        // 40 equal-length skills, budget 600: full entries fill first, then names-only, then omit + notice.
        val skills = (1..40).map { skill("skill-%02d".format(it), "描".repeat(30)) }
        val budget = 600
        val out = SkillListFormatter.format(skills, charBudget = budget)

        val fullLines = out.lines().filter { it.startsWith("- skill-") && it.contains(": ") }
        val nameLines = out.lines().filter { it.startsWith("- skill-") && !it.contains(": ") }
        assertTrue("full entries should consume budget first", fullLines.isNotEmpty())
        assertTrue("should fall back to names-only after budget is exhausted", nameLines.isNotEmpty())

        val omitted = skills.size - fullLines.size - nameLines.size
        assertTrue("some skills should be omitted", omitted > 0)
        assertTrue(out.contains("$omitted more skill"))
        assertTrue(out.contains("~/.aicode/skills/"))
        assertTrue(out.contains("loadSkill"))
        assertTrue("output length ${out.length} exceeds budget $budget", out.length <= budget)

        val lastFull = out.lines().indexOfLast { it.startsWith("- skill-") && it.contains(": ") }
        val firstName = out.lines().indexOfFirst { it.startsWith("- skill-") && !it.contains(": ") }
        assertTrue("full entries must come before names-only", firstName > lastFull)
    }

    @Test
    fun tinyBudget_onlyNoticeAndStaysBounded() {
        val skills = (1..10).map { skill("skill-$it", "描述") }
        val out = SkillListFormatter.format(skills, charBudget = 200)
        assertTrue(out.contains("omitted due to prompt budget"))
        assertTrue(out.contains("${skills.size} more skill"))
        assertTrue("output length ${out.length} exceeds budget 200", out.length <= 200)
    }

    @Test
    fun defaultBudget_fitsRealWorldScale() {
        // Device-scale: 118 skills, ~131 chars per description; raw list was 15441 chars before this.
        val skills = (1..118).map { skill("builtin-skill-$it", "描".repeat(131)) }
        val out = SkillListFormatter.format(skills)
        assertTrue(out.length <= SkillListFormatter.DEFAULT_CHAR_BUDGET)
        assertTrue(out.contains("omitted due to prompt budget"))
    }

    @Test
    fun allEntriesFit_nothingOmitted() {
        val skills = (1..50).map { skill("s$it", "短描述") }
        val out = SkillListFormatter.format(skills)
        assertFalse(out.contains("omitted due to prompt budget"))
        assertEquals(50, out.lines().size)
    }
}
