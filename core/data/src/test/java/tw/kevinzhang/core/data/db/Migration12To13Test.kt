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
class Migration12To13Test {
    private val databaseName = "migration-12-13-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration retains transactions and adds global date index`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(12) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE transfers (id TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, " +
                                    "extensionId TEXT NOT NULL, txnDateTime TEXT NOT NULL, description TEXT NOT NULL, " +
                                    "amount REAL NOT NULL, balance REAL, memo TEXT NOT NULL, type TEXT, status TEXT)",
                            )
                            db.execSQL(
                                "CREATE INDEX index_transfers_accountId_txnDateTime " +
                                    "ON transfers(accountId, txnDateTime)",
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

        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO transfers VALUES ('transaction', 'account', 'extension', '2026-07-21', " +
                    "'測試', 2.0, NULL, '', NULL, NULL)",
            )
            MIGRATION_12_13.migrate(db)

            db.query("SELECT txnDateTime FROM transfers WHERE id = 'transaction'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2026-07-21", cursor.getString(0))
            }
            db.query("PRAGMA index_list(`transfers`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }
                assertTrue("index_transfers_txnDateTime" in names)
            }
        }
        helper.close()
    }
}
