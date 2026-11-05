package com.xencode.desktop

import com.xencode.core.goal.GoalStatus
import com.xencode.core.telemetry.XpLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-renderer tests for [Formatters]: goal glyphs, progress lines, waiting-on
 * sublines, id parsing, tool lines, API-key masking and the XP curve math.
 */
class FormattersTest {

    @Test
    fun statusGlyphsMatchTheSharedContract() {
        assertEquals("✓", Formatters.statusGlyph(GoalStatus.DONE))
        assertEquals("●", Formatters.statusGlyph(GoalStatus.ACTIVE))
        assertEquals("▸", Formatters.statusGlyph(GoalStatus.READY))
        assertEquals("·", Formatters.statusGlyph(GoalStatus.PENDING))
        assertEquals("✗", Formatters.statusGlyph(GoalStatus.FAILED))
        assertEquals("⊘", Formatters.statusGlyph(GoalStatus.CANCELLED))
    }

    @Test
    fun percentTreatsEmptyBoardAsComplete() {
        assertEquals(100, Formatters.percent(0, 0))
        assertEquals(0, Formatters.percent(0, 4))
        assertEquals(40, Formatters.percent(2, 5))
        assertEquals("2/5 done (40%)", Formatters.progressLine(2, 5))
    }

    @Test
    fun waitingOnLineListsBlockersOrNull() {
        assertNull(Formatters.waitingOnLine(emptyList()))
        assertEquals(
            "waiting on g1 (Set up repo), g3 (Write tests)",
            Formatters.waitingOnLine(listOf("g1 (Set up repo)", "g3 (Write tests)"))
        )
    }

    @Test
    fun parseIdsNormalizesSeparatorSoup() {
        assertEquals(listOf("g1", "g3", "g5"), Formatters.parseIds("G1 g3, g5"))
        assertEquals(listOf("g2"), Formatters.parseIds(" g2; "))
        assertEquals(emptyList(), Formatters.parseIds("   "))
    }

    @Test
    fun toolLineIsMonospaceFriendly() {
        assertEquals(
            "[tool] read_file {\"path\":\"README.md\"}",
            Formatters.toolLine("read_file", "{\"path\":\"README.md\"}")
        )
    }

    @Test
    fun maskApiKeyShowsFirstSixCharsOnly() {
        assertEquals("sk-abc...", Formatters.maskApiKey("sk-abcdefghijklmnopqrstuvwxyz"))
        assertEquals("123456...", Formatters.maskApiKey("1234567"))
        assertEquals("none...", Formatters.maskApiKey("none"))
    }

    @Test
    fun levelProgressFractionFollowsTheCurve() {
        // XpMath: level L ends at 100 * L^2 XP, so level 0 -> 100, level 1 -> 400.
        assertEquals(0f, Formatters.levelProgressFraction(0L))
        assertEquals(0f, Formatters.levelProgressFraction(100L))
        assertEquals(0.5f, Formatters.levelProgressFraction(250L))
        assertTrue(Formatters.levelProgressFraction(399L) > 0.99f)
    }

    @Test
    fun eventLineRendersKindAndLabel() {
        assertEquals(
            "+50 GOAL_COMPLETED — Ship v1",
            Formatters.eventLine(XpLedger.Event("GOAL_COMPLETED", 50L, "Ship v1", 0L))
        )
    }

    @Test
    fun streakHintTracksStages() {
        assertEquals("Earn XP today to start a streak.", Formatters.streakHint(0))
        assertEquals("Come back tomorrow to grow your streak (bonus starts on day 2).", Formatters.streakHint(1))
        assertEquals("Keep it up — the current daily streak bonus is +20 XP.", Formatters.streakHint(2))
        assertEquals("Keep it up — the current daily streak bonus is +50 XP.", Formatters.streakHint(9))
    }

    @Test
    fun eventTimeUsesEnglishMonths() {
        // The clock depends on the host time zone, but the "Mon d, HH:mm" shape
        // with an English month abbreviation must hold everywhere.
        val rendered = Formatters.eventTime(1_782_880_700_000L)
        assertTrue(
            Regex("^[A-Z][a-z]{2} \\d{1,2}, \\d{2}:\\d{2}$").matches(rendered),
            "got: $rendered"
        )
    }
}
