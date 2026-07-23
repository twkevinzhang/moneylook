package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration14To15Test {
    private val databaseName = "migration-14-15-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration adds nullable posting datetime without changing existing transfers`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(14) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE transfers (id TEXT NOT NULL PRIMARY KEY, " +
                                    "accountId TEXT NOT NULL, extensionId TEXT NOT NULL, " +
                                    "txnDateTime TEXT NOT NULL, description TEXT NOT NULL, " +
                                    "amount REAL NOT NULL, balance REAL, memo TEXT NOT NULL, " +
                                    "type TEXT, status TEXT)",
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
                "INSERT INTO transfers (id, accountId, extensionId, txnDateTime, description, " +
                    "amount, balance, memo, type, status) VALUES " +
                    "('transfer', 'account', 'extension', '2026-07-21', '測試', 2.0, NULL, '', NULL, 'posted')",
            )
            MIGRATION_14_15.migrate(db)

            db.query("SELECT postingDateTime FROM transfers WHERE id = 'transfer'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
        }
        helper.close()
    }
}
