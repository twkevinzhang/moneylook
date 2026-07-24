package tw.kevinzhang.core.data.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration17To18Test {
    private lateinit var database: MoneylookDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `empty catalog in the ASUS v17 table shape receives the complete public catalog`() {
        val db = database.openHelper.writableDatabase

        MIGRATION_17_18.migrate(db)
        MIGRATION_17_18.migrate(db)

        assertEquals(DefaultClassificationCatalog.categories.size, count(db, "categories"))
        assertEquals(
            DefaultClassificationCatalog.publicAutoCategoryRules.size +
                DefaultClassificationCatalog.publicMccRules.size +
                DefaultClassificationCatalog.publicStructuralRules.size,
            count(db, "auto_category_rules"),
        )
        assertEquals(2, count(db, "auto_category_rule_sets"))
        assertEquals(
            DefaultClassificationCatalog.publicMccRules.sumOf { it.conditions.size } +
                DefaultClassificationCatalog.publicStructuralRules.sumOf { it.conditions.size },
            count(db, "auto_category_rule_conditions"),
        )
    }

    @Test
    fun `empty categories with existing rules remains untouched`() {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            """
            INSERT INTO `auto_category_rules` (
                `id`, `name`, `descriptionContains`, `direction`, `minAbsoluteAmount`,
                `maxAbsoluteAmount`, `accountId`, `categoryId`, `enabled`, `priority`,
                `descriptionMatchMode`, `isDefault`, `ruleSetId`, `extensionId`, `accountKind`,
                `origin`, `action`
            ) VALUES (
                'existing-rule', 'Existing rule', 'fictional', 'EXPENSE', NULL,
                NULL, NULL, NULL, 1, 1, 'CONTAINS', 0, NULL, NULL, NULL, 'LEGACY', 'AUTO_APPLY'
            )
            """.trimIndent(),
        )

        MIGRATION_17_18.migrate(db)

        assertEquals(0, count(db, "categories"))
        assertEquals(1, count(db, "auto_category_rules"))
        assertEquals(0, count(db, "auto_category_rule_sets"))
        assertEquals(0, count(db, "auto_category_rule_conditions"))
        assertEquals(1, countWhere(db, "auto_category_rules", "id = 'existing-rule'"))
    }

    @Test
    fun `partial category catalog remains untouched`() {
        val db = database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO `categories` (`id`, `name`, `color`, `emoji`, `kind`) " +
                "VALUES ('custom', 'Custom', '#000000', 'C', 'EXPENSE')",
        )

        MIGRATION_17_18.migrate(db)

        assertEquals(1, count(db, "categories"))
        assertEquals(1, countWhere(db, "categories", "id = 'custom'"))
        assertEquals(0, count(db, "auto_category_rules"))
        assertEquals(0, count(db, "auto_category_rule_sets"))
        assertEquals(0, count(db, "auto_category_rule_conditions"))
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        countWhere(db, table, "1 = 1")

    private fun countWhere(db: SupportSQLiteDatabase, table: String, where: String): Int =
        db.query("SELECT COUNT(*) FROM `$table` WHERE $where").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
