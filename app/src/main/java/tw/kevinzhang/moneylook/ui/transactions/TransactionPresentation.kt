package tw.kevinzhang.moneylook.ui.transactions

/** UI-owned values keep rule matching independently testable from Room and Compose. */
data class CategoryOption(
    val id: String,
    val name: String,
    val color: Long,
)

data class TagOption(
    val id: String,
    val name: String,
    val color: Long,
)

enum class TransactionDirection { INCOME, EXPENSE }

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

internal fun AutoRuleDraft.matches(candidate: TransactionRuleCandidate): Boolean {
    val min = minAbsoluteAmount.toDoubleOrNull()
    val max = maxAbsoluteAmount.toDoubleOrNull()
    return descriptionContains.trim().let { it.isEmpty() || candidate.description.contains(it, ignoreCase = true) } &&
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
