package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin

@RunWith(RobolectricTestRunner::class)
class Migration26To27Test {
    private lateinit var database: MoneylookDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).addCallback(MoneylookDatabase.defaultClassificationSeedCallback())
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `migration appends v5 merchant rules and preserves an existing edited row`() = runBlocking {
        val db = database.openHelper.writableDatabase
        db.execSQL("DELETE FROM `auto_category_rules` WHERE `id` LIKE 'public-v5-%'")
        db.execSQL("DELETE FROM `categories` WHERE `id` = 'expense-stationery'")
        database.autoCategoryRuleDao().upsert(
            AutoCategoryRule(
                id = "public-v5-food-sukiya",
                name = "使用者保留的規則名稱",
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
                categoryId = "expense-food",
                priority = 999,
                isDefault = true,
                ruleSetId = DefaultClassificationCatalog.PUBLIC_GENERIC_RULE_SET_ID,
                origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            ),
        )

        MIGRATION_26_27.migrate(db)

        assertTrue(
            database.categoryDao().getByReportingGroup(
                tw.kevinzhang.core.data.model.CategoryReportingGroup.EXPENSE,
            ).any { it.id == "expense-stationery" },
        )
        val v5Rules = database.autoCategoryRuleDao().observeAll().first()
            .filter { it.rule.id.startsWith("public-v5-") }
        assertEquals(49, v5Rules.size)
        assertEquals(
            "使用者保留的規則名稱",
            v5Rules.single { it.rule.id == "public-v5-food-sukiya" }.rule.name,
        )
        assertTrue(v5Rules.any { it.rule.id == "public-v5-stationery-101" })
    }

    @Test
    fun `migration does not recreate v5 rows after the generic collection is removed`() = runBlocking {
        val db = database.openHelper.writableDatabase
        db.execSQL("DELETE FROM `auto_category_rules` WHERE `id` LIKE 'public-v5-%'")
        db.execSQL("DELETE FROM `categories` WHERE `id` = 'expense-stationery'")
        db.execSQL(
            "DELETE FROM `auto_category_rule_sets` WHERE `id` = ?",
            arrayOf(DefaultClassificationCatalog.PUBLIC_GENERIC_RULE_SET_ID),
        )

        MIGRATION_26_27.migrate(db)

        val ids = database.autoCategoryRuleDao().observeAll().first().map { it.rule.id }
        assertFalse(ids.any { it.startsWith("public-v5-") })
        assertFalse(
            database.categoryDao().getByReportingGroup(
                tw.kevinzhang.core.data.model.CategoryReportingGroup.EXPENSE,
            ).any { it.id == "expense-stationery" },
        )
    }

    @Test
    fun `migration adds the annual-fee waiver exclusion only to a pristine public rule`() {
        val db = database.openHelper.writableDatabase
        val annualFeeRuleId = "public-structural-auto-credit-card-annual-fee-v2"
        db.execSQL(
            "DELETE FROM `auto_category_rule_conditions` WHERE `ruleId` = ? AND `position` = 9",
            arrayOf(annualFeeRuleId),
        )

        MIGRATION_26_27.migrate(db)

        db.query(
            """
            SELECT `conditionGroup`, `field`, `matchMode`, `pattern`
            FROM `auto_category_rule_conditions`
            WHERE `ruleId` = ? AND `position` = 9
            """.trimIndent(),
            arrayOf(annualFeeRuleId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("EXCLUDE_ANY", cursor.getString(0))
            assertEquals("SEARCHABLE_TEXT", cursor.getString(1))
            assertEquals("CONTAINS", cursor.getString(2))
            assertEquals("減免年費", cursor.getString(3))
        }

        db.execSQL(
            "DELETE FROM `auto_category_rule_conditions` WHERE `ruleId` = ? AND `position` = 9",
            arrayOf(annualFeeRuleId),
        )
        db.execSQL(
            "UPDATE `auto_category_rules` SET `name` = '使用者修改' WHERE `id` = ?",
            arrayOf(annualFeeRuleId),
        )

        MIGRATION_26_27.migrate(db)

        db.query(
            "SELECT 1 FROM `auto_category_rule_conditions` WHERE `ruleId` = ? AND `position` = 9",
            arrayOf(annualFeeRuleId),
        ).use { cursor -> assertFalse(cursor.moveToFirst()) }
    }

    @Test
    fun `migration excludes the ambiguous hoho payment provider only from a pristine wallet rule`() {
        val db = database.openHelper.writableDatabase
        val walletRuleId = "public-v4-expense-digital-wallet"
        db.execSQL(
            "DELETE FROM `auto_category_rule_conditions` WHERE `ruleId` = ? AND `position` = 8",
            arrayOf(walletRuleId),
        )

        MIGRATION_26_27.migrate(db)

        db.query(
            """
            SELECT `conditionGroup`, `field`, `matchMode`, `pattern`
            FROM `auto_category_rule_conditions`
            WHERE `ruleId` = ? AND `position` = 8
            """.trimIndent(),
            arrayOf(walletRuleId),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("EXCLUDE_ANY", cursor.getString(0))
            assertEquals("SEARCHABLE_TEXT", cursor.getString(1))
            assertEquals("CONTAINS", cursor.getString(2))
            assertEquals("連加*HOHO", cursor.getString(3))
        }

        db.execSQL(
            "DELETE FROM `auto_category_rule_conditions` WHERE `ruleId` = ? AND `position` = 8",
            arrayOf(walletRuleId),
        )
        db.execSQL(
            "UPDATE `auto_category_rules` SET `name` = '使用者修改' WHERE `id` = ?",
            arrayOf(walletRuleId),
        )

        MIGRATION_26_27.migrate(db)

        db.query(
            "SELECT 1 FROM `auto_category_rule_conditions` WHERE `ruleId` = ? AND `position` = 8",
            arrayOf(walletRuleId),
        ).use { cursor -> assertFalse(cursor.moveToFirst()) }
    }
}
