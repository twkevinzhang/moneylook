package tw.kevinzhang.moneylook.ui.transactions

import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.core.data.model.normalizeAutoCategoryRuleText
import tw.kevinzhang.core.data.model.normalizeAutoCategoryRuleTextV2

/** UI-owned values keep rule matching independently testable from Room and Compose. */
data class CategoryOption(
    val id: String,
    val name: String,
    val color: Long,
    val emoji: String = "🏷️",
    /** The one and only accounting group that owns this category. */
    val reportingGroup: CategoryReportingGroup = CategoryReportingGroup.EXPENSE,
)

sealed interface CategoryCreationResult {
    data class Created(val category: CategoryOption) : CategoryCreationResult
    data object DuplicateName : CategoryCreationResult
    data object Failed : CategoryCreationResult
}

data class TagOption(
    val id: String,
    val name: String,
    val color: Long,
)

/** The three mutually exclusive accounting meanings a transaction may have. */
data class TransactionRuleCandidate(
    val description: String,
    val amountSign: AutoCategoryRuleAmountSign,
    val absoluteAmount: Double,
    val accountId: String,
    val memo: String = "",
    val type: String? = null,
)

data class AutoRuleDraft(
    val id: String = "",
    val name: String = "",
    val descriptionContains: String = "",
    val descriptionMatchMode: AutoCategoryRuleDescriptionMatchMode =
        AutoCategoryRuleDescriptionMatchMode.CONTAINS,
    val amountSign: AutoCategoryRuleAmountSign = AutoCategoryRuleAmountSign.ANY,
    val minAbsoluteAmount: String = "",
    val maxAbsoluteAmount: String = "",
    val accountId: String? = null,
    val categoryId: String? = null,
    val tagIds: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val priority: Int = 0,
    val isDefault: Boolean = false,
    val applyExisting: Boolean = false,
    /** Marks a single DESCRIPTION clause that the legacy editor can safely update. */
    val createExactDescriptionCondition: Boolean = false,
    /** Marks a migrated single LEGACY_ANY_TEXT clause that the legacy editor can safely update. */
    val updateLegacyAnyTextCondition: Boolean = false,
    val conditions: List<AutoCategoryRuleCondition> = emptyList(),
    val ruleSetId: String? = null,
    val accountKind: AssetKind? = null,
    val extensionId: String? = null,
    val origin: AutoCategoryRuleOrigin = AutoCategoryRuleOrigin.USER_CONFIRMED,
    val action: AutoCategoryRuleAction = AutoCategoryRuleAction.AUTO_APPLY,
)

/**
 * UI-only draft for an open transaction detail.  It deliberately contains no
 * database mutations: pressing back or cancel can therefore discard it safely.
 */
data class TransactionDetailDraft(
    val categoryId: String?,
    val tagIds: Set<String>,
    val note: String,
    val newTagNames: List<String> = emptyList(),
    val resumeAutomatic: Boolean = false,
    val matchingRule: AutoRuleDraft? = null,
) {
    fun isDirtyComparedWith(state: TransactionDetailUiState): Boolean =
        categoryId != state.selectedCategoryId ||
            tagIds != state.selectedTagIds ||
            note != state.userNote ||
            newTagNames.isNotEmpty() ||
            resumeAutomatic ||
            matchingRule != null
}

internal fun AutoRuleDraft.matches(candidate: TransactionRuleCandidate): Boolean {
    val min = minAbsoluteAmount.toDoubleOrNull()
    val max = maxAbsoluteAmount.toDoubleOrNull()
    val textMatches = when {
        createExactDescriptionCondition -> {
            val normalizedQuery = normalizeAutoCategoryRuleTextV2(descriptionContains.trim())
            val normalizedDescription = normalizeAutoCategoryRuleTextV2(candidate.description)
            normalizedQuery.isNotEmpty() && when (descriptionMatchMode) {
                AutoCategoryRuleDescriptionMatchMode.CONTAINS ->
                    normalizedDescription.contains(normalizedQuery)
                AutoCategoryRuleDescriptionMatchMode.EXACT ->
                    normalizedDescription == normalizedQuery
            }
        }
        conditions.isNotEmpty() && !updateLegacyAnyTextCondition -> false
        else -> {
            descriptionContains.trim().let { query ->
                val normalizedQuery = normalizeAutoCategoryRuleText(query)
                query.isEmpty() || normalizedQuery.isNotEmpty() && when (descriptionMatchMode) {
                    AutoCategoryRuleDescriptionMatchMode.CONTAINS ->
                        candidate.transactionTextFields().any { it.contains(normalizedQuery) }
                    AutoCategoryRuleDescriptionMatchMode.EXACT ->
                        candidate.transactionTextFields().any { it == normalizedQuery }
                }
            }
        }
    }
    return textMatches &&
        (amountSign == AutoCategoryRuleAmountSign.ANY || amountSign == candidate.amountSign) &&
        (min == null || candidate.absoluteAmount >= min) &&
        (max == null || candidate.absoluteAmount <= max) &&
        (accountId == null || accountId == candidate.accountId)
}

private fun TransactionRuleCandidate.transactionTextFields(): List<String> =
    listOf(description, memo, type.orEmpty()).map(::normalizeAutoCategoryRuleText)

internal fun orderedMatchingRules(
    rules: List<AutoRuleDraft>,
    candidate: TransactionRuleCandidate,
): List<AutoRuleDraft> = rules.filter { it.enabled && it.matches(candidate) }.sortedWith(autoRuleDraftComparator)

internal val autoRuleDraftComparator = compareBy<AutoRuleDraft>(
    { if (it.isDefault) 1 else 0 },
    { it.priority },
    { it.id },
)

internal fun nextUserRulePriority(rules: List<AutoRuleDraft>): Int = rules.asSequence()
    .filterNot(AutoRuleDraft::isDefault)
    .maxOfOrNull(AutoRuleDraft::priority)
    ?.let { if (it == Int.MAX_VALUE) it else it + 1 }
    ?: 0

internal val defaultCategoryColors = listOf(
    0xFF2E7D32L,
    0xFF1565C0L,
    0xFF6A1B9AL,
    0xFFEF6C00L,
    0xFFC62828L,
    0xFF00695CL,
)
