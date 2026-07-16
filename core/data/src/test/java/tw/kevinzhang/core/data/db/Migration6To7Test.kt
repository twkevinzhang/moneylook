package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.LEGACY_CREDENTIAL_FIELDS_JSON

@RunWith(RobolectricTestRunner::class)
class Migration6To7Test {
    private val databaseName = "migration-6-7-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration safely preserves legacy credential values as JSON`() {
        val syntheticUsername = "synthetic'\"\\account\n中文🙂"
        val syntheticPassword = "synthetic'\"\\secret\n符號🙂"
        val helper = openVersion6Database()

        helper.writableDatabase.use { db ->
            insertVersion6Data(db, syntheticUsername, syntheticPassword)
            MIGRATION_6_7.migrate(db)

            db.query(
                """
                SELECT credential, scheduleEnabled, scheduleCron, timezoneId, lastRunAt, lastRunStatus
                FROM credential_profiles WHERE extensionId = 'ext::repo'
                """.trimIndent(),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                val credential = JsonParser.parseString(cursor.getString(0)).asJsonObject
                assertEquals(syntheticUsername, credential.get("username").asString)
                assertEquals(syntheticPassword, credential.get("password").asString)
                assertEquals(1, cursor.getInt(1))
                assertEquals("0 8 * * *", cursor.getString(2))
                assertEquals("Asia/Taipei", cursor.getString(3))
                assertEquals(123L, cursor.getLong(4))
                assertEquals("SUCCESS", cursor.getString(5))
            }

            db.query(
                "SELECT credentialFieldsJson FROM installed_extensions WHERE id = 'ext::repo'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    JsonParser.parseString(LEGACY_CREDENTIAL_FIELDS_JSON),
                    JsonParser.parseString(cursor.getString(0)),
                )
            }

            val credentialColumns = columnNames(db, "credential_profiles")
            assertTrue("credential" in credentialColumns)
            assertFalse("username" in credentialColumns)
            assertFalse("password" in credentialColumns)
            assertTrue("credentialFieldsJson" in columnNames(db, "installed_extensions"))
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
        }
        helper.close()
    }

    private fun openVersion6Database(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion6Schema(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

    private fun createVersion6Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE installed_extensions (
                id TEXT NOT NULL PRIMARY KEY, manifestId TEXT NOT NULL, name TEXT NOT NULL,
                version INTEGER NOT NULL, repoUrl TEXT NOT NULL, syncTriggerCachePath TEXT NOT NULL,
                iconUrl TEXT, suggestedScheduleCron TEXT, suggestedScheduleTimezone TEXT NOT NULL,
                suggestedScheduleEnabled INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE credential_profiles (
                extensionId TEXT NOT NULL PRIMARY KEY, username TEXT NOT NULL, password TEXT NOT NULL,
                scheduleEnabled INTEGER NOT NULL, scheduleCron TEXT NOT NULL, timezoneId TEXT NOT NULL,
                lastRunAt INTEGER, lastRunStatus TEXT,
                FOREIGN KEY(extensionId) REFERENCES installed_extensions(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    private fun insertVersion6Data(
        db: SupportSQLiteDatabase,
        syntheticUsername: String,
        syntheticPassword: String,
    ) {
        db.execSQL(
            """
            INSERT INTO installed_extensions (
                id, manifestId, name, version, repoUrl, syncTriggerCachePath, iconUrl,
                suggestedScheduleCron, suggestedScheduleTimezone, suggestedScheduleEnabled
            ) VALUES ('ext::repo', 'ext', 'Bank', 2, 'https://github.com/test/repo',
                '/tmp/sync.js', NULL, '0 8 * * *', 'Asia/Taipei', 1)
            """.trimIndent(),
        )
        db.compileStatement(
            """
            INSERT INTO credential_profiles (
                extensionId, username, password, scheduleEnabled, scheduleCron,
                timezoneId, lastRunAt, lastRunStatus
            ) VALUES ('ext::repo', ?, ?, 1, '0 8 * * *', 'Asia/Taipei', 123, 'SUCCESS')
            """.trimIndent(),
        ).use { statement ->
            statement.bindString(1, syntheticUsername)
            statement.bindString(2, syntheticPassword)
            statement.executeInsert()
        }
    }

    private fun columnNames(
        db: SupportSQLiteDatabase,
        table: String,
    ): Set<String> = db.query("PRAGMA table_info(`$table`)").use { cursor ->
        buildSet {
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) add(cursor.getString(nameIndex))
        }
    }
}
