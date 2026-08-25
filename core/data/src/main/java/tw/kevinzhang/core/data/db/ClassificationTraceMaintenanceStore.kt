package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.withTransaction
import tw.kevinzhang.core.data.model.ClassificationDetailState
import tw.kevinzhang.core.data.model.ClassificationExecution

enum class ClassificationTraceMaintenancePhase {
    STALE_PENDING_EXECUTIONS,
    LEGACY_NON_MATCH_CONDITIONS,
    LEGACY_NON_MATCH_RULES,
    LEGACY_NULL_RUN_CONDITIONS,
    LEGACY_NULL_RUN_RULES,
    LEGACY_EXPIRED_RUN_CONDITIONS,
    LEGACY_EXPIRED_RUN_RULES,
    LEGACY_EVENT_STATES,
    EXECUTION_CONDITIONS,
    EXECUTION_RULES,
    COMPLETE,
}

data class ClassificationTraceMaintenanceBatchResult(
    val rowsDeleted: Int,
    val hasMore: Boolean,
    val phase: ClassificationTraceMaintenancePhase,
    val executionId: String? = null,
)

data class ClassificationTraceDatabaseStats(
    val pageCount: Long,
    val freelistCount: Long,
    val pageSize: Long,
    val databaseBytes: Long,
    /** 0=NONE, 1=FULL, 2=INCREMENTAL. */
    val autoVacuum: Int,
    val walBusy: Int,
    val walLogFrames: Int,
    val walCheckpointedFrames: Int,
)

interface ClassificationTraceMaintenanceStore {
    /** Deletes at most [limit] trace rows; safe to cancel and call again. Never runs VACUUM. */
    suspend fun pruneBatch(
        limit: Int = DEFAULT_TRACE_PRUNE_BATCH_SIZE,
        retainTerminalExecutions: Int = DEFAULT_RETAINED_CLASSIFICATION_EXECUTIONS,
        now: Long = System.currentTimeMillis(),
    ): ClassificationTraceMaintenanceBatchResult

    /** Runs only a passive WAL checkpoint and reads SQLite allocation statistics. */
    suspend fun checkpointAndStats(): ClassificationTraceDatabaseStats

    companion object {
        const val DEFAULT_TRACE_PRUNE_BATCH_SIZE = 500
        const val DEFAULT_RETAINED_CLASSIFICATION_EXECUTIONS = 5
        const val STALE_PENDING_EXECUTION_MILLIS = 24L * 60L * 60L * 1_000L
    }
}

data class LegacyClassificationRunCandidate(val runId: String)

@Dao
abstract class ClassificationTraceMaintenanceDao {
    @Query(
        """
        UPDATE classification_executions
        SET status = 'FAILED', completedAt = startedAt
        WHERE id IN (
            SELECT id FROM classification_executions
            WHERE status = 'PENDING' AND startedAt < :cutoff
            ORDER BY startedAt, id LIMIT :limit
        )
        """,
    )
    abstract suspend fun failStalePendingExecutions(cutoff: Long, limit: Int): Int

    @Query(
        """
        DELETE FROM classification_condition_evaluations
        WHERE id IN (
            SELECT c.id
            FROM (
                SELECT id
                FROM classification_rule_evaluations
                    INDEXED BY index_classification_rule_evaluations_classificationExecutionId
                WHERE classificationExecutionId IS NULL AND matched = 0 AND selected = 0
                LIMIT :parentLimit
            ) candidate
            CROSS JOIN classification_condition_evaluations c
                INDEXED BY index_classification_condition_evaluations_ruleEvaluationId_position
            WHERE c.ruleEvaluationId = candidate.id
            LIMIT :limit
        )
        """,
    )
    abstract suspend fun deleteLegacyNonMatchConditions(limit: Int, parentLimit: Int): Int

    @Query(
        """
        DELETE FROM classification_rule_evaluations
        WHERE id IN (
            SELECT id FROM classification_rule_evaluations
            WHERE classificationExecutionId IS NULL AND matched = 0 AND selected = 0
              AND NOT EXISTS (
                  SELECT 1 FROM classification_condition_evaluations c
                  WHERE c.ruleEvaluationId = classification_rule_evaluations.id
              )
            LIMIT :limit
        )
        """,
    )
    abstract suspend fun deleteLegacyNonMatchRules(limit: Int): Int

    @Query(
        """
        DELETE FROM classification_condition_evaluations
        WHERE id IN (
            SELECT c.id
            FROM (
                SELECT id
                FROM classification_rule_evaluations
                    INDEXED BY index_classification_rule_evaluations_runId
                WHERE classificationExecutionId IS NULL AND runId IS NULL
                LIMIT :parentLimit
            ) candidate
            CROSS JOIN classification_condition_evaluations c
                INDEXED BY index_classification_condition_evaluations_ruleEvaluationId_position
            WHERE c.ruleEvaluationId = candidate.id
            LIMIT :limit
        )
        """,
    )
    abstract suspend fun deleteLegacyNullRunConditions(limit: Int, parentLimit: Int): Int

    @Query(
        """
        DELETE FROM classification_rule_evaluations
        WHERE id IN (
            SELECT id FROM classification_rule_evaluations
            WHERE classificationExecutionId IS NULL AND runId IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM classification_condition_evaluations c
                  WHERE c.ruleEvaluationId = classification_rule_evaluations.id
              )
            LIMIT :limit
        )
        """,
    )
    abstract suspend fun deleteLegacyNullRunRules(limit: Int): Int

    @Query(
        """
        SELECT ir.id AS runId
        FROM ingestion_runs ir
        WHERE ir.classificationStatus IN ('COMPLETE', 'FAILED')
          AND EXISTS (
              SELECT 1 FROM classification_rule_evaluations r
              WHERE r.classificationExecutionId IS NULL AND r.runId = ir.id
          )
          AND (
              SELECT COUNT(*) FROM ingestion_runs newer
              WHERE newer.extensionId = ir.extensionId
                AND newer.classificationStatus IN ('COMPLETE', 'FAILED')
                AND (newer.startedAt > ir.startedAt OR (newer.startedAt = ir.startedAt AND newer.id > ir.id))
                AND EXISTS (
                    SELECT 1 FROM classification_rule_evaluations nr
                    WHERE nr.classificationExecutionId IS NULL AND nr.runId = newer.id
                )
          ) >= :retainCount
        ORDER BY ir.startedAt, ir.id
        LIMIT 1
        """,
    )
    abstract suspend fun findExpiredLegacyRun(retainCount: Int): LegacyClassificationRunCandidate?

    @Query(
        """
        DELETE FROM classification_condition_evaluations
        WHERE id IN (
            SELECT c.id
            FROM (
                SELECT id
                FROM classification_rule_evaluations
                    INDEXED BY index_classification_rule_evaluations_runId
                WHERE classificationExecutionId IS NULL AND runId = :runId
                LIMIT :parentLimit
            ) candidate
            CROSS JOIN classification_condition_evaluations c
                INDEXED BY index_classification_condition_evaluations_ruleEvaluationId_position
            WHERE c.ruleEvaluationId = candidate.id
            LIMIT :limit
        )
        """,
    )
    abstract suspend fun deleteLegacyRunConditions(runId: String, limit: Int, parentLimit: Int): Int

    @Query(
        """
        DELETE FROM classification_rule_evaluations
        WHERE id IN (
            SELECT id FROM classification_rule_evaluations
            WHERE classificationExecutionId IS NULL AND runId = :runId
              AND NOT EXISTS (
                  SELECT 1 FROM classification_condition_evaluations c
                  WHERE c.ruleEvaluationId = classification_rule_evaluations.id
              )
            LIMIT :limit
        )
        """,
    )
    abstract suspend fun deleteLegacyRunRules(runId: String, limit: Int): Int

    @Query(
        """
        UPDATE transfer_annotation_events
        SET detailState = CASE
            WHEN runId IS NOT NULL AND EXISTS (
                SELECT 1 FROM classification_rule_evaluations r
                WHERE r.classificationExecutionId IS NULL
                  AND r.runId = transfer_annotation_events.runId
                  AND r.transferId = transfer_annotation_events.transferId
            ) THEN 'LEGACY_REDUCED'
            ELSE 'SUMMARY_ONLY'
        END
        WHERE id IN (
            SELECT id FROM transfer_annotation_events
            WHERE detailState = 'LEGACY_FULL'
            LIMIT :limit
        )
        """,
    )
    abstract suspend fun updateLegacyEventStates(limit: Int): Int

    @Query("SELECT EXISTS(SELECT 1 FROM transfer_annotation_events WHERE detailState = 'LEGACY_FULL')")
    abstract suspend fun hasLegacyEventStates(): Boolean

    @Query(
        """
        SELECT * FROM classification_executions
        WHERE detailState IN ('PRUNING_CONDITIONS', 'PRUNING_RULES')
        ORDER BY completedAt, id LIMIT 1
        """,
    )
    abstract suspend fun findPruningExecution(): ClassificationExecution?

    @Query(
        """
        SELECT e.* FROM classification_executions e
        WHERE e.status IN ('COMPLETE', 'FAILED') AND e.detailState = 'REDUCED'
          AND (
              SELECT COUNT(*) FROM classification_executions newer
              WHERE newer.extensionId = e.extensionId
                AND newer.status IN ('COMPLETE', 'FAILED')
                AND (newer.completedAt > e.completedAt OR (newer.completedAt = e.completedAt AND newer.id > e.id))
          ) >= :retainCount
        ORDER BY e.completedAt, e.id LIMIT 1
        """,
    )
    abstract suspend fun findExpiredExecution(retainCount: Int): ClassificationExecution?

    @Query("UPDATE classification_executions SET detailState = :state WHERE id = :executionId")
    abstract suspend fun updateExecutionState(executionId: String, state: ClassificationDetailState): Int

    @Query(
        """
        UPDATE transfer_annotation_events
        SET detailState = :state,
            retainedRuleCount = CASE WHEN :state = 'SUMMARY_ONLY' THEN 0 ELSE retainedRuleCount END,
            retainedConditionCount = CASE WHEN :state = 'SUMMARY_ONLY' THEN 0 ELSE retainedConditionCount END
        WHERE classificationExecutionId = :executionId
        """,
    )
    abstract suspend fun updateExecutionEventStates(executionId: String, state: ClassificationDetailState): Int

    @Query(
        """
        DELETE FROM classification_condition_evaluations
        WHERE id IN (
            SELECT c.id
            FROM (
                SELECT id
                FROM classification_rule_evaluations
                    INDEXED BY index_classification_rule_evaluations_classificationExecutionId
                WHERE classificationExecutionId = :executionId
                LIMIT :parentLimit
            ) candidate
            CROSS JOIN classification_condition_evaluations c
                INDEXED BY index_classification_condition_evaluations_ruleEvaluationId_position
            WHERE c.ruleEvaluationId = candidate.id
            LIMIT :limit
        )
        """,
    )
    abstract suspend fun deleteExecutionConditions(executionId: String, limit: Int, parentLimit: Int): Int

    @Query(
        """
        DELETE FROM classification_rule_evaluations
        WHERE id IN (
            SELECT id FROM classification_rule_evaluations
            WHERE classificationExecutionId = :executionId
              AND NOT EXISTS (
                  SELECT 1 FROM classification_condition_evaluations c
                  WHERE c.ruleEvaluationId = classification_rule_evaluations.id
              )
            LIMIT :limit
        )
        """,
    )
    abstract suspend fun deleteExecutionRules(executionId: String, limit: Int): Int

}

class RoomClassificationTraceMaintenanceStore(
    private val database: MoneylookDatabase,
    private val dao: ClassificationTraceMaintenanceDao,
) : ClassificationTraceMaintenanceStore {
    override suspend fun pruneBatch(
        limit: Int,
        retainTerminalExecutions: Int,
        now: Long,
    ): ClassificationTraceMaintenanceBatchResult {
        require(limit in 1..10_000) { "trace prune batch size must be between 1 and 10000" }
        require(retainTerminalExecutions >= 0) { "retained execution count must not be negative" }
        return database.withTransaction {
            pruneBatchInTransaction(limit, retainTerminalExecutions, now)
        }
    }

    private suspend fun pruneBatchInTransaction(
        limit: Int,
        retainCount: Int,
        now: Long,
    ): ClassificationTraceMaintenanceBatchResult {
        val staleCount = dao.failStalePendingExecutions(
            cutoff = now - ClassificationTraceMaintenanceStore.STALE_PENDING_EXECUTION_MILLIS,
            limit = limit,
        )
        if (staleCount > 0) {
            return ClassificationTraceMaintenanceBatchResult(
                rowsDeleted = 0,
                hasMore = true,
                phase = ClassificationTraceMaintenancePhase.STALE_PENDING_EXECUTIONS,
            )
        }
        val legacyNonMatchConditions = dao.deleteLegacyNonMatchConditions(
            limit = conditionDeleteLimit(limit),
            parentLimit = parentDeleteLimit(limit),
        )
        val legacyNonMatchRules = dao.deleteLegacyNonMatchRules(limit - legacyNonMatchConditions)
        if (legacyNonMatchConditions + legacyNonMatchRules > 0) {
            return result(
                legacyNonMatchConditions + legacyNonMatchRules,
                if (legacyNonMatchConditions > 0) {
                    ClassificationTraceMaintenancePhase.LEGACY_NON_MATCH_CONDITIONS
                } else {
                    ClassificationTraceMaintenancePhase.LEGACY_NON_MATCH_RULES
                },
            )
        }

        val legacyNullRunConditions = dao.deleteLegacyNullRunConditions(
            limit = conditionDeleteLimit(limit),
            parentLimit = parentDeleteLimit(limit),
        )
        val legacyNullRunRules = dao.deleteLegacyNullRunRules(limit - legacyNullRunConditions)
        if (legacyNullRunConditions + legacyNullRunRules > 0) {
            return result(
                legacyNullRunConditions + legacyNullRunRules,
                if (legacyNullRunConditions > 0) {
                    ClassificationTraceMaintenancePhase.LEGACY_NULL_RUN_CONDITIONS
                } else {
                    ClassificationTraceMaintenancePhase.LEGACY_NULL_RUN_RULES
                },
            )
        }

        dao.findExpiredLegacyRun(retainCount)?.let { candidate ->
            val conditions = dao.deleteLegacyRunConditions(
                runId = candidate.runId,
                limit = conditionDeleteLimit(limit),
                parentLimit = parentDeleteLimit(limit),
            )
            val rules = dao.deleteLegacyRunRules(candidate.runId, limit - conditions)
            check(conditions + rules > 0) { "expired legacy run candidate made no cleanup progress" }
            return result(
                conditions + rules,
                if (conditions > 0) {
                    ClassificationTraceMaintenancePhase.LEGACY_EXPIRED_RUN_CONDITIONS
                } else {
                    ClassificationTraceMaintenancePhase.LEGACY_EXPIRED_RUN_RULES
                },
                candidate.runId,
            )
        }

        if (dao.hasLegacyEventStates()) {
            val updated = dao.updateLegacyEventStates(limit)
            return ClassificationTraceMaintenanceBatchResult(
                rowsDeleted = 0,
                hasMore = updated > 0,
                phase = ClassificationTraceMaintenancePhase.LEGACY_EVENT_STATES,
            )
        }

        var execution = dao.findPruningExecution()
        if (execution == null) {
            execution = dao.findExpiredExecution(retainCount)
            if (execution != null) {
                dao.updateExecutionState(execution.id, ClassificationDetailState.PRUNING_CONDITIONS)
                dao.updateExecutionEventStates(execution.id, ClassificationDetailState.PRUNING_CONDITIONS)
                execution = execution.copy(detailState = ClassificationDetailState.PRUNING_CONDITIONS)
            }
        }
        if (execution == null) {
            return ClassificationTraceMaintenanceBatchResult(0, false, ClassificationTraceMaintenancePhase.COMPLETE)
        }

        if (execution.detailState == ClassificationDetailState.PRUNING_CONDITIONS) {
            val conditions = dao.deleteExecutionConditions(
                executionId = execution.id,
                limit = conditionDeleteLimit(limit),
                parentLimit = parentDeleteLimit(limit),
            )
            val rules = dao.deleteExecutionRules(execution.id, limit - conditions)
            if (conditions + rules > 0) {
                return result(
                    conditions + rules,
                    if (conditions > 0) {
                        ClassificationTraceMaintenancePhase.EXECUTION_CONDITIONS
                    } else {
                        ClassificationTraceMaintenancePhase.EXECUTION_RULES
                    },
                    execution.id,
                )
            }
            dao.updateExecutionState(execution.id, ClassificationDetailState.PRUNING_RULES)
            dao.updateExecutionEventStates(execution.id, ClassificationDetailState.PRUNING_RULES)
        }

        val remainingRules = dao.deleteExecutionRules(execution.id, limit)
        if (remainingRules > 0) {
            return result(remainingRules, ClassificationTraceMaintenancePhase.EXECUTION_RULES, execution.id)
        }
        dao.updateExecutionState(execution.id, ClassificationDetailState.SUMMARY_ONLY)
        dao.updateExecutionEventStates(execution.id, ClassificationDetailState.SUMMARY_ONLY)
        return ClassificationTraceMaintenanceBatchResult(
            rowsDeleted = 0,
            hasMore = true,
            phase = ClassificationTraceMaintenancePhase.EXECUTION_RULES,
            executionId = execution.id,
        )
    }

    override suspend fun checkpointAndStats(): ClassificationTraceDatabaseStats {
        val db = database.openHelper.writableDatabase
        var busy = 0
        var logFrames = 0
        var checkpointedFrames = 0
        db.query("PRAGMA wal_checkpoint(PASSIVE)").use { cursor ->
            if (cursor.moveToFirst()) {
                busy = cursor.getInt(0)
                logFrames = cursor.getInt(1)
                checkpointedFrames = cursor.getInt(2)
            }
        }
        fun pragmaLong(name: String): Long = db.query("PRAGMA $name").use { cursor ->
            check(cursor.moveToFirst()) { "SQLite did not return PRAGMA $name" }
            cursor.getLong(0)
        }
        val pageCount = pragmaLong("page_count")
        val freelistCount = pragmaLong("freelist_count")
        val pageSize = pragmaLong("page_size")
        val autoVacuum = pragmaLong("auto_vacuum").toInt()
        return ClassificationTraceDatabaseStats(
            pageCount = pageCount,
            freelistCount = freelistCount,
            pageSize = pageSize,
            databaseBytes = pageCount * pageSize,
            autoVacuum = autoVacuum,
            walBusy = busy,
            walLogFrames = logFrames,
            walCheckpointedFrames = checkpointedFrames,
        )
    }

    private fun result(
        rowsDeleted: Int,
        phase: ClassificationTraceMaintenancePhase,
        executionId: String? = null,
    ) = ClassificationTraceMaintenanceBatchResult(
        rowsDeleted = rowsDeleted,
        hasMore = true,
        phase = phase,
        executionId = executionId,
    )

    /** Reserves at least one row per normal batch for advancing parent-rule deletion. */
    private fun conditionDeleteLimit(limit: Int): Int = if (limit == 1) 1 else (limit * 3) / 4

    private fun parentDeleteLimit(limit: Int): Int = maxOf(1, limit / 4)
}
