package tw.kevinzhang.moneylook.ui.transactions

import tw.kevinzhang.core.data.model.CategoryKind
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.abs

private val GLOBAL_DATE_PAGER_START = LocalDate.of(1970, 1, 1)

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
    /** Latest-rate reporting amount in TWD; null means this currency could not be converted. */
    val amountTwd: Double? = null,
)

data class GlobalTag(val id: String, val name: String)

enum class GlobalTransactionDirection { INCOME, EXPENSE, TRANSFER }
/**
 * Visual treatment for a transaction amount in the detail ledger.
 *
 * Muted rows are intentionally excluded from the income/expense report, either
 * because they represent an internal transfer/zero-value record or because a
 * reporting exchange rate is currently unavailable.
 */
enum class GlobalTransactionAmountTone { POSITIVE, NEGATIVE, MUTED }
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

    /** First line of a date pager tab. Cross-year custom ranges retain both years. */
    fun tabYearLabel(): String = if (startInclusive.year == endInclusive.year) {
        startInclusive.year.toString()
    } else {
        "${startInclusive.year}–${endInclusive.year}"
    }

    /** Second line of a date pager tab; calendar months deliberately show their full range. */
    fun tabDateRangeLabel(): String =
        "${startInclusive.monthValue}/${startInclusive.dayOfMonth}–${endInclusive.monthValue}/${endInclusive.dayOfMonth}"

    companion object {
        fun thisMonth(today: LocalDate): GlobalDateRange = month(YearMonth.from(today))
        fun month(month: YearMonth): GlobalDateRange = GlobalDateRange(month.atDay(1), month.atEndOfMonth())
        private val DATE_LABEL = DateTimeFormatter.ofPattern("yyyy/M/d", Locale.TAIWAN)
    }
}

/**
 * Pure pager model used by the ledger's date tabs.
 *
 * Calendar ranges always have one page per calendar month. A custom range uses
 * its inclusive day count as the size of every neighbouring page, with the
 * selected range as its anchor. The two outer pages may be shortened only at
 * the 1970-01-01 and today boundaries.
 */
data class GlobalDateRangePager(
    val selectedRange: GlobalDateRange,
    val today: LocalDate,
) {
    init {
        require(today >= GLOBAL_DATE_PAGER_START) { "today must not be before 1970-01-01" }
        require(selectedRange.startInclusive >= GLOBAL_DATE_PAGER_START) { "range must not start before 1970-01-01" }
        require(!selectedRange.isCustom || selectedRange.endInclusive <= today) {
            "custom range must not end after today"
        }
        require(YearMonth.from(selectedRange.startInclusive) <= YearMonth.from(today)) {
            "range month must not be after the current month"
        }
    }

    private val customPeriodDays: Long
        get() = ChronoUnit.DAYS.between(selectedRange.startInclusive, selectedRange.endInclusive) + 1

    private val firstCustomOffset: Long
        get() = ceilDiv(
            ChronoUnit.DAYS.between(selectedRange.startInclusive, GLOBAL_DATE_PAGER_START) - customPeriodDays + 1,
            customPeriodDays,
        )

    private val lastCustomOffset: Long
        get() = Math.floorDiv(ChronoUnit.DAYS.between(selectedRange.startInclusive, today), customPeriodDays)

    val pageCount: Int = if (selectedRange.isCustom) {
        (lastCustomOffset - firstCustomOffset + 1).toPagerInt()
    } else {
        (ChronoUnit.MONTHS.between(YearMonth.from(GLOBAL_DATE_PAGER_START), YearMonth.from(today)) + 1).toPagerInt()
    }

    /** The tab index that represents [selectedRange]. */
    val selectedPage: Int = if (selectedRange.isCustom) {
        (-firstCustomOffset).toPagerInt()
    } else {
        ChronoUnit.MONTHS.between(YearMonth.from(GLOBAL_DATE_PAGER_START), YearMonth.from(selectedRange.startInclusive)).toPagerInt()
    }

    fun rangeAt(page: Int): GlobalDateRange {
        require(page in 0 until pageCount) { "page $page is outside 0 until $pageCount" }
        return if (selectedRange.isCustom) {
            customRangeAt(firstCustomOffset + page)
        } else {
            GlobalDateRange.month(YearMonth.from(GLOBAL_DATE_PAGER_START).plusMonths(page.toLong()))
        }
    }

    private fun customRangeAt(offset: Long): GlobalDateRange {
        val start = selectedRange.startInclusive.plusDays(Math.multiplyExact(offset, customPeriodDays))
        val end = start.plusDays(customPeriodDays - 1)
        return GlobalDateRange(
            startInclusive = maxOf(start, GLOBAL_DATE_PAGER_START),
            endInclusive = minOf(end, today),
            isCustom = true,
        )
    }
}

fun globalDateRangePager(selectedRange: GlobalDateRange, today: LocalDate): GlobalDateRangePager =
    GlobalDateRangePager(selectedRange, today)

private fun ceilDiv(dividend: Long, divisor: Long): Long = -Math.floorDiv(-dividend, divisor)

private fun Long.toPagerInt(): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "pager page count/index is outside Int range" }
    return toInt()
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
    /** Stable anchor used to derive custom-range pager pages while [dateRange] moves. */
    val datePagerAnchor: GlobalDateRange = dateRange,
    val filter: GlobalTransactionsFilter = GlobalTransactionsFilter(),
    val activeTab: GlobalTransactionsTab = GlobalTransactionsTab.CATEGORY,
    val categoryDirection: GlobalTransactionDirection = GlobalTransactionDirection.EXPENSE,
    val allItems: List<GlobalTransactionItem> = emptyList(),
    val items: List<GlobalTransactionItem> = emptyList(),
    /** Six calendar months ending in the current period's end month, for the Analysis tab. */
    val trendItems: List<GlobalTransactionItem> = emptyList(),
    val summary: GlobalTransactionsSummary = GlobalTransactionsSummary(),
    val categories: List<GlobalCategorySummary> = emptyList(),
    val missingExchangeCurrencies: List<String> = emptyList(),
    val exchangeRatesLoading: Boolean = true,
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

fun globalTransactionDirection(item: GlobalTransactionItem): GlobalTransactionDirection? = when {
    item.categoryKind == CategoryKind.TRANSFER -> GlobalTransactionDirection.TRANSFER
    else -> when {
        item.amount > 0.0 -> GlobalTransactionDirection.INCOME
        item.amount < 0.0 -> GlobalTransactionDirection.EXPENSE
        else -> null
    }
}

/** Report totals exclude intentional transfers and zero-value rows. */
fun globalTransactionsSummary(items: List<GlobalTransactionItem>): GlobalTransactionsSummary {
    val reportable = items.mapNotNull { item ->
        val direction = globalTransactionDirection(item)
        val amountTwd = item.reportingAmountTwd()
        if (direction == null || direction == GlobalTransactionDirection.TRANSFER || amountTwd == null) null
        else direction to amountTwd
    }
    return GlobalTransactionsSummary(
        income = reportable.filter { it.first == GlobalTransactionDirection.INCOME }.sumOf { abs(it.second) },
        expense = reportable.filter { it.first == GlobalTransactionDirection.EXPENSE }.sumOf { abs(it.second) },
    )
}

fun globalCategorySummaries(
    items: List<GlobalTransactionItem>,
    direction: GlobalTransactionDirection,
): List<GlobalCategorySummary> {
    if (direction == GlobalTransactionDirection.TRANSFER) return emptyList()
    val grouped = items
        .filter { globalTransactionDirection(it) == direction && it.reportingAmountTwd() != null }
        .groupBy { it.categoryId }
    val total = grouped.values.flatten().sumOf { abs(requireNotNull(it.reportingAmountTwd())) }
    if (total <= 0.0) return emptyList()
    return grouped.map { (categoryId, rows) ->
        val amount = rows.sumOf { abs(requireNotNull(it.reportingAmountTwd())) }
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

fun GlobalTransactionItem.reportingAmountTwd(): Double? = amountTwd
    ?: amount.takeIf { currency.trim().equals("TWD", ignoreCase = true) && it.isFinite() }

/**
 * Keeps the ledger's amount colour consistent with whether the row contributes
 * to reports. Successfully converted foreign-currency rows retain their
 * signed colour; only unavailable reporting amounts are muted.
 */
fun globalTransactionAmountTone(item: GlobalTransactionItem): GlobalTransactionAmountTone = when {
    item.categoryKind == CategoryKind.TRANSFER -> GlobalTransactionAmountTone.MUTED
    item.amount == 0.0 -> GlobalTransactionAmountTone.MUTED
    item.reportingAmountTwd() == null -> GlobalTransactionAmountTone.MUTED
    item.amount > 0.0 -> GlobalTransactionAmountTone.POSITIVE
    else -> GlobalTransactionAmountTone.NEGATIVE
}

fun missingExchangeCurrencies(items: List<GlobalTransactionItem>): List<String> = items.asSequence()
    .filter { globalTransactionDirection(it) !in setOf(null, GlobalTransactionDirection.TRANSFER) }
    .filter { it.reportingAmountTwd() == null }
    .map { it.currency.trim().uppercase(Locale.ROOT) }
    .filter(String::isNotBlank)
    .distinct()
    .sorted()
    .toList()

fun filterGlobalTransactions(
    items: List<GlobalTransactionItem>,
    filter: GlobalTransactionsFilter,
): List<GlobalTransactionItem> {
    val query = filter.query.trim()
    val min = filter.minimumAmount.toDoubleOrNull()?.takeIf { it >= 0.0 }
    val max = filter.maximumAmount.toDoubleOrNull()?.takeIf { it >= 0.0 }
    return items.asSequence()
        .filter { item ->
            query.isEmpty() || listOf(
                item.description, item.memo, item.userNote, item.categoryName.orEmpty(), item.accountName, item.extensionName,
            ).plus(item.tags.map(GlobalTag::name)).any { it.contains(query, ignoreCase = true) }
        }
        .filter { filter.accountId == null || it.accountId == filter.accountId }
        .filter { filter.extensionId == null || it.extensionId == filter.extensionId }
        .filter { filter.categoryId == null || it.categoryId == filter.categoryId }
        .filter { filter.tagId == null || it.tags.any { tag -> tag.id == filter.tagId } }
        .filter { item -> matchesGlobalDirectionFilter(item, filter.direction) }
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

/** Income and expense selections intentionally follow the transaction sign, not category metadata. */
private fun matchesGlobalDirectionFilter(
    item: GlobalTransactionItem,
    direction: GlobalTransactionDirection?,
): Boolean = when (direction) {
    null -> true
    GlobalTransactionDirection.INCOME -> item.amount > 0.0
    GlobalTransactionDirection.EXPENSE -> item.amount < 0.0
    GlobalTransactionDirection.TRANSFER -> globalTransactionDirection(item) == GlobalTransactionDirection.TRANSFER
}

fun moveGlobalMonth(range: GlobalDateRange, delta: Long, today: LocalDate): GlobalDateRange {
    val target = YearMonth.from(range.startInclusive).plusMonths(delta)
    val current = YearMonth.from(today)
    val first = YearMonth.from(GLOBAL_DATE_PAGER_START)
    return if (target !in first..current) range else GlobalDateRange.month(target)
}

fun customGlobalDateRange(startInclusive: LocalDate?, endInclusive: LocalDate?, today: LocalDate): GlobalDateRange? {
    if (startInclusive == null || endInclusive == null || startInclusive < GLOBAL_DATE_PAGER_START || startInclusive > endInclusive || endInclusive > today) return null
    return GlobalDateRange(startInclusive, endInclusive, isCustom = true)
}

fun globalTransactionDate(value: String): LocalDate? = try {
    LocalDate.parse(value.take(10))
} catch (_: DateTimeParseException) {
    null
}
