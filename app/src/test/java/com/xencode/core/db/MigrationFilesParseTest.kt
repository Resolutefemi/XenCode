package com.xencode.core.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static validation of migration files (pure JVM, no Robolectric — this container's JVM cannot load Robolectric's conscrypt native library).
 *
 * Reads migration scripts straight from the filesystem (Gradle unit-test working dir = module root app/):
 * - every non-blank script must produce statements after [SqlScriptSplitter] splitting (guards against parser regressions);
 * - version numbers strictly increasing, contiguous, no duplicates (a gap makes Room fall back to a destructive rebuild);
 * - aligned with SCHEMA_VERSION in AgentDatabase.kt.
 * CI additionally reconciles at the git-refs level via scripts/check_migrations.py (freezing published tags etc.); the two complement each other.
 */
class MigrationFilesParseTest {

    private val migrationsDir = File("src/main/assets/migrations")
    private val dbSource = File("src/main/java/com/xencode/feature/agent/data/local/database/AgentDatabase.kt")

    private fun migrationFiles(): List<File> =
        migrationsDir.listFiles { f -> f.name.endsWith(".sql") }?.sortedBy { it.name } ?: emptyList()

    @Test
    fun every_non_blank_script_splits_into_statements() {
        assertTrue("Migrations directory not found: ${migrationsDir.absolutePath}", migrationsDir.isDirectory)
        for (file in migrationFiles()) {
            val script = file.readText()
            if (script.isBlank()) continue // blank migrations allowed (e.g. #35 only records history)
            val stmts = SqlScriptSplitter.split(script)
            assertTrue("${file.name} produced no statements after parsing", stmts.isNotEmpty())
            assertTrue("${file.name} produced blank statements", stmts.none { it.isBlank() })
        }
    }

    @Test
    fun versions_are_contiguous_unique_and_match_schema_version() {
        val versions = migrationFiles().mapNotNull { file ->
            Regex("(\\d+)_").find(file.name)?.groupValues?.get(1)?.toInt()
        }.sorted()
        assertTrue("No migration version numbers parsed at all", versions.isNotEmpty())
        assertEquals("Version numbers must be strictly increasing and contiguous (starting at 8 is fine, but no gaps)",
            (versions.first()..versions.last()).toList(), versions)
        val schemaVer = Regex("const val SCHEMA_VERSION = (\\d+)")
            .find(dbSource.readText())?.groupValues?.get(1)?.toInt()
        assertEquals("SCHEMA_VERSION must equal the largest migration version", versions.last(), schemaVer)
    }
}