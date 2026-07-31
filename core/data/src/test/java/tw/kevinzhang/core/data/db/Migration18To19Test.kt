package tw.kevinzhang.core.data.db

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class Migration18To19Test {
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
    fun `base marker absent leaves an existing catalog untouched`() {
        val db = database.openHelper.writableDatabase
        DefaultClassificationSeeder.seedCategories(db, DefaultClassificationCatalog.categories)

        MIGRATION_18_19.migrate(db)

        assertEquals(0, count(db, "auto_category_rule_sets"))
        assertEquals(0, count(db, "auto_category_rules"))
        assertEquals(0, count(db, "auto_category_rule_conditions"))
    }

    @Test
    fun `generic ruleset collision of any origin is never modified`() {
        val db = database.openHelper.writableDatabase
        insertPublicBaseMarker(db)
        db.execSQL(
            """
            INSERT INTO `auto_category_rule_sets`
                (`id`, `name`, `origin`, `version`, `canonicalizerVersion`, `contentSha256`, `isActive`)
            VALUES ('public-generic-rules-v3', 'User replacement', 'LEGACY', '1', 'v2-space-fold', 'fictional', 0)
            """.trimIndent(),
        )

        MIGRATION_18_19.migrate(db)

        assertEquals(0, count(db, "auto_category_rules"))
        assertEquals(0, count(db, "auto_category_rule_conditions"))
        assertEquals(1, countWhere(db, "auto_category_rule_sets", "id = 'public-generic-rules-v3' AND origin = 'LEGACY'"))
    }

    @Test
    fun `missing categories skip related generic rules without recreating categories`() {
        val db = database.openHelper.writableDatabase
        insertPublicBaseMarker(db)
        db.execSQL(
            "INSERT INTO `categories` (`id`, `name`, `color`, `emoji`, `kind`) " +
                "VALUES ('expense-food', 'Food', '#000000', 'F', 'EXPENSE')",
        )

        MIGRATION_18_19.migrate(db)

        val foodRules = DefaultClassificationCatalog.publicGenericRules
            .filter { it.rule.categoryId == "expense-food" }
        val expectedRules = foodRules.size
        val expectedConditions = foodRules.sumOf { it.conditions.size }
        assertEquals(1, count(db, "categories"))
        assertEquals(expectedRules, count(db, "auto_category_rules"))
        assertEquals(expectedConditions, count(db, "auto_category_rule_conditions"))
        assertEquals(2, count(db, "auto_category_rule_sets"))
    }

    @Test
    fun `individual rule collision is retained and receives no replacement conditions`() {
        val db = database.openHelper.writableDatabase
        insertPublicBaseMarker(db)
        DefaultClassificationSeeder.seedCategories(db, DefaultClassificationCatalog.categories)
        val collision = DefaultClassificationCatalog.publicGenericRules.first().rule
        db.execSQL(
            """
            INSERT INTO `auto_category_rules` (
                `id`, `name`, `descriptionContains`, `direction`, `minAbsoluteAmount`,
                `maxAbsoluteAmount`, `accountId`, `categoryId`, `enabled`, `priority`,
                `descriptionMatchMode`, `isDefault`, `ruleSetId`, `extensionId`, `accountKind`,
                `origin`, `action`
            ) VALUES (?, 'User-owned collision', NULL, 'ANY', NULL, NULL, NULL, ?, 1, 1,
                'CONTAINS', 0, NULL, NULL, NULL, 'LEGACY', 'AUTO_APPLY')
            """.trimIndent(),
            arrayOf(collision.id, collision.categoryId),
        )

        MIGRATION_18_19.migrate(db)

        assertEquals(DefaultClassificationCatalog.publicGenericRules.size, count(db, "auto_category_rules"))
        assertEquals(
            "User-owned collision",
            stringValue(db, "SELECT name FROM `auto_category_rules` WHERE id = ?", collision.id),
        )
        assertEquals(0, countWhere(db, "auto_category_rule_conditions", "ruleId = '${collision.id}'"))
    }

    @Test
    fun `migration is one time and does not revive deleted generic rules or user edits`() {
        val db = database.openHelper.writableDatabase
        insertPublicBaseMarker(db)
        DefaultClassificationSeeder.seedCategories(db, DefaultClassificationCatalog.categories)

        MIGRATION_18_19.migrate(db)
        val edited = DefaultClassificationCatalog.publicGenericRules[1].rule
        val deleted = DefaultClassificationCatalog.publicGenericRules.last().rule
        db.execSQL("UPDATE `auto_category_rules` SET `name` = 'User edited' WHERE `id` = ?", arrayOf(edited.id))
        db.execSQL("DELETE FROM `auto_category_rules` WHERE `id` = ?", arrayOf(deleted.id))

        MIGRATION_18_19.migrate(db)

        assertEquals(
            DefaultClassificationCatalog.publicGenericRules.size - 1,
            count(db, "auto_category_rules"),
        )
        assertEquals("User edited", stringValue(db, "SELECT name FROM `auto_category_rules` WHERE id = ?", edited.id))
        assertEquals(0, countWhere(db, "auto_category_rules", "id = '${deleted.id}'"))
        assertTrue(
            countWhere(
                db,
                "auto_category_rule_sets",
                "id = 'public-generic-rules-v3' AND origin = 'PUBLIC_DEFAULT'",
            ) == 1,
        )
    }

    private fun insertPublicBaseMarker(db: SupportSQLiteDatabase) {
        val ruleSet = DefaultClassificationCatalog.publicMccRuleSet
        db.execSQL(
            """
            INSERT INTO `auto_category_rule_sets`
                (`id`, `name`, `origin`, `version`, `canonicalizerVersion`, `contentSha256`, `isActive`)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any>(
                ruleSet.id,
                ruleSet.name,
                ruleSet.origin.name,
                ruleSet.version,
                ruleSet.canonicalizerVersion,
                ruleSet.contentSha256,
                1,
            ),
        )
    }

    private fun count(db: SupportSQLiteDatabase, table: String): Int =
        countWhere(db, table, "1 = 1")

    private fun countWhere(db: SupportSQLiteDatabase, table: String, where: String): Int =
        db.query("SELECT COUNT(*) FROM `$table` WHERE $where").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun stringValue(db: SupportSQLiteDatabase, sql: String, arg: String): String =
        db.query(sql, arrayOf(arg)).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
}
