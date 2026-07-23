package tw.kevinzhang.moneylook.sync

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.db.AutoCategoryRuleWithTags
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.db.TransferClassificationCandidate
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryKind
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
        runBlocking {
            database.accountDao().upsertAll(listOf(account("account")))
            database.categoryDao().upsert(
                Category(
                    id = "transfer-account",
                    name = "帳戶移轉",
                    color = "#607D8B",
                    emoji = "🔄",
                    kind = CategoryKind.TRANSFER,
                ),
            )
        }
        categorizer = AutoCategorizer(
            transferDao = database.transferDao(),
            annotationDao = database.transferAnnotationDao(),
            ruleDao = database.autoCategoryRuleDao(),
            categoryDao = database.categoryDao(),
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
    fun `matcher normalizes transaction text and keeps direction amount and account conditions`() {
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

        assertEquals(true, matching.matches(transfer("one", "Ｓｈｏｐ－１２", -100.0)))
        assertEquals(false, matching.matches(transfer("zero", "shop", 0.0)))
        assertEquals(false, matching.matches(transfer("income", "shop", 100.0)))
        assertEquals(false, matching.matches(transfer("amount", "shop", -101.0)))
        assertEquals(false, matching.matches(transfer("description", "market", -100.0)))
    }

    @Test
    fun `matcher checks normalized description memo and type independently without cross-field joining`() {
        val contains = ruleWithTags(
            AutoCategoryRule(
                id = "counterparty",
                name = "counterparty",
                descriptionContains = "ＣＯＦＦＥＥ—ＳＨＯＰ",
                categoryId = "category",
            ),
        )
        val exact = ruleWithTags(
            AutoCategoryRule(
                id = "type",
                name = "type",
                descriptionContains = "薪資入帳",
                descriptionMatchMode = AutoCategoryRuleDescriptionMatchMode.EXACT,
                categoryId = "category",
            ),
        )

        assertTrue(contains.matches(transfer("memo", "一般扣款", -50.0, memo = "Coffee shop 台北店")))
        assertTrue(exact.matches(transfer("type", "一般扣款", 50.0, type = "薪資－入帳")))
        assertFalse(contains.matches(transfer("split", "Coffee", -50.0, memo = "shop")))
    }

    @Test
    fun `exact transaction text matching accepts a normalized individual field only`() {
        val matching = ruleWithTags(
            AutoCategoryRule(
                id = "exact",
                name = "exact",
                descriptionContains = "捷運扣款",
                descriptionMatchMode = AutoCategoryRuleDescriptionMatchMode.EXACT,
                categoryId = "category",
            ),
        )

        assertEquals(true, matching.matches(transfer("description", "  捷運－扣款  ", -50.0)))
        assertEquals(true, matching.matches(transfer("memo", "其他", -50.0, memo = "捷運 扣款")))
        assertEquals(false, matching.matches(transfer("fragment", "捷運扣款 超商", -50.0)))
        assertFalse(matching.matches(transfer("split", "捷運", -50.0, memo = "扣款")))
    }

    @Test
    fun `mutually unique opposite transactions take priority over ordinary automatic rules`() = runBlocking {
        database.accountDao().upsertAll(listOf(account("incoming-account")))
        val ordinary = Category("ordinary", "一般分類", "#1565C0")
        database.categoryDao().upsert(ordinary)
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "ordinary-rule",
                name = "一般規則",
                descriptionContains = "自有移轉",
                categoryId = ordinary.id,
            ),
            emptySet(),
        )
        val outgoing = transfer(
            id = "outgoing",
            description = "自有移轉",
            amount = -500.0,
            txnDateTime = "2026-07-22T12:00:00",
        )
        val incoming = transfer(
            id = "incoming",
            description = "自有移轉",
            amount = 500.0,
            txnDateTime = "2026-07-22T12:00:30",
            accountId = "incoming-account",
        )
        database.transferDao().upsertAll(listOf(outgoing, incoming))

        val result = categorizer.applyToExistingTransactions()

        assertEquals(2, result.matchedTransferCount)
        assertEquals(
            "transfer-account",
            database.transferAnnotationDao().observeDetail(outgoing.id).first()!!.annotation?.categoryId,
        )
        assertEquals(
            "transfer-account",
            database.transferAnnotationDao().observeDetail(incoming.id).first()!!.annotation?.categoryId,
        )
    }

    @Test
    fun `one-to-many ambiguity leaves every possible transfer pair unclassified`() = runBlocking {
        database.accountDao().upsertAll(
            listOf(account("expense-a"), account("expense-b"), account("income")),
        )
        val transfers = listOf(
            transfer("expense-a", "移轉", -100.0, "2026-07-22T08:00:00", "expense-a"),
            transfer("expense-b", "移轉", -100.0, "2026-07-22T08:00:10", "expense-b"),
            transfer("income", "移轉", 100.0, "2026-07-22T08:00:05", "income"),
        )
        database.transferDao().upsertAll(transfers)

        categorizer.applyToExistingTransactions()

        transfers.forEach { item ->
            assertNull(database.transferAnnotationDao().observeDetail(item.id).first()!!.annotation)
        }
    }

    @Test
    fun `sync finds an existing counterpart and clears a stale automatic transfer when it disappears`() = runBlocking {
        database.accountDao().upsertAll(listOf(account("incoming-account")))
        val outgoing = transfer(
            id = "outgoing",
            description = "移轉",
            amount = -800.0,
            txnDateTime = "2026-07-22T09:30:00",
        )
        val incoming = transfer(
            id = "incoming",
            description = "移轉",
            amount = 800.0,
            txnDateTime = "2026-07-22T09:30:20",
            accountId = "incoming-account",
        )
        database.transferDao().upsertAll(listOf(outgoing, incoming))

        categorizer.categorizeTransferIds(listOf(incoming.id))

        assertEquals(
            "transfer-account",
            database.transferAnnotationDao().observeDetail(outgoing.id).first()!!.annotation?.categoryId,
        )
        assertEquals(
            "transfer-account",
            database.transferAnnotationDao().observeDetail(incoming.id).first()!!.annotation?.categoryId,
        )

        database.openHelper.writableDatabase.execSQL(
            "DELETE FROM transfers WHERE id = ?",
            arrayOf(incoming.id),
        )
        categorizer.categorizeTransferIds(emptyList())

        assertNull(
            database.transferAnnotationDao().observeDetail(outgoing.id).first()!!.annotation?.categoryId,
        )
    }

    @Test
    fun `upgrade backfill classifies existing pairs without applying unrelated ordinary rules`() = runBlocking {
        database.accountDao().upsertAll(listOf(account("incoming-account")))
        val ordinary = Category("ordinary", "一般分類", "#1565C0")
        database.categoryDao().upsert(ordinary)
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "ordinary-rule",
                name = "一般規則",
                descriptionContains = "一般支出",
                categoryId = ordinary.id,
            ),
            emptySet(),
        )
        val outgoing = transfer(
            id = "existing-outgoing",
            description = "自有移轉",
            amount = -600.0,
            txnDateTime = "2026-07-22T11:00:00",
        )
        val incoming = transfer(
            id = "existing-incoming",
            description = "自有移轉",
            amount = 600.0,
            txnDateTime = "2026-07-22T11:00:20",
            accountId = "incoming-account",
        )
        val unrelated = transfer(
            id = "ordinary-expense",
            description = "一般支出",
            amount = -50.0,
            txnDateTime = "2026-07-22T12:00:00",
        )
        database.transferDao().upsertAll(listOf(outgoing, incoming, unrelated))

        assertTrue(categorizer.applyInternalTransferBackfill())

        assertEquals(
            "transfer-account",
            database.transferAnnotationDao().observeDetail(outgoing.id).first()!!.annotation?.categoryId,
        )
        assertEquals(
            "transfer-account",
            database.transferAnnotationDao().observeDetail(incoming.id).first()!!.annotation?.categoryId,
        )
        assertNull(database.transferAnnotationDao().observeDetail(unrelated.id).first()!!.annotation)
    }

    @Test
    fun `upgrade backfill remains retryable when transfer category is unavailable`() = runBlocking {
        database.categoryDao().deleteById("transfer-account")

        assertFalse(categorizer.applyInternalTransferBackfill())
    }

    @Test
    fun `manual category remains authoritative while the automatic counterpart is classified`() = runBlocking {
        database.accountDao().upsertAll(listOf(account("incoming-account")))
        val manualCategory = Category("manual-category", "手動分類", "#1565C0")
        database.categoryDao().upsert(manualCategory)
        val outgoing = transfer(
            id = "manual-outgoing",
            description = "移轉",
            amount = -300.0,
            txnDateTime = "2026-07-22T10:00:00",
        )
        val incoming = transfer(
            id = "automatic-incoming",
            description = "移轉",
            amount = 300.0,
            txnDateTime = "2026-07-22T10:00:10",
            accountId = "incoming-account",
        )
        database.transferDao().upsertAll(listOf(outgoing, incoming))
        database.transferAnnotationDao().saveManualAnnotation(
            TransferAnnotation(
                transferId = outgoing.id,
                extensionId = outgoing.extensionId,
                categoryId = manualCategory.id,
                categoryAssignment = AssignmentSource.MANUAL,
            ),
            emptySet(),
        )

        categorizer.categorizeTransferIds(listOf(incoming.id))

        database.transferAnnotationDao().observeDetail(outgoing.id).first()!!.annotation!!.also {
            assertEquals(manualCategory.id, it.categoryId)
            assertEquals(AssignmentSource.MANUAL, it.categoryAssignment)
        }
        assertEquals(
            "transfer-account",
            database.transferAnnotationDao().observeDetail(incoming.id).first()!!.annotation?.categoryId,
        )
    }

    @Test
    fun `pair detector rejects incomplete mismatched ambiguous and non ISO candidates`() {
        val baselineExpense = classificationCandidate(
            transfer("expense", "移轉", -100.0, "2026-07-22T12:00:00", "expense-account"),
        )
        fun pair(
            incoming: Transfer,
            incomingCurrency: String = "TWD",
        ) = internalTransferCounterparts(
            listOf(baselineExpense, classificationCandidate(incoming, incomingCurrency)),
        )

        assertTrue(
            pair(
                transfer("valid", "移轉", 100.0, "2026-07-22T12:00:30", "income-account"),
                incomingCurrency = " twd ",
            ).isNotEmpty(),
        )
        assertTrue(
            pair(transfer("late", "移轉", 100.0, "2026-07-22T12:00:31", "income-account")).isEmpty(),
        )
        assertTrue(
            pair(transfer("same-account", "移轉", 100.0, "2026-07-22T12:00:20", "expense-account")).isEmpty(),
        )
        assertTrue(
            pair(
                transfer("currency", "移轉", 100.0, "2026-07-22T12:00:20", "income-account"),
                incomingCurrency = "USD",
            ).isEmpty(),
        )
        assertTrue(
            pair(transfer("amount", "移轉", 100.01, "2026-07-22T12:00:20", "income-account")).isEmpty(),
        )
        assertTrue(
            pair(transfer("sign", "移轉", -100.0, "2026-07-22T12:00:20", "income-account")).isEmpty(),
        )
        assertTrue(
            pair(transfer("zero", "移轉", 0.0, "2026-07-22T12:00:20", "income-account")).isEmpty(),
        )
        assertTrue(
            pair(
                transfer(
                    "non-finite",
                    "移轉",
                    Double.POSITIVE_INFINITY,
                    "2026-07-22T12:00:20",
                    "income-account",
                ),
            ).isEmpty(),
        )
        assertTrue(
            pair(transfer("date-only", "移轉", 100.0, "2026-07-22", "income-account")).isEmpty(),
        )
        assertTrue(
            pair(transfer("next-day", "移轉", 100.0, "2026-07-23T00:00:00", "income-account")).isEmpty(),
        )
        assertTrue(
            internalTransferCounterparts(listOf(baselineExpense)).isEmpty(),
        )
    }

    private fun ruleWithTags(rule: AutoCategoryRule) = AutoCategoryRuleWithTags(rule, null, emptyList())

    private fun account(id: String, currency: String = "TWD") = Account(
        id = id,
        extensionId = "extension",
        extensionName = "測試銀行",
        accountName = "測試帳戶",
        balance = 0.0,
        currency = currency,
        lastSyncAt = 0,
    )

    private fun transfer(
        id: String,
        description: String,
        amount: Double,
        txnDateTime: String = "2026-07-22",
        accountId: String = "account",
        memo: String = "",
        type: String? = null,
    ) = Transfer(
        id = id,
        accountId = accountId,
        extensionId = "extension",
        txnDateTime = txnDateTime,
        description = description,
        amount = amount,
        balance = null,
        memo = memo,
        type = type,
    )

    private fun classificationCandidate(
        transfer: Transfer,
        currency: String = "TWD",
    ) = TransferClassificationCandidate(transfer, currency)
}
