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
import tw.kevinzhang.core.data.db.DefaultClassificationCatalog
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.db.RoomClassificationTraceStore
import tw.kevinzhang.core.data.db.TransferClassificationCandidate
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.core.data.model.ClassificationOutcome
import tw.kevinzhang.core.data.model.ClassificationTrigger
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
                    reportingGroup = CategoryReportingGroup.EXCLUDED,
                ),
            )
        }
        categorizer = AutoCategorizer(
            transferDao = database.transferDao(),
            annotationDao = database.transferAnnotationDao(),
            ruleDao = database.autoCategoryRuleDao(),
            categoryDao = database.categoryDao(),
            ruleSetDao = database.autoCategoryRuleSetDao(),
            classificationTraceStore = RoomClassificationTraceStore(
                database,
                database.transferAnnotationDao(),
                database.ingestionProvenanceDao(),
            ),
        )
    }

    @Test
    fun `records every enabled rule and complete condition actual values`() = runBlocking {
        val food = Category("food", "餐飲", "#2E7D32")
        database.categoryDao().upsert(food)
        database.autoCategoryRuleDao().upsertWithDetails(
            rule = AutoCategoryRule(
                id = "merchant-rule",
                name = "商家",
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
                categoryId = food.id,
            ),
            conditions = listOf(
                AutoCategoryRuleCondition(
                    ruleId = "merchant-rule",
                    position = 0,
                    conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    field = AutoCategoryRuleConditionField.MERCHANT_NAME,
                    matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
                    pattern = "fictional",
                ),
            ),
            tagIds = emptySet(),
        )
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "nonmatching-rule",
                name = "不符合",
                descriptionContains = "absent",
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
                categoryId = food.id,
            ),
            emptySet(),
        )
        val transfer = transfer("trace-rules", "anything", -10.0)
            .copy(merchantName = "Fictional Merchant")
        database.transferDao().upsertAll(listOf(transfer))

        categorizer.categorizeTransferIds(listOf(transfer.id), "run-evaluation")

        val rules = database.ingestionProvenanceDao().getRuleEvaluations(transfer.id)
        assertEquals(setOf("merchant-rule", "nonmatching-rule"), rules.map { it.ruleId }.toSet())
        assertTrue(rules.single { it.ruleId == "merchant-rule" }.selected)
        assertFalse(rules.single { it.ruleId == "nonmatching-rule" }.matched)
        database.ingestionProvenanceDao().getConditionEvaluations(transfer.id).single().also {
            assertEquals("MERCHANT_NAME", it.field)
            assertEquals("""["Fictional Merchant"]""", it.candidateValuesJson)
            assertTrue(it.matched)
        }
        Unit
    }

    @Test
    fun `annotation and evaluation trace roll back together when trace insert fails`() = runBlocking {
        val category = Category("rollback-category", "Rollback", "#000000")
        database.categoryDao().upsert(category)
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "rollback-rule",
                name = "Rollback rule",
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
                categoryId = category.id,
            ),
            emptySet(),
        )
        val transfer = transfer("rollback-transfer", "anything", -10.0)
        database.transferDao().upsertAll(listOf(transfer))
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_classification_trace
            BEFORE INSERT ON classification_rule_evaluations
            BEGIN SELECT RAISE(ABORT, 'fictional trace failure'); END
            """.trimIndent(),
        )

        val failed = runCatching {
            categorizer.categorizeTransferIds(listOf(transfer.id), "rollback-run")
        }.isFailure

        assertTrue(failed)
        assertTrue(database.transferAnnotationDao().getByTransferIds(listOf(transfer.id)).isEmpty())
        assertTrue(database.ingestionProvenanceDao().getTransferAnnotationEvents(transfer.id).isEmpty())
        assertTrue(database.ingestionProvenanceDao().getRuleEvaluations(transfer.id).isEmpty())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `stronger matching rule assigns category and provenance while manual save remains authoritative`() = runBlocking {
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
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
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
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
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
            assertEquals("coffee", detail.annotation?.autoRuleId)
            assertEquals(55, detail.annotation?.autoMatchScore)
            assertEquals("rules-v2", detail.annotation?.classifierVersion)
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
            assertNull(detail.annotation?.autoRuleId)
            assertNull(detail.annotation?.autoMatchScore)
            assertFalse(detail.tags.isNotEmpty())
        }
        Unit
    }

    @Test
    fun `ingestion and manual decisions append traceable immutable events`() = runBlocking {
        val food = Category("food", "餐飲", "#2E7D32")
        database.categoryDao().upsert(food)
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "coffee",
                name = "咖啡",
                descriptionContains = "coffee",
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
                categoryId = food.id,
            ),
            emptySet(),
        )
        val transfer = transfer("traceable", "Coffee", -100.0)
        database.transferDao().upsertAll(listOf(transfer))

        categorizer.categorizeTransferIds(listOf(transfer.id), "run-1")

        val automatic = database.ingestionProvenanceDao()
            .getTransferAnnotationEvents(transfer.id)
            .single()
        assertEquals("run-1", automatic.runId)
        assertEquals(ClassificationTrigger.INGESTION, automatic.trigger)
        assertEquals(ClassificationOutcome.AUTO_APPLIED, automatic.outcome)
        assertEquals("coffee", automatic.ruleId)
        assertEquals(64, automatic.ruleContentSha256?.length)

        database.transferAnnotationDao().saveManualAnnotation(
            TransferAnnotation(
                transferId = transfer.id,
                extensionId = transfer.extensionId,
                categoryId = null,
                categoryAssignment = AssignmentSource.MANUAL,
            ),
            emptySet(),
        )

        val manual = database.ingestionProvenanceDao()
            .getTransferAnnotationEvents(transfer.id)
            .first { it.trigger == ClassificationTrigger.MANUAL_EDIT }
        assertEquals(ClassificationTrigger.MANUAL_EDIT, manual.trigger)
        assertEquals(ClassificationOutcome.MANUAL_CLEARED, manual.outcome)
        assertEquals(food.id, manual.previousCategoryId)
        assertNull(manual.newCategoryId)
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
    fun `matcher normalizes transaction text and keeps amount-sign amount and account conditions`() {
        val matching = ruleWithTags(
            AutoCategoryRule(
                id = "matching",
                name = "matching",
                descriptionContains = "SHOP",
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
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
    fun `v2 matcher normalizes structured facts and honours include groups without joining fields`() {
        val category = Category("food", "餐飲", "#2E7D32")
        val rule = v2Rule(
            id = "structured-food",
            category = category,
            conditions = listOf(
                condition(0, AutoCategoryRuleConditionGroup.INCLUDE_ALL, AutoCategoryRuleConditionField.MERCHANT_NAME, AutoCategoryRuleConditionMatchMode.EXACT, "ＡＣＭＥ—ＭＡＲＫＥＴ"),
                condition(1, AutoCategoryRuleConditionGroup.INCLUDE_ALL, AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE, AutoCategoryRuleConditionMatchMode.EXACT, "５４１１"),
                condition(2, AutoCategoryRuleConditionGroup.INCLUDE_ANY, AutoCategoryRuleConditionField.PURPOSE, AutoCategoryRuleConditionMatchMode.TOKEN, "日常 消費"),
                condition(3, AutoCategoryRuleConditionGroup.EXCLUDE_ANY, AutoCategoryRuleConditionField.STATUS, AutoCategoryRuleConditionMatchMode.EXACT, "撤銷"),
            ),
        )
        val candidate = classificationCandidate(
            transfer(
                id = "structured",
                description = "無關文字",
                amount = -80.0,
                merchantName = "Acme Market",
                merchantCategoryCode = "5411",
                purpose = "日常，消費",
            ),
        )

        assertTrue(listOf(rule).classificationDecision(candidate) is ClassificationDecision.AutoApply)
        assertNull(
            listOf(rule).classificationDecision(
                candidate.copy(transfer = candidate.transfer.copy(status = "撤銷")),
            ),
        )
        assertNull(
            listOf(rule).classificationDecision(
                candidate.copy(
                    transfer = candidate.transfer.copy(
                        merchantName = "Acme",
                        purpose = "Market 日常 消費",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `searchable text matches each textual fact without crossing field boundaries`() {
        val food = Category("food", "餐飲", "#2E7D32")
        val rule = v2Rule(
            id = "searchable-food",
            category = food,
            conditions = listOf(
                condition(
                    0,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.SEARCHABLE_TEXT,
                    AutoCategoryRuleConditionMatchMode.TOKEN,
                    "餐飲 消費",
                ),
            ),
        )
        val candidate = classificationCandidate(
            transfer(
                id = "searchable",
                description = "無關文字",
                amount = -80.0,
                merchantName = "公開商家",
                purpose = "餐飲，消費",
            ),
        )

        assertTrue(listOf(rule).classificationDecision(candidate) is ClassificationDecision.AutoApply)
        assertNull(
            listOf(rule).classificationDecision(
                candidate.copy(
                    transfer = candidate.transfer.copy(
                        description = "餐飲",
                        purpose = "消費",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `v2 matcher abstains on cross category evidence ties and insufficient margins including legacy rules`() {
        val food = Category("food", "餐飲", "#2E7D32")
        val transport = Category("transport", "交通", "#1565C0")
        val candidate = classificationCandidate(
            transfer("collision", "ACME MARKET", -100.0, merchantName = "Acme Market"),
        )
        val structured = v2Rule(
            id = "structured",
            category = food,
            conditions = listOf(condition(0, AutoCategoryRuleConditionGroup.INCLUDE_ANY, AutoCategoryRuleConditionField.MERCHANT_NAME, AutoCategoryRuleConditionMatchMode.EXACT, "ACME MARKET")),
        )
        val text = v2Rule(
            id = "text",
            category = transport,
            conditions = listOf(condition(0, AutoCategoryRuleConditionGroup.INCLUDE_ANY, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.EXACT, "ACME MARKET")),
        )
        assertTrue(listOf(structured, text).classificationDecision(candidate) is ClassificationDecision.Abstain)

        val legacyFood = ruleWithTags(
            AutoCategoryRule("legacy-food", "舊餐飲", descriptionContains = "ACME", categoryId = food.id),
            food,
        )
        val legacyTransport = ruleWithTags(
            AutoCategoryRule("legacy-transport", "舊交通", descriptionContains = "ACME", categoryId = transport.id),
            transport,
        )
        assertTrue(listOf(legacyFood, legacyTransport).classificationDecision(candidate) is ClassificationDecision.Abstain)
    }

    @Test
    fun `tag only rule does not create a false cross category collision`() {
        val food = Category("food", "餐飲", "#2E7D32")
        val candidate = classificationCandidate(transfer("tagged", "ACME", -100.0))
        val condition = condition(
            0,
            AutoCategoryRuleConditionGroup.INCLUDE_ANY,
            AutoCategoryRuleConditionField.DESCRIPTION,
            AutoCategoryRuleConditionMatchMode.EXACT,
            "ACME",
        )
        val tagOnly = AutoCategoryRuleWithTags(
            rule = AutoCategoryRule(
                id = "a-tag-only",
                name = "標籤",
                origin = AutoCategoryRuleOrigin.USER_CONFIRMED,
            ),
            category = null,
            tags = listOf(Tag("tag", "自動標籤", "#1565C0")),
            conditions = listOf(condition),
        )
        val categorized = v2Rule(
            id = "category",
            category = food,
            conditions = listOf(condition.copy(ruleId = "category")),
        )

        val decision = listOf(tagOnly, categorized).classificationDecision(candidate)

        assertTrue(decision is ClassificationDecision.AutoApply)
        assertEquals(
            "a-tag-only",
            (decision as ClassificationDecision.AutoApply).evaluation.ruleWithTags.rule.id,
        )
    }

    @Test
    fun `v2 matcher enforces action amount sign account kind extension and category guards`() {
        val expense = Category("expense", "餐飲", "#2E7D32")
        val income = Category("income", "收入", "#1565C0", reportingGroup = CategoryReportingGroup.INCOME)
        val candidate = classificationCandidate(
            transfer("scoped", "薪資", 100.0, extensionId = "target-extension"),
            accountKind = AssetKind.CREDIT_CARD,
        )
        val automatic = v2Rule(
            id = "automatic",
            category = income,
            action = AutoCategoryRuleAction.AUTO_APPLY,
            amountSign = AutoCategoryRuleAmountSign.POSITIVE,
            accountKind = AssetKind.CREDIT_CARD,
            extensionId = "target-extension",
            conditions = listOf(condition(0, AutoCategoryRuleConditionGroup.INCLUDE_ANY, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.EXACT, "薪資")),
        )
        assertTrue(listOf(automatic).classificationDecision(candidate) is ClassificationDecision.AutoApply)
        assertNull(listOf(automatic).classificationDecision(candidate.copy(accountKind = AssetKind.DEPOSIT)))
        assertNull(listOf(automatic).classificationDecision(candidate.copy(transfer = candidate.transfer.copy(extensionId = "other-extension"))))

        val wrongKind = v2Rule(
            id = "wrong-kind",
            category = expense,
            amountSign = AutoCategoryRuleAmountSign.POSITIVE,
            conditions = listOf(condition(0, AutoCategoryRuleConditionGroup.INCLUDE_ANY, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.EXACT, "薪資")),
        )
        assertNull(listOf(wrongKind).classificationDecision(candidate))
    }

    @Test
    fun `category reporting group is the sole category compatibility authority`() {
        val income = Category(
            "income",
            "收入",
            "#1565C0",
            reportingGroup = CategoryReportingGroup.INCOME,
        )
        val expense = Category("expense", "支出", "#D32F2F")
        val excluded = Category(
            "excluded",
            "不統計",
            "#607D8B",
            reportingGroup = CategoryReportingGroup.EXCLUDED,
        )

        fun isCompatible(category: Category, amount: Double): Boolean {
            val rule = v2Rule(
                id = "${category.id}-$amount",
                category = category,
                conditions = listOf(
                    condition(
                        0,
                        AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                        AutoCategoryRuleConditionField.DESCRIPTION,
                        AutoCategoryRuleConditionMatchMode.EXACT,
                        "compatible",
                    ),
                ),
            )
            return listOf(rule).classificationDecision(
                classificationCandidate(transfer("${category.id}-$amount", "compatible", amount)),
            ) is ClassificationDecision.AutoApply
        }

        assertTrue(isCompatible(income, 1.0))
        assertFalse(isCompatible(expense, 1.0))
        assertTrue(isCompatible(excluded, 1.0))
        assertFalse(isCompatible(income, -1.0))
        assertTrue(isCompatible(expense, -1.0))
        assertTrue(isCompatible(excluded, -1.0))
        assertFalse(isCompatible(income, 0.0))
        assertFalse(isCompatible(expense, 0.0))
        assertTrue(isCompatible(excluded, 0.0))
    }

    @Test
    fun `default financial patterns retain precise reporting-group and amount-sign boundaries`() {
        val interest = Category(
            "income-interest",
            "利息收入",
            "#1565C0",
            reportingGroup = CategoryReportingGroup.INCOME,
        )
        val cashback = Category(
            "income-cashback",
            "現金回饋",
            "#1565C0",
            reportingGroup = CategoryReportingGroup.INCOME,
        )
        val cash = Category("expense-cash", "現金消費", "#D32F2F")
        val accountTransfer = Category(
            "transfer-account",
            "帳戶移轉",
            "#607D8B",
            reportingGroup = CategoryReportingGroup.EXCLUDED,
        )
        val depositInterest = v2Rule(
            id = "deposit-interest",
            category = interest,
            amountSign = AutoCategoryRuleAmountSign.POSITIVE,
            accountKind = AssetKind.DEPOSIT,
            conditions = listOf(
                condition(
                    0,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.DESCRIPTION,
                    AutoCategoryRuleConditionMatchMode.CONTAINS,
                    "存款利息",
                ),
                condition(
                    1,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.DESCRIPTION,
                    AutoCategoryRuleConditionMatchMode.CONTAINS,
                    "優惠利息",
                ),
                condition(
                    2,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.DESCRIPTION,
                    AutoCategoryRuleConditionMatchMode.CONTAINS,
                    "存款息",
                ),
            ),
        )
        val cardCashback = v2Rule(
            id = "card-cashback",
            category = cashback,
            amountSign = AutoCategoryRuleAmountSign.POSITIVE,
            accountKind = AssetKind.CREDIT_CARD,
            conditions = listOf(
                condition(
                    0,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.DESCRIPTION,
                    AutoCategoryRuleConditionMatchMode.CONTAINS,
                    "刷卡現金回饋",
                ),
            ),
        )
        val cashWithdrawal = v2Rule(
            id = "cash-withdrawal",
            category = cash,
            amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
            accountKind = AssetKind.DEPOSIT,
            conditions = listOf(
                condition(
                    0,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.DESCRIPTION,
                    AutoCategoryRuleConditionMatchMode.CONTAINS,
                    "提款",
                ),
                condition(
                    1,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.DESCRIPTION,
                    AutoCategoryRuleConditionMatchMode.CONTAINS,
                    "跨提",
                ),
                condition(
                    2,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.DESCRIPTION,
                    AutoCategoryRuleConditionMatchMode.CONTAINS,
                    "現金提",
                ),
            ),
        )
        val cashDeposit = v2Rule(
            id = "cash-deposit",
            category = accountTransfer,
            amountSign = AutoCategoryRuleAmountSign.POSITIVE,
            accountKind = AssetKind.DEPOSIT,
            conditions = listOf(
                condition(
                    0,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.DESCRIPTION,
                    AutoCategoryRuleConditionMatchMode.CONTAINS,
                    "atm存",
                ),
                condition(
                    1,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.DESCRIPTION,
                    AutoCategoryRuleConditionMatchMode.CONTAINS,
                    "cdm存款",
                ),
            ),
        )
        val easyCardTopUp = v2Rule(
            id = "easycard-topup",
            category = accountTransfer,
            conditions = listOf(
                condition(
                    0,
                    AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    AutoCategoryRuleConditionField.DESCRIPTION,
                    AutoCategoryRuleConditionMatchMode.CONTAINS,
                    "代扣 悠遊儲值",
                ),
            ),
        )

        fun selectedCategoryId(
            transfer: Transfer,
            accountKind: AssetKind,
        ): String? = ((listOf(depositInterest, cardCashback, cashWithdrawal, cashDeposit, easyCardTopUp)
            .classificationDecision(classificationCandidate(transfer, accountKind = accountKind))
            as? ClassificationDecision.AutoApply)
            ?.evaluation?.ruleWithTags?.rule?.categoryId)

        assertEquals(
            interest.id,
            selectedCategoryId(transfer("interest", "存款利息", 10.0), AssetKind.DEPOSIT),
        )
        assertEquals(
            interest.id,
            selectedCategoryId(transfer("promotional-interest", "優惠利息", 10.0), AssetKind.DEPOSIT),
        )
        assertEquals(
            interest.id,
            selectedCategoryId(transfer("deposit-interest-short", "存款息", 10.0), AssetKind.DEPOSIT),
        )
        assertNull(
            selectedCategoryId(transfer("revolving", "循環信用利息", -10.0), AssetKind.CREDIT_CARD),
        )
        assertEquals(
            cashback.id,
            selectedCategoryId(transfer("cashback", "刷卡現金回饋", 25.0), AssetKind.CREDIT_CARD),
        )
        assertNull(
            selectedCategoryId(transfer("cashback-negative", "刷卡現金回饋", -25.0), AssetKind.CREDIT_CARD),
        )
        assertEquals(
            cash.id,
            selectedCategoryId(transfer("atm", "ATM 提款", -100.0), AssetKind.DEPOSIT),
        )
        assertEquals(
            cash.id,
            selectedCategoryId(transfer("interbank", "跨提 手續", -100.0), AssetKind.DEPOSIT),
        )
        assertEquals(
            cash.id,
            selectedCategoryId(transfer("cash", "現金提領", -100.0), AssetKind.DEPOSIT),
        )
        assertNull(
            selectedCategoryId(transfer("positive-withdrawal", "ATM 提款", 100.0), AssetKind.DEPOSIT),
        )
        assertEquals(
            accountTransfer.id,
            selectedCategoryId(transfer("atm-deposit", "ＡＴＭ存", 100.0), AssetKind.DEPOSIT),
        )
        assertEquals(
            accountTransfer.id,
            selectedCategoryId(transfer("cdm-deposit", "CDM存款", 100.0), AssetKind.DEPOSIT),
        )
        assertNull(
            selectedCategoryId(transfer("negative-deposit", "ＡＴＭ存", -100.0), AssetKind.DEPOSIT),
        )
        assertEquals(
            accountTransfer.id,
            selectedCategoryId(transfer("easycard-negative", "代扣：悠遊儲值 全家", -200.0), AssetKind.DEPOSIT),
        )
        assertEquals(
            accountTransfer.id,
            selectedCategoryId(transfer("easycard-positive", "代扣：悠遊儲值 統一", 200.0), AssetKind.LOAN),
        )
        assertEquals(
            accountTransfer.id,
            selectedCategoryId(transfer("easycard-credit-card", "代扣：悠遊儲值", -200.0), AssetKind.CREDIT_CARD),
        )
        assertNull(
            selectedCategoryId(transfer("other-topup", "代扣：一卡通儲值", -200.0), AssetKind.DEPOSIT),
        )
    }

    @Test
    fun `aggressive defaults preserve precise winners while covering broad fallbacks`() {
        val categories = DefaultClassificationCatalog.categories.associateBy { it.id }
        val rules = (
            DefaultClassificationCatalog.publicStructuralRules +
                DefaultClassificationCatalog.publicGenericRules
            ).map { publicRule ->
            AutoCategoryRuleWithTags(
                rule = publicRule.rule,
                category = categories[publicRule.rule.categoryId],
                tags = emptyList(),
                conditions = publicRule.conditions,
            )
        }

        fun selectedCategory(
            description: String,
            amount: Double,
            accountKind: AssetKind,
            memo: String = "",
        ): String? = (
            rules.classificationDecision(
                classificationCandidate(
                    transfer(
                        id = "$description-$amount",
                        description = description,
                        amount = amount,
                        memo = memo,
                    ),
                    accountKind = accountKind,
                ),
            ) as? ClassificationDecision.AutoApply
            )?.evaluation?.ruleWithTags?.rule?.categoryId

        assertEquals(
            "transfer-account",
            selectedCategory("連結帳戶交易", -500.0, AssetKind.DEPOSIT),
        )
        assertEquals(
            "transfer-account",
            selectedCategory("繳費轉出", -500.0, AssetKind.DEPOSIT, "繳費名稱：信用卡費"),
        )
        assertEquals(
            "income-refund",
            selectedCategory("未辨識商戶退款", 500.0, AssetKind.CREDIT_CARD),
        )
        assertEquals(
            "income-cashback",
            selectedCategory("刷卡現金回饋", 50.0, AssetKind.CREDIT_CARD),
        )
        assertEquals(
            "expense-digital-wallet",
            selectedCategory("街口電支－未辨識商戶", -120.0, AssetKind.CREDIT_CARD),
        )
        assertEquals(
            "expense-food",
            selectedCategory("街口電支－全家便利商店", -120.0, AssetKind.CREDIT_CARD),
        )
    }

    @Test
    fun `matching abstain rule vetoes a stronger automatic classification`() {
        val food = Category("food", "餐飲", "#2E7D32")
        val candidate = classificationCandidate(transfer("veto", "ACME", -50.0, merchantName = "Acme"))
        val automatic = v2Rule(
            id = "automatic",
            category = food,
            conditions = listOf(condition(0, AutoCategoryRuleConditionGroup.INCLUDE_ANY, AutoCategoryRuleConditionField.MERCHANT_NAME, AutoCategoryRuleConditionMatchMode.EXACT, "ACME")),
        )
        val veto = v2Rule(
            id = "veto",
            category = food,
            action = AutoCategoryRuleAction.ABSTAIN,
            conditions = listOf(condition(0, AutoCategoryRuleConditionGroup.INCLUDE_ANY, AutoCategoryRuleConditionField.DESCRIPTION, AutoCategoryRuleConditionMatchMode.EXACT, "ACME")),
        )

        assertTrue(listOf(automatic, veto).classificationDecision(candidate) is ClassificationDecision.Abstain)
    }

    @Test
    fun `automatic rule replaces an existing automatic annotation`() = runBlocking {
        val income = Category("income", "收入", "#1565C0", reportingGroup = CategoryReportingGroup.INCOME)
        val existing = Category("existing", "既有", "#2E7D32", reportingGroup = CategoryReportingGroup.INCOME)
        database.categoryDao().upsert(income)
        database.categoryDao().upsert(existing)
        val rule = AutoCategoryRule(
            id = "salary-automatic",
            name = "薪資分類",
            amountSign = AutoCategoryRuleAmountSign.POSITIVE,
            categoryId = income.id,
            action = AutoCategoryRuleAction.AUTO_APPLY,
        )
        database.autoCategoryRuleDao().upsertWithDetails(
            rule,
            listOf(
                AutoCategoryRuleCondition(
                    ruleId = rule.id,
                    position = 0,
                    conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    field = AutoCategoryRuleConditionField.DESCRIPTION,
                    matchMode = AutoCategoryRuleConditionMatchMode.EXACT,
                    pattern = "薪資入帳",
                ),
            ),
            emptySet(),
        )
        val transfer = transfer("suggested", "薪資入帳", 100.0)
        database.transferDao().upsertAll(listOf(transfer))
        val annotation = TransferAnnotation(
            transferId = transfer.id,
            extensionId = transfer.extensionId,
            categoryId = existing.id,
        )
        database.transferAnnotationDao().upsert(annotation)

        categorizer.applyToExistingTransactions()

        assertEquals(income.id, database.transferAnnotationDao().observeDetail(transfer.id).first()!!.annotation?.categoryId)
    }

    @Test
    fun `legacy canonicalizer retains punctuation removal while v2 canonicalizer separates tokens`() {
        assertEquals("coffeeshop", normalizeLegacyAutoCategoryRuleText("Ｃｏｆｆｅｅ—Ｓｈｏｐ"))
        assertEquals(
            "coffee shop",
            tw.kevinzhang.core.data.model.normalizeAutoCategoryRuleTextV2("Ｃｏｆｆｅｅ—Ｓｈｏｐ"),
        )
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

    private fun ruleWithTags(rule: AutoCategoryRule, category: Category? = null) = AutoCategoryRuleWithTags(rule, category, emptyList())

    private fun v2Rule(
        id: String,
        category: Category,
        conditions: List<AutoCategoryRuleCondition>,
        action: AutoCategoryRuleAction = AutoCategoryRuleAction.AUTO_APPLY,
        amountSign: AutoCategoryRuleAmountSign = AutoCategoryRuleAmountSign.ANY,
        accountKind: AssetKind? = null,
        extensionId: String? = null,
    ) = AutoCategoryRuleWithTags(
        rule = AutoCategoryRule(
            id = id,
            name = id,
            categoryId = category.id,
            action = action,
            origin = AutoCategoryRuleOrigin.USER_CONFIRMED,
            amountSign = amountSign,
            accountKind = accountKind,
            extensionId = extensionId,
        ),
        category = category,
        tags = emptyList(),
        conditions = conditions,
    )

    private fun condition(
        position: Int,
        group: AutoCategoryRuleConditionGroup,
        field: AutoCategoryRuleConditionField,
        mode: AutoCategoryRuleConditionMatchMode,
        pattern: String,
    ) = AutoCategoryRuleCondition("unused-in-direct-matcher-test", position, group, field, mode, pattern)

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
        status: String? = null,
        merchantName: String? = null,
        merchantCategoryCode: String? = null,
        counterpartyName: String? = null,
        purpose: String? = null,
        extensionId: String = "extension",
    ) = Transfer(
        id = id,
        accountId = accountId,
        extensionId = extensionId,
        txnDateTime = txnDateTime,
        description = description,
        amount = amount,
        balance = null,
        memo = memo,
        type = type,
        status = status,
        merchantName = merchantName,
        merchantCategoryCode = merchantCategoryCode,
        counterpartyName = counterpartyName,
        purpose = purpose,
    )

    private fun classificationCandidate(
        transfer: Transfer,
        currency: String = "TWD",
        accountKind: AssetKind = AssetKind.DEPOSIT,
    ) = TransferClassificationCandidate(transfer, currency, accountKind)
}
