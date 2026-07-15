package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration5To6Test {
    private val databaseName = "migration-5-6-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration preserves extension and credentials while removing obsolete columns`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            createVersion5Schema(db)
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        helper.writableDatabase.use { db ->
            MIGRATION_5_6.migrate(db)

            db.query("SELECT name, version FROM installed_extensions WHERE id = 'ext::repo'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Bank", cursor.getString(0))
                assertEquals(2, cursor.getInt(1))
            }
            db.query(
                "SELECT username, password, scheduleCron FROM credential_profiles WHERE extensionId = 'ext::repo'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("user", cursor.getString(0))
                assertEquals("plain-password", cursor.getString(1))
                assertEquals("0 8 * * *", cursor.getString(2))
            }

            val installedColumns = columnNames(db, "installed_extensions")
            assertFalse("loginUrl" in installedColumns)
            assertFalse("targetDomainsJson" in installedColumns)
            assertFalse("loginAutomationJson" in installedColumns)
            val credentialColumns = columnNames(db, "credential_profiles")
            assertFalse("approvedLoginHost" in credentialColumns)
            assertFalse("approvedDomainsJson" in credentialColumns)
        }
        helper.close()
    }

    private fun createVersion5Schema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE installed_extensions (
                id TEXT NOT NULL PRIMARY KEY, manifestId TEXT NOT NULL, name TEXT NOT NULL,
                version INTEGER NOT NULL, repoUrl TEXT NOT NULL, syncTriggerCachePath TEXT NOT NULL,
                loginUrl TEXT NOT NULL, targetDomainsJson TEXT NOT NULL, iconUrl TEXT,
                loginAutomationJson TEXT NOT NULL, suggestedScheduleCron TEXT,
                suggestedScheduleTimezone TEXT NOT NULL, suggestedScheduleEnabled INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE credential_profiles (
                extensionId TEXT NOT NULL PRIMARY KEY, username TEXT NOT NULL, password TEXT NOT NULL,
                approvedLoginHost TEXT NOT NULL, approvedDomainsJson TEXT NOT NULL,
                scheduleEnabled INTEGER NOT NULL, scheduleCron TEXT NOT NULL, timezoneId TEXT NOT NULL,
                lastRunAt INTEGER, lastRunStatus TEXT,
                FOREIGN KEY(extensionId) REFERENCES installed_extensions(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO installed_extensions VALUES (
                'ext::repo', 'ext', 'Bank', 2, 'https://github.com/test/repo', '/tmp/sync.js',
                'https://bank.example', '["bank.example"]', NULL, '{}', '0 8 * * *',
                'Asia/Taipei', 1
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO credential_profiles VALUES (
                'ext::repo', 'user', 'plain-password', 'bank.example', '["bank.example"]',
                1, '0 8 * * *', 'Asia/Taipei', 123, 'SUCCESS'
            )
            """.trimIndent(),
        )
    }

    private fun columnNames(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
    ): Set<String> = db.query("PRAGMA table_info(`$table`)").use { cursor ->
        buildSet {
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }
}
