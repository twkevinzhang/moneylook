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

@RunWith(RobolectricTestRunner::class)
class Migration23To24Test {
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
    fun `migration maps legacy values and retires only pristine public rules`() = runBlocking {
        database.autoCategoryRuleDao().upsert(
            legacyRule("public-rule-006", "休閒娛樂｜Steam", "steam", "expense-entertainment", 60),
        )
        database.autoCategoryRuleDao().upsert(
            legacyRule("public-rule-003", "使用者調整的保險", "保險股份有限公司", "expense-insurance", 30),
        )
        val db = database.openHelper.writableDatabase
        db.execSQL("UPDATE `categories` SET `kind` = 'TRANSFER' WHERE `id` = 'transfer-account'")
        db.execSQL("UPDATE `auto_category_rules` SET `direction` = 'EXPENSE'")

        MIGRATION_23_24.migrate(db)

        assertEquals(
            "EXCLUDED",
            db.query("SELECT `kind` FROM `categories` WHERE `id` = 'transfer-account'")
                .use { cursor -> cursor.moveToFirst(); cursor.getString(0) },
        )
        val rules = database.autoCategoryRuleDao().observeAll().first().map { it.rule }
        assertFalse(rules.any { it.id == "public-rule-006" })
        assertTrue(rules.any { it.id == "public-rule-003" && it.name == "使用者調整的保險" })
        assertTrue(rules.none { it.amountSign.name in setOf("INCOME", "EXPENSE") })
    }

    private fun legacyRule(
        id: String,
        name: String,
        description: String,
        categoryId: String,
        priority: Int,
    ) = AutoCategoryRule(
        id = id,
        name = name,
        descriptionContains = description,
        amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
        categoryId = categoryId,
        priority = priority,
        isDefault = true,
    )
}
