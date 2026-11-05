package com.xencode.core.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xencode.feature.agent.data.local.database.AgentDatabase
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Room's official migration tests (Robolectric, real Android SQLite on the JVM).
 *
 * This container (PRoot on an Android host) has a JVM that cannot load Robolectric's conscrypt native library
 * (UnsatisfiedLinkError; java.library.path points at Android paths like /data/app, /system),
 * so [guardEnvironment] skips on non-standard Linux environments — a skip does not count as a failure;
 * CI (ubuntu x86_64) and ordinary dev machines run it for real.
 *
 * Schema jsons for historical versions (8~49) were never exported back then, so [MigrationTestHelper] can only cover
 * "the current version and later" paths; contiguity/no-duplicates for historical paths is doubly guarded by [MigrationFilesParseTest] and
 * scripts/check_migrations.py.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java
    )

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    companion object {
        @JvmStatic
        @BeforeClass
        fun guardEnvironment() {
            val androidContainer = File("/system").exists() ||
                System.getProperty("java.library.path")?.contains("/data/app") == true
            // Beyond the Android container: whenever the current JVM cannot load the conscrypt native
            // library Robolectric depends on (e.g. Windows ARM lacking conscrypt_openjdk_jni-windows-aarch_64), it equally cannot run —
            // judged by "can it run" rather than "is it Android", so non-standard dev machines do not turn this case into a failure.
            val conscryptLoadable = runCatching {
                // isAvailable() actually triggers native library loading and swallows UnsatisfiedLinkError,
                // making it an accurate test of "can this environment run Robolectric".
                Class.forName("org.conscrypt.Conscrypt")
                    .getMethod("isAvailable")
                    .invoke(null) as Boolean
            }.getOrDefault(false)
            assumeTrue(
                "Robolectric requires a standard Linux/CI environment with a loadable conscrypt native library (current environment does not qualify)",
                !androidContainer && conscryptLoadable
            )
        }
    }

    @Test
    fun open_current_schema_passes_identity_check() {
        helper.createDatabase(dbName, AgentDatabase.SCHEMA_VERSION).close()

        val db = Room.databaseBuilder(context, AgentDatabase::class.java, dbName)
            .addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
        // Opening triggers Room's identity/schema check; a mismatch between entities and migration output throws
        db.openHelper.writableDatabase
        db.close()
    }

    /**
     * When adding future migrations (51+), include the @AutoMigration/new migrations and add upgrade cases here:
     * createDatabase(50) -> run the migration array -> assert new columns/tables exist.
     */
}