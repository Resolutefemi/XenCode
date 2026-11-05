package com.xencode.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Token-level coverage for the goal grammar. Cases mirror the Android
 * /goal parser (AIAgentViewModel.manageGoals) one by one.
 */
class GoalParsingTest {

    @Test
    fun emptyAndListVerbsMeanList() {
        assertEquals(GoalRequest.Listing, parseGoalArgs(emptyList()))
        assertEquals(GoalRequest.Listing, parseGoalArgs(listOf("list")))
        assertEquals(GoalRequest.Listing, parseGoalArgs(listOf("LIST")))
        assertEquals(GoalRequest.Listing, parseGoalArgs(listOf("ls")))
    }

    @Test
    fun addPlainTitle() {
        val r = parseGoalArgs(listOf("add", "Write", "tests", "for", "core"))
        isAdd(r) { title, deps, note ->
            assertEquals("Write tests for core", title)
            assertEquals(emptyList(), deps)
            assertNull(note)
        }
    }

    @Test
    fun addWithAfterAndNote() {
        val r = parseGoalArgs(
            listOf("add", "Ship", "it", "after", "G1", "g2", "note", "blocked", "until", "review")
        )
        isAdd(r) { title, deps, note ->
            assertEquals("Ship it", title)
            assertEquals(listOf("g1", "g2"), deps) // ids lowercased
            assertEquals("blocked until review", note)
        }
    }

    @Test
    fun addNoteOnly() {
        val r = parseGoalArgs(listOf("add", "T", "note", "hello"))
        isAdd(r) { title, deps, note ->
            assertEquals("T", title)
            assertEquals(emptyList(), deps)
            assertEquals("hello", note)
        }
    }

    @Test
    fun addAfterOnly() {
        val r = parseGoalArgs(listOf("add", "Second", "after", "g1"))
        isAdd(r) { title, deps, note ->
            assertEquals("Second", title)
            assertEquals(listOf("g1"), deps)
            assertNull(note)
        }
    }

    @Test
    fun addEmptyTitleWhenNoteIsFirstToken() {
        // `add note hi` -> title is empty (the engine rejects it later).
        val r = parseGoalArgs(listOf("add", "note", "hi"))
        isAdd(r) { title, deps, note ->
            assertEquals("", title)
            assertEquals(emptyList(), deps)
            assertEquals("hi", note)
        }
    }

    @Test
    fun addNoteBeforeAfterDoesNotCrash() {
        // Android computes subList(after+1, noteIdx) with from > to here and
        // crashes; the CLI parser must not.
        val r = parseGoalArgs(listOf("add", "T", "note", "x", "after", "g1"))
        isAdd(r) { title, deps, note ->
            assertEquals("T", title)
            assertEquals(listOf("g1"), deps)
            assertEquals("x after g1", note)
        }
    }

    @Test
    fun byIdVerbsParseIdAndNote() {
        val done = parseGoalArgs(listOf("done", "G3", "note", "works", "well"))
        isById(done) { verb, id, note ->
            assertEquals("done", verb)
            assertEquals("g3", id)
            assertEquals("works well", note)
        }
        val fail = parseGoalArgs(listOf("fail", "g2"))
        isById(fail) { verb, id, note ->
            assertEquals("fail", verb)
            assertEquals("g2", id)
            assertNull(note)
        }
        val start = parseGoalArgs(listOf("start", "G1", "note", "now"))
        isById(start) { verb, id, note ->
            assertEquals("start", verb)
            assertEquals("g1", id)
            assertEquals("now", note)
        }
    }

    @Test
    fun byIdWithoutIdIsMissingId() {
        for (verb in listOf("done", "fail", "retry", "start", "drop")) {
            assertEquals(GoalRequest.MissingId(verb), parseGoalArgs(listOf(verb)))
        }
    }

    @Test
    fun unknownVerbIsReported() {
        val r = parseGoalArgs(listOf("bogus", "x"))
        val unknown = assertIs<GoalRequest.Unknown>(r)
        assertEquals("bogus", unknown.verb)
    }

    // ---- helpers ----

    private fun isAdd(
        r: GoalRequest,
        check: (title: String, deps: List<String>, note: String?) -> Unit
    ) {
        val add = assertIs<GoalRequest.Add>(r)
        check(add.title, add.deps, add.note)
    }

    private fun isById(
        r: GoalRequest,
        check: (verb: String, id: String, note: String?) -> Unit
    ) {
        val byId = assertIs<GoalRequest.ById>(r)
        check(byId.verb, byId.id, byId.note)
    }
}
