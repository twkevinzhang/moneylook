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
class Migration8To9Test {
    private val databaseName = "migration-8-9-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration keeps prior transactions and makes running balance optional`() {
        val helper = openVersion8Database()

        helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO accounts VALUES ('account', 'extension', 'Bank', '活期', 12.0, 'TWD', 1, NULL, 'DEPOSIT', NULL, NULL, NULL)")
            db.execSQL("INSERT INTO transfers VALUES ('transfer', 'account', 'extension', '2026-07-21', '測試', 2.0, 14.0, '')")
            MIGRATION_8_9.migrate(db)

            db.query("SELECT balance FROM transfers WHERE id = 'transfer'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(14.0, cursor.getDouble(0), 0.0)
            }
            db.query("PRAGMA table_info(`transfers`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                val notNull = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) {
                    if (cursor.getString(name) == "balance") assertEquals(0, cursor.getInt(notNull))
                }
            }
            db.query("PRAGMA table_info(`accounts`)").use { cursor ->
                val names = buildSet {
                    val name = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(name))
                }
                assertTrue("transferSyncComplete" in names)
            }
        }
        helper.close()
    }

    private fun openVersion8Database(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE accounts (
                                    id TEXT NOT NULL PRIMARY KEY, extensionId TEXT NOT NULL, extensionName TEXT NOT NULL,
                                    accountName TEXT NOT NULL, balance REAL NOT NULL, currency TEXT NOT NULL,
                                    lastSyncAt INTEGER NOT NULL, accountNo TEXT, kind TEXT NOT NULL,
                                    branchName TEXT, availableCredit REAL, creditLimit REAL
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE transfers (
                                    id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, extensionId TEXT NOT NULL,
                                    txnDateTime TEXT NOT NULL, description TEXT NOT NULL, amount REAL NOT NULL,
                                    balance REAL NOT NULL, memo TEXT NOT NULL
                                )
                                """.trimIndent(),
                            )
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
}
