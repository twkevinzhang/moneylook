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
class Migration9To10Test {
    private val databaseName = "migration-9-10-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration retains legacy rows without deriving an exposed cursor key`() {
        val helper = openVersion9Database()

        helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO accounts VALUES ('account', 'extension', 'Bank', '活期', 12.0, 'TWD', 1, '0012345678', 'DEPOSIT', NULL, NULL, NULL, NULL)")
            db.execSQL("INSERT INTO transfers VALUES ('transfer', 'account', 'extension', '2026-07-21', '測試', 2.0, NULL, '')")
            MIGRATION_9_10.migrate(db)

            db.query("SELECT sourceAccountKey FROM accounts WHERE id = 'account'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            db.query("SELECT type, status FROM transfers WHERE id = 'transfer'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
            db.query("PRAGMA index_list(`accounts`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                assertTrue("index_accounts_extensionId_sourceAccountKey_kind_currency" in names)
            }
            db.query("PRAGMA index_list(`transfers`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                assertTrue("index_transfers_accountId_txnDateTime" in names)
            }
        }
        helper.close()
    }

    private fun openVersion9Database(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE accounts (
                                    id TEXT NOT NULL PRIMARY KEY, extensionId TEXT NOT NULL, extensionName TEXT NOT NULL,
                                    accountName TEXT NOT NULL, balance REAL NOT NULL, currency TEXT NOT NULL,
                                    lastSyncAt INTEGER NOT NULL, accountNo TEXT, kind TEXT NOT NULL,
                                    branchName TEXT, availableCredit REAL, creditLimit REAL, transferSyncComplete INTEGER
                                )
                                """.trimIndent(),
                            )
                            db.execSQL(
                                """
                                CREATE TABLE transfers (
                                    id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, extensionId TEXT NOT NULL,
                                    txnDateTime TEXT NOT NULL, description TEXT NOT NULL, amount REAL NOT NULL,
                                    balance REAL, memo TEXT NOT NULL
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
