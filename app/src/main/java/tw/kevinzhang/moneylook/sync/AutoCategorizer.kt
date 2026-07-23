package tw.kevinzhang.moneylook.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tw.kevinzhang.core.data.db.AutoCategoryRuleDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleWithTags
import tw.kevinzhang.core.data.db.TransferAnnotationDao
import tw.kevinzhang.core.data.db.TransferDao
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import javax.inject.Inject
import javax.inject.Singleton
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
) : TransferAutoCategorizer {

    override suspend fun categorizeTransferIds(transferIds: List<String>) {
        if (transferIds.isEmpty()) return
        categorize(transferDao.getByIds(transferIds.distinct()))
    }

    suspend fun applyToExistingTransactions(): AutoCategoryApplicationResult = categorize(transferDao.getAll())

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

    private suspend fun categorize(transfers: List<Transfer>): AutoCategoryApplicationResult {
        if (transfers.isEmpty()) {
            return AutoCategoryApplicationResult(
                processedTransferCount = 0,
                matchedTransferCount = 0,
                preservedManualOverrideCount = 0,
            )
        }
        val rules = ruleDao.getEnabledInPriorityOrder().filter(::isUsableRule)
        val annotations = annotationDao.getByTransferIds(transfers.map(Transfer::id))
            .associateBy(TransferAnnotation::transferId)
        var matchedTransferCount = 0
        var preservedManualOverrideCount = 0

        transfers.forEach { transfer ->
            val existing = annotations[transfer.id]
            if (existing?.manualOverride == true) {
                preservedManualOverrideCount += 1
                return@forEach
            }

            val match = rules.firstOrNull { it.matches(transfer) }
            if (match == null && existing == null) return@forEach
            if (match != null) matchedTransferCount += 1

            annotationDao.upsert(
                TransferAnnotation(
                    transferId = transfer.id,
                    extensionId = transfer.extensionId,
                    categoryId = match?.rule?.categoryId,
                    note = existing?.note.orEmpty(),
                    categoryAssignment = AssignmentSource.AUTO,
                    manualOverride = false,
                ),
            )
            annotationDao.replaceAutoTags(
                transferId = transfer.id,
                tagIds = match?.tags?.mapTo(mutableSetOf()) { it.id }.orEmpty(),
            )
        }
        return AutoCategoryApplicationResult(
            processedTransferCount = transfers.size,
            matchedTransferCount = matchedTransferCount,
            preservedManualOverrideCount = preservedManualOverrideCount,
        )
    }
}

internal fun AutoCategoryRuleWithTags.matches(transfer: Transfer): Boolean {
    val candidateAmount = abs(transfer.amount)
    val descriptionMatches = rule.descriptionContains
        ?.takeIf(String::isNotBlank)
        ?.let { expected ->
            when (rule.descriptionMatchMode) {
                AutoCategoryRuleDescriptionMatchMode.CONTAINS ->
                    transfer.description.contains(expected, ignoreCase = true)
                AutoCategoryRuleDescriptionMatchMode.EXACT ->
                    transfer.description.trim().equals(expected.trim(), ignoreCase = true)
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
