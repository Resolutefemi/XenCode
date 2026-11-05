package com.xencode.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Literal/comment awareness cases for [SqlScriptSplitter]: a semicolon only splits at statement boundaries,
 * and semicolons inside string literals or comments must be preserved verbatim (the old `split(";")` would cut mid-statement).
 */
class SqlScriptSplitterTest {

    @Test
    fun semicolon_in_single_quoted_string_is_kept() {
        val stmts = SqlScriptSplitter.split("INSERT INTO t VALUES ('a;b'); SELECT 1;")
        assertEquals(2, stmts.size)
        assertEquals("INSERT INTO t VALUES ('a;b')", stmts[0])
        assertEquals("SELECT 1", stmts[1])
    }

    @Test
    fun escaped_quote_inside_string() {
        val stmts = SqlScriptSplitter.split("UPDATE t SET s = 'it''s;fine'; SELECT 2")
        assertEquals(2, stmts.size)
        assertEquals("UPDATE t SET s = 'it''s;fine'", stmts[0])
    }

    @Test
    fun base64_like_literal_with_semicolon() {
        // The case that once forced a char(59) workaround: a semicolon inside a literal
        val stmts = SqlScriptSplitter.split("UPDATE t SET x = 'data:image/png;base64,AAAA'; SELECT 1;")
        assertEquals(2, stmts.size)
        assertEquals("UPDATE t SET x = 'data:image/png;base64,AAAA'", stmts[0])
    }

    @Test
    fun semicolon_in_line_comment_is_kept() {
        val stmts = SqlScriptSplitter.split("-- pick one; or not\nSELECT 1;")
        assertEquals(1, stmts.size)
        assertEquals("-- pick one; or not\nSELECT 1", stmts[0])
    }

    @Test
    fun semicolon_in_block_comment_is_kept() {
        val stmts = SqlScriptSplitter.split("/* a;\nb;c */ SELECT 1; SELECT 2;")
        assertEquals(2, stmts.size)
        assertEquals("/* a;\nb;c */ SELECT 1", stmts[0])
        assertEquals("SELECT 2", stmts[1])
    }

    @Test
    fun double_quote_and_backtick_identifiers() {
        val stmts = SqlScriptSplitter.split("SELECT \"a;b\" FROM `t;bl`; SELECT 2")
        assertEquals(2, stmts.size)
        assertEquals("SELECT \"a;b\" FROM `t;bl`", stmts[0])
    }

    @Test
    fun bracket_identifier() {
        val stmts = SqlScriptSplitter.split("SELECT [a;b] FROM t; SELECT 2;")
        assertEquals(2, stmts.size)
        assertEquals("SELECT [a;b] FROM t", stmts[0])
    }

    @Test
    fun last_statement_without_trailing_semicolon() {
        val stmts = SqlScriptSplitter.split("SELECT 1; SELECT 2")
        assertEquals(2, stmts.size)
        assertEquals("SELECT 2", stmts[1])
    }

    @Test
    fun empty_or_comment_only_script_yields_no_statements() {
        assertEquals(0, SqlScriptSplitter.split("").size)
        assertEquals(0, SqlScriptSplitter.split("  \n\t ").size)
        assertEquals(0, SqlScriptSplitter.split("-- nothing\n/* still nothing */").size)
        assertEquals(0, SqlScriptSplitter.split("-- a;b;c\n").size)
    }

    @Test
    fun comment_only_segment_between_real_statements_is_skipped() {
        // Comment-only segments don't count as statements, but the comment text still belongs to the adjacent statement (SQLite ignores comments when executing)
        val stmts = SqlScriptSplitter.split("SELECT 1;\n-- just a note;\n\nSELECT 2;")
        assertEquals(2, stmts.size)
        assertEquals("SELECT 1", stmts[0])
        assertTrue(stmts[1].endsWith("SELECT 2"))
        assertTrue(stmts[1].contains("just a note"))
    }

    @Test
    fun multiple_real_world_statements_split_correctly() {
        val script = """
            CREATE TABLE IF NOT EXISTS t (
                id TEXT NOT NULL,
                note TEXT
            );

            CREATE INDEX IF NOT EXISTS idx_t_id ON t(id);

            UPDATE t SET note = 'see; you' WHERE id = 'x';
        """.trimIndent()
        val stmts = SqlScriptSplitter.split(script)
        assertEquals(3, stmts.size)
        assertTrue(stmts[0].startsWith("CREATE TABLE IF NOT EXISTS t"))
        assertTrue(stmts[0].contains("id TEXT NOT NULL"))
        assertTrue(stmts[1].startsWith("CREATE INDEX IF NOT EXISTS idx_t_id"))
        assertTrue(stmts[2].startsWith("UPDATE t SET note = 'see; you'"))
    }
}