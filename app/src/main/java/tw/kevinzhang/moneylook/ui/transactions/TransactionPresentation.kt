package tw.kevinzhang.moneylook.ui.transactions

import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.CategoryKind

/** UI-owned values keep rule matching independently testable from Room and Compose. */
data class CategoryOption(
    val id: String,
    val name: String,
    val color: Long,
    val emoji: String = "🏷️",
    val kind: CategoryKind = CategoryKind.EXPENSE,
)

data class TagOption(
    val id: String,
    val name: String,
    val color: Long,
)

enum class TransactionDirection { INCOME, EXPENSE }

/** The three mutually exclusive accounting meanings a transaction may have. */
data class TransactionRuleCandidate(
    val description: String,
    val direction: TransactionDirection,
    val absoluteAmount: Double,
    val accountId: String,
)

data class AutoRuleDraft(
    val id: String = "",
    val name: String = "",
    val descriptionContains: String = "",
    val descriptionMatchMode: AutoCategoryRuleDescriptionMatchMode =
        AutoCategoryRuleDescriptionMatchMode.CONTAINS,
    val direction: TransactionDirection? = null,
    val minAbsoluteAmount: String = "",
    val maxAbsoluteAmount: String = "",
    val accountId: String? = null,
    val categoryId: String? = null,
    val tagIds: Set<String> = emptySet(),
    val enabled: Boolean = true,
    val priority: Int = 0,
    val applyExisting: Boolean = false,
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
    return descriptionContains.trim().let { query ->
        query.isEmpty() || when (descriptionMatchMode) {
            AutoCategoryRuleDescriptionMatchMode.CONTAINS -> candidate.description.contains(query, ignoreCase = true)
            AutoCategoryRuleDescriptionMatchMode.EXACT -> candidate.description.trim().equals(query, ignoreCase = true)
        }
    } &&
        (direction == null || direction == candidate.direction) &&
        (min == null || candidate.absoluteAmount >= min) &&
        (max == null || candidate.absoluteAmount <= max) &&
        (accountId == null || accountId == candidate.accountId)
}

internal fun orderedMatchingRules(
    rules: List<AutoRuleDraft>,
    candidate: TransactionRuleCandidate,
): List<AutoRuleDraft> = rules.filter { it.enabled && it.matches(candidate) }.sortedBy { it.priority }

internal val defaultCategoryColors = listOf(
    0xFF2E7D32L,
    0xFF1565C0L,
    0xFF6A1B9AL,
    0xFFEF6C00L,
    0xFFC62828L,
    0xFF00695CL,
)
