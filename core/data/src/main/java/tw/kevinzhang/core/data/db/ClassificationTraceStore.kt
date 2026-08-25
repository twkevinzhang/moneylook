package tw.kevinzhang.core.data.db

import androidx.room.withTransaction
import tw.kevinzhang.core.data.model.ClassificationConditionEvaluation
import tw.kevinzhang.core.data.model.ClassificationRuleEvaluation
import tw.kevinzhang.core.data.model.ClassificationExecution
import tw.kevinzhang.core.data.model.ClassificationExecutionStatus
import tw.kevinzhang.core.data.model.ClassificationDetailState
import tw.kevinzhang.core.data.model.ClassificationTrigger
import java.util.UUID

/** Commits the current annotation and its complete evaluation trace as one Room transaction. */
interface ClassificationTraceStore {
    suspend fun startExecution(
        extensionId: String,
        trigger: ClassificationTrigger,
        runId: String?,
        startedAt: Long = System.currentTimeMillis(),
    ): String

    suspend fun completeExecution(
        executionId: String,
        completedAt: Long = System.currentTimeMillis(),
    )

    suspend fun failExecution(
        executionId: String,
        completedAt: Long = System.currentTimeMillis(),
    )

    suspend fun apply(
        decision: AutomaticClassificationDecision,
        summary: ClassificationDecisionSummary,
        ruleEvaluations: List<ClassificationRuleEvaluation>,
        conditionEvaluations: List<ClassificationConditionEvaluation>,
    ): AutomaticClassificationWriteResult

    /** Compatibility overload for callers that have no aggregate-only non-match evidence. */
    suspend fun apply(
        decision: AutomaticClassificationDecision,
        ruleEvaluations: List<ClassificationRuleEvaluation>,
        conditionEvaluations: List<ClassificationConditionEvaluation>,
    ): AutomaticClassificationWriteResult = apply(
        decision = decision,
        summary = ClassificationDecisionSummary(
            evaluatedRuleCount = ruleEvaluations.size,
            matchedRuleCount = ruleEvaluations.count { it.matched },
            retainedRuleCount = ruleEvaluations.size,
            unusableRuleCount = ruleEvaluations.count { it.reasonCode == "UNUSABLE_RULE" },
            scopeMismatchCount = ruleEvaluations.count { it.reasonCode == "SCOPE_MISMATCH" },
            conditionMismatchCount = ruleEvaluations.count { it.reasonCode == "CONDITION_MISMATCH" },
            categoryIncompatibleCount = ruleEvaluations.count { it.reasonCode == "CATEGORY_INCOMPATIBLE" },
            evaluatedConditionCount = conditionEvaluations.size,
            matchedConditionCount = conditionEvaluations.count { it.matched },
            retainedConditionCount = conditionEvaluations.size,
        ),
        ruleEvaluations = ruleEvaluations,
        conditionEvaluations = conditionEvaluations,
    )
}

class RoomClassificationTraceStore(
    private val database: MoneylookDatabase,
    private val annotationDao: TransferAnnotationDao,
    private val provenanceDao: IngestionProvenanceDao,
) : ClassificationTraceStore {
    override suspend fun startExecution(
        extensionId: String,
        trigger: ClassificationTrigger,
        runId: String?,
        startedAt: Long,
    ): String {
        val executionId = UUID.randomUUID().toString()
        provenanceDao.insertClassificationExecution(
            ClassificationExecution(
                id = executionId,
                extensionId = extensionId,
                runId = runId,
                trigger = trigger,
                startedAt = startedAt,
                completedAt = null,
                status = ClassificationExecutionStatus.PENDING,
                detailState = ClassificationDetailState.REDUCED,
            ),
        )
        return executionId
    }

    override suspend fun completeExecution(executionId: String, completedAt: Long) {
        require(
            provenanceDao.finishClassificationExecution(
                executionId,
                ClassificationExecutionStatus.COMPLETE,
                completedAt,
            ) == 1,
        ) { "classification execution is missing or already terminal: $executionId" }
    }

    override suspend fun failExecution(executionId: String, completedAt: Long) {
        require(
            provenanceDao.finishClassificationExecution(
                executionId,
                ClassificationExecutionStatus.FAILED,
                completedAt,
            ) == 1,
        ) { "classification execution is missing or already terminal: $executionId" }
    }

    override suspend fun apply(
        decision: AutomaticClassificationDecision,
        summary: ClassificationDecisionSummary,
        ruleEvaluations: List<ClassificationRuleEvaluation>,
        conditionEvaluations: List<ClassificationConditionEvaluation>,
    ): AutomaticClassificationWriteResult = database.withTransaction {
        require(decision.classificationExecutionId != null) {
            "automatic classification requires a classification execution"
        }
        val execution = requireNotNull(
            provenanceDao.getClassificationExecution(decision.classificationExecutionId),
        ) { "classification execution does not exist: ${decision.classificationExecutionId}" }
        require(execution.status == ClassificationExecutionStatus.PENDING) {
            "classification execution is already terminal: ${decision.classificationExecutionId}"
        }
        require(execution.extensionId == decision.extensionId && execution.runId == decision.runId) {
            "classification execution context mismatch"
        }
        require(execution.trigger == decision.trigger) { "classification execution trigger mismatch" }
        require(ruleEvaluations.all { it.classificationExecutionId == decision.classificationExecutionId }) {
            "rule evaluation execution mismatch"
        }
        val retainedRuleIds = ruleEvaluations.mapTo(mutableSetOf()) { it.id }
        require(conditionEvaluations.all { it.ruleEvaluationId in retainedRuleIds }) {
            "condition evaluation must belong to a retained rule evaluation"
        }
        require(summary.retainedRuleCount == ruleEvaluations.size)
        require(summary.retainedConditionCount == conditionEvaluations.size)
        val result = annotationDao.applyAutomaticDecision(decision, summary)
        if (ruleEvaluations.isNotEmpty()) provenanceDao.insertRuleEvaluations(ruleEvaluations)
        if (conditionEvaluations.isNotEmpty()) provenanceDao.insertConditionEvaluations(conditionEvaluations)
        result
    }
}
