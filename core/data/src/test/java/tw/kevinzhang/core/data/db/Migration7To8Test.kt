package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration7To8Test {
    private val databaseName = "migration-7-8-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration adds typed account columns and preserves credential profiles`() {
        val helper = openVersion7Database()

        helper.writableDatabase.use { db ->
            insertVersion7Data(db)
            MIGRATION_7_8.migrate(db)

            db.query(
                "SELECT kind, branchName, availableCredit, creditLimit FROM accounts WHERE id = 'account'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("DEPOSIT", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
                assertTrue(cursor.isNull(3))
            }
            db.query("SELECT credential FROM credential_profiles WHERE extensionId = 'extension'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("{\"opaque\":\"value\"}", cursor.getString(0))
            }
            assertTrue("kind" in columnNames(db, "accounts"))
            assertTrue("branchName" in columnNames(db, "accounts"))
            assertTrue("availableCredit" in columnNames(db, "accounts"))
            assertTrue("creditLimit" in columnNames(db, "accounts"))
            db.query("PRAGMA foreign_key_check").use { cursor ->
                assertEquals(0, cursor.count)
            }
        }
        helper.close()
    }

    private fun openVersion7Database(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createVersion7Schema(db)
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

    private fun createVersion7Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE installed_extensions (
                id TEXT NOT NULL PRIMARY KEY, manifestId TEXT NOT NULL, name TEXT NOT NULL,
                version INTEGER NOT NULL, repoUrl TEXT NOT NULL, syncTriggerCachePath TEXT NOT NULL,
                iconUrl TEXT, suggestedScheduleCron TEXT, suggestedScheduleTimezone TEXT NOT NULL,
                suggestedScheduleEnabled INTEGER NOT NULL, credentialFieldsJson TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE credential_profiles (
                extensionId TEXT NOT NULL PRIMARY KEY, credential TEXT NOT NULL,
                scheduleEnabled INTEGER NOT NULL, scheduleCron TEXT NOT NULL, timezoneId TEXT NOT NULL,
                lastRunAt INTEGER, lastRunStatus TEXT,
                FOREIGN KEY(extensionId) REFERENCES installed_extensions(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE accounts (
                id TEXT NOT NULL PRIMARY KEY, extensionId TEXT NOT NULL, extensionName TEXT NOT NULL,
                accountName TEXT NOT NULL, balance REAL NOT NULL, currency TEXT NOT NULL,
                lastSyncAt INTEGER NOT NULL, accountNo TEXT
            )
            """.trimIndent(),
        )
    }

    private fun insertVersion7Data(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO installed_extensions VALUES (
                'extension', 'bank', 'Bank', 7, 'https://github.com/test/repo', '/tmp/sync.js',
                NULL, NULL, 'Asia/Taipei', 0, '[]'
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO credential_profiles VALUES (
                'extension', '{"opaque":"value"}', 0, '0 8 * * *', 'Asia/Taipei', NULL, NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO accounts VALUES ('account', 'extension', 'Bank', '活期', 12.0, 'TWD', 1, NULL)
            """.trimIndent(),
        )
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(nameIndex))
            }
        }
}
