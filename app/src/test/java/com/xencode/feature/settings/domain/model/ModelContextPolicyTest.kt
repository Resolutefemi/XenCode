package com.xencode.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelContextPolicyTest {

    // ---------- preserveRecentTokens: usableTokens / 4, then clamped to [2000, 20000] ----------

    @Test
    fun preserveRecentTokens_zero_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(0))
    }

    /** Below the floor (under 2000 after division) it is clamped to the floor. */
    @Test
    fun preserveRecentTokens_belowMinimum_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(7_999))
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(1))
    }

    /** Exactly at the floor: no clamp needed. */
    @Test
    fun preserveRecentTokens_atMinimum_boundary() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(8_000))
    }

    /** Within the range it divides by 4 normally. */
    @Test
    fun preserveRecentTokens_inRange_quarterDown() {
        assertEquals(2_500, ModelContextPolicy.preserveRecentTokens(10_000))
        assertEquals(19_999, ModelContextPolicy.preserveRecentTokens(79_999))
    }

    /** Upper boundary: exactly 20000 after division, and slightly over (truncated division still 20000). */
    @Test
    fun preserveRecentTokens_atMaximum_boundary() {
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(80_000))
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(80_001))
    }

    /** Above the ceiling it clamps to the ceiling, including huge values and Int.MAX_VALUE. */
    @Test
    fun preserveRecentTokens_aboveMaximum_clampedToMaximum() {
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(128_000))
        assertEquals(20_000, ModelContextPolicy.preserveRecentTokens(Int.MAX_VALUE))
    }

    /** Negatives (theoretically impossible) are also clamped to the floor. */
    @Test
    fun preserveRecentTokens_negative_clampedToMinimum() {
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(-1))
        assertEquals(2_000, ModelContextPolicy.preserveRecentTokens(Int.MIN_VALUE))
    }

    // ---------- estimateTokens: (chars + 3) / 4 rounded up ----------

    @Test
    fun estimateTokens_zero_isZero() {
        assertEquals(0, ModelContextPolicy.estimateTokens(0))
    }

    /** Exact multiple of 4: no rounding up needed. */
    @Test
    fun estimateTokens_exactMultiple() {
        assertEquals(1, ModelContextPolicy.estimateTokens(4))
        assertEquals(25, ModelContextPolicy.estimateTokens(100))
    }

    /** With a remainder it rounds up. */
    @Test
    fun estimateTokens_roundsUp() {
        assertEquals(1, ModelContextPolicy.estimateTokens(1))
        assertEquals(2, ModelContextPolicy.estimateTokens(5))
        assertEquals(26, ModelContextPolicy.estimateTokens(101))
    }

    /** Huge values must not overflow (Int.MAX_VALUE cannot be used: +3 would integer-overflow to negative). */
    @Test
    fun estimateTokens_largeValue() {
        assertEquals(250_000_000, ModelContextPolicy.estimateTokens(1_000_000_000))
    }
}