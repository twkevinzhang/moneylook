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

@RunWith(RobolectricTestRunner::class)
class DefaultClassificationSeedTest {
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
    fun tearDown() {
        database.close()
    }

    @Test
    fun `fresh database seeds the catalog and user rules run before defaults`() = runBlocking {
        val categories = database.categoryDao().getByKind(tw.kevinzhang.core.data.model.CategoryKind.EXPENSE)
        assertTrue(categories.any { it.id == "expense-food" })

        val defaultRule = DefaultClassificationCatalog.publicAutoCategoryRules.first()
        assertTrue(defaultRule.isDefault)
        DefaultClassificationSeeder.seedRulesForExistingCategories(database.openHelper.writableDatabase)
        DefaultClassificationSeeder.seedRulesForExistingCategories(database.openHelper.writableDatabase)
        database.autoCategoryRuleDao().upsert(
            AutoCategoryRule(
                id = "user-rule",
                name = "使用者規則",
                descriptionContains = defaultRule.descriptionContains,
                categoryId = defaultRule.categoryId,
                priority = 999,
            ),
        )

        val ordered = database.autoCategoryRuleDao().getEnabledInPriorityOrder()
        assertEquals(listOf("user-rule", defaultRule.id), ordered.take(2).map { it.rule.id })
        assertFalse(ordered.first().rule.isDefault)
        assertTrue(ordered.last().rule.isDefault)
        assertEquals(defaultRule.name, ordered[1].rule.name)
        assertEquals(17, ordered.size)
        assertEquals(ordered.map { it.rule.id }, database.autoCategoryRuleDao().observeAll().first().map { it.rule.id })

        assertTrue(
            database.autoCategoryRuleDao()
                .insertIfAbsent(defaultRule.copy(id = "insert-if-absent", name = "原始名稱")) != -1L,
        )
        assertEquals(-1, database.autoCategoryRuleDao().insertIfAbsent(defaultRule.copy(id = "insert-if-absent", name = "不可覆寫")))
        assertEquals(
            "原始名稱",
            database.autoCategoryRuleDao().observeAll().first()
                .first { it.rule.id == "insert-if-absent" }
                .rule.name,
        )
    }
}
