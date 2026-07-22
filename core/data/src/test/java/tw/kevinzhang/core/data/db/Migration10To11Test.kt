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
class Migration10To11Test {
    private val databaseName = "migration-10-11-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration creates user metadata tables with case insensitive names and nullable categories`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )

        helper.writableDatabase.use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            MIGRATION_10_11.migrate(db)
            db.execSQL("INSERT INTO categories VALUES ('food', '餐飲', '#FF0000')")
            db.execSQL(
                "INSERT INTO transfer_annotations VALUES " +
                    "('transfer', 'extension', 'food', '', 'AUTO', 0)",
            )
            db.execSQL("DELETE FROM categories WHERE id = 'food'")
            db.query("SELECT categoryId FROM transfer_annotations WHERE transferId = 'transfer'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            db.query("PRAGMA index_list(`categories`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                assertTrue(buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }.contains("index_categories_name"))
            }
            db.query("SELECT count(*) FROM auto_category_rules").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
        helper.close()
    }
}
