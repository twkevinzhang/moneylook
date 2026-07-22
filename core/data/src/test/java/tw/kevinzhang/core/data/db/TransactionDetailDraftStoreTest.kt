package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.TransferAnnotation

@RunWith(RobolectricTestRunner::class)
class TransactionDetailDraftStoreTest {
    private lateinit var database: MoneylookDatabase
    private lateinit var store: TransactionDetailDraftStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = TransactionDetailDraftStore(
            database,
            database.tagDao(),
            database.transferAnnotationDao(),
            database.autoCategoryRuleDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `save creates draft tags then atomically applies annotation and optional rule`() = runBlocking {
        database.categoryDao().upsert(Category("food", "餐飲", "#FF9800", "🍽️"))

        val result = store.save(
            TransactionDetailDraftSave(
                annotation = TransferAnnotation(
                    transferId = "transfer",
                    extensionId = "extension",
                    categoryId = "food",
                    note = "午餐",
                    categoryAssignment = AssignmentSource.MANUAL,
                ),
                pendingTags = listOf(PendingTag("報帳", "#1565C0")),
                autoCategoryRule = AutoCategoryRuleSave(
                    rule = AutoCategoryRule(
                        id = "lunch",
                        name = "午餐自動分類",
                        descriptionContains = "午餐",
                        categoryId = "food",
                    ),
                    pendingTagNames = setOf("報帳"),
                ),
            ),
        )

        assertEquals(1, result.createdTags.size)
        assertEquals(setOf(result.createdTags.single().id), result.annotationTagIds)
        assertEquals(result.annotationTagIds, result.ruleTagIds)
        assertEquals("午餐", database.transferAnnotationDao().getByTransferIds(listOf("transfer")).single().note)
        database.autoCategoryRuleDao().getEnabledInPriorityOrder().single().also { rule ->
            assertEquals("lunch", rule.rule.id)
            assertEquals(listOf("報帳"), rule.tags.map { it.name })
        }
        assertTrue(result.createdTags.single().id in result.annotationTagIds)
    }
}
