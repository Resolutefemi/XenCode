package com.xencode.feature.agent.presentation.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Same-turn continuation" criteria for streaming text: after a page switch / item recycle-and-remount, this decides whether the restored typing progress and
 * thinking timer can carry over, so the focus is on turn-change cases (text got shorter, head changed, stream broke off) that must not carry over.
 */
class StreamContinuityTest {

    private fun seen(text: String): Pair<Int, Int> = text.length to streamHeadFingerprint(text)

    @Test
    fun prefixGrowth_isContinuation() {
        val (chars, head) = seen("Hello, let me take a look first")
        assertTrue(isStreamContinuation("Hello, let me take a look first at the project layout", chars, head))
    }

    @Test
    fun unchangedText_isContinuation() {
        val text = "a stretch of already-delivered reply"
        val (chars, head) = seen(text)
        assertTrue(isStreamContinuation(text, chars, head))
    }

    @Test
    fun shorterText_isNotContinuation() {
        val (chars, head) = seen("the previous turn already produced a long stretch of output")
        assertFalse(isStreamContinuation("a fresh turn just began", chars, head))
    }

    @Test
    fun differentHeadSameLength_isNotContinuation() {
        val (chars, head) = seen("the old turn head")
        assertFalse(isStreamContinuation("the new turn head", chars, head))
    }

    @Test
    fun emptyText_isNotContinuation() {
        val (chars, head) = seen("content already output before the stream broke off")
        assertFalse(isStreamContinuation("", chars, head))
    }

    @Test
    fun nothingSeenYet_isNotContinuation() {
        assertFalse(isStreamContinuation("output just started", 0, 0))
        assertFalse(isStreamContinuation("", 0, 0))
    }

    @Test
    fun growthBeyondSampleWindow_isContinuation() {
        // Seen text keeps growing past the sampling window: only the first 64 chars are compared, still judged a same-turn continuation
        val seenText = "x".repeat(80) + "tail of the prior segment"
        val (chars, head) = seen(seenText)
        assertTrue(isStreamContinuation(seenText + "newly streamed continuation", chars, head))
    }

    @Test
    fun longTextWithDifferentHead_isNotContinuation() {
        val (chars, head) = seen("a".repeat(100))
        assertFalse(isStreamContinuation("b".repeat(120), chars, head))
    }

    @Test
    fun surrogatePairs_measuredInChars() {
        // Fingerprint and length both count chars, so an emoji's surrogate pair cannot flip a same-turn check into a turn change
        val (chars, head) = seen("progress🚀🚀")
        assertTrue(isStreamContinuation("progress🚀🚀more", chars, head))
    }
}
