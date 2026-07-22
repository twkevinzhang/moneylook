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
import tw.kevinzhang.core.data.model.AutoCategoryRule

@RunWith(RobolectricTestRunner::class)
class Migration13To14Test {
    private val databaseName = "migration-13-14-test.db"

    @Before
    @After
    fun deleteDatabase() {
        RuntimeEnvironment.getApplication().deleteDatabase(databaseName)
    }

    @Test
    fun `migration preserves user rules and seeds only present categories idempotently`() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .name(databaseName)
                .callback(v13Callback())
                .build(),
        )

        helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO categories VALUES ('expense-food', '餐飲', '#FB8C00', '🍽️', 'EXPENSE')")
            db.execSQL(
                "INSERT INTO auto_category_rules VALUES " +
                    "('user-rule', '使用者規則', '自訂', 'ANY', NULL, NULL, NULL, 'expense-food', 1, 0, 'CONTAINS')",
            )

            MIGRATION_13_14.migrate(db)

            assertEquals(0, columnLong(db, "SELECT isDefault FROM auto_category_rules WHERE id = ?", "user-rule"))
            assertEquals(1, count(db, "SELECT COUNT(*) FROM auto_category_rules WHERE id = ?", "public-rule-002"))
            DefaultClassificationSeeder.seedRulesForExistingCategories(
                db,
                listOf(
                    defaultRule(id = "present-default", categoryId = "expense-food", name = "原始預設"),
                    defaultRule(id = "missing-default", categoryId = "missing", name = "不應插入"),
                ),
            )
            DefaultClassificationSeeder.seedRulesForExistingCategories(
                db,
                listOf(defaultRule(id = "present-default", categoryId = "expense-food", name = "不可覆寫")),
            )

            assertEquals(1, count(db, "SELECT COUNT(*) FROM auto_category_rules WHERE id = ?", "present-default"))
            assertEquals(0, count(db, "SELECT COUNT(*) FROM auto_category_rules WHERE id = ?", "missing-default"))
            assertEquals("原始預設", columnString(db, "SELECT name FROM auto_category_rules WHERE id = ?", "present-default"))
            assertEquals(1, columnLong(db, "SELECT isDefault FROM auto_category_rules WHERE id = ?", "present-default"))
            assertEquals(0, foreignKeyViolationCount(db))
        }
        helper.close()
    }

    private fun v13Callback() = object : SupportSQLiteOpenHelper.Callback(13) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL, " +
                    "emoji TEXT NOT NULL, kind TEXT NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE auto_category_rules (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, " +
                    "descriptionContains TEXT, direction TEXT NOT NULL, minAbsoluteAmount REAL, maxAbsoluteAmount REAL, " +
                    "accountId TEXT, categoryId TEXT, enabled INTEGER NOT NULL, priority INTEGER NOT NULL, " +
                    "descriptionMatchMode TEXT NOT NULL, FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL)",
            )
            db.execSQL(
                "CREATE INDEX index_auto_category_rules_enabled_priority_id " +
                    "ON auto_category_rules(enabled, priority, id)",
            )
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private fun defaultRule(id: String, categoryId: String, name: String) = AutoCategoryRule(
        id = id,
        name = name,
        descriptionContains = "通用關鍵字",
        categoryId = categoryId,
        isDefault = true,
    )

    private fun count(db: SupportSQLiteDatabase, sql: String, arg: String? = null): Long =
        (if (arg == null) db.query(sql, emptyArray<Any?>()) else db.query(sql, arrayOf(arg))).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun foreignKeyViolationCount(db: SupportSQLiteDatabase): Int =
        db.query("PRAGMA foreign_key_check").use { it.count }

    private fun columnLong(db: SupportSQLiteDatabase, sql: String, arg: String): Long =
        db.query(sql, arrayOf(arg)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun columnString(db: SupportSQLiteDatabase, sql: String, arg: String): String =
        db.query(sql, arrayOf(arg)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
}
