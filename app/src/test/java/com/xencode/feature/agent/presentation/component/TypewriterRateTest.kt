package com.xencode.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typewriter rate and drain behavior.
 *
 * Focus is on two pitfalls hit before:
 * 1. The display rate was hard-capped, so lag only grew when the model streamed fast (the old implementation's steady-state lag was ~1.8× the upstream rate,
 *    often a hundred-plus chars), and the whole segment popped out at once when upstream ended — hence the assertions here that lag stays bounded at typical upstream rates;
 * 2. When upstream ends, the remainder must not complete in one frame: the leftover text types out evenly within constant-order time, no dragging.
 */
class TypewriterRateTest {

    private companion object {
        /** Frame interval for the simulation (seconds): 60fps. */
        const val FRAME_SEC = 1f / 60f

        /** Typical upstream output rates (chars/sec): from slow models to bursty output. */
        val TYPICAL_UPSTREAM_RATES = listOf(20f, 50f, 100f, 200f)
    }

    /**
     * Simulates lag convergence with "constant-rate upstream + per-frame display advance" and returns the steady-state lag (chars) after 10 seconds.
     * Isomorphic to the UI implementation: upstream accumulates per frame, display advances via [typewriterRate] and caps at upstream progress.
     */
    private fun steadyStateLag(upstreamCps: Float): Float {
        var arrived = 0f
        var shown = 0f
        repeat(600) {
            arrived += upstreamCps * FRAME_SEC
            val lag = arrived - shown
            if (lag > 0f) {
                val rate = typewriterRate(lag = lag, arrivalRate = upstreamCps)
                shown = minOf(arrived, shown + rate * FRAME_SEC)
            }
        }
        return arrived - shown
    }

    /** Frames needed to type out [lag] chars evenly at [typewriterDrainRate]. */
    private fun drainFrames(lag: Float): Int {
        val rate = typewriterDrainRate(lag)
        var remaining = lag
        var frames = 0
        while (remaining > 0f && frames < 10_000) {
            remaining -= rate * FRAME_SEC
            frames++
        }
        return frames
    }

    @Test
    fun noLag_zeroRate() {
        assertEquals(0f, typewriterRate(lag = 0f, arrivalRate = 100f), 0.001f)
        assertEquals(0f, typewriterRate(lag = -5f, arrivalRate = 100f), 0.001f)
    }

    @Test
    fun followsUpstreamRate_soLagCannotGrowUnbounded() {
        // Even with tiny lag and fast upstream, it must nearly keep up: the follow term = upstream rate × 0.95
        val rate = typewriterRate(lag = 1f, arrivalRate = 200f)
        assertTrue("rate=$rate should be at least 95% of the upstream rate", rate >= 200f * 0.95f)
    }

    @Test
    fun lagBeyondTarget_breaksNormalCeiling() {
        // Once lag exceeds the target, breaking the normal ceiling is allowed — this is the key to bounded lag
        val rate = typewriterRate(lag = 40f, arrivalRate = 0f)
        assertTrue("rate=$rate should break the normal ceiling", rate > 220f)
        assertTrue("rate=$rate should not exceed the emergency ceiling", rate <= 1600f)
    }

    @Test
    fun lagWithinTarget_keepsTypingFeel() {
        // With tiny lag it must not rocket: the floor keeps typing visible, the ceiling keeps it from being torn through
        val rate = typewriterRate(lag = 5f, arrivalRate = 0f)
        assertTrue("rate=$rate should not exceed the normal ceiling", rate <= 220f)
        assertTrue("rate=$rate should not fall below the minimum rate", rate >= 30f)
    }

    @Test
    fun lagStaysSmall_atTypicalUpstreamRates() {
        TYPICAL_UPSTREAM_RATES.forEach { upstream ->
            val lag = steadyStateLag(upstream)
            // The old implementation's steady-state lag was ~1.8× upstream (100 chars/sec → ~180 chars); here it must be single to low-double digits
            assertTrue("steady-state lag $lag chars at ${upstream} chars/sec upstream is too large", lag <= 14f)
        }
    }

    @Test
    fun lagStaysBounded_atBurstUpstreamRate() {
        // Bursty output (some channels dump a big chunk at once): lag may be larger, but still bounded
        val lag = steadyStateLag(1000f)
        assertTrue("steady-state lag $lag chars under burst output should be bounded", lag <= 80f)
    }

    @Test
    fun drainFinishesShortLagWithinTargetWindow() {
        // Typical remainders (a dozen to a few dozen chars) should finish near the target window (0.2s), not drag into a visible pause
        listOf(1f, 5f, 12f, 30f, 60f).forEach { lag ->
            val seconds = drainFrames(lag) * FRAME_SEC
            assertTrue("draining $lag remaining chars took $seconds seconds, beyond the target window", seconds <= 0.2f + FRAME_SEC)
        }
    }

    @Test
    fun drainNeverStalls_andHonoursMinRate() {
        // Even with a tiny remainder it must not drop below the minimum rate (otherwise it stalls mid-way waiting for next-frame rounding)
        assertEquals(30f, typewriterDrainRate(0.01f), 0.001f)
        // A large remainder must not flash by in an instant either
        assertEquals(400f, typewriterDrainRate(10_000f), 0.001f)
        assertTrue(drainFrames(1f) in 1..3)
    }

    @Test
    fun drainRateDegradesGracefully_withHugeBacklog() {
        // Extreme case (returning from another page to find hundreds of chars backlogged) must still drain, and no slower than the minimum rate
        val seconds = drainFrames(240f) * FRAME_SEC
        assertTrue("draining 240 remaining chars took $seconds seconds", seconds <= 0.62f)
    }
}
