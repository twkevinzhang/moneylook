package tw.kevinzhang.core.data.db

import androidx.room.Room
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
import tw.kevinzhang.core.data.model.ClassificationConditionEvaluation
import tw.kevinzhang.core.data.model.ClassificationOutcome
import tw.kevinzhang.core.data.model.ClassificationRuleEvaluation
import tw.kevinzhang.core.data.model.ClassificationTrigger
import tw.kevinzhang.core.data.model.IngestionClassificationStatus
import tw.kevinzhang.core.data.model.IngestionRun
import tw.kevinzhang.core.data.model.IngestionStatus
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.core.data.model.TransferAnnotationEvent
import tw.kevinzhang.core.data.model.ClassificationDetailState
import tw.kevinzhang.core.data.model.ClassificationExecution
import tw.kevinzhang.core.data.model.ClassificationExecutionStatus

@RunWith(RobolectricTestRunner::class)
class ClassificationTraceMaintenanceStoreTest {
    private lateinit var database: MoneylookDatabase
    private lateinit var store: ClassificationTraceMaintenanceStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
        store = RoomClassificationTraceMaintenanceStore(
            database,
            database.classificationTraceMaintenanceDao(),
        )
    }

    @After fun tearDown() = database.close()

    @Test
    fun `legacy cleanup reduces latest five runs and removes the older run child first`() = runBlocking {
        val dao = database.ingestionProvenanceDao()
        repeat(6) { runIndex ->
            val runId = "run-$runIndex"
            dao.insertRun(run(runId, runIndex.toLong()))
            dao.insertAnnotationEvent(
                annotationEvent("event-$runIndex", runId, "matched-transfer-$runIndex", runIndex.toLong()),
            )
            if (runIndex == 5) {
                dao.insertAnnotationEvent(
                    annotationEvent("no-match-event", runId, "miss-transfer-$runIndex", runIndex.toLong()),
                )
            }
            dao.insertRuleEvaluations(
                listOf(
                    rule("matched-$runIndex", runId, matched = true),
                    rule("miss-$runIndex", runId, matched = false),
                ),
            )
            dao.insertConditionEvaluations(
                listOf(
                    condition("matched-condition-$runIndex", "matched-$runIndex", matched = true),
                    condition("miss-condition-$runIndex", "miss-$runIndex", matched = false),
                ),
            )
        }
        dao.insertRuleEvaluations(listOf(rule("null-run", null, matched = true)))
        dao.insertConditionEvaluations(listOf(condition("null-condition", "null-run", matched = true)))

        val observedPhases = mutableListOf<ClassificationTraceMaintenancePhase>()
        repeat(100) {
            val result = store.pruneBatch(limit = 1, retainTerminalExecutions = 5)
            observedPhases += result.phase
            if (!result.hasMore) return@repeat
        }

        assertTrue(observedPhases.indexOf(ClassificationTraceMaintenancePhase.LEGACY_NON_MATCH_CONDITIONS) <
            observedPhases.indexOf(ClassificationTraceMaintenancePhase.LEGACY_NON_MATCH_RULES))
        assertTrue(observedPhases.indexOf(ClassificationTraceMaintenancePhase.LEGACY_EXPIRED_RUN_CONDITIONS) <
            observedPhases.indexOf(ClassificationTraceMaintenancePhase.LEGACY_EXPIRED_RUN_RULES))
        assertEquals(5, scalarInt("SELECT COUNT(*) FROM classification_rule_evaluations"))
        assertEquals(5, scalarInt("SELECT COUNT(*) FROM classification_condition_evaluations"))
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM classification_rule_evaluations WHERE runId = 'run-0'"))
        assertEquals(5, scalarInt("SELECT COUNT(*) FROM transfer_annotation_events WHERE detailState = 'LEGACY_REDUCED'"))
        assertEquals(2, scalarInt("SELECT COUNT(*) FROM transfer_annotation_events WHERE detailState = 'SUMMARY_ONLY'"))
        assertEquals(
            0,
            scalarInt(
                "SELECT COUNT(*) FROM classification_condition_evaluations c " +
                    "LEFT JOIN classification_rule_evaluations r ON r.id = c.ruleEvaluationId WHERE r.id IS NULL",
            ),
        )
    }

    @Test
    fun `legacy non-match batches advance parents without exceeding the row limit`() = runBlocking {
        val dao = database.ingestionProvenanceDao()
        dao.insertRun(run("run", 0))
        val rules = (0 until 10).map { index ->
            rule("miss-$index", "run", matched = false)
        }
        dao.insertRuleEvaluations(rules)
        dao.insertConditionEvaluations(
            rules.flatMap { rule ->
                (0 until 3).map { conditionIndex ->
                    condition("condition-${rule.id}-$conditionIndex", rule.id, matched = false)
                }
            },
        )

        val first = store.pruneBatch(limit = 4, retainTerminalExecutions = 5)

        assertEquals(4, first.rowsDeleted)
        assertEquals(ClassificationTraceMaintenancePhase.LEGACY_NON_MATCH_CONDITIONS, first.phase)
        assertEquals(9, scalarInt("SELECT COUNT(*) FROM classification_rule_evaluations"))
        assertEquals(27, scalarInt("SELECT COUNT(*) FROM classification_condition_evaluations"))
        assertEquals(
            0,
            scalarInt(
                "SELECT COUNT(*) FROM classification_condition_evaluations c " +
                    "LEFT JOIN classification_rule_evaluations r ON r.id = c.ruleEvaluationId WHERE r.id IS NULL",
            ),
        )
    }

    @Test
    fun `decision summary rejects overlapping or missing rule reason counts`() {
        var failed = false
        try {
            ClassificationDecisionSummary(
                evaluatedRuleCount = 2,
                matchedRuleCount = 1,
                retainedRuleCount = 1,
                unusableRuleCount = 0,
                scopeMismatchCount = 0,
                conditionMismatchCount = 0,
                categoryIncompatibleCount = 0,
                evaluatedConditionCount = 0,
                matchedConditionCount = 0,
                retainedConditionCount = 0,
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `checkpoint reports allocation without vacuuming`() = runBlocking {
        val stats = store.checkpointAndStats()
        assertTrue(stats.pageCount > 0)
        assertTrue(stats.pageSize > 0)
        assertEquals(stats.pageCount * stats.pageSize, stats.databaseBytes)
        assertFalse(stats.freelistCount < 0)
    }

    @Test
    fun `file database reuses pruned pages and shrinks only after explicit vacuum`() = runBlocking {
        val databaseName = "classification-trace-maintenance-file-test.db"
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)
        val fileDatabase = Room.databaseBuilder(
            context,
            MoneylookDatabase::class.java,
            databaseName,
        ).allowMainThreadQueries().build()
        try {
            // Force the fallback mode where DELETE creates reusable pages and only an explicit
            // VACUUM returns them to the filesystem. Some framework SQLite builds default to FULL.
            fileDatabase.openHelper.writableDatabase.execSQL("PRAGMA auto_vacuum=NONE")
            fileDatabase.openHelper.writableDatabase.execSQL("VACUUM")
            val dao = fileDatabase.ingestionProvenanceDao()
            val largeCandidateValues = "[\"${"x".repeat(4_096)}\"]"
            repeat(6) { executionIndex ->
                val executionId = "file-execution-$executionIndex"
                dao.insertClassificationExecution(
                    execution(
                        id = executionId,
                        startedAt = executionIndex.toLong(),
                        status = ClassificationExecutionStatus.COMPLETE,
                    ),
                )
                val rules = (0 until 100).map { ruleIndex ->
                    ruleForExecution(
                        id = "file-rule-$executionIndex-$ruleIndex",
                        executionId = executionId,
                    )
                }
                dao.insertRuleEvaluations(rules)
                dao.insertConditionEvaluations(
                    rules.flatMap { rule ->
                        (0 until 3).map { conditionIndex ->
                            condition(
                                id = "file-condition-${rule.id}-$conditionIndex",
                                ruleId = rule.id,
                                matched = true,
                            ).copy(candidateValuesJson = largeCandidateValues)
                        }
                    },
                )
            }
            val fileStore = RoomClassificationTraceMaintenanceStore(
                fileDatabase,
                fileDatabase.classificationTraceMaintenanceDao(),
            )
            val before = fileStore.checkpointAndStats()

            while (fileStore.pruneBatch(limit = 73, retainTerminalExecutions = 1).hasMore) {
                // Delete most of the fixture so whole SQLite pages become reusable.
            }
            val afterDelete = fileStore.checkpointAndStats()

            assertEquals(100, fileScalarInt(fileDatabase, "classification_rule_evaluations"))
            assertEquals(300, fileScalarInt(fileDatabase, "classification_condition_evaluations"))
            assertEquals(0, afterDelete.autoVacuum)
            assertTrue(
                "expected freelist growth: before=$before afterDelete=$afterDelete",
                afterDelete.freelistCount > before.freelistCount,
            )
            assertEquals(before.pageCount, afterDelete.pageCount)

            fileDatabase.openHelper.writableDatabase.execSQL("VACUUM")
            val afterVacuum = fileStore.checkpointAndStats()

            assertEquals(0, afterVacuum.freelistCount)
            assertTrue(afterVacuum.pageCount < afterDelete.pageCount)
            assertTrue(afterVacuum.databaseBytes < afterDelete.databaseBytes)
        } finally {
            fileDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun `stale pending execution fails and is evicted while a fresh pending execution remains`() = runBlocking {
        val dao = database.ingestionProvenanceDao()
        val now = 1_000_000_000L
        dao.insertClassificationExecution(execution("stale", 0, ClassificationExecutionStatus.PENDING))
        dao.insertClassificationExecution(execution("fresh", now, ClassificationExecutionStatus.PENDING))
        dao.insertRuleEvaluations(listOf(ruleForExecution("stale-rule", "stale")))
        repeat(5) { index ->
            val id = "terminal-$index"
            dao.insertClassificationExecution(
                execution(id, 100L + index, ClassificationExecutionStatus.COMPLETE),
            )
            dao.insertRuleEvaluations(listOf(ruleForExecution("$id-rule", id)))
        }

        var finalResult: ClassificationTraceMaintenanceBatchResult? = null
        repeat(100) {
            val result = store.pruneBatch(limit = 1, retainTerminalExecutions = 5, now = now)
            finalResult = result
            if (!result.hasMore) return@repeat
        }

        assertEquals(ClassificationExecutionStatus.FAILED, dao.getClassificationExecution("stale")?.status)
        assertEquals(ClassificationDetailState.SUMMARY_ONLY, dao.getClassificationExecution("stale")?.detailState)
        assertEquals(ClassificationExecutionStatus.PENDING, dao.getClassificationExecution("fresh")?.status)
        assertEquals(ClassificationDetailState.REDUCED, dao.getClassificationExecution("fresh")?.detailState)
        assertEquals(0, scalarInt("SELECT COUNT(*) FROM classification_rule_evaluations WHERE classificationExecutionId = 'stale'"))
        assertEquals(ClassificationTraceMaintenancePhase.COMPLETE, finalResult?.phase)
    }

    private fun run(id: String, startedAt: Long) = IngestionRun(
        id = id,
        startedAt = startedAt,
        completedAt = startedAt + 1,
        extensionId = "ext",
        extensionVersion = 1,
        artifactRevision = null,
        artifactSha256 = null,
        trigger = IngestionTrigger.USER_SYNC,
        status = IngestionStatus.SUCCESS,
        classificationStatus = IngestionClassificationStatus.COMPLETE,
        classificationCompletedAt = startedAt + 1,
        accountCount = 0,
        transferCount = 1,
        sourceFingerprint = "source",
        fingerprintKeyVersion = 1,
    )

    private fun annotationEvent(id: String, runId: String, transferId: String, time: Long) = TransferAnnotationEvent(
        id = id,
        occurredAt = time,
        runId = runId,
        transferId = transferId,
        extensionId = "ext",
        trigger = ClassificationTrigger.INGESTION,
        outcome = ClassificationOutcome.AUTO_APPLIED,
        previousCategoryId = null,
        newCategoryId = "category",
        ruleId = "rule",
        ruleSetId = null,
        ruleContentSha256 = null,
        ruleSetContentSha256 = null,
        matchScore = 1,
        classifierVersion = "legacy",
        detailState = tw.kevinzhang.core.data.model.ClassificationDetailState.LEGACY_FULL,
    )

    private fun rule(id: String, runId: String?, matched: Boolean) = ClassificationRuleEvaluation(
        id = id,
        runId = runId,
        transferId = when {
            runId == null -> "null-transfer"
            id.startsWith("miss-") -> "miss-transfer-${runId.removePrefix("run-")}"
            else -> "matched-transfer-${runId.removePrefix("run-")}"
        },
        extensionId = "ext",
        evaluatedAt = 1,
        trigger = ClassificationTrigger.INGESTION,
        ruleId = "rule-$id",
        ruleSetId = null,
        ruleContentSha256 = null,
        scopeMatched = matched,
        conditionsMatched = matched,
        categoryCompatible = matched,
        matched = matched,
        selected = matched,
        score = if (matched) 1 else null,
        reasonCode = if (matched) "SELECTED" else "SCOPE_MISMATCH",
        classifierVersion = "legacy",
    )

    private fun condition(id: String, ruleId: String, matched: Boolean) = ClassificationConditionEvaluation(
        id = id,
        ruleEvaluationId = ruleId,
        transferId = "transfer",
        evaluatedAt = 1,
        position = 0,
        conditionGroup = "ALL",
        field = "DESCRIPTION",
        matchMode = "CONTAINS",
        pattern = "pattern",
        candidateValuesJson = "[]",
        matched = matched,
    )

    private fun execution(id: String, startedAt: Long, status: ClassificationExecutionStatus) =
        ClassificationExecution(
            id = id,
            extensionId = "ext-new",
            runId = null,
            trigger = ClassificationTrigger.BULK_REAPPLY,
            startedAt = startedAt,
            completedAt = if (status == ClassificationExecutionStatus.PENDING) null else startedAt + 1,
            status = status,
            detailState = ClassificationDetailState.REDUCED,
        )

    private fun ruleForExecution(id: String, executionId: String) = rule(id, null, matched = true).copy(
        classificationExecutionId = executionId,
    )

    private fun scalarInt(sql: String): Int = database.openHelper.writableDatabase.query(sql).use { cursor ->
        cursor.moveToFirst()
        cursor.getInt(0)
    }

    private fun fileScalarInt(database: MoneylookDatabase, table: String): Int =
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
