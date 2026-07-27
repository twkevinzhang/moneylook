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
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.core.data.model.IngestionStatus
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.core.data.model.TransferObservation
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.ClassificationOutcome
import tw.kevinzhang.core.data.model.ClassificationTrigger
import tw.kevinzhang.core.data.model.IngestionClassificationStatus
import tw.kevinzhang.core.data.model.IngestionRun

@RunWith(RobolectricTestRunner::class)
class IngestionProvenanceDaoTest {
    private lateinit var database: MoneylookDatabase

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MoneylookDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test
    fun `monthly aggregate excludes an intentional manual clear and reports structured field presence`() = runBlocking {
        database.accountDao().upsertAll(listOf(Account("a", "ext", "Ext", "Account", 0.0, "TWD", 0L, kind = AssetKind.DEPOSIT)))
        database.categoryDao().upsert(
            Category("food", "Food", "#000000", "🍽️", CategoryReportingGroup.EXPENSE),
        )
        database.transferDao().upsertAll(listOf(
            transfer("auto", merchantName = "merchant"),
            transfer("manual"),
            transfer("cleared"),
            transfer("unclassified", purpose = "purpose"),
        ))
        database.transferAnnotationDao().upsert(TransferAnnotation("auto", "ext", "food", categoryAssignment = AssignmentSource.AUTO))
        database.transferAnnotationDao().upsert(TransferAnnotation("manual", "ext", "food", categoryAssignment = AssignmentSource.MANUAL))
        database.transferAnnotationDao().upsert(TransferAnnotation("cleared", "ext", null, categoryAssignment = AssignmentSource.MANUAL))

        val aggregate = database.ingestionProvenanceDao().monthlyExpenseClassificationAggregates().single()
        val presence = database.ingestionProvenanceDao().structuredFieldPresenceAggregate()

        assertEquals(4, aggregate.expenseTotal)
        assertEquals(1, aggregate.autoClassified)
        assertEquals(1, aggregate.manualClassified)
        assertEquals(1, aggregate.unclassified)
        assertEquals(1, presence.merchantNameCount)
        assertEquals(1, presence.purposeCount)
    }

    @Test
    fun `structured field aggregate returns zeroes for an empty database`() = runBlocking {
        val presence = database.ingestionProvenanceDao().structuredFieldPresenceAggregate()

        assertEquals(0, presence.transferCount)
        assertEquals(0, presence.merchantNameCount)
        assertEquals(0, presence.merchantCategoryCodeCount)
        assertEquals(0, presence.counterpartyNameCount)
        assertEquals(0, presence.purposeCount)
    }

    @Test
    fun `provenance overload records inserted unchanged and updated observations`() = runBlocking {
        val account = Account("a", "ext", "Ext", "Account", 0.0, "TWD", 0L, kind = AssetKind.DEPOSIT)
        val original = transfer("stable", merchantName = "Fictional Merchant")
        val store = database.syncResultDao()

        store.replaceSnapshot(
            extensionId = "ext",
            accounts = listOf(account),
            transfers = listOf(original),
            refreshes = listOf(AccountTransferRefresh("a", null)),
            ingestionContext = context("run-1", original.id, "payload-1"),
        )
        store.replaceSnapshot(
            extensionId = "ext",
            accounts = listOf(account),
            transfers = listOf(original),
            refreshes = listOf(AccountTransferRefresh("a", null)),
            ingestionContext = context("run-2", original.id, "payload-1"),
        )
        store.replaceSnapshot(
            extensionId = "ext",
            accounts = listOf(account),
            transfers = listOf(original.copy(status = "posted")),
            refreshes = listOf(AccountTransferRefresh("a", null)),
            ingestionContext = context("run-3", original.id, "payload-2"),
        )

        val events = database.ingestionProvenanceDao()
            .getTransferIngestionEvents(original.id)
            .sortedBy { it.occurredAt }
        assertEquals(
            listOf(
                TransferObservation.INSERTED,
                TransferObservation.UNCHANGED,
                TransferObservation.UPDATED,
            ),
            events.map { it.observation },
        )
        assertEquals(listOf("payload-1", "payload-1", "payload-2"), events.map { it.payloadFingerprint })
        assertEquals(3, database.ingestionProvenanceDao().getRecentRuns("ext").size)
    }

    @Test
    fun `automatic decision transaction rechecks and preserves a live manual override`() = runBlocking {
        database.accountDao().upsertAll(
            listOf(Account("a", "ext", "Ext", "Account", 0.0, "TWD", 0L, kind = AssetKind.DEPOSIT)),
        )
        database.transferDao().upsertAll(listOf(transfer("manual")))
        database.transferAnnotationDao().upsert(
            TransferAnnotation("manual", "ext", null, categoryAssignment = AssignmentSource.MANUAL),
        )

        val result = database.transferAnnotationDao().applyAutomaticDecision(
            AutomaticClassificationDecision(
                transferId = "manual",
                extensionId = "ext",
                categoryId = "food",
                tagIds = emptySet(),
                runId = "run",
                trigger = ClassificationTrigger.INGESTION,
                outcome = ClassificationOutcome.AUTO_APPLIED,
                ruleId = "rule",
                ruleSetId = null,
                ruleContentSha256 = "hash",
                ruleSetContentSha256 = null,
                matchScore = 1,
                classifierVersion = "v",
            ),
        )

        assertEquals(AutomaticClassificationWriteResult.PRESERVED_MANUAL, result)
        assertEquals(
            AssignmentSource.MANUAL,
            database.transferAnnotationDao().getByTransferIds(listOf("manual")).single().categoryAssignment,
        )
        assertEquals(
            ClassificationOutcome.PRESERVED_MANUAL,
            database.ingestionProvenanceDao().getTransferAnnotationEvents("manual").single().outcome,
        )
    }

    @Test
    fun `manual tag edit audit stores only aggregate delta counts`() = runBlocking {
        database.accountDao().upsertAll(
            listOf(Account("a", "ext", "Ext", "Account", 0.0, "TWD", 0L, kind = AssetKind.DEPOSIT)),
        )
        database.transferDao().upsertAll(listOf(transfer("tagged")))
        database.tagDao().upsert(Tag("first", "First", "#000000"))
        database.tagDao().upsert(Tag("second", "Second", "#000000"))
        database.transferAnnotationDao().saveManualAnnotation(
            TransferAnnotation("tagged", "ext", categoryAssignment = AssignmentSource.MANUAL),
            setOf("first"),
        )

        database.transferAnnotationDao().replaceManualTags("tagged", setOf("second"))

        val event = database.ingestionProvenanceDao().getTransferAnnotationEvents("tagged")
            .first { it.outcome == ClassificationOutcome.MANUAL_TAG_EDIT }
        assertEquals(1, event.tagAddedCount)
        assertEquals(1, event.tagRemovedCount)
        assertEquals(null, event.ruleId)
    }

    @Test
    fun `resume automatic transaction records previous manual category before clearing`() = runBlocking {
        database.accountDao().upsertAll(
            listOf(Account("a", "ext", "Ext", "Account", 0.0, "TWD", 0L, kind = AssetKind.DEPOSIT)),
        )
        database.categoryDao().upsert(
            Category("food", "Food", "#000000", "🍽️", CategoryReportingGroup.EXPENSE),
        )
        database.transferDao().upsertAll(listOf(transfer("resume")))
        database.transferAnnotationDao().upsert(
            TransferAnnotation("resume", "ext", "food", categoryAssignment = AssignmentSource.MANUAL),
        )

        database.transferAnnotationDao().resumeAutomaticClassification("resume")

        val annotation = database.transferAnnotationDao().getByTransferIds(listOf("resume")).single()
        val event = database.ingestionProvenanceDao().getTransferAnnotationEvents("resume").single()
        assertEquals(AssignmentSource.AUTO, annotation.categoryAssignment)
        assertEquals(null, annotation.categoryId)
        assertEquals(ClassificationTrigger.RESUME, event.trigger)
        assertEquals(ClassificationOutcome.RESUMED_AUTOMATIC, event.outcome)
        assertEquals("food", event.previousCategoryId)
        assertEquals(null, event.newCategoryId)
    }

    @Test
    fun `failed ingestion and classification completion remain independently queryable`() = runBlocking {
        val store = database.syncResultDao()
        store.recordFailedIngestion("ext", context("run-1", "unused", "payload"), 0, 0)
        val failed = database.ingestionProvenanceDao().getRecentRuns("ext").single()
        assertEquals(IngestionStatus.FAILED, failed.status)
        assertEquals(IngestionClassificationStatus.FAILED, failed.classificationStatus)

        val account = Account("a", "ext", "Ext", "Account", 0.0, "TWD", 0L, kind = AssetKind.DEPOSIT)
        val transfer = transfer("pending")
        store.replaceSnapshot(
            "ext",
            listOf(account),
            listOf(transfer),
            listOf(AccountTransferRefresh("a", null)),
            ingestionContext = context("run-2", transfer.id, "payload"),
        )
        store.updateClassificationStatus("run-2", IngestionClassificationStatus.COMPLETE, 10L)
        val complete = database.ingestionProvenanceDao().getRecentRuns("ext")
            .first { it.id == "run-2" }
        assertEquals(IngestionStatus.SUCCESS, complete.status)
        assertEquals(IngestionClassificationStatus.COMPLETE, complete.classificationStatus)
        assertEquals(10L, complete.classificationCompletedAt)
    }

    @Test
    fun `run summaries page without loading giant failure detail`() = runBlocking {
        val dao = database.ingestionProvenanceDao()
        repeat(25) { index ->
            dao.insertRun(
                IngestionRun(
                    id = "run-${index.toString().padStart(2, '0')}",
                    startedAt = index.toLong(),
                    completedAt = index.toLong() + 1,
                    extensionId = "ext",
                    extensionVersion = 3,
                    artifactRevision = null,
                    artifactSha256 = null,
                    trigger = IngestionTrigger.USER_SYNC,
                    status = IngestionStatus.FAILED,
                    classificationStatus = IngestionClassificationStatus.FAILED,
                    accountCount = 0,
                    transferCount = 0,
                    sourceFingerprint = "fingerprint",
                    fingerprintKeyVersion = 1,
                    failureOrigin = "SCRIPT",
                    failureCode = "CODE-$index",
                    failureMessage = "message-$index-" + "m".repeat(100_000),
                    failureStack = "stack-$index-" + "s".repeat(100_000),
                    failureDiagnosticJson = """{"index":$index,"body":"${"d".repeat(100_000)}"}""",
                    failureScriptFrame = "line ${index + 1}",
                ),
            )
        }

        val firstPage = dao.getRunSummaries("ext", limit = 10, offset = 0)
        val thirdPage = dao.getRunSummaries("ext", limit = 10, offset = 20)

        assertEquals((24 downTo 15).map { "run-${it.toString().padStart(2, '0')}" }, firstPage.map { it.id })
        assertEquals((4 downTo 0).map { "run-${it.toString().padStart(2, '0')}" }, thirdPage.map { it.id })
        assertEquals("SCRIPT", firstPage.first().failureOrigin)
        assertEquals("CODE-24", firstPage.first().failureCode)
        assertEquals("line 25", firstPage.first().failureScriptFrame)

        val selectedDetail = requireNotNull(dao.getIngestionRun("run-24"))
        assertTrue(requireNotNull(selectedDetail.failureMessage).startsWith("message-24-"))
        assertTrue(requireNotNull(selectedDetail.failureStack).startsWith("stack-24-"))
        assertTrue(requireNotNull(selectedDetail.failureDiagnosticJson).contains("\"index\":24"))
    }

    private fun context(
        runId: String,
        transferId: String,
        payloadFingerprint: String,
    ) = IngestionContext(
        runId = runId,
        startedAt = runId.takeLast(1).toLong(),
        completedAt = runId.takeLast(1).toLong(),
        extensionVersion = 1,
        artifactRevision = "a".repeat(40),
        artifactSha256 = "b".repeat(64),
        trigger = IngestionTrigger.USER_SYNC,
        status = IngestionStatus.SUCCESS,
        sourceFingerprint = "run-source",
        fingerprintKeyVersion = 1,
        transferFingerprints = mapOf(
            transferId to TransferFingerprintEvidence(
                sourceFingerprint = "record-source",
                payloadFingerprint = payloadFingerprint,
            ),
        ),
    )

    private fun transfer(id: String, merchantName: String? = null, purpose: String? = null) = Transfer(
        id = id, accountId = "a", extensionId = "ext", txnDateTime = "2026-07-01T10:00:00",
        description = "", amount = -1.0, balance = null, memo = "", merchantName = merchantName, purpose = purpose,
    )
}
