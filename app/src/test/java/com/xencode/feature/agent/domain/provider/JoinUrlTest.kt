package com.xencode.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class JoinUrlTest {

    /** Trailing slash on base: trimmed before joining to avoid a double slash. */
    @Test
    fun baseTrailingSlash_trimmed() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            joinUrl("https://api.openai.com/v1/", "chat/completions")
        )
    }

    /** base ends and path starts with the same version segment (v1): deduplicated to a single copy. */
    @Test
    fun duplicateVersionSegment_deduplicated() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            joinUrl("https://api.openai.com/v1", "v1/chat/completions")
        )
    }

    /** base ends with v1beta and path also starts with v1beta: deduplicated likewise. */
    @Test
    fun duplicateV1betaSegment_deduplicated() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent",
            joinUrl(
                "https://generativelanguage.googleapis.com/v1beta",
                "v1beta/models/gemini-2.0-flash:generateContent"
            )
        )
    }

    /** base ends with a version segment and path starts with a different one: the base version wins and the path's version segment is dropped. */
    @Test
    fun baseVersionWins_pathVersionDropped() {
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            joinUrl("https://open.bigmodel.cn/api/paas/v4", "v1/chat/completions")
        )
    }

    /** base is v2, path is v1: the base version still wins. */
    @Test
    fun baseV2Wins_pathV1Dropped() {
        assertEquals(
            "https://host/v2/models",
            joinUrl("https://host/v2", "v1/models")
        )
    }

    /** path is only a version segment with nothing after: returns base itself. */
    @Test
    fun versionOnlyPath_returnsBase() {
        assertEquals(
            "https://host/v2",
            joinUrl("https://host/v2", "v1")
        )
    }

    /** base has no version segment: plain join. */
    @Test
    fun plainJoin_pathAppended() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            joinUrl("https://api.deepseek.com", "v1/chat/completions")
        )
    }

    /** host-root base (no sub-path): direct join. */
    @Test
    fun hostOnlyBase_pathAppended() {
        assertEquals(
            "https://host/v1/chat",
            joinUrl("https://host", "v1/chat")
        )
    }

    /** Leading slash on path: trimmed before joining to avoid a double slash. */
    @Test
    fun pathLeadingSlash_trimmed() {
        assertEquals(
            "https://host/v1/chat",
            joinUrl("https://host", "/v1/chat")
        )
    }

    /** Whitespace around base: stripped before joining. */
    @Test
    fun baseWhitespace_trimmed() {
        assertEquals(
            "https://host/v1/chat/completions",
            joinUrl(" https://host/v1 ", "v1/chat/completions")
        )
    }

    /** Version-segment dedupe is case-insensitive and keeps base's original spelling. */
    @Test
    fun duplicateSegment_ignoreCaseKeepsBaseCase() {
        assertEquals(
            "https://host/V1/chat",
            joinUrl("https://host/V1", "v1/chat")
        )
    }
}