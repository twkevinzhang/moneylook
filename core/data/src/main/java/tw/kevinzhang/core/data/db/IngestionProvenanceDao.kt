package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tw.kevinzhang.core.data.model.IngestionRun
import tw.kevinzhang.core.data.model.TransferAnnotationEvent
import tw.kevinzhang.core.data.model.TransferIngestionEvent
import tw.kevinzhang.core.data.model.IngestionClassificationStatus
import tw.kevinzhang.core.data.model.SourceDocument
import tw.kevinzhang.core.data.model.TransferFieldObservation
import tw.kevinzhang.core.data.model.ClassificationRuleEvaluation
import tw.kevinzhang.core.data.model.ClassificationConditionEvaluation

/**
 * Bounded-list projection for ingestion history.
 *
 * Complete failure message/stack/diagnostic JSON are intentionally absent so merely opening the
 * history does not read or compose every potentially multi-megabyte failure payload.
 */
data class IngestionRunSummary(
    val id: String,
    val startedAt: Long,
    val completedAt: Long,
    val extensionId: String,
    val extensionVersion: Int,
    val trigger: String,
    val status: String,
    val classificationStatus: String,
    val accountCount: Int,
    val transferCount: Int,
    val failureOrigin: String?,
    val failureCode: String?,
    val failureScriptFrame: String?,
)

/** Safe, aggregate-only quality signals. No transaction text is selected by this DAO. */
data class MonthlyClassificationAggregate(
    val month: String,
    val expenseTotal: Int,
    val autoClassified: Int,
    val manualClassified: Int,
    val unclassified: Int,
)

/** Counts structured source fields without exposing their values. */
data class StructuredFieldPresenceAggregate(
    val transferCount: Int,
    val merchantNameCount: Int,
    val merchantCategoryCodeCount: Int,
    val counterpartyNameCount: Int,
    val purposeCount: Int,
    val memoCount: Int,
    val typeCount: Int,
)

/** Raw body is deliberately excluded; detail screens load it only after an explicit user action. */
data class SourceDocumentSummary(
    val id: String,
    val runId: String,
    val extensionId: String,
    val capturedAt: Long,
    val stage: String,
    val transport: String,
    val method: String,
    val url: String,
    val statusCode: Int?,
    val responseHeadersJson: String,
    val mediaKind: String?,
    val bodyEncoding: String,
    val representation: String,
    val bodyByteCount: Long,
    val bodySha256: String,
)

@Dao
interface IngestionProvenanceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(run: IngestionRun)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTransferEvents(events: List<TransferIngestionEvent>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnnotationEvent(event: TransferAnnotationEvent)

    @Query(
        """
        UPDATE ingestion_runs
        SET classificationStatus = :status, classificationCompletedAt = :completedAt
        WHERE id = :runId
        """,
    )
    suspend fun updateClassificationStatus(
        runId: String,
        status: IngestionClassificationStatus,
        completedAt: Long?,
    )

    @Query(
        """
        SELECT * FROM ingestion_runs
        WHERE extensionId = :extensionId
        ORDER BY startedAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun getRecentRuns(extensionId: String, limit: Int = 100): List<IngestionRun>

    @Query(
        """
        SELECT id, startedAt, completedAt, extensionId, extensionVersion, trigger, status,
            classificationStatus, accountCount, transferCount, failureOrigin, failureCode,
            failureScriptFrame
        FROM ingestion_runs
        WHERE extensionId = :extensionId
        ORDER BY startedAt DESC, id DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getRunSummaries(
        extensionId: String,
        limit: Int,
        offset: Int,
    ): List<IngestionRunSummary>

    @Query("SELECT * FROM ingestion_runs WHERE id = :runId LIMIT 1")
    suspend fun getIngestionRun(runId: String): IngestionRun?

    @Query(
        """
        SELECT * FROM transfer_ingestion_events
        WHERE transferId = :transferId
        ORDER BY occurredAt DESC, id DESC
        """,
    )
    suspend fun getTransferIngestionEvents(transferId: String): List<TransferIngestionEvent>

    @Query(
        """
        SELECT r.* FROM ingestion_runs r
        WHERE r.id IN (
            SELECT e.runId FROM transfer_ingestion_events e WHERE e.transferId = :transferId
        )
        ORDER BY r.startedAt DESC, r.id DESC
        """,
    )
    suspend fun getIngestionRunsForTransfer(transferId: String): List<IngestionRun>

    @Query(
        """
        SELECT * FROM transfer_annotation_events
        WHERE transferId = :transferId
        ORDER BY occurredAt DESC, id DESC
        """,
    )
    suspend fun getTransferAnnotationEvents(transferId: String): List<TransferAnnotationEvent>

    @Query(
        """
        SELECT d.id, d.runId, d.extensionId, d.capturedAt, d.stage, d.transport,
            d.method, d.url, d.statusCode, d.responseHeadersJson, d.mediaKind, d.bodyEncoding,
            d.representation,
            d.bodyByteCount, d.bodySha256
        FROM source_documents d
        WHERE d.runId IN (
            SELECT e.runId FROM transfer_ingestion_events e WHERE e.transferId = :transferId
        )
        ORDER BY d.capturedAt DESC, d.id DESC
        """,
    )
    suspend fun getSourceDocumentsForTransfer(transferId: String): List<SourceDocumentSummary>

    @Query(
        """
        SELECT d.id, d.runId, d.extensionId, d.capturedAt, d.stage, d.transport,
            d.method, d.url, d.statusCode, d.responseHeadersJson, d.mediaKind, d.bodyEncoding,
            d.representation,
            d.bodyByteCount, d.bodySha256
        FROM source_documents d
        WHERE d.runId IN (
            SELECT f.runId FROM transfer_field_observations f
            WHERE f.assetType = :assetType AND f.assetId = :assetId
        )
        ORDER BY d.capturedAt DESC, d.id DESC
        """,
    )
    suspend fun getSourceDocumentsForAsset(
        assetType: String,
        assetId: String,
    ): List<SourceDocumentSummary>

    @Query(
        """
        SELECT d.id, d.runId, d.extensionId, d.capturedAt, d.stage, d.transport,
            d.method, d.url, d.statusCode, d.responseHeadersJson, d.mediaKind, d.bodyEncoding,
            d.representation, d.bodyByteCount, d.bodySha256
        FROM source_documents d
        WHERE d.runId = :runId
        ORDER BY d.capturedAt DESC, d.id DESC
        """,
    )
    suspend fun getSourceDocumentsForRun(runId: String): List<SourceDocumentSummary>

    @Query(
        """
        SELECT * FROM transfer_field_observations
        WHERE transferId = :transferId
        ORDER BY observedAt DESC, fieldName, id
        """,
    )
    suspend fun getTransferFieldObservations(transferId: String): List<TransferFieldObservation>

    @Query(
        """
        SELECT * FROM transfer_field_observations
        WHERE assetType = :assetType AND assetId = :assetId
        ORDER BY observedAt DESC, fieldName, id
        """,
    )
    suspend fun getFieldObservationsForAsset(
        assetType: String,
        assetId: String,
    ): List<TransferFieldObservation>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRuleEvaluations(evaluations: List<ClassificationRuleEvaluation>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConditionEvaluations(evaluations: List<ClassificationConditionEvaluation>)

    @Query(
        """
        SELECT * FROM classification_rule_evaluations
        WHERE transferId = :transferId
        ORDER BY evaluatedAt DESC, ruleId, id
        """,
    )
    suspend fun getRuleEvaluations(transferId: String): List<ClassificationRuleEvaluation>

    @Query(
        """
        SELECT * FROM classification_condition_evaluations
        WHERE transferId = :transferId
        ORDER BY evaluatedAt DESC, ruleEvaluationId, position
        """,
    )
    suspend fun getConditionEvaluations(transferId: String): List<ClassificationConditionEvaluation>

    @Query("SELECT * FROM source_documents WHERE id = :id LIMIT 1")
    suspend fun getSourceDocument(id: String): SourceDocument?

    @Query(
        """
        SELECT substr(t.txnDateTime, 1, 7) AS month,
            COUNT(*) AS expenseTotal,
            COALESCE(SUM(CASE WHEN a.categoryAssignment = 'AUTO' AND a.categoryId IS NOT NULL THEN 1 ELSE 0 END), 0) AS autoClassified,
            COALESCE(SUM(CASE WHEN a.categoryAssignment = 'MANUAL' AND a.categoryId IS NOT NULL THEN 1 ELSE 0 END), 0) AS manualClassified,
            COALESCE(SUM(CASE WHEN a.categoryId IS NULL AND COALESCE(a.manualOverride, 0) = 0 THEN 1 ELSE 0 END), 0) AS unclassified
        FROM transfers t
        LEFT JOIN transfer_annotations a ON a.transferId = t.id
        WHERE t.amount < 0
        GROUP BY substr(t.txnDateTime, 1, 7)
        ORDER BY month
        """,
    )
    suspend fun monthlyExpenseClassificationAggregates(): List<MonthlyClassificationAggregate>

    @Query(
        """
        SELECT COUNT(*) AS transferCount,
            COALESCE(SUM(CASE WHEN merchantName IS NOT NULL AND trim(merchantName) != '' THEN 1 ELSE 0 END), 0) AS merchantNameCount,
            COALESCE(SUM(CASE WHEN merchantCategoryCode IS NOT NULL AND trim(merchantCategoryCode) != '' THEN 1 ELSE 0 END), 0) AS merchantCategoryCodeCount,
            COALESCE(SUM(CASE WHEN counterpartyName IS NOT NULL AND trim(counterpartyName) != '' THEN 1 ELSE 0 END), 0) AS counterpartyNameCount,
            COALESCE(SUM(CASE WHEN purpose IS NOT NULL AND trim(purpose) != '' THEN 1 ELSE 0 END), 0) AS purposeCount,
            COALESCE(SUM(CASE WHEN trim(memo) != '' THEN 1 ELSE 0 END), 0) AS memoCount,
            COALESCE(SUM(CASE WHEN type IS NOT NULL AND trim(type) != '' THEN 1 ELSE 0 END), 0) AS typeCount
        FROM transfers
        """,
    )
    suspend fun structuredFieldPresenceAggregate(): StructuredFieldPresenceAggregate
}
