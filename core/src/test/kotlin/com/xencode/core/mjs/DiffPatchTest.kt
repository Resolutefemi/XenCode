package com.xencode.core.mjs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** DiffPatch: hunk application, context verification, fuzz and rejections. */
class DiffPatchTest {

    @Test
    fun apply_simpleModification() {
        val current = "alpha\nbeta\ngamma\n"
        val diff = """
            --- a/f.txt
            +++ b/f.txt
            @@ -1,3 +1,3 @@
             alpha
            -beta
            +BETA
             gamma
        """.trimIndent()
        val (next, result) = DiffPatch.applyToString(current, diff)
        assertTrue(result is DiffPatch.Result.Applied, result.toString())
        assertEquals("alpha\nBETA\ngamma\n", next)
    }

    @Test
    fun apply_insertionAndDeletion() {
        val current = "one\nthree\n"
        val diff = """
            @@ -1,2 +1,3 @@
             one
            +two
             three
        """.trimIndent()
        val (next, result) = DiffPatch.applyToString(current, diff)
        assertTrue(result is DiffPatch.Result.Applied, result.toString())
        assertEquals("one\ntwo\nthree\n", next)

        val delDiff = """
            @@ -1,3 +1,2 @@
             one
            -two
             three
        """.trimIndent()
        val (next2, result2) = DiffPatch.applyToString(next, delDiff)
        assertTrue(result2 is DiffPatch.Result.Applied, result2.toString())
        assertEquals("one\nthree\n", next2)
    }

    @Test
    fun apply_rejectsStalePatch() {
        val diff = """
            @@ -1,2 +1,2 @@
            -totally
            +different
             alpha
        """.trimIndent()
        val (next, result) = DiffPatch.applyToString("alpha\nbeta\n", diff)
        assertTrue(result is DiffPatch.Result.Rejected, result.toString())
        assertEquals("alpha\nbeta\n", next) // unchanged
        assertTrue((result as DiffPatch.Result.Rejected).reason.contains("not found") || result.reason.contains("match"), result.reason)
    }

    @Test
    fun apply_fuzzWindowToleratesOffsetDrift() {
        // Hunk claims old line 1 but the target text is at line 2 — within the +/-3 fuzz.
        val current = "intro line\nalpha\nbeta\ngamma\n"
        val diff = """
            @@ -1,3 +1,3 @@
             alpha
            -beta
            +BETA
             gamma
        """.trimIndent()
        val (next, result) = DiffPatch.applyToString(current, diff)
        assertTrue(result is DiffPatch.Result.Applied, result.toString())
        assertEquals("intro line\nalpha\nBETA\ngamma\n", next)
    }

    @Test
    fun apply_multipleHunksSequentially() {
        val current = (1..10).joinToString("\n") { "line$it" } + "\n"
        val diff = """
            @@ -1,2 +1,2 @@
            -line1
            +LINE1
             line2
            @@ -9,2 +9,2 @@
             line9
            -line10
            +LINE10
        """.trimIndent()
        val (next, result) = DiffPatch.applyToString(current, diff)
        assertTrue(result is DiffPatch.Result.Applied, result.toString())
        assertEquals(2, (result as DiffPatch.Result.Applied).hunks)
        assertTrue(next.contains("LINE1") && next.contains("LINE10"), next)
        assertTrue(next.contains("line5"), next)
    }

    @Test
    fun apply_noHunksRejected() {
        val (_, result) = DiffPatch.applyToString("x", "not a diff")
        assertTrue(result is DiffPatch.Result.Rejected)
    }
}
