package com.xencode.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Line-level LCS diff computation and unified text rendering for [LineDiff].
 *
 * Two implementation details shape the assertion style:
 * 1. During LCS backtracking the removal branch takes priority (dp[i+1][j] >= dp[i][j+1] picks REMOVE);
 * 2. split("\n") makes empty text / a trailing newline each yield an empty-line element, and diff results are asserted against that actual behavior.
 */
class LineDiffTest {

    private fun ctx(text: String) = LineDiff.DiffLine(LineDiff.LineType.CONTEXT, text)
    private fun add(text: String) = LineDiff.DiffLine(LineDiff.LineType.ADD, text)
    private fun del(text: String) = LineDiff.DiffLine(LineDiff.LineType.REMOVE, text)

    @Test
    fun identical_text_produces_only_context_lines() {
        assertEquals(
            listOf(ctx("a"), ctx("b"), ctx("c")),
            LineDiff.diff("a\nb\nc", "a\nb\nc")
        )
    }

    @Test
    fun single_line_identical_is_context() {
        assertEquals(listOf(ctx("hello")), LineDiff.diff("hello", "hello"))
    }

    @Test
    fun pure_insertion_produces_add_lines() {
        // Empty text splits into an empty line: remove it first, then add the new lines one by one
        assertEquals(
            listOf(del(""), add("a"), add("b")),
            LineDiff.diff("", "a\nb")
        )
    }

    @Test
    fun insert_into_middle_produces_add() {
        assertEquals(
            listOf(ctx("a"), add("b"), ctx("c")),
            LineDiff.diff("a\nc", "a\nb\nc")
        )
    }

    @Test
    fun pure_deletion_produces_remove_lines() {
        assertEquals(
            listOf(del("a"), del("b"), add("")),
            LineDiff.diff("a\nb", "")
        )
    }

    @Test
    fun delete_from_middle_produces_remove() {
        assertEquals(
            listOf(ctx("a"), del("b"), ctx("c")),
            LineDiff.diff("a\nb\nc", "a\nc")
        )
    }

    @Test
    fun modified_middle_line_produces_remove_then_add() {
        assertEquals(
            listOf(ctx("a"), del("b"), add("x"), ctx("c")),
            LineDiff.diff("a\nb\nc", "a\nx\nc")
        )
    }

    @Test
    fun adjacent_duplicate_lines_favor_removal() {
        // Removal branch takes priority: once a is removed, b is still a common line
        assertEquals(
            listOf(del("a"), ctx("b")),
            LineDiff.diff("a\nb", "b")
        )
    }

    @Test
    fun both_empty_texts_produce_single_empty_context() {
        assertEquals(listOf(ctx("")), LineDiff.diff("", ""))
    }

    @Test
    fun blank_line_in_middle_is_context() {
        assertEquals(
            listOf(ctx("a"), ctx(""), ctx("b")),
            LineDiff.diff("a\n\nb", "a\n\nb")
        )
    }

    @Test
    fun multi_line_mixed_change() {
        assertEquals(
            listOf(ctx("l1"), del("l2"), del("l3"), add("l2x"), ctx("l4"), add("l5")),
            LineDiff.diff("l1\nl2\nl3\nl4", "l1\nl2x\nl4\nl5")
        )
    }

    @Test
    fun trailing_newline_addition_appears_as_empty_add() {
        assertEquals(
            listOf(ctx("a"), add("")),
            LineDiff.diff("a", "a\n")
        )
    }

    @Test
    fun trailing_newline_removal_appears_as_empty_remove() {
        assertEquals(
            listOf(ctx("a"), del("")),
            LineDiff.diff("a\n", "a")
        )
    }

    @Test
    fun to_unified_uses_prefix_per_line_type() {
        assertEquals(" a\n-b\n+x\n c", LineDiff.toUnified("a\nb\nc", "a\nx\nc"))
    }

    @Test
    fun to_unified_identical_text_uses_space_prefix() {
        assertEquals(" hello", LineDiff.toUnified("hello", "hello"))
    }

    @Test
    fun to_unified_empty_to_multi_line() {
        // Empty text -> multi-line: remove the empty line first (-), then add the new lines one by one
        assertEquals("-\n+a\n+b", LineDiff.toUnified("", "a\nb"))
    }

    @Test
    fun to_unified_trailing_newline() {
        assertEquals(" a\n+", LineDiff.toUnified("a", "a\n"))
    }

    @Test
    fun to_unified_lines_starting_with_plus_minus_are_not_ambiguous() {
        // The marker occupies only the first column: lines whose content starts with +/- can still be restored to the original after rendering
        assertEquals(" -x\n +y", LineDiff.toUnified("-x\n+y", "-x\n+y"))
    }
}