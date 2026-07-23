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
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.CategoryKind
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.core.data.model.normalizeAutoCategoryRuleText
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
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

/** Applies the first enabled matching rule while preserving every manual transaction edit. */
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
                transfers = context.candidates.asSequence()
                    .map(TransferClassificationCandidate::transfer)
                    .filter { it.id in targetIds }
                    .toList(),
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
            transfers = context.candidates.asSequence()
                .map(TransferClassificationCandidate::transfer)
                .filter { it.id in targetIds }
                .toList(),
            internalTransferIds = context.internalTransferCounterparts.keys,
            existingAnnotations = context.annotations,
        )
        true
    }

    suspend fun applyToExistingTransactions(): AutoCategoryApplicationResult =
        categorizationMutex.withLock {
            val context = loadClassificationContext()
            categorize(
                transfers = context.candidates.map(TransferClassificationCandidate::transfer),
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
        transfers: List<Transfer>,
        internalTransferIds: Set<String>,
        existingAnnotations: Map<String, TransferAnnotation>,
    ): AutoCategoryApplicationResult {
        if (transfers.isEmpty()) {
            return AutoCategoryApplicationResult(
                processedTransferCount = 0,
                matchedTransferCount = 0,
                preservedManualOverrideCount = 0,
            )
        }
        val rules = ruleDao.getEnabledInPriorityOrder().filter(::isUsableRule)
        var matchedTransferCount = 0
        var preservedManualOverrideCount = 0

        transfers.forEach { transfer ->
            val existing = existingAnnotations[transfer.id]
            if (existing?.manualOverride == true) {
                preservedManualOverrideCount += 1
                return@forEach
            }

            val isInternalTransfer = transfer.id in internalTransferIds
            val match = if (isInternalTransfer) null else rules.firstOrNull { it.matches(transfer) }
            if (!isInternalTransfer && match == null && existing == null) return@forEach
            if (isInternalTransfer || match != null) matchedTransferCount += 1

            annotationDao.upsert(
                TransferAnnotation(
                    transferId = transfer.id,
                    extensionId = transfer.extensionId,
                    categoryId = if (isInternalTransfer) {
                        INTERNAL_TRANSFER_CATEGORY_ID
                    } else {
                        match?.rule?.categoryId
                    },
                    note = existing?.note.orEmpty(),
                    categoryAssignment = AssignmentSource.AUTO,
                    manualOverride = false,
                ),
            )
            annotationDao.replaceAutoTags(
                transferId = transfer.id,
                tagIds = if (isInternalTransfer) {
                    emptySet()
                } else {
                    match?.tags?.mapTo(mutableSetOf()) { it.id }.orEmpty()
                },
            )
        }
        return AutoCategoryApplicationResult(
            processedTransferCount = transfers.size,
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

internal fun AutoCategoryRuleWithTags.matches(transfer: Transfer): Boolean {
    val candidateAmount = abs(transfer.amount)
    val descriptionMatches = rule.descriptionContains
        ?.takeIf(String::isNotBlank)
        ?.let { expected ->
            val normalizedExpected = normalizeAutoCategoryRuleText(expected)
            if (normalizedExpected.isEmpty()) return@let false
            val normalizedFields = listOf(transfer.description, transfer.memo, transfer.type.orEmpty())
                .map(::normalizeAutoCategoryRuleText)
            when (rule.descriptionMatchMode) {
                AutoCategoryRuleDescriptionMatchMode.CONTAINS ->
                    normalizedFields.any { it.contains(normalizedExpected) }
                AutoCategoryRuleDescriptionMatchMode.EXACT ->
                    normalizedFields.any { it == normalizedExpected }
            }
        }
        ?: true
    val directionMatches =
        when (rule.direction) {
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

internal fun isUsableRule(ruleWithTags: AutoCategoryRuleWithTags): Boolean {
    val rule = ruleWithTags.rule
    val hasCondition = !rule.descriptionContains.isNullOrBlank() ||
        rule.direction != AutoCategoryRuleDirection.ANY ||
        rule.minAbsoluteAmount != null ||
        rule.maxAbsoluteAmount != null ||
        rule.accountId != null
    val hasAction = rule.categoryId != null || ruleWithTags.tags.isNotEmpty()
    return rule.enabled && hasCondition && hasAction
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AutoCategorizerModule {
    @Binds
    abstract fun bindTransferAutoCategorizer(impl: AutoCategorizer): TransferAutoCategorizer
}
