package tw.kevinzhang.core.data.db

import androidx.room.withTransaction
import tw.kevinzhang.core.data.model.ClassificationConditionEvaluation
import tw.kevinzhang.core.data.model.ClassificationRuleEvaluation

/** Commits the current annotation and its complete evaluation trace as one Room transaction. */
interface ClassificationTraceStore {
    suspend fun apply(
        decision: AutomaticClassificationDecision,
        ruleEvaluations: List<ClassificationRuleEvaluation>,
        conditionEvaluations: List<ClassificationConditionEvaluation>,
    ): AutomaticClassificationWriteResult
}

class RoomClassificationTraceStore(
    private val database: MoneylookDatabase,
    private val annotationDao: TransferAnnotationDao,
    private val provenanceDao: IngestionProvenanceDao,
) : ClassificationTraceStore {
    override suspend fun apply(
        decision: AutomaticClassificationDecision,
        ruleEvaluations: List<ClassificationRuleEvaluation>,
        conditionEvaluations: List<ClassificationConditionEvaluation>,
    ): AutomaticClassificationWriteResult = database.withTransaction {
        val result = annotationDao.applyAutomaticDecision(decision)
        if (ruleEvaluations.isNotEmpty()) provenanceDao.insertRuleEvaluations(ruleEvaluations)
        if (conditionEvaluations.isNotEmpty()) provenanceDao.insertConditionEvaluations(conditionEvaluations)
        result
    }
}
