package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import tw.kevinzhang.core.data.model.IngestionRun
import tw.kevinzhang.core.data.model.TransferAnnotationEvent
import tw.kevinzhang.core.data.model.TransferIngestionEvent
import tw.kevinzhang.core.data.model.IngestionClassificationStatus

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
        SELECT * FROM transfer_ingestion_events
        WHERE transferId = :transferId
        ORDER BY occurredAt DESC, id DESC
        """,
    )
    suspend fun getTransferIngestionEvents(transferId: String): List<TransferIngestionEvent>

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
