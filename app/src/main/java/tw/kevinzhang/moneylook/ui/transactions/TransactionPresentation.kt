package tw.kevinzhang.moneylook.ui.transactions

import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.CategoryKind
import tw.kevinzhang.core.data.model.normalizeAutoCategoryRuleText

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
    val memo: String = "",
    val type: String? = null,
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
    val isDefault: Boolean = false,
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
        val normalizedQuery = normalizeAutoCategoryRuleText(query)
        query.isEmpty() || normalizedQuery.isNotEmpty() && when (descriptionMatchMode) {
            AutoCategoryRuleDescriptionMatchMode.CONTAINS -> candidate.transactionTextFields()
                .any { it.contains(normalizedQuery) }
            AutoCategoryRuleDescriptionMatchMode.EXACT -> candidate.transactionTextFields()
                .any { it == normalizedQuery }
        }
    } &&
        (direction == null || direction == candidate.direction) &&
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
