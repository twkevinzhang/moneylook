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
class Migration15To16Test {
    private val databaseName = "migration-15-16-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration adds encrypted card table and nullable transfer link without changing history`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(15) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE transfers (id TEXT NOT NULL PRIMARY KEY, " +
                                    "accountId TEXT NOT NULL, extensionId TEXT NOT NULL, " +
                                    "txnDateTime TEXT NOT NULL, description TEXT NOT NULL, " +
                                    "amount REAL NOT NULL, balance REAL, memo TEXT NOT NULL, " +
                                    "type TEXT, status TEXT, postingDateTime TEXT)",
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
                "INSERT INTO transfers (id, accountId, extensionId, txnDateTime, description, amount, memo) " +
                    "VALUES ('transfer', 'account', 'extension', '2026-07-21', 'test', -1.0, '')",
            )
            MIGRATION_15_16.migrate(db)

            db.query("SELECT cardInstrumentId FROM transfers WHERE id = 'transfer'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            db.query("PRAGMA table_info(credit_card_instruments)").use { cursor ->
                val names = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("panCiphertext" in names)
                assertTrue("panIv" in names)
                assertTrue("panFingerprint" in names)
                assertTrue("pan" !in names)
            }
            db.query("SELECT COUNT(*) FROM credit_card_instruments").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        helper.close()
    }
}
