package tw.kevinzhang.moneylook.sync

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
import tw.kevinzhang.core.data.model.normalizeAutoCategoryRuleTextV2
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

fun interface TransferAutoCategorizer {
    suspend fun categorizeTransferIds(transferIds: List<String>)
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
) : TransferAutoCategorizer {
    private val categorizationMutex = Mutex()

    override suspend fun categorizeTransferIds(transferIds: List<String>) {
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
                existingAnnotations = context.annotations,
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
            existingAnnotations = context.annotations,
        )
        true
    }

    suspend fun applyToExistingTransactions(): AutoCategoryApplicationResult =
        categorizationMutex.withLock {
            val context = loadClassificationContext()
            categorize(
                candidates = context.candidates,
                internalTransferIds = context.internalTransferCounterparts.keys,
                existingAnnotations = context.annotations,
            )
        }

    suspend fun resumeAutomaticCategorization(transferId: String) {
        val existing = annotationDao.getByTransferIds(listOf(transferId)).singleOrNull()
        if (existing != null) {
            annotationDao.upsert(
                existing.copy(
                    categoryId = null,
                    categoryAssignment = AssignmentSource.AUTO,
                    manualOverride = false,
                ),
            )
        }
        categorizeTransferIds(listOf(transferId))
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
        return ClassificationContext(candidates, annotations, counterparts, categoryAvailable)
    }

    private suspend fun categorize(
        candidates: List<TransferClassificationCandidate>,
        internalTransferIds: Set<String>,
        existingAnnotations: Map<String, TransferAnnotation>,
    ): AutoCategoryApplicationResult {
        if (candidates.isEmpty()) {
            return AutoCategoryApplicationResult(
                processedTransferCount = 0,
                matchedTransferCount = 0,
                preservedManualOverrideCount = 0,
            )
        }
        val rules = ruleDao.getEnabledInPriorityOrder().filter(::isUsableRule)
        var matchedTransferCount = 0
        var preservedManualOverrideCount = 0

        candidates.forEach { candidate ->
            val transfer = candidate.transfer
            val existing = existingAnnotations[transfer.id]
            if (existing?.manualOverride == true) {
                preservedManualOverrideCount += 1
                return@forEach
            }

            val isInternalTransfer = transfer.id in internalTransferIds
            val decision = if (isInternalTransfer) null else rules.classificationDecision(candidate)
            if (decision is ClassificationDecision.Suggest || decision is ClassificationDecision.Abstain) {
                return@forEach
            }
            val match = decision as? ClassificationDecision.AutoApply
            if (!isInternalTransfer && match == null && existing == null) return@forEach
            if (isInternalTransfer || match != null) matchedTransferCount += 1

            annotationDao.upsert(
                TransferAnnotation(
                    transferId = transfer.id,
                    extensionId = transfer.extensionId,
                    categoryId = if (isInternalTransfer) {
                        INTERNAL_TRANSFER_CATEGORY_ID
                    } else {
                        match?.evaluation?.ruleWithTags?.rule?.categoryId
                    },
                    note = existing?.note.orEmpty(),
                    categoryAssignment = AssignmentSource.AUTO,
                    manualOverride = false,
                    autoRuleId = match?.evaluation?.ruleWithTags?.rule?.id,
                    autoRuleSetId = match?.evaluation?.ruleWithTags?.rule?.ruleSetId,
                    autoMatchScore = match?.evaluation?.score,
                    classifierVersion = match?.let { CLASSIFIER_VERSION },
                ),
            )
            annotationDao.replaceAutoTags(
                transferId = transfer.id,
                tagIds = if (isInternalTransfer) {
                    emptySet()
                } else {
                    match?.evaluation?.ruleWithTags?.tags?.mapTo(mutableSetOf()) { it.id }.orEmpty()
                },
            )
        }
        return AutoCategoryApplicationResult(
            processedTransferCount = candidates.size,
            matchedTransferCount = matchedTransferCount,
            preservedManualOverrideCount = preservedManualOverrideCount,
        )
    }

    private data class ClassificationContext(
        val candidates: List<TransferClassificationCandidate>,
        val annotations: Map<String, TransferAnnotation>,
        val internalTransferCounterparts: Map<String, String>,
        val internalTransferCategoryAvailable: Boolean,
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
        AutoCategoryRuleAction.SUGGEST -> ClassificationDecision.Suggest(winning)
        AutoCategoryRuleAction.ABSTAIN -> ClassificationDecision.Abstain
    }
}

internal sealed interface ClassificationDecision {
    data class AutoApply(val evaluation: RuleEvaluation) : ClassificationDecision
    data class Suggest(val evaluation: RuleEvaluation) : ClassificationDecision
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
    AutoCategoryRuleConditionField.DESCRIPTION -> listOf(transfer.description)
    AutoCategoryRuleConditionField.MEMO -> listOf(transfer.memo)
    AutoCategoryRuleConditionField.TYPE -> listOf(transfer.type.orEmpty())
    AutoCategoryRuleConditionField.STATUS -> listOf(transfer.status.orEmpty())
    AutoCategoryRuleConditionField.MERCHANT_NAME -> listOf(transfer.merchantName.orEmpty())
    AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE -> listOf(transfer.merchantCategoryCode.orEmpty())
    AutoCategoryRuleConditionField.COUNTERPARTY_NAME -> listOf(transfer.counterpartyName.orEmpty())
    AutoCategoryRuleConditionField.PURPOSE -> listOf(transfer.purpose.orEmpty())
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
    -> when (mode) {
        AutoCategoryRuleConditionMatchMode.EXACT -> 100
        AutoCategoryRuleConditionMatchMode.TOKEN -> 80
        AutoCategoryRuleConditionMatchMode.CONTAINS -> 70
    }
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

private const val MIN_CATEGORY_MARGIN = 20
private const val MAX_INCLUDE_ALL_BONUS = 15
private const val CLASSIFIER_VERSION = "rules-v2"

@Module
@InstallIn(SingletonComponent::class)
abstract class AutoCategorizerModule {
    @Binds
    abstract fun bindTransferAutoCategorizer(impl: AutoCategorizer): TransferAutoCategorizer
}
