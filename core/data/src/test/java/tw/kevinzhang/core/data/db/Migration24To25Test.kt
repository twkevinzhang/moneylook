package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.CategoryReportingGroup

@RunWith(RobolectricTestRunner::class)
class Migration24To25Test {
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
    fun `migration appends aggressive catalog and preserves an edited row`() = runBlocking {
        val db = database.openHelper.writableDatabase
        db.execSQL("DELETE FROM `auto_category_rules` WHERE `id` LIKE 'public-v4-%'")
        db.execSQL(
            "DELETE FROM `categories` WHERE `id` IN ('expense-digital-wallet', 'income-subsidy')",
        )
        database.autoCategoryRuleDao().upsert(
            AutoCategoryRule(
                id = "public-v4-transfer-linked-account",
                name = "使用者保留的規則名稱",
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
                categoryId = "transfer-account",
                priority = 999,
                isDefault = true,
                ruleSetId = DefaultClassificationCatalog.PUBLIC_GENERIC_RULE_SET_ID,
                accountKind = AssetKind.DEPOSIT,
                origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            ),
        )

        MIGRATION_24_25.migrate(db)

        assertEquals(
            CategoryReportingGroup.EXPENSE,
            database.categoryDao().getByReportingGroup(CategoryReportingGroup.EXPENSE)
                .single { it.id == "expense-digital-wallet" }
                .reportingGroup,
        )
        assertEquals(
            CategoryReportingGroup.INCOME,
            database.categoryDao().getByReportingGroup(CategoryReportingGroup.INCOME)
                .single { it.id == "income-subsidy" }
                .reportingGroup,
        )
        val v4Rules = database.autoCategoryRuleDao().observeAll().first()
            .filter { it.rule.id.startsWith("public-v4-") }
        assertEquals(17, v4Rules.size)
        assertEquals(
            "使用者保留的規則名稱",
            v4Rules.single { it.rule.id == "public-v4-transfer-linked-account" }.rule.name,
        )
        val wallet = v4Rules.single { it.rule.id == "public-v4-expense-digital-wallet" }
        assertTrue(wallet.conditions.any { it.conditionGroup.name == "EXCLUDE_ANY" })
        assertTrue(
            v4Rules.single { it.rule.id == "public-v4-income-credit-card-refund-fallback" }
                .conditions.isEmpty(),
        )
    }
}
