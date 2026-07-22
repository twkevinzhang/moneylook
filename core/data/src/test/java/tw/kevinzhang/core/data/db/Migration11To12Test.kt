package tw.kevinzhang.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration11To12Test {
    private val databaseName = "migration-11-12-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration preserves categories and rules with compatible defaults`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )

        helper.writableDatabase.use { db ->
            db.execSQL(
                "CREATE TABLE categories (id TEXT NOT NULL, name TEXT NOT NULL COLLATE NOCASE, " +
                    "color TEXT NOT NULL, PRIMARY KEY(id))",
            )
            db.execSQL("CREATE UNIQUE INDEX index_categories_name ON categories(name)")
            db.execSQL(
                "CREATE TABLE auto_category_rules (id TEXT NOT NULL, name TEXT NOT NULL, " +
                    "descriptionContains TEXT, direction TEXT NOT NULL, minAbsoluteAmount REAL, " +
                    "maxAbsoluteAmount REAL, accountId TEXT, categoryId TEXT, enabled INTEGER NOT NULL, " +
                    "priority INTEGER NOT NULL, PRIMARY KEY(id))",
            )
            db.execSQL("INSERT INTO categories (id, name, color) VALUES ('food', '餐飲', '#FF0000')")
            db.execSQL(
                "INSERT INTO auto_category_rules " +
                    "(id, name, descriptionContains, direction, enabled, priority) " +
                    "VALUES ('coffee', '咖啡分類', '咖啡', 'EXPENSE', 1, 0)",
            )

            MIGRATION_11_12.migrate(db)

            db.query("SELECT emoji, kind FROM categories WHERE id = 'food'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("🏷️", cursor.getString(0))
                assertEquals("EXPENSE", cursor.getString(1))
            }
            db.query("SELECT descriptionMatchMode FROM auto_category_rules WHERE id = 'coffee'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("CONTAINS", cursor.getString(0))
            }
            db.query("PRAGMA index_list(`categories`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                assertTrue(buildSet { while (cursor.moveToNext()) add(cursor.getString(name)) }.contains("index_categories_kind"))
            }
            db.execSQL(
                "INSERT INTO categories (id, name, color, emoji, kind) " +
                    "VALUES ('coffee', 'coffee', '#795548', '☕', 'EXPENSE')",
            )
            try {
                db.execSQL(
                    "INSERT INTO categories (id, name, color, emoji, kind) " +
                        "VALUES ('coffee-upper', 'COFFEE', '#795548', '☕', 'EXPENSE')",
                )
                fail("category names must remain unique under NOCASE collation")
            } catch (_: Exception) {
                // The unique index created in v11 must remain effective after ALTER TABLE.
            }
        }
        helper.close()
    }
}
