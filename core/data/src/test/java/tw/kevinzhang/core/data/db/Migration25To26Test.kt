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
class Migration25To26Test {
    private val databaseName = "migration-25-26-test.db"

    @Before @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration creates an extension-scoped queue with no existing requests`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(25) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE installed_extensions (id TEXT NOT NULL PRIMARY KEY)")
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            MIGRATION_25_26.migrate(db)
            db.query("SELECT extensionId, trigger, status, requestedAt, updatedAt FROM pending_sync_requests").use { cursor ->
                assertEquals(0, cursor.count)
            }
            db.execSQL("INSERT INTO installed_extensions (id) VALUES ('fictional')")
            db.execSQL(
                "INSERT INTO pending_sync_requests VALUES ('fictional', 'USER', 'QUEUED', 1, 1)",
            )
            db.query("SELECT status FROM pending_sync_requests WHERE extensionId = 'fictional'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("QUEUED", cursor.getString(0))
            }
            db.execSQL("DELETE FROM installed_extensions WHERE id = 'fictional'")
            db.query("SELECT * FROM pending_sync_requests").use { cursor ->
                assertEquals(0, cursor.count)
            }
        }
        helper.close()
    }
}
