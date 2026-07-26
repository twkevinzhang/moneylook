package tw.kevinzhang.moneylook.sync

import com.google.gson.Gson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tw.kevinzhang.core.data.db.AutoCategoryRuleDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleWithTags
import tw.kevinzhang.core.data.db.CategoryDao
import tw.kevinzhang.core.data.db.TransferAnnotationDao
import tw.kevinzhang.core.data.db.TransferClassificationCandidate
import tw.kevinzhang.core.data.db.TransferDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleSetDao
import tw.kevinzhang.core.data.db.AutomaticClassificationDecision
import tw.kevinzhang.core.data.db.AutomaticClassificationWriteResult
import tw.kevinzhang.core.data.db.ClassificationTraceStore
import tw.kevinzhang.core.data.model.ClassificationOutcome
import tw.kevinzhang.core.data.model.ClassificationTrigger
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.CategoryKind
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.core.data.model.ClassificationRuleEvaluation
import tw.kevinzhang.core.data.model.ClassificationConditionEvaluation
import tw.kevinzhang.core.data.model.normalizeAutoCategoryRuleTextV2
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.text.Normalizer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import java.util.UUID

fun interface TransferAutoCategorizer {
    suspend fun categorizeTransferIds(transferIds: List<String>)

    /** Sync persistence overrides this to connect every decision to its ingestion run. */
    suspend fun categorizeTransferIds(transferIds: List<String>, ingestionRunId: String) {
        categorizeTransferIds(transferIds)
    }
}

/** Aggregate-only outcome for a bulk application; it intentionally contains no transaction data. */
data class AutoCategoryApplicationResult(
    val processedTransferCount: Int,
    val matchedTransferCount: Int,
    val preservedManualOverrideCount: Int,
)

/** Evaluates enabled rules conservatively while preserving every explicit user transaction edit. */
@Singleton
class AutoCategorizer @Inject constructor(
    private val transferDao: TransferDao,
    private val annotationDao: TransferAnnotationDao,
    private val ruleDao: AutoCategoryRuleDao,
    private val categoryDao: CategoryDao,
    private val classificationTraceStore: ClassificationTraceStore,
    private val ruleSetDao: AutoCategoryRuleSetDao? = null,
    private val gson: Gson = Gson(),
) : TransferAutoCategorizer {
    private val categorizationMutex = Mutex()

    override suspend fun categorizeTransferIds(transferIds: List<String>) =
        categorizeTransferIdsInternal(transferIds, ingestionRunId = null)

    override suspend fun categorizeTransferIds(transferIds: List<String>, ingestionRunId: String) =
        categorizeTransferIdsInternal(transferIds, ingestionRunId)

    private suspend fun categorizeTransferIdsInternal(
        transferIds: List<String>,
        ingestionRunId: String?,
    ) {
        categorizationMutex.withLock {
            val context = loadClassificationContext()
            val automaticTransferIds = context.automaticInternalTransferIds()
            val existingIds = context.candidates.mapTo(mutableSetOf()) { it.transfer.id }
            val seedIds = (transferIds.toSet() + automaticTransferIds).intersect(existingIds)
            val targetIds = seedIds.toMutableSet().apply {
                seedIds.mapNotNullTo(this) { context.internalTransferCounterparts[it] }
            }
            categorize(
                candidates = context.candidates.filter { it.transfer.id in targetIds },
                internalTransferIds = context.internalTransferCounterparts.keys,
                ingestionRunId = ingestionRunId,
                trigger = ClassificationTrigger.INGESTION,
                ruleSetContentById = context.ruleSetContentById,
            )
        }
    }

    /**
     * Re-evaluates only the built-in cross-account rule for an app upgrade. Ordinary automatic
     * rules remain scoped to their existing sync/manual-apply entry points.
     *
     * Returns false when the user-owned transfer category is unavailable so callers can retry
     * after the catalog is restored instead of permanently recording a skipped backfill.
     */
    suspend fun applyInternalTransferBackfill(): Boolean = categorizationMutex.withLock {
        val context = loadClassificationContext()
        if (!context.internalTransferCategoryAvailable) return@withLock false
        val targetIds = context.internalTransferCounterparts.keys + context.automaticInternalTransferIds()
        categorize(
            candidates = context.candidates.filter { it.transfer.id in targetIds },
            internalTransferIds = context.internalTransferCounterparts.keys,
            ingestionRunId = null,
            trigger = ClassificationTrigger.INTERNAL_BACKFILL,
            ruleSetContentById = context.ruleSetContentById,
        )
        true
    }

    suspend fun applyToExistingTransactions(
        trigger: ClassificationTrigger = ClassificationTrigger.BULK_REAPPLY,
    ): AutoCategoryApplicationResult =
        categorizationMutex.withLock {
            val context = loadClassificationContext()
            categorize(
                candidates = context.candidates,
                internalTransferIds = context.internalTransferCounterparts.keys,
                ingestionRunId = null,
                trigger = trigger,
                ruleSetContentById = context.ruleSetContentById,
            )
        }

    suspend fun resumeAutomaticCategorization(transferId: String) {
        categorizationMutex.withLock {
            annotationDao.resumeAutomaticClassification(transferId)
            val context = loadClassificationContext()
            categorize(
                candidates = context.candidates.filter { it.transfer.id == transferId },
                internalTransferIds = context.internalTransferCounterparts.keys,
                ingestionRunId = null,
                trigger = ClassificationTrigger.RESUME,
                ruleSetContentById = context.ruleSetContentById,
            )
        }
    }

    private suspend fun loadClassificationContext(): ClassificationContext {
        val candidates = transferDao.getAllClassificationCandidates()
        val annotations = annotationDao.getByTransferIds(candidates.map { it.transfer.id })
            .associateBy(TransferAnnotation::transferId)
        val internalTransferCategory = categoryDao.getById(INTERNAL_TRANSFER_CATEGORY_ID)
        val categoryAvailable = internalTransferCategory?.kind == CategoryKind.TRANSFER
        val counterparts = if (categoryAvailable) {
            internalTransferCounterparts(candidates)
        } else {
            emptyMap()
        }
        val ruleSetContentById = ruleSetDao?.getAll()
            .orEmpty()
            .associate { it.id to it.contentSha256 }
        return ClassificationContext(
            candidates,
            annotations,
            counterparts,
            categoryAvailable,
            ruleSetContentById,
        )
    }

    private suspend fun categorize(
        candidates: List<TransferClassificationCandidate>,
        internalTransferIds: Set<String>,
        ingestionRunId: String?,
        trigger: ClassificationTrigger,
        ruleSetContentById: Map<String, String>,
    ): AutoCategoryApplicationResult {
        if (candidates.isEmpty()) {
            return AutoCategoryApplicationResult(
                processedTransferCount = 0,
                matchedTransferCount = 0,
                preservedManualOverrideCount = 0,
            )
        }
        val enabledRules = ruleDao.getEnabledInPriorityOrder()
        val rules = enabledRules.filter(::isUsableRule)
        var matchedTransferCount = 0
        var preservedManualOverrideCount = 0

        candidates.forEach { candidate ->
            val transfer = candidate.transfer
            val isInternalTransfer = transfer.id in internalTransferIds
            val decision = if (isInternalTransfer) null else rules.classificationDecision(candidate)
            val match = decision as? ClassificationDecision.AutoApply
            val outcome = when {
                decision is ClassificationDecision.Abstain -> ClassificationOutcome.ABSTAINED
                isInternalTransfer || match != null -> ClassificationOutcome.AUTO_APPLIED
                else -> ClassificationOutcome.NO_MATCH
            }
            val trace = completeEvaluationTrace(
                transfer = transfer,
                candidate = candidate,
                rules = enabledRules,
                selectedRuleId = match?.evaluation?.ruleWithTags?.rule?.id,
                ingestionRunId = ingestionRunId,
                trigger = trigger,
            )
            val writeResult = classificationTraceStore.apply(
                AutomaticClassificationDecision(
                    transferId = transfer.id,
                    extensionId = transfer.extensionId,
                    categoryId = if (isInternalTransfer) {
                        INTERNAL_TRANSFER_CATEGORY_ID
                    } else {
                        match?.evaluation?.ruleWithTags?.rule?.categoryId
                    },
                    tagIds = if (isInternalTransfer || outcome != ClassificationOutcome.AUTO_APPLIED) {
                        emptySet()
                    } else {
                        match?.evaluation?.ruleWithTags?.tags?.mapTo(mutableSetOf()) { it.id }.orEmpty()
                    },
                    runId = ingestionRunId,
                    trigger = trigger,
                    outcome = outcome,
                    ruleId = match?.evaluation?.ruleWithTags?.rule?.id,
                    ruleSetId = match?.evaluation?.ruleWithTags?.rule?.ruleSetId,
                    ruleContentSha256 = match?.evaluation?.ruleWithTags?.contentSha256(),
                    ruleSetContentSha256 = match?.evaluation?.ruleWithTags?.rule?.ruleSetId
                        ?.let(ruleSetContentById::get),
                    matchScore = match?.evaluation?.score,
                    classifierVersion = CLASSIFIER_VERSION,
                ),
                ruleEvaluations = trace.rules,
                conditionEvaluations = trace.conditions,
            )
            when (writeResult) {
                AutomaticClassificationWriteResult.APPLIED -> matchedTransferCount += 1
                AutomaticClassificationWriteResult.PRESERVED_MANUAL ->
                    preservedManualOverrideCount += 1
                AutomaticClassificationWriteResult.RECORDED_ONLY -> Unit
            }
        }
        return AutoCategoryApplicationResult(
            processedTransferCount = candidates.size,
            matchedTransferCount = matchedTransferCount,
            preservedManualOverrideCount = preservedManualOverrideCount,
        )
    }

    private fun completeEvaluationTrace(
        transfer: Transfer,
        candidate: TransferClassificationCandidate,
        rules: List<AutoCategoryRuleWithTags>,
        selectedRuleId: String?,
        ingestionRunId: String?,
        trigger: ClassificationTrigger,
    ): EvaluationTrace {
        val evaluatedAt = System.currentTimeMillis()
        val conditionRows = mutableListOf<ClassificationConditionEvaluation>()
        val ruleRows = rules.map { ruleWithTags ->
            val evaluationId = UUID.randomUUID().toString()
            val scopeMatched = ruleWithTags.scopeMatches(candidate)
            val categoryCompatible = ruleWithTags.categoryIsCompatibleWith(transfer)
            val conditionMatches = ruleWithTags.conditions.map { condition ->
                val actualValues = candidate.facts(condition.field)
                val match = condition.matches(candidate)
                conditionRows += ClassificationConditionEvaluation(
                    id = UUID.randomUUID().toString(),
                    ruleEvaluationId = evaluationId,
                    transferId = transfer.id,
                    evaluatedAt = evaluatedAt,
                    position = condition.position,
                    conditionGroup = condition.conditionGroup.name,
                    field = condition.field.name,
                    matchMode = condition.matchMode.name,
                    pattern = condition.pattern,
                    candidateValuesJson = gson.toJson(actualValues),
                    matched = match.matched,
                )
                match
            }
            val legacyMatched = if (ruleWithTags.conditions.isEmpty()) {
                ruleWithTags.legacyMatchScore(transfer) != null
            } else {
                true
            }
            val conditionsMatched = if (ruleWithTags.conditions.isEmpty()) {
                legacyMatched
            } else {
                val byGroup = ruleWithTags.conditions.zip(conditionMatches)
                    .groupBy({ it.first.conditionGroup }, { it.second.matched })
                byGroup[AutoCategoryRuleConditionGroup.INCLUDE_ALL].orEmpty().all { it } &&
                    byGroup[AutoCategoryRuleConditionGroup.INCLUDE_ANY].orEmpty()
                        .let { it.isEmpty() || it.any { matched -> matched } } &&
                    byGroup[AutoCategoryRuleConditionGroup.EXCLUDE_ANY].orEmpty().none { it }
            }
            val evaluation = ruleWithTags.evaluate(candidate)
            val usable = isUsableRule(ruleWithTags)
            val matched = usable && scopeMatched && conditionsMatched && categoryCompatible && evaluation != null
            ClassificationRuleEvaluation(
                id = evaluationId,
                runId = ingestionRunId,
                transferId = transfer.id,
                extensionId = transfer.extensionId,
                evaluatedAt = evaluatedAt,
                trigger = trigger,
                ruleId = ruleWithTags.rule.id,
                ruleSetId = ruleWithTags.rule.ruleSetId,
                ruleContentSha256 = ruleWithTags.contentSha256(),
                scopeMatched = scopeMatched,
                conditionsMatched = conditionsMatched,
                categoryCompatible = categoryCompatible,
                matched = matched,
                selected = ruleWithTags.rule.id == selectedRuleId,
                score = evaluation?.score,
                reasonCode = when {
                    !usable -> "UNUSABLE_RULE"
                    !scopeMatched -> "SCOPE_MISMATCH"
                    !conditionsMatched -> "CONDITION_MISMATCH"
                    !categoryCompatible -> "CATEGORY_INCOMPATIBLE"
                    ruleWithTags.rule.id == selectedRuleId -> "SELECTED"
                    matched -> "MATCHED_NOT_SELECTED"
                    else -> "NO_MATCH"
                },
                classifierVersion = CLASSIFIER_VERSION,
            )
        }
        return EvaluationTrace(ruleRows, conditionRows)
    }

    private data class EvaluationTrace(
        val rules: List<ClassificationRuleEvaluation>,
        val conditions: List<ClassificationConditionEvaluation>,
    )

    private data class ClassificationContext(
        val candidates: List<TransferClassificationCandidate>,
        val annotations: Map<String, TransferAnnotation>,
        val internalTransferCounterparts: Map<String, String>,
        val internalTransferCategoryAvailable: Boolean,
        val ruleSetContentById: Map<String, String>,
    )

    private fun ClassificationContext.automaticInternalTransferIds(): Set<String> =
        annotations.values.asSequence()
            .filter { annotation ->
                annotation.categoryAssignment == AssignmentSource.AUTO &&
                    annotation.categoryId == INTERNAL_TRANSFER_CATEGORY_ID
            }
            .map(TransferAnnotation::transferId)
            .toSet()
}

/**
 * Returns both directions of every unambiguous internal-transfer pair.
 *
 * A candidate edge must be the only possible edge for both transactions. This mutual-uniqueness
 * rule intentionally leaves every one-to-many or many-to-many group unclassified instead of using
 * ordering or a greedy tie-breaker that could turn an ordinary payment into an account transfer.
 */
internal fun internalTransferCounterparts(
    candidates: List<TransferClassificationCandidate>,
): Map<String, String> {
    val timedCandidates = candidates.mapNotNull { candidate ->
        val transfer = candidate.transfer
        val normalizedCurrency = candidate.currency.trim().uppercase(Locale.ROOT)
        val postedAt = transfer.txnDateTime.parseIsoLocalDateTimeOrNull()
        if (
            normalizedCurrency.isBlank() ||
            !transfer.amount.isFinite() ||
            transfer.amount == 0.0 ||
            postedAt == null
        ) {
            null
        } else {
            TimedTransferCandidate(candidate, normalizedCurrency, postedAt)
        }
    }
    val neighbours = mutableMapOf<String, MutableSet<String>>()
    timedCandidates.groupBy { candidate ->
        InternalTransferBucket(
            currency = candidate.normalizedCurrency,
            absoluteAmount = abs(candidate.candidate.transfer.amount),
            date = candidate.postedAt.toLocalDate(),
        )
    }.values.forEach { bucket ->
        val income = bucket.filter { it.candidate.transfer.amount > 0.0 }
        val expense = bucket.filter { it.candidate.transfer.amount < 0.0 }
        income.forEach { incoming ->
            expense.forEach { outgoing ->
                val incomingTransfer = incoming.candidate.transfer
                val outgoingTransfer = outgoing.candidate.transfer
                if (
                    incomingTransfer.accountId != outgoingTransfer.accountId &&
                    Duration.between(incoming.postedAt, outgoing.postedAt).abs() <= INTERNAL_TRANSFER_WINDOW
                ) {
                    neighbours.getOrPut(incomingTransfer.id, ::mutableSetOf).add(outgoingTransfer.id)
                    neighbours.getOrPut(outgoingTransfer.id, ::mutableSetOf).add(incomingTransfer.id)
                }
            }
        }
    }
    return buildMap {
        neighbours.forEach { (transferId, possibleCounterparts) ->
            val counterpart = possibleCounterparts.singleOrNull() ?: return@forEach
            if (neighbours[counterpart]?.singleOrNull() == transferId) {
                put(transferId, counterpart)
            }
        }
    }
}

private data class TimedTransferCandidate(
    val candidate: TransferClassificationCandidate,
    val normalizedCurrency: String,
    val postedAt: LocalDateTime,
)

private data class InternalTransferBucket(
    val currency: String,
    val absoluteAmount: Double,
    val date: LocalDate,
)

private fun String.parseIsoLocalDateTimeOrNull(): LocalDateTime? {
    val value = trim()
    return parseDateTimeOrNull { LocalDateTime.parse(value) }
        ?: parseDateTimeOrNull { OffsetDateTime.parse(value).toLocalDateTime() }
}

private inline fun parseDateTimeOrNull(parse: () -> LocalDateTime): LocalDateTime? = try {
    parse()
} catch (_: DateTimeParseException) {
    null
}

private const val INTERNAL_TRANSFER_CATEGORY_ID = "transfer-account"
private val INTERNAL_TRANSFER_WINDOW: Duration = Duration.ofSeconds(30)

/**
 * Evaluates every matching rule before selecting an automatic action.  Rule priority only breaks
 * ties inside one category; it must never hide materially conflicting classifications.
 */
internal fun List<AutoCategoryRuleWithTags>.classificationDecision(
    candidate: TransferClassificationCandidate,
): ClassificationDecision? {
    val evaluations = asSequence()
        .mapNotNull { it.evaluate(candidate) }
        .filter { it.ruleWithTags.categoryIsCompatibleWith(candidate.transfer) }
        .toList()
    if (evaluations.isEmpty()) return null
    if (evaluations.any { it.ruleWithTags.rule.action == AutoCategoryRuleAction.ABSTAIN }) {
        return ClassificationDecision.Abstain
    }

    val bestByCategory = evaluations
        .groupBy { it.ruleWithTags.rule.categoryId ?: "__tags:${it.ruleWithTags.rule.id}" }
        .values
        .map { choices -> choices.sortedWith(ruleEvaluationComparator).first() }
        .sortedWith(ruleEvaluationComparator)
    val winning = bestByCategory.first()
    val categoryChoices = bestByCategory.filter { it.ruleWithTags.rule.categoryId != null }
    if (
        categoryChoices.size > 1 &&
        categoryChoices[0].score - categoryChoices[1].score < MIN_CATEGORY_MARGIN
    ) {
        return ClassificationDecision.Abstain
    }
    return when (winning.ruleWithTags.rule.action) {
        AutoCategoryRuleAction.AUTO_APPLY -> ClassificationDecision.AutoApply(winning)
        AutoCategoryRuleAction.ABSTAIN -> ClassificationDecision.Abstain
    }
}

internal sealed interface ClassificationDecision {
    data class AutoApply(val evaluation: RuleEvaluation) : ClassificationDecision
    data object Abstain : ClassificationDecision
}

internal data class RuleEvaluation(
    val ruleWithTags: AutoCategoryRuleWithTags,
    val score: Int,
)

private val ruleEvaluationComparator = compareByDescending<RuleEvaluation> { it.score }
    .thenBy { it.ruleWithTags.rule.priority }
    .thenBy { it.ruleWithTags.rule.id }

private fun AutoCategoryRuleWithTags.evaluate(
    candidate: TransferClassificationCandidate,
): RuleEvaluation? {
    val rule = this.rule
    if (!scopeMatches(candidate)) return null
    val conditionList = this.conditions
    val conditionScore = if (conditionList.isEmpty()) {
        legacyMatchScore(candidate.transfer) ?: return null
    } else {
        conditionList.matchScore(candidate) ?: return null
    }
    return RuleEvaluation(this, conditionScore + rule.scopeScore() + rule.originScore())
}

private fun AutoCategoryRuleWithTags.scopeMatches(candidate: TransferClassificationCandidate): Boolean {
    val rule = rule
    val transfer = candidate.transfer
    val directionMatches = when (rule.direction) {
        AutoCategoryRuleDirection.ANY -> true
        AutoCategoryRuleDirection.INCOME -> transfer.amount > 0.0
        AutoCategoryRuleDirection.EXPENSE -> transfer.amount < 0.0
    }
    val absoluteAmount = abs(transfer.amount)
    return directionMatches &&
        (rule.minAbsoluteAmount?.let { absoluteAmount >= it } ?: true) &&
        (rule.maxAbsoluteAmount?.let { absoluteAmount <= it } ?: true) &&
        (rule.accountId == null || transfer.accountId == rule.accountId) &&
        (rule.accountKind == null || rule.accountKind == candidate.accountKind) &&
        (rule.extensionId == null || rule.extensionId == transfer.extensionId)
}

private fun AutoCategoryRuleWithTags.categoryIsCompatibleWith(transfer: Transfer): Boolean {
    val categoryId = rule.categoryId ?: return true
    val category = category ?: return false
    return when {
        transfer.amount > 0.0 -> category.kind == CategoryKind.INCOME || category.kind == CategoryKind.TRANSFER
        transfer.amount < 0.0 -> category.kind == CategoryKind.EXPENSE || category.kind == CategoryKind.TRANSFER
        else -> category.kind == CategoryKind.TRANSFER
    }
}

private fun List<AutoCategoryRuleCondition>.matchScore(
    candidate: TransferClassificationCandidate,
): Int? {
    val byGroup = groupBy(AutoCategoryRuleCondition::conditionGroup)
    val includeAll = byGroup[AutoCategoryRuleConditionGroup.INCLUDE_ALL].orEmpty()
    val includeAny = byGroup[AutoCategoryRuleConditionGroup.INCLUDE_ANY].orEmpty()
    val excludeAny = byGroup[AutoCategoryRuleConditionGroup.EXCLUDE_ANY].orEmpty()
    val allMatches = includeAll.map { condition -> condition.matches(candidate) }
    if (allMatches.any { !it.matched }) return null
    val anyMatches = includeAny.map { condition -> condition.matches(candidate) }
    if (includeAny.isNotEmpty() && anyMatches.none { it.matched }) return null
    if (excludeAny.any { it.matches(candidate).matched }) return null

    val positiveMatches = allMatches + anyMatches.filter(ConditionMatch::matched)
    if (positiveMatches.isEmpty()) return null
    val evidence = positiveMatches.maxOf(ConditionMatch::score)
    val includeAllBonus = ((allMatches.count(ConditionMatch::matched) - 1).coerceAtLeast(0) * 5)
        .coerceAtMost(MAX_INCLUDE_ALL_BONUS)
    return evidence + includeAllBonus
}

private data class ConditionMatch(val matched: Boolean, val score: Int = 0)

private fun AutoCategoryRuleCondition.matches(candidate: TransferClassificationCandidate): ConditionMatch {
    val normalize = if (field == AutoCategoryRuleConditionField.LEGACY_ANY_TEXT) {
        ::normalizeLegacyAutoCategoryRuleText
    } else {
        ::normalizeAutoCategoryRuleTextV2
    }
    val expected = normalize(pattern)
    if (expected.isBlank()) return ConditionMatch(false)
    val matched = candidate.facts(field)
        .asSequence()
        .map(normalize)
        .filter(String::isNotBlank)
        .any { actual ->
            when (matchMode) {
                AutoCategoryRuleConditionMatchMode.EXACT -> actual == expected
                AutoCategoryRuleConditionMatchMode.TOKEN -> actual.tokensContain(expected)
                AutoCategoryRuleConditionMatchMode.CONTAINS -> actual.contains(expected)
            }
        }
    return ConditionMatch(matched, if (matched) evidenceScore(field, matchMode) else 0)
}

private fun TransferClassificationCandidate.facts(field: AutoCategoryRuleConditionField): List<String> = when (field) {
    AutoCategoryRuleConditionField.LEGACY_ANY_TEXT ->
        listOf(transfer.description, transfer.memo, transfer.type.orEmpty())
    AutoCategoryRuleConditionField.SEARCHABLE_TEXT -> listOf(
        transfer.description,
        transfer.memo,
        transfer.type.orEmpty(),
        transfer.merchantName.orEmpty(),
        transfer.counterpartyName.orEmpty(),
        transfer.purpose.orEmpty(),
        transfer.channel.orEmpty(),
        transfer.referenceNumber.orEmpty(),
        transfer.merchantLocation.orEmpty(),
    )
    AutoCategoryRuleConditionField.DESCRIPTION -> listOf(transfer.description)
    AutoCategoryRuleConditionField.MEMO -> listOf(transfer.memo)
    AutoCategoryRuleConditionField.TYPE -> listOf(transfer.type.orEmpty())
    AutoCategoryRuleConditionField.STATUS -> listOf(transfer.status.orEmpty())
    AutoCategoryRuleConditionField.MERCHANT_NAME -> listOf(transfer.merchantName.orEmpty())
    AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE -> listOf(transfer.merchantCategoryCode.orEmpty())
    AutoCategoryRuleConditionField.COUNTERPARTY_NAME -> listOf(transfer.counterpartyName.orEmpty())
    AutoCategoryRuleConditionField.PURPOSE -> listOf(transfer.purpose.orEmpty())
    AutoCategoryRuleConditionField.CHANNEL -> listOf(transfer.channel.orEmpty())
    AutoCategoryRuleConditionField.TRANSACTION_CODE -> listOf(
        transfer.transactionCode.orEmpty(),
        transfer.type.orEmpty(),
    )
    AutoCategoryRuleConditionField.REFERENCE_NUMBER -> listOf(transfer.referenceNumber.orEmpty())
    AutoCategoryRuleConditionField.MERCHANT_LOCATION -> listOf(transfer.merchantLocation.orEmpty())
}

private fun String.tokensContain(expected: String): Boolean {
    val actualTokens = split(' ').filter(String::isNotBlank).toSet()
    return expected.split(' ').filter(String::isNotBlank).all(actualTokens::contains)
}

private fun evidenceScore(
    field: AutoCategoryRuleConditionField,
    mode: AutoCategoryRuleConditionMatchMode,
): Int = when (field) {
    AutoCategoryRuleConditionField.MERCHANT_NAME,
    AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE,
    AutoCategoryRuleConditionField.COUNTERPARTY_NAME,
    AutoCategoryRuleConditionField.PURPOSE,
    AutoCategoryRuleConditionField.CHANNEL,
    AutoCategoryRuleConditionField.TRANSACTION_CODE,
    AutoCategoryRuleConditionField.REFERENCE_NUMBER,
    AutoCategoryRuleConditionField.MERCHANT_LOCATION,
    -> when (mode) {
        AutoCategoryRuleConditionMatchMode.EXACT -> 100
        AutoCategoryRuleConditionMatchMode.TOKEN -> 80
        AutoCategoryRuleConditionMatchMode.CONTAINS -> 70
    }
    AutoCategoryRuleConditionField.SEARCHABLE_TEXT,
    AutoCategoryRuleConditionField.DESCRIPTION,
    AutoCategoryRuleConditionField.MEMO,
    AutoCategoryRuleConditionField.TYPE,
    AutoCategoryRuleConditionField.STATUS,
    -> when (mode) {
        AutoCategoryRuleConditionMatchMode.EXACT -> 90
        AutoCategoryRuleConditionMatchMode.TOKEN -> 70
        AutoCategoryRuleConditionMatchMode.CONTAINS -> 60
    }
    AutoCategoryRuleConditionField.LEGACY_ANY_TEXT -> when (mode) {
        AutoCategoryRuleConditionMatchMode.EXACT -> 65
        AutoCategoryRuleConditionMatchMode.TOKEN,
        AutoCategoryRuleConditionMatchMode.CONTAINS,
        -> 50
    }
}

private fun AutoCategoryRuleWithTags.legacyMatchScore(transfer: Transfer): Int? =
    if (matchesLegacy(transfer)) {
        if (rule.descriptionContains.isNullOrBlank()) {
            0
        } else {
            when (rule.descriptionMatchMode) {
                AutoCategoryRuleDescriptionMatchMode.EXACT -> 65
                AutoCategoryRuleDescriptionMatchMode.CONTAINS -> 50
            }
        }
    } else {
        null
    }

/** Legacy rule rows preserve the historical description/memo/type matching contract. */
private fun AutoCategoryRuleWithTags.matchesLegacy(transfer: Transfer): Boolean {
    val candidateAmount = abs(transfer.amount)
    val descriptionMatches = rule.descriptionContains
        ?.takeIf(String::isNotBlank)
        ?.let { expected ->
            val normalizedExpected = normalizeLegacyAutoCategoryRuleText(expected)
            if (normalizedExpected.isEmpty()) return@let false
            val normalizedFields = listOf(transfer.description, transfer.memo, transfer.type.orEmpty())
                .map(::normalizeLegacyAutoCategoryRuleText)
            when (rule.descriptionMatchMode) {
                AutoCategoryRuleDescriptionMatchMode.CONTAINS ->
                    normalizedFields.any { it.contains(normalizedExpected) }
                AutoCategoryRuleDescriptionMatchMode.EXACT ->
                    normalizedFields.any { it == normalizedExpected }
            }
        }
        ?: true
    val directionMatches = when (rule.direction) {
        AutoCategoryRuleDirection.ANY -> true
        AutoCategoryRuleDirection.INCOME -> transfer.amount > 0.0
        AutoCategoryRuleDirection.EXPENSE -> transfer.amount < 0.0
    }
    return descriptionMatches &&
        directionMatches &&
        (rule.minAbsoluteAmount?.let { candidateAmount >= it } ?: true) &&
        (rule.maxAbsoluteAmount?.let { candidateAmount <= it } ?: true) &&
        (rule.accountId == null || transfer.accountId == rule.accountId)
}

/** Retained for the v1 unit tests and callers while persisted v1 rows are migrated lazily. */
internal fun AutoCategoryRuleWithTags.matches(transfer: Transfer): Boolean = matchesLegacy(transfer)

/** v1 punctuation-removal behaviour is frozen for persisted legacy rules. */
internal fun normalizeLegacyAutoCategoryRuleText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

internal fun isUsableRule(ruleWithTags: AutoCategoryRuleWithTags): Boolean {
    val rule = ruleWithTags.rule
    val hasCondition = ruleWithTags.conditions.isNotEmpty() || !rule.descriptionContains.isNullOrBlank() ||
        rule.direction != AutoCategoryRuleDirection.ANY ||
        rule.minAbsoluteAmount != null ||
        rule.maxAbsoluteAmount != null ||
        rule.accountId != null ||
        rule.accountKind != null ||
        rule.extensionId != null
    val hasAction = rule.categoryId != null || ruleWithTags.tags.isNotEmpty() ||
        rule.action == AutoCategoryRuleAction.ABSTAIN
    return rule.enabled && hasCondition && hasAction
}

private fun tw.kevinzhang.core.data.model.AutoCategoryRule.scopeScore(): Int =
    listOf(direction != AutoCategoryRuleDirection.ANY, accountKind != null, extensionId != null).count { it } * 5

private fun tw.kevinzhang.core.data.model.AutoCategoryRule.originScore(): Int = when (origin) {
    AutoCategoryRuleOrigin.USER_CONFIRMED -> 30
    AutoCategoryRuleOrigin.PRIVATE_LEARNED -> 10
    else -> 0
}

/** Immutable evidence for the exact rule, conditions, and tags evaluated at decision time. */
private fun AutoCategoryRuleWithTags.contentSha256(): String {
    val canonicalParts = buildList {
        val value = rule
        add(value.id)
        add(value.name)
        add(value.descriptionContains.orEmpty())
        add(value.direction.name)
        add(value.minAbsoluteAmount?.toString().orEmpty())
        add(value.maxAbsoluteAmount?.toString().orEmpty())
        add(value.accountId.orEmpty())
        add(value.categoryId.orEmpty())
        add(value.enabled.toString())
        add(value.priority.toString())
        add(value.descriptionMatchMode.name)
        add(value.isDefault.toString())
        add(value.ruleSetId.orEmpty())
        add(value.extensionId.orEmpty())
        add(value.accountKind?.name.orEmpty())
        add(value.origin.name)
        add(value.action.name)
        conditions.sortedBy { it.position }.forEach { condition ->
            add(condition.position.toString())
            add(condition.conditionGroup.name)
            add(condition.field.name)
            add(condition.matchMode.name)
            add(condition.pattern)
        }
        tags.sortedBy { it.id }.forEach { tag -> add(tag.id) }
    }.joinToString("\u001f") { value -> "${value.length}:$value" }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonicalParts.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private const val MIN_CATEGORY_MARGIN = 20
private const val MAX_INCLUDE_ALL_BONUS = 15
private const val CLASSIFIER_VERSION = "rules-v2"

@Module
@InstallIn(SingletonComponent::class)
abstract class AutoCategorizerModule {
    @Binds
    abstract fun bindTransferAutoCategorizer(impl: AutoCategorizer): TransferAutoCategorizer
}
