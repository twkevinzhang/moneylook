package tw.kevinzhang.moneylook.sync

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.db.AutoCategoryRuleWithTags
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation

@RunWith(RobolectricTestRunner::class)
class AutoCategorizerTest {
    private lateinit var database: MoneylookDatabase
    private lateinit var categorizer: AutoCategorizer

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
        categorizer = AutoCategorizer(
            transferDao = database.transferDao(),
            annotationDao = database.transferAnnotationDao(),
            ruleDao = database.autoCategoryRuleDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `first matching rule assigns category and tags while a manual save remains authoritative`() = runBlocking {
        val food = Category("food", "餐飲", "#2E7D32")
        val other = Category("other", "其他", "#1565C0")
        val work = Tag("work", "公司", "#6A1B9A")
        database.categoryDao().upsert(food)
        database.categoryDao().upsert(other)
        database.tagDao().upsert(work)
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "coffee",
                name = "咖啡",
                descriptionContains = "COFFEE",
                direction = AutoCategoryRuleDirection.EXPENSE,
                minAbsoluteAmount = 100.0,
                maxAbsoluteAmount = 200.0,
                accountId = "account",
                categoryId = food.id,
                priority = 0,
            ),
            setOf(work.id),
        )
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "fallback",
                name = "所有支出",
                direction = AutoCategoryRuleDirection.EXPENSE,
                categoryId = other.id,
                priority = 10,
            ),
            emptySet(),
        )
        val transfer = transfer(id = "transfer", description = "Coffee shop", amount = -150.0)
        database.transferDao().upsertAll(listOf(transfer))

        categorizer.categorizeTransferIds(listOf(transfer.id))

        database.transferAnnotationDao().observeDetail(transfer.id).first()!!.also { detail ->
            assertEquals(food.id, detail.annotation?.categoryId)
            assertEquals(AssignmentSource.AUTO, detail.annotation?.categoryAssignment)
            assertEquals(listOf(work.id), detail.tags.map(Tag::id))
        }

        database.transferAnnotationDao().saveManualAnnotation(
            TransferAnnotation(
                transferId = transfer.id,
                extensionId = transfer.extensionId,
                categoryId = other.id,
                note = "手動備註",
                categoryAssignment = AssignmentSource.MANUAL,
                manualOverride = true,
            ),
            emptySet(),
        )
        categorizer.categorizeTransferIds(listOf(transfer.id))

        database.transferAnnotationDao().observeDetail(transfer.id).first()!!.also { detail ->
            assertEquals(other.id, detail.annotation?.categoryId)
            assertEquals("手動備註", detail.annotation?.note)
            assertEquals(AssignmentSource.MANUAL, detail.annotation?.categoryAssignment)
            assertFalse(detail.tags.isNotEmpty())
        }
        Unit
    }

    @Test
    fun `rule removal clears only automatic output and preserves note`() = runBlocking {
        val category = Category("category", "交通", "#1565C0")
        database.categoryDao().upsert(category)
        val rule = AutoCategoryRule(
            id = "rule",
            name = "交通",
            descriptionContains = "捷運",
            categoryId = category.id,
        )
        database.autoCategoryRuleDao().upsertWithTags(rule, emptySet())
        val transfer = transfer(id = "metro", description = "捷運加值", amount = -500.0)
        database.transferDao().upsertAll(listOf(transfer))
        database.transferAnnotationDao().upsert(
            TransferAnnotation(transfer.id, transfer.extensionId, note = "保留"),
        )

        categorizer.categorizeTransferIds(listOf(transfer.id))
        database.autoCategoryRuleDao().deleteById(rule.id)
        categorizer.categorizeTransferIds(listOf(transfer.id))

        database.transferAnnotationDao().observeDetail(transfer.id).first()!!.annotation!!.also { annotation ->
            assertNull(annotation.categoryId)
            assertEquals("保留", annotation.note)
            assertEquals(AssignmentSource.AUTO, annotation.categoryAssignment)
        }
        Unit
    }

    @Test
    fun `applying all existing transactions reports safe counts and preserves manual edits`() = runBlocking {
        val category = Category("food", "餐飲", "#2E7D32")
        val automaticTag = Tag("automatic", "自動", "#1565C0")
        val manualTag = Tag("manual", "手動", "#6A1B9A")
        database.categoryDao().upsert(category)
        database.tagDao().upsert(automaticTag)
        database.tagDao().upsert(manualTag)
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "coffee",
                name = "咖啡",
                descriptionContains = "COFFEE",
                categoryId = category.id,
            ),
            setOf(automaticTag.id),
        )
        val automatic = transfer("automatic", "Coffee shop", -100.0)
        val manual = transfer("manual", "Coffee shop", -200.0)
        val unmatched = transfer("unmatched", "Other shop", -300.0)
        database.transferDao().upsertAll(listOf(automatic, manual, unmatched))
        database.transferAnnotationDao().saveManualAnnotation(
            TransferAnnotation(
                transferId = manual.id,
                extensionId = manual.extensionId,
                categoryId = null,
                note = "保留備註",
                categoryAssignment = AssignmentSource.MANUAL,
                manualOverride = true,
            ),
            setOf(manualTag.id),
        )

        val result = categorizer.applyToExistingTransactions()

        assertEquals(3, result.processedTransferCount)
        assertEquals(1, result.matchedTransferCount)
        assertEquals(1, result.preservedManualOverrideCount)
        database.transferAnnotationDao().observeDetail(automatic.id).first()!!.also { detail ->
            assertEquals(category.id, detail.annotation?.categoryId)
            assertEquals(listOf(automaticTag.id), detail.tags.map(Tag::id))
        }
        database.transferAnnotationDao().observeDetail(manual.id).first()!!.also { detail ->
            assertEquals("保留備註", detail.annotation?.note)
            assertEquals(AssignmentSource.MANUAL, detail.annotation?.categoryAssignment)
            assertEquals(listOf(manualTag.id), detail.tags.map(Tag::id))
        }
        assertNull(database.transferAnnotationDao().observeDetail(unmatched.id).first()!!.annotation)
        Unit
    }

    @Test
    fun `matcher uses case insensitive AND conditions absolute inclusive bounds and excludes zero direction`() {
        val matching = ruleWithTags(
            AutoCategoryRule(
                id = "matching",
                name = "matching",
                descriptionContains = "SHOP",
                direction = AutoCategoryRuleDirection.EXPENSE,
                minAbsoluteAmount = 100.0,
                maxAbsoluteAmount = 100.0,
                accountId = "account",
                categoryId = "category",
            ),
        )

        assertEquals(true, matching.matches(transfer("one", "shop", -100.0)))
        assertEquals(false, matching.matches(transfer("zero", "shop", 0.0)))
        assertEquals(false, matching.matches(transfer("income", "shop", 100.0)))
        assertEquals(false, matching.matches(transfer("amount", "shop", -101.0)))
        assertEquals(false, matching.matches(transfer("description", "market", -100.0)))
    }

    @Test
    fun `exact description matching accepts normalized full description only`() {
        val matching = ruleWithTags(
            AutoCategoryRule(
                id = "exact",
                name = "exact",
                descriptionContains = "捷運扣款",
                descriptionMatchMode = AutoCategoryRuleDescriptionMatchMode.EXACT,
                categoryId = "category",
            ),
        )

        assertEquals(true, matching.matches(transfer("trimmed", "  捷運扣款  ", -50.0)))
        assertEquals(false, matching.matches(transfer("fragment", "捷運扣款 超商", -50.0)))
    }

    private fun ruleWithTags(rule: AutoCategoryRule) = AutoCategoryRuleWithTags(rule, null, emptyList())

    private fun transfer(id: String, description: String, amount: Double) = Transfer(
        id = id,
        accountId = "account",
        extensionId = "extension",
        txnDateTime = "2026-07-22",
        description = description,
        amount = amount,
        balance = null,
        memo = "",
    )
}
