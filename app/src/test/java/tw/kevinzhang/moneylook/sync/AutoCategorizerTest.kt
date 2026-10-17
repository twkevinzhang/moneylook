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
import tw.kevinzhang.core.data.db.RoomClassificationCatalogResetStore
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
import tw.kevinzhang.core.data.model.ClassificationDetailState
import tw.kevinzhang.core.data.model.ClassificationExecutionStatus
import tw.kevinzhang.core.data.model.ClassificationTrigger
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.moneylook.schedule.ClassificationTraceMaintenanceScheduler

@RunWith(RobolectricTestRunner::class)
class AutoCategorizerTest {
    @Test fun `cancelled bulk snapshot rolls back and rerun completes without duplicate annotations`() = runBlocking {
        database.categoryDao().upsert(Category("cancel-category", "取消測試", "#000000"))
        database.autoCategoryRuleDao().upsertWithTags(AutoCategoryRule("cancel-rule", "取消測試", descriptionContains = "陌生交易", categoryId = "cancel-category"), emptySet())
        val rows = (1..260).map { transfer("cancel-$it", "陌生交易", -it.toDouble()) }
        database.transferDao().upsertAll(rows)
        var cancelled = false
        try {
            categorizer.applyToExistingTransactions { if (it.processedTransferCount >= 25) throw kotlinx.coroutines.CancellationException("test cancellation") }
        } catch (_: kotlinx.coroutines.CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertTrue(database.transferAnnotationDao().getAll().isEmpty())
        assertEquals(260, categorizer.applyToExistingTransactions().processedTransferCount)
        assertEquals(260, database.transferAnnotationDao().getAll().size)
    }
    @Test fun `bulk classification pages all unknown rows while retaining intentional manual clear`() = runBlocking {
        val rows = (1..511).map { transfer("page-$it", "陌生交易", -it.toDouble()) }
        database.transferDao().upsertAll(rows)
        database.transferAnnotationDao().saveManualAnnotation(
            TransferAnnotation(rows.first().id, rows.first().extensionId, categoryAssignment = AssignmentSource.MANUAL), emptySet())
        val result = categorizer.applyToExistingTransactions()
        assertEquals(511, result.processedTransferCount)
        assertEquals(1, result.preservedManualOverrideCount)
        assertEquals(0, result.matchedTransferCount)
        assertNull(database.transferAnnotationDao().getByTransferIds(listOf(rows.first().id)).single().categoryId)
    }

    @Test fun `unknown transactions remain uncategorized without creating categories`() = runBlocking {
        val before = database.categoryDao().allNames()
        val rows = listOf(-500.0, 500.0, 0.0).mapIndexed { index, amount -> transfer("unknown-$index", "轉帳", amount) }
        database.transferDao().upsertAll(rows)
        categorizer.categorizeTransferIds(rows.map { it.id })
        rows.forEach { assertNull(database.transferAnnotationDao().getByTransferIds(listOf(it.id)).singleOrNull()?.categoryId) }
        assertEquals(before, database.categoryDao().allNames())
    }

    @Test fun `manual correction teaches future matching merchant without changing old automatic rows`() = runBlocking {
        database.categoryDao().upsert(Category("local-food", "本機餐飲", "#000000"))
        val first = transfer("learn-source", "晨光咖啡", -59.0).copy(merchantName = "晨光咖啡")
        val future = first.copy(id = "learn-future", amount = -75.0)
        database.transferDao().upsertAll(listOf(first, future))
        database.transferAnnotationDao().saveManualAnnotation(
            TransferAnnotation(first.id, first.extensionId, "local-food", categoryAssignment = AssignmentSource.MANUAL), emptySet())
        categorizer.categorizeTransferIds(listOf(future.id))
        assertEquals("local-food", database.transferAnnotationDao().getByTransferIds(listOf(future.id)).single().categoryId)
    }

    private lateinit var database: MoneylookDatabase
    private lateinit var categorizer: AutoCategorizer
    private lateinit var maintenanceScheduler: RecordingMaintenanceScheduler

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
        maintenanceScheduler = RecordingMaintenanceScheduler()
        categorizer = AutoCategorizer(
            localStore = tw.kevinzhang.core.data.db.LocalClassificationStore(database),
            transferDao = database.transferDao(),
            annotationDao = database.transferAnnotationDao(),
            ruleDao = database.autoCategoryRuleDao(),
            categoryDao = database.categoryDao(),
            classificationCatalogResetStore = RoomClassificationCatalogResetStore(
                database,
                database.transferAnnotationDao(),
                database.ingestionProvenanceDao(),
            ),
            ruleSetDao = database.autoCategoryRuleSetDao(),
            classificationTraceStore = RoomClassificationTraceStore(
                database,
                database.transferAnnotationDao(),
                database.ingestionProvenanceDao(),
            ),
            classificationTraceMaintenanceScheduler = maintenanceScheduler,
        )
    }

    @Test
    fun `records only matched rule details and aggregates non-match reasons`() = runBlocking {
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
        database.autoCategoryRuleDao().upsertWithDetails(
            rule = AutoCategoryRule(
                id = "nonmatching-rule",
                name = "不符合",
                amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
                categoryId = food.id,
            ),
            conditions = listOf(
                AutoCategoryRuleCondition(
                    ruleId = "nonmatching-rule",
                    position = 0,
                    conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    field = AutoCategoryRuleConditionField.MERCHANT_NAME,
                    matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
                    pattern = "absent",
                ),
            ),
            tagIds = emptySet(),
        )
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "scope-mismatch-rule",
                name = "正數交易",
                amountSign = AutoCategoryRuleAmountSign.POSITIVE,
                categoryId = food.id,
            ),
            emptySet(),
        )
        val transfer = transfer("trace-rules", "anything", -10.0)
            .copy(merchantName = "Fictional Merchant")
        database.transferDao().upsertAll(listOf(transfer))

        categorizer.categorizeTransferIds(listOf(transfer.id), "run-evaluation")

        val rules = database.ingestionProvenanceDao().getRuleEvaluations(transfer.id)
        assertEquals(listOf("merchant-rule"), rules.map { it.ruleId })
        assertTrue(rules.single().selected)
        assertTrue(rules.single().matched)
        database.ingestionProvenanceDao().getConditionEvaluations(transfer.id).single().also {
            assertEquals("MERCHANT_NAME", it.field)
            assertEquals("""["Fictional Merchant"]""", it.candidateValuesJson)
            assertTrue(it.matched)
        }
        database.ingestionProvenanceDao().getTransferAnnotationEvents(transfer.id).single().also {
            assertEquals(3, it.evaluatedRuleCount)
            assertEquals(1, it.matchedRuleCount)
            assertEquals(1, it.retainedRuleCount)
            assertEquals(0, it.unusableRuleCount)
            assertEquals(1, it.scopeMismatchCount)
            assertEquals(1, it.conditionMismatchCount)
            assertEquals(0, it.categoryIncompatibleCount)
            assertEquals(2, it.evaluatedConditionCount)
            assertEquals(1, it.matchedConditionCount)
            assertEquals(1, it.retainedConditionCount)
            assertEquals(ClassificationDetailState.REDUCED, it.detailState)
        }
        Unit
    }

    @Test
    fun `pure non-match decision is summary-only without trace detail`() = runBlocking {
        val food = Category("summary-food", "餐飲", "#2E7D32")
        database.categoryDao().upsert(food)
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "summary-miss",
                name = "不符合",
                descriptionContains = "absent",
                categoryId = food.id,
            ),
            emptySet(),
        )
        val transfer = transfer("summary-only-transfer", "anything", -10.0)
        database.transferDao().upsertAll(listOf(transfer))

        categorizer.categorizeTransferIds(listOf(transfer.id), "summary-only-run")

        assertTrue(database.ingestionProvenanceDao().getRuleEvaluations(transfer.id).isEmpty())
        assertTrue(database.ingestionProvenanceDao().getConditionEvaluations(transfer.id).isEmpty())
        database.ingestionProvenanceDao().getTransferAnnotationEvents(transfer.id).single().also {
            assertEquals(ClassificationOutcome.NO_MATCH, it.outcome)
            assertEquals(1, it.evaluatedRuleCount)
            assertEquals(0, it.retainedRuleCount)
            assertEquals(1, it.conditionMismatchCount)
            assertEquals(ClassificationDetailState.SUMMARY_ONLY, it.detailState)
        }
        Unit
    }

    @Test
    fun `abstained decision retains every conflicting matched candidate and no non-match`() = runBlocking {
        val food = Category("abstain-food", "餐飲", "#2E7D32")
        val transport = Category("abstain-transport", "交通", "#1565C0")
        database.categoryDao().upsert(food)
        database.categoryDao().upsert(transport)
        listOf(
            "food-rule" to food,
            "transport-rule" to transport,
        ).forEach { (ruleId, category) ->
            database.autoCategoryRuleDao().upsertWithDetails(
                rule = AutoCategoryRule(
                    id = ruleId,
                    name = ruleId,
                    amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
                    categoryId = category.id,
                ),
                conditions = listOf(
                    AutoCategoryRuleCondition(
                        ruleId = ruleId,
                        position = 0,
                        conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                        field = AutoCategoryRuleConditionField.DESCRIPTION,
                        matchMode = AutoCategoryRuleConditionMatchMode.EXACT,
                        pattern = "collision",
                    ),
                ),
                tagIds = emptySet(),
            )
        }
        database.autoCategoryRuleDao().upsertWithTags(
            AutoCategoryRule(
                id = "scope-mismatch",
                name = "scope mismatch",
                amountSign = AutoCategoryRuleAmountSign.POSITIVE,
                categoryId = food.id,
            ),
            emptySet(),
        )
        val transfer = transfer("abstain-trace", "collision", -100.0)
        database.transferDao().upsertAll(listOf(transfer))

        categorizer.categorizeTransferIds(listOf(transfer.id))

        val rules = database.ingestionProvenanceDao().getRuleEvaluations(transfer.id)
        assertEquals(setOf("food-rule", "transport-rule"), rules.map { it.ruleId }.toSet())
        assertTrue(rules.all { it.matched && !it.selected })
        assertEquals(2, database.ingestionProvenanceDao().getConditionEvaluations(transfer.id).size)
        database.ingestionProvenanceDao().getTransferAnnotationEvents(transfer.id).single().also {
            assertEquals(ClassificationOutcome.NO_MATCH, it.outcome)
            assertEquals(3, it.evaluatedRuleCount)
            assertEquals(2, it.matchedRuleCount)
            assertEquals(2, it.retainedRuleCount)
            assertEquals(1, it.scopeMismatchCount)
            assertEquals(2, it.retainedConditionCount)
        }
        Unit
    }

    @Test
    fun `one terminal classification execution is recorded per extension`() = runBlocking {
        val first = transfer("execution-first", "first", -1.0, extensionId = "extension-first")
        val second = transfer("execution-second", "second", -1.0, extensionId = "extension-second")
        database.transferDao().upsertAll(listOf(first, second))

        categorizer.categorizeTransferIds(listOf(first.id, second.id))

        val firstEvent = database.ingestionProvenanceDao()
            .getTransferAnnotationEvents(first.id)
            .single()
        val secondEvent = database.ingestionProvenanceDao()
            .getTransferAnnotationEvents(second.id)
            .single()
        assertTrue(firstEvent.classificationExecutionId != secondEvent.classificationExecutionId)
        listOf(firstEvent, secondEvent).forEach { event ->
            val execution = database.ingestionProvenanceDao()
                .getClassificationExecution(checkNotNull(event.classificationExecutionId))
            assertEquals(event.extensionId, execution?.extensionId)
            assertEquals(ClassificationExecutionStatus.COMPLETE, execution?.status)
            assertTrue(execution?.completedAt != null)
        }
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
        assertEquals(1, maintenanceScheduler.wakeCount)
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
            assertEquals("local-adaptive-v1", detail.annotation?.classifierVersion)
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
        val manualCategory = Category("manual-category", "手動分類", "#EF6C00")
        val automaticTag = Tag("automatic", "自動", "#1565C0")
        val manualTag = Tag("manual", "手動", "#6A1B9A")
        database.categoryDao().upsert(category)
        database.categoryDao().upsert(manualCategory)
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
                categoryId = manualCategory.id,
                note = "保留備註",
                categoryAssignment = AssignmentSource.MANUAL,
                manualOverride = true,
            ),
            setOf(manualTag.id),
        )
        val progress = mutableListOf<AutoCategoryApplicationProgress>()

        val result = categorizer.applyToExistingTransactions(onProgress = progress::add)

        assertEquals(3, result.processedTransferCount)
        assertEquals(1, result.matchedTransferCount)
        assertEquals(1, result.preservedManualOverrideCount)
        assertEquals(
            listOf(
                AutoCategoryApplicationProgress(
                    processedTransferCount = 0,
                    totalTransferCount = 3,
                ),
                AutoCategoryApplicationProgress(
                    processedTransferCount = 3,
                    totalTransferCount = 3,
                ),
            ),
            progress,
        )
        database.transferAnnotationDao().observeDetail(automatic.id).first()!!.also { detail ->
            assertEquals(manualCategory.id, detail.annotation?.categoryId)
            assertEquals(listOf(automaticTag.id), detail.tags.map(Tag::id))
        }
        database.transferAnnotationDao().observeDetail(manual.id).first()!!.also { detail ->
            assertEquals(manualCategory.id, detail.annotation?.categoryId)
            assertEquals("保留備註", detail.annotation?.note)
            assertEquals(AssignmentSource.MANUAL, detail.annotation?.categoryAssignment)
            assertEquals(listOf(manualTag.id), detail.tags.map(Tag::id))
        }
        val manualAudit = database.ingestionProvenanceDao()
            .getTransferAnnotationEvents(manual.id)
        assertTrue(manualAudit.any { it.trigger == ClassificationTrigger.MANUAL_EDIT })
        assertTrue(manualAudit.any { it.trigger == ClassificationTrigger.BULK_REAPPLY })
        assertNull(database.transferAnnotationDao().observeDetail(unmatched.id).first()!!.annotation?.categoryId)
        Unit
    }

    @Test
    fun `applying all existing transactions reports zero progress for an empty catalog`() = runBlocking {
        val progress = mutableListOf<AutoCategoryApplicationProgress>()

        val result = categorizer.applyToExistingTransactions(onProgress = progress::add)

        assertEquals(0, result.processedTransferCount)
        assertEquals(
            listOf(
                AutoCategoryApplicationProgress(
                    processedTransferCount = 0,
                    totalTransferCount = 0,
                ),
            ),
            progress,
        )
    }

    @Test
    fun `applying all existing transactions reports every 25 transactions and the final transaction`() =
        runBlocking {
            database.transferDao().upsertAll(
                (1..26).map { index ->
                    transfer("apply-progress-$index", "未分類交易 $index", -index.toDouble())
                },
            )
            val progress = mutableListOf<AutoCategoryApplicationProgress>()

            categorizer.applyToExistingTransactions(onProgress = progress::add)

            assertEquals(
                listOf(
                    AutoCategoryApplicationProgress(
                        processedTransferCount = 0,
                        totalTransferCount = 26,
                    ),
                    AutoCategoryApplicationProgress(
                        processedTransferCount = 25,
                        totalTransferCount = 26,
                    ),
                    AutoCategoryApplicationProgress(
                        processedTransferCount = 26,
                        totalTransferCount = 26,
                    ),
                ),
                progress,
            )
        }

    @Test
    fun `classification reset removes manual catalog state and reclassifies while preserving note and audit`() =
        runBlocking {
            val customCategory = Category("custom-category", "自訂分類", "#123456")
            val customTag = Tag("custom-tag", "自訂標籤", "#654321")
            val customRule = AutoCategoryRule(
                id = "custom-rule",
                name = "自訂規則",
                descriptionContains = "薪資入帳",
                categoryId = customCategory.id,
            )
            database.categoryDao().upsert(customCategory)
            database.tagDao().upsert(customTag)
            database.autoCategoryRuleDao().upsertWithTags(customRule, setOf(customTag.id))
            val transfer = transfer("reset-transfer", "薪資入帳", 30_000.0)
            database.transferDao().upsertAll(listOf(transfer))
            database.transferAnnotationDao().saveManualAnnotation(
                TransferAnnotation(
                    transferId = transfer.id,
                    extensionId = transfer.extensionId,
                    categoryId = customCategory.id,
                    note = "這段備註必須保留",
                    categoryAssignment = AssignmentSource.MANUAL,
                ),
                setOf(customTag.id),
            )

            val progress = mutableListOf<ClassificationResetProgress>()
            val result = categorizer.resetClassificationSystem(progress::add)

            assertEquals(1, result.processedTransferCount)
            assertEquals(1, result.matchedTransferCount)
            assertEquals(0, result.preservedManualOverrideCount)
            assertEquals(
                listOf(
                    ClassificationResetProgress(ClassificationResetStage.RESETTING_CATALOG),
                    ClassificationResetProgress(
                        stage = ClassificationResetStage.RECLASSIFYING_TRANSACTIONS,
                        totalTransferCount = 1,
                    ),
                    ClassificationResetProgress(
                        stage = ClassificationResetStage.RECLASSIFYING_TRANSACTIONS,
                        processedTransferCount = 1,
                        totalTransferCount = 1,
                    ),
                ),
                progress,
            )
            assertEquals(
                DefaultClassificationCatalog.categories.map { it.id }.toSet(),
                database.categoryDao().observeAll().first().map { it.id }.filterNot { it.startsWith("local-fallback-") }.toSet(),
            )
            assertTrue(database.tagDao().observeAll().first().isEmpty())
            assertFalse(
                database.autoCategoryRuleDao().observeAll().first()
                    .any { it.rule.id == customRule.id },
            )
            database.transferAnnotationDao().observeDetail(transfer.id).first()!!.also { detail ->
                assertEquals("income-salary", detail.annotation?.categoryId)
                assertEquals("這段備註必須保留", detail.annotation?.note)
                assertEquals(AssignmentSource.AUTO, detail.annotation?.categoryAssignment)
                assertFalse(detail.annotation?.manualOverride == true)
                assertTrue(detail.tags.isEmpty())
            }
            val audit = database.ingestionProvenanceDao().getTransferAnnotationEvents(transfer.id)
            assertTrue(audit.any { it.trigger == ClassificationTrigger.MANUAL_EDIT })
            assertTrue(audit.any { it.trigger == ClassificationTrigger.CATALOG_RESET })
            Unit
        }

    @Test
    fun `classification reset reports every 25 transactions and the final transaction`() = runBlocking {
        database.transferDao().upsertAll(
            (1..26).map { index ->
                transfer("reset-progress-$index", "未分類交易 $index", -index.toDouble())
            },
        )
        val progress = mutableListOf<ClassificationResetProgress>()

        categorizer.resetClassificationSystem(progress::add)

        assertEquals(
            listOf(
                ClassificationResetProgress(ClassificationResetStage.RESETTING_CATALOG),
                ClassificationResetProgress(
                    stage = ClassificationResetStage.RECLASSIFYING_TRANSACTIONS,
                    totalTransferCount = 26,
                ),
                ClassificationResetProgress(
                    stage = ClassificationResetStage.RECLASSIFYING_TRANSACTIONS,
                    processedTransferCount = 25,
                    totalTransferCount = 26,
                ),
                ClassificationResetProgress(
                    stage = ClassificationResetStage.RECLASSIFYING_TRANSACTIONS,
                    processedTransferCount = 26,
                    totalTransferCount = 26,
                ),
            ),
            progress,
        )
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
    fun `bundled merchant rules classify ASUS credit card fixtures without MCC and preserve manual choices`() = runBlocking {
        // Exercise the catalog through the same reset/seeding path as the app.  Do not rebuild a
        // test-only rule list here: these fixtures must fail whenever the shipped catalog drifts.
        categorizer.resetClassificationSystem()
        database.accountDao().upsertAll(
            listOf(
                account("credit-card").copy(kind = AssetKind.CREDIT_CARD),
            ),
        )

        data class Fixture(
            val id: String,
            val merchantName: String?,
            val description: String = "信用卡商戶消費",
            val expectedCategoryId: String?,
        )

        fun merchantFixtures(
            categoryId: String,
            vararg merchants: Pair<String, String>,
        ) = merchants.map { (id, merchantName) ->
            Fixture(id, merchantName, expectedCategoryId = categoryId)
        }

        val fixtures = merchantFixtures(
            "expense-food",
            "sukiya-latin" to "ＭＧ- ＳＵＫＩＹＡ　南京三民店",
            "sukiya-japanese" to "Ｇ- すき家 南京三民店",
            "sukiya-corporate" to "Ｇ- 台灣善商股份有限公司",
            "komeda" to "Ｇ- 客美多咖啡（民生圓環店）",
            "matsuya" to "Ｇ- 松屋 南京東路店",
            "kebuke" to "Ｇ- 可不可熟成紅茶 南京東店",
            "pizzahut" to "Ｇ- 必勝客",
            "sato" to "Ｇ- 佐藤精肉店",
            "saizeriya" to "Ｇ- 薩莉亞",
            "bafang" to "Ｇ- 八方雲集",
            "xindian" to "Ｇ- 辛殿麻辣鍋",
            "hikari" to "Ｇ- 光鮨迴轉壽司",
            "daoting" to "Ｇ- 稻町家",
            "kayiyi" to "Ｇ- 咖一味",
            "torijin" to "Ｇ- 鳥人拉麵",
            "su" to "Ｇ- 蘇氏麵館",
            "zen" to "Ｇ- 禪風茶樓",
            "ippudo" to "Ｇ- 一風堂",
            "louisa" to "Ｇ- ＬＯＵＩＳＡ　ＣＯＦＦＥＥ",
            "starbucks" to "Ｇ- 星巴克",
            "qiaozhiwei" to "Ｇ- 巧之味",
            "chiyuan" to "Ｇ- 季緣",
            "att4fun-restaurant" to "Ｇ- ＡＴＴ ４ ＦＵＮ 餐廳",
        ) + merchantFixtures(
            "expense-shopping",
            "jinhua" to "Ｇ- 今華電子",
            "shengsheng" to "Ｇ- 勝勝小舖",
            "dream" to "Ｇ- 情趣夢天堂",
            "shenghua" to "Ｇ- 勝華百貨行",
            "shengyue" to "Ｇ- 勝越企業社",
            "sanchuang" to "Ｇ- 三創數位",
            "pchome" to "Ｇ- ＰＣＨＯＭＥ",
            "carrefour" to "Ｇ- 家樂福",
            "rtmart" to "Ｇ- 大全聯",
            "jsf" to "Ｇ- 金興發",
            "yuanda" to "Ｇ- 源達科技",
            "nihonbashi" to "Ｇ- 日本橋３Ｃ",
            "xiangchang" to "Ｇ- 祥昌電子",
            "miramar" to "Ｇ- 美麗華百樂園",
            "zhouquan" to "Ｇ- 洲全生活館",
        ) + merchantFixtures(
            "expense-transport",
            "guoyuan" to "Ｇ- 國園加油站",
            "sunshine" to "Ｇ- 陽光市民加油站",
            "cpc" to "Ｇ- 中油－復興北路站（Ｄ２１）",
        ) + merchantFixtures(
            "expense-stationery",
            "stationery" to "Ｇ- １０１文具天堂－台北民生店",
            "printing" to "Ｇ- 藍新－經典數位印刷中山民生店",
        ) + merchantFixtures(
            "expense-home",
            "ikea-latin" to "Ｇ- ＩＫＥＡ",
            "ikea-chinese" to "Ｇ- 宜家家居",
            "zhenyu" to "Ｇ- 振宇五金",
        ) + merchantFixtures(
            "expense-clothing",
            "net" to "Ｇ- ＮＥＴ忠孝旗艦",
        ) + merchantFixtures(
            "expense-entertainment",
            "cyber-cafe" to "Ｇ- 麗華行電競旗艦館",
        ) + merchantFixtures(
            "expense-medical",
            "pharmacy" to "Ｇ- 正康藥局",
        ) + listOf(
            Fixture("teahouse-sanchuang", "Ｇ- 茶屋 三創店", expectedCategoryId = "expense-food"),
            Fixture("uniparking", "Ｇ- 統一精工 停車", expectedCategoryId = "expense-transport"),
            Fixture("squarespace", null, description = "SQSP* DOMAIN", expectedCategoryId = "expense-internet"),
            // Merchant evidence must win over the generic electronic-payment channel.
            Fixture(
                "familymart",
                "Ｇ- 全家便利商店",
                description = "街口電支－全家便利商店",
                expectedCategoryId = "expense-food",
            ),
            Fixture("newebpay-alone", "藍新科技", expectedCategoryId = null),
            Fixture("lianjia", "連加＊HOHO", expectedCategoryId = null),
            Fixture("paymonade", "匯票旅支/虛擬貨幣/PAYMONADE", expectedCategoryId = null),
            Fixture("generic-card-charge", "未辨識商戶", description = "簽帳消費", expectedCategoryId = null),
            Fixture(
                "annual-fee-waiver",
                "減免年費 - 魔BUY悠遊鈦金卡",
                description = "減免年費 - 魔BUY悠遊鈦金卡",
                expectedCategoryId = null,
            ),
        )
        val transfers = fixtures.map { fixture ->
            transfer(
                id = fixture.id,
                description = fixture.description,
                amount = -100.0,
                accountId = "credit-card",
                merchantName = fixture.merchantName,
                merchantCategoryCode = null,
            )
        }
        val manuallyClassified = transfer(
            id = "manual-sukiya",
            description = "信用卡商戶消費",
            amount = -100.0,
            accountId = "credit-card",
            merchantName = "獨立人工商戶",
            merchantCategoryCode = null,
        )
        database.transferDao().upsertAll(transfers + manuallyClassified)
        database.transferAnnotationDao().saveManualAnnotation(
            TransferAnnotation(
                transferId = manuallyClassified.id,
                extensionId = manuallyClassified.extensionId,
                categoryId = "expense-shopping",
                note = "使用者指定",
                categoryAssignment = AssignmentSource.MANUAL,
                manualOverride = true,
            ),
            emptySet(),
        )

        val result = categorizer.applyToExistingTransactions()

        assertEquals(1, result.preservedManualOverrideCount)
        fixtures.forEach { fixture ->
            val actual = database.transferAnnotationDao().observeDetail(fixture.id).first()?.annotation?.categoryId
            if (fixture.expectedCategoryId == null) assertNull("${fixture.id} remains uncategorized", actual)
            else assertEquals("${fixture.id} uses seeded catalog", fixture.expectedCategoryId, actual)
        }
        database.transferAnnotationDao().observeDetail(manuallyClassified.id).first()!!.annotation!!.also {
            assertEquals("expense-shopping", it.categoryId)
            assertEquals("使用者指定", it.note)
            assertEquals(AssignmentSource.MANUAL, it.categoryAssignment)
            assertTrue(it.manualOverride)
        }
        Unit
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

    @Test fun `apply all reads legacy post evidence and removed login retracts ownership`() = runBlocking {
        val source = tw.kevinzhang.core.data.model.InstalledExtension("extension", "tw.gov.post.ipost", "Post", 1, "repo", "script", null)
        val target = source.copy(id = "target-bank", manifestId = "tw.com.taishinbank")
        database.installedExtensionDao().insert(source)
        database.installedExtensionDao().insert(target)
        database.credentialProfileDao().upsert(tw.kevinzhang.core.data.model.CredentialProfile(target.id, "test", scheduleCron = "0 0 * * *", timezoneId = "Asia/Taipei"))
        database.accountDao().upsertAll(listOf(account("own-target").copy(extensionId = target.id, accountNo = "12345678901234")))
        val row = transfer("post-legacy", "網路跨行", -24000.0).copy(
            sourceFactsJson = """{"branchNumber":{"value":"0012345678901234","rawKey":"COS_BRH_NO"}}""")
        database.transferDao().upsertAll(listOf(row))
        assertTrue(database.transferAnnotationDao().getByTransferIds(listOf(row.id)).isEmpty())
        categorizer.applyToExistingTransactions()
        assertEquals("transfer-account", database.transferAnnotationDao().getByTransferIds(listOf(row.id)).single().categoryId)
        database.credentialProfileDao().deleteByExtensionId(target.id)
        categorizer.applyToExistingTransactions()
        assertFalse(database.transferAnnotationDao().getByTransferIds(listOf(row.id)).singleOrNull()?.categoryId == "transfer-account")
    }

    @Test fun `bank and owned suffix classify without a counterpart and removed login stops matching`() = runBlocking {
        val extension = tw.kevinzhang.core.data.model.InstalledExtension("owned-bank", "tw.com.cathaybk", "Bank", 1, "repo", "script", null)
        database.installedExtensionDao().insert(extension)
        database.credentialProfileDao().upsert(tw.kevinzhang.core.data.model.CredentialProfile(extension.id, "test", scheduleCron = "0 0 * * *", timezoneId = "Asia/Taipei"))
        database.accountDao().upsertAll(listOf(account("own-target").copy(extensionId = extension.id, accountNo = "001234567890", bankId = "013")))
        val row = transfer("bank-match", "轉帳", -500.0).copy(counterpartyBank = "013", counterpartyAccount = "***67890")
        database.transferDao().upsertAll(listOf(row))
        categorizer.categorizeTransferIds(listOf(row.id))
        assertEquals("transfer-account", database.transferAnnotationDao().getByTransferIds(listOf(row.id)).single().categoryId)
        database.credentialProfileDao().deleteByExtensionId(extension.id)
        categorizer.applyToExistingTransactions()
        assertNull(database.transferAnnotationDao().getByTransferIds(listOf(row.id)).singleOrNull()?.categoryId)
    }

    @Test fun `same amount and time never exclude cash flow without bank account evidence`() = runBlocking {
        database.accountDao().upsertAll(listOf(account("incoming-account")))
        val outgoing = transfer("out", "轉帳", -100.0, "2026-07-22T12:00:00")
        val incoming = transfer("in", "轉帳", 100.0, "2026-07-22T12:00:10", "incoming-account")
        database.transferDao().upsertAll(listOf(outgoing, incoming))
        categorizer.applyToExistingTransactions()
        assertNull(database.transferAnnotationDao().getByTransferIds(listOf(outgoing.id)).singleOrNull()?.categoryId)
        assertNull(database.transferAnnotationDao().getByTransferIds(listOf(incoming.id)).singleOrNull()?.categoryId)
    }

    @Test fun `new ingestion does not revisit historical automatic transfers`() = runBlocking {
        val old = transfer("old-transfer", "轉帳", -100.0)
        val fresh = transfer("fresh-transfer", "轉帳", 100.0)
        database.transferDao().upsertAll(listOf(old, fresh))
        database.transferAnnotationDao().upsert(TransferAnnotation(old.id, old.extensionId, "transfer-account"))
        categorizer.categorizeTransferIds(listOf(fresh.id))
        assertEquals("transfer-account", database.transferAnnotationDao().getByTransferIds(listOf(old.id)).single().categoryId)
        categorizer.categorizeTransferIds(emptyList())
        assertEquals("transfer-account", database.transferAnnotationDao().getByTransferIds(listOf(old.id)).single().categoryId)
        categorizer.applyToExistingTransactions()
        assertNull(database.transferAnnotationDao().getByTransferIds(listOf(old.id)).singleOrNull()?.categoryId)
    }

    @Test fun `ordinary rule cannot bypass account evidence to exclude a transfer`() = runBlocking {
        database.autoCategoryRuleDao().upsertWithTags(AutoCategoryRule("unsafe-transfer", "移轉", descriptionContains = "轉帳", categoryId = "transfer-account"), emptySet())
        val row = transfer("ordinary-transfer", "轉帳", -100.0)
        database.transferDao().upsertAll(listOf(row))
        categorizer.categorizeTransferIds(listOf(row.id))
        assertNull(database.transferAnnotationDao().getByTransferIds(listOf(row.id)).singleOrNull()?.categoryId)
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

    private class RecordingMaintenanceScheduler : ClassificationTraceMaintenanceScheduler {
        var wakeCount: Int = 0

        override fun wake() {
            wakeCount += 1
        }

        override fun continueAfter(continuation: Int) = Unit
    }
}
