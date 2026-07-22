package tw.kevinzhang.moneylook.ui.transactions

import tw.kevinzhang.core.data.model.CategoryKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

/**
 * UI-owned, redaction-safe row for the person-wide ledger.  It deliberately
 * contains display names only; account numbers never cross this boundary.
 */
data class GlobalTransactionItem(
    val transferId: String,
    val transactionDateTime: String,
    val description: String,
    val memo: String,
    val amount: Double,
    val userNote: String,
    val categoryId: String?,
    val categoryName: String?,
    val categoryKind: CategoryKind?,
    val categoryEmoji: String?,
    val categoryColor: String?,
    val tags: List<GlobalTag>,
    val accountId: String,
    val accountName: String,
    val extensionId: String,
    val extensionName: String,
    val currency: String,
)

data class GlobalTag(val id: String, val name: String)

enum class GlobalTransactionDirection { INCOME, EXPENSE, TRANSFER }
enum class GlobalCategoryAssignment { ALL, CATEGORIZED, UNCATEGORIZED }
/** Tabs live inside the single global-ledger destination, not in bottom navigation. */
enum class GlobalTransactionsTab { CATEGORY, DETAILS, ANALYSIS }

data class GlobalDateRange(
    val startInclusive: LocalDate,
    val endInclusive: LocalDate,
    val isCustom: Boolean = false,
) {
    val endExclusive: LocalDate get() = endInclusive.plusDays(1)
    val startKey: String get() = startInclusive.toString()
    val endExclusiveKey: String get() = endExclusive.toString()

    fun label(): String = if (isCustom) {
        "${startInclusive.format(DATE_LABEL)} ～ ${endInclusive.format(DATE_LABEL)}"
    } else {
        "${startInclusive.year} 年 ${startInclusive.monthValue} 月"
    }

    companion object {
        fun thisMonth(today: LocalDate): GlobalDateRange = month(YearMonth.from(today))
        fun month(month: YearMonth): GlobalDateRange = GlobalDateRange(month.atDay(1), month.atEndOfMonth())
        private val DATE_LABEL = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.TAIWAN)
    }
}

data class GlobalTransactionsFilter(
    val currency: String = "TWD",
    val query: String = "",
    val extensionId: String? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val tagId: String? = null,
    val direction: GlobalTransactionDirection? = null,
    val assignment: GlobalCategoryAssignment = GlobalCategoryAssignment.ALL,
    val minimumAmount: String = "",
    val maximumAmount: String = "",
)

data class GlobalCategorySummary(
    val id: String?,
    val name: String,
    val emoji: String,
    val amount: Double,
    val percentage: Float,
    val transactionCount: Int,
)

data class GlobalTransactionsSummary(val income: Double = 0.0, val expense: Double = 0.0) {
    val net: Double get() = income - expense
}

data class GlobalTransactionsUiState(
    val dateRange: GlobalDateRange,
    val filter: GlobalTransactionsFilter = GlobalTransactionsFilter(),
    val activeTab: GlobalTransactionsTab = GlobalTransactionsTab.CATEGORY,
    val categoryDirection: GlobalTransactionDirection = GlobalTransactionDirection.EXPENSE,
    val allItems: List<GlobalTransactionItem> = emptyList(),
    val items: List<GlobalTransactionItem> = emptyList(),
    /** Six calendar months ending in the current period's end month, for the Analysis tab. */
    val trendItems: List<GlobalTransactionItem> = emptyList(),
    val summary: GlobalTransactionsSummary = GlobalTransactionsSummary(),
    val categories: List<GlobalCategorySummary> = emptyList(),
) {
    val currencies: List<String> get() = (allItems.map(GlobalTransactionItem::currency) + filter.currency)
        .filter(String::isNotBlank).distinct().sorted()
    val accounts: List<GlobalChoice> get() = allItems
        .groupBy { it.accountId to it.accountName }
        .map { (key, _) -> GlobalChoice(key.first, key.second) }.sortedBy(GlobalChoice::name)
    val extensions: List<GlobalChoice> get() = allItems
        .groupBy { it.extensionId to it.extensionName }
        .map { (key, _) -> GlobalChoice(key.first, key.second) }.sortedBy(GlobalChoice::name)
    val categoryOptions: List<GlobalChoice> get() = allItems.mapNotNull { item ->
        item.categoryId?.let { GlobalChoice(it, item.categoryName ?: "未命名分類") }
    }.distinctBy(GlobalChoice::id).sortedBy(GlobalChoice::name)
    val tagOptions: List<GlobalChoice> get() = allItems.flatMap(GlobalTransactionItem::tags)
        .distinctBy(GlobalTag::id).map { GlobalChoice(it.id, it.name) }.sortedBy(GlobalChoice::name)
}

data class GlobalChoice(val id: String, val name: String)

fun globalTransactionDirection(item: GlobalTransactionItem): GlobalTransactionDirection? = when (item.categoryKind) {
    CategoryKind.TRANSFER -> GlobalTransactionDirection.TRANSFER
    CategoryKind.INCOME -> GlobalTransactionDirection.INCOME
    CategoryKind.EXPENSE -> GlobalTransactionDirection.EXPENSE
    null -> when {
        item.amount > 0.0 -> GlobalTransactionDirection.INCOME
        item.amount < 0.0 -> GlobalTransactionDirection.EXPENSE
        else -> null
    }
}

/** Report totals exclude intentional transfers and zero-value rows. */
fun globalTransactionsSummary(items: List<GlobalTransactionItem>): GlobalTransactionsSummary {
    val reportable = items.mapNotNull { item -> globalTransactionDirection(item)?.let { it to item } }
    return GlobalTransactionsSummary(
        income = reportable.filter { it.first == GlobalTransactionDirection.INCOME }.sumOf { abs(it.second.amount) },
        expense = reportable.filter { it.first == GlobalTransactionDirection.EXPENSE }.sumOf { abs(it.second.amount) },
    )
}

fun globalCategorySummaries(
    items: List<GlobalTransactionItem>,
    direction: GlobalTransactionDirection,
): List<GlobalCategorySummary> {
    if (direction == GlobalTransactionDirection.TRANSFER) return emptyList()
    val grouped = items.filter { globalTransactionDirection(it) == direction }.groupBy { it.categoryId }
    val total = grouped.values.flatten().sumOf { abs(it.amount) }
    if (total <= 0.0) return emptyList()
    return grouped.map { (categoryId, rows) ->
        val amount = rows.sumOf { abs(it.amount) }
        val first = rows.first()
        GlobalCategorySummary(
            id = categoryId,
            name = first.categoryName ?: "尚未分類",
            emoji = first.categoryEmoji ?: "•",
            amount = amount,
            percentage = (amount / total).toFloat(),
            transactionCount = rows.size,
        )
    }.sortedByDescending(GlobalCategorySummary::amount)
}

fun filterGlobalTransactions(
    items: List<GlobalTransactionItem>,
    filter: GlobalTransactionsFilter,
): List<GlobalTransactionItem> {
    val query = filter.query.trim()
    val min = filter.minimumAmount.toDoubleOrNull()?.takeIf { it >= 0.0 }
    val max = filter.maximumAmount.toDoubleOrNull()?.takeIf { it >= 0.0 }
    return items.asSequence()
        .filter { it.currency.equals(filter.currency, ignoreCase = true) }
        .filter { item ->
            query.isEmpty() || listOf(
                item.description, item.memo, item.userNote, item.categoryName.orEmpty(), item.accountName, item.extensionName,
            ).plus(item.tags.map(GlobalTag::name)).any { it.contains(query, ignoreCase = true) }
        }
        .filter { filter.accountId == null || it.accountId == filter.accountId }
        .filter { filter.extensionId == null || it.extensionId == filter.extensionId }
        .filter { filter.categoryId == null || it.categoryId == filter.categoryId }
        .filter { filter.tagId == null || it.tags.any { tag -> tag.id == filter.tagId } }
        .filter { filter.direction == null || globalTransactionDirection(it) == filter.direction }
        .filter { item -> when (filter.assignment) {
            GlobalCategoryAssignment.ALL -> true
            GlobalCategoryAssignment.CATEGORIZED -> item.categoryId != null
            GlobalCategoryAssignment.UNCATEGORIZED -> item.categoryId == null
        } }
        .filter { min == null || abs(it.amount) >= min }
        .filter { max == null || abs(it.amount) <= max }
        .sortedByDescending(GlobalTransactionItem::transactionDateTime)
        .toList()
}

fun moveGlobalMonth(range: GlobalDateRange, delta: Long, today: LocalDate): GlobalDateRange {
    val target = YearMonth.from(range.startInclusive).plusMonths(delta)
    val current = YearMonth.from(today)
    return if (target > current) range else GlobalDateRange.month(target)
}

fun customGlobalDateRange(startInclusive: LocalDate?, endInclusive: LocalDate?, today: LocalDate): GlobalDateRange? {
    if (startInclusive == null || endInclusive == null || startInclusive > endInclusive || endInclusive > today) return null
    return GlobalDateRange(startInclusive, endInclusive, isCustom = true)
}

fun globalTransactionDate(value: String): LocalDate? = try {
    LocalDate.parse(value.take(10))
} catch (_: DateTimeParseException) {
    null
}
