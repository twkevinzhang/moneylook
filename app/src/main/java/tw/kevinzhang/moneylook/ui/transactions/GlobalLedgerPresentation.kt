package tw.kevinzhang.moneylook.ui.transactions

import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.CategoryReportingGroup
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
data class GlobalLedgerItem(
    val transferId: String,
    val transactionDateTime: String,
    val description: String,
    val memo: String,
    val amount: Double,
    val userNote: String,
    val categoryId: String?,
    val categoryName: String?,
    val categoryReportingGroup: CategoryReportingGroup?,
    val categoryEmoji: String?,
    val categoryColor: String?,
    val tags: List<GlobalTag>,
    val accountId: String,
    val accountName: String,
    val extensionId: String,
    val extensionName: String,
    val currency: String,
    /** Account product semantics are used only for the credit-card settlement label. */
    val accountKind: AssetKind = AssetKind.DEPOSIT,
    /** Bank-provided safe card identifiers only; never a complete PAN. */
    val cardDisplayLabel: String? = null,
    /** Extension-provided card settlement status; only exact known values receive a label. */
    val status: String? = null,
    /** Bank-provided settlement date for a posted credit-card transaction. */
    val postingDateTime: String? = null,
    /** Latest-rate reporting amount in TWD; null means this currency could not be converted. */
    val amountTwd: Double? = null,
)

data class GlobalTag(val id: String, val name: String)

enum class GlobalLedgerDirection { INCOME, EXPENSE, EXCLUDED }
/**
 * Visual treatment for a transaction amount in the detail ledger.
 *
 * Muted rows are intentionally excluded from the income/expense report, either
 * because they represent an internal transfer/zero-value record or because a
 * reporting exchange rate is currently unavailable.
 */
enum class GlobalLedgerAmountTone { POSITIVE, NEGATIVE, MUTED }
enum class GlobalCreditCardTransactionStatus(val label: String) {
    POSTED("已出帳"),
    PENDING("未出帳"),
}
enum class GlobalCategoryAssignment { ALL, CATEGORIZED, UNCATEGORIZED }
/** Tabs live inside the single global-ledger destination, not in bottom navigation. */
enum class GlobalLedgerTab { CATEGORY, DETAILS, ANALYSIS }

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

    /**
     * Returns the page that exactly represents [range], or null when the
     * range cannot belong to this pager. This is intentionally non-throwing:
     * callers can retain a safe fallback while state is being restored.
     */
    fun activePageFor(range: GlobalDateRange): Int? {
        // The outer custom pages may be shortened at the 1970/today bounds,
        // so their starts are not necessarily aligned with the anchor period.
        if (range == rangeAt(0)) return 0
        if (range == rangeAt(pageCount - 1)) return pageCount - 1
        val candidate = if (selectedRange.isCustom) {
            if (!range.isCustom) return null
            val daysFromAnchor = ChronoUnit.DAYS.between(selectedRange.startInclusive, range.startInclusive)
            if (daysFromAnchor % customPeriodDays != 0L) return null
            daysFromAnchor / customPeriodDays - firstCustomOffset
        } else {
            if (range.isCustom || range != GlobalDateRange.month(YearMonth.from(range.startInclusive))) return null
            ChronoUnit.MONTHS.between(YearMonth.from(GLOBAL_DATE_PAGER_START), YearMonth.from(range.startInclusive))
        }
        if (candidate !in 0 until pageCount.toLong()) return null
        return candidate.toInt().takeIf { rangeAt(it) == range }
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

data class GlobalLedgerFilter(
    val currency: String = "TWD",
    val query: String = "",
    val extensionId: String? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val tagId: String? = null,
    val direction: GlobalLedgerDirection? = null,
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

data class GlobalLedgerSummary(
    val income: Double = 0.0,
    val expense: Double = 0.0,
    /** Excluded rows live on their dedicated detail page and do not affect income or expense totals. */
    val excludedCount: Int = 0,
) {
    val net: Double get() = income - expense
}

data class GlobalLedgerUiState(
    val dateRange: GlobalDateRange,
    /** Stable anchor used to derive custom-range pager pages while [dateRange] moves. */
    val datePagerAnchor: GlobalDateRange = dateRange,
    val filter: GlobalLedgerFilter = GlobalLedgerFilter(),
    val activeTab: GlobalLedgerTab = GlobalLedgerTab.CATEGORY,
    val categoryDirection: GlobalLedgerDirection = GlobalLedgerDirection.EXPENSE,
    val allItems: List<GlobalLedgerItem> = emptyList(),
    val items: List<GlobalLedgerItem> = emptyList(),
    /** Six calendar months ending in the current period's end month, for the Analysis tab. */
    val trendItems: List<GlobalLedgerItem> = emptyList(),
    val summary: GlobalLedgerSummary = GlobalLedgerSummary(),
    val categories: List<GlobalCategorySummary> = emptyList(),
    val missingExchangeCurrencies: List<String> = emptyList(),
    val exchangeRatesLoading: Boolean = true,
) {
    val currencies: List<String> get() = (allItems.map(GlobalLedgerItem::currency) + filter.currency)
        .filter(String::isNotBlank).distinct().sorted()
    /** Account choices are intentionally unavailable until their data source is selected. */
    fun accountsForExtension(extensionId: String?): List<GlobalChoice> = extensionId?.let { selectedExtensionId ->
        allItems.asSequence()
            .filter { it.extensionId == selectedExtensionId }
            .map { GlobalChoice(it.accountId, it.accountName) }
            .distinctBy(GlobalChoice::id)
            .sortedBy(GlobalChoice::name)
            .toList()
    }.orEmpty()
    val extensions: List<GlobalChoice> get() = allItems
        .groupBy { it.extensionId to it.extensionName }
        .map { (key, _) -> GlobalChoice(key.first, key.second) }.sortedBy(GlobalChoice::name)
    val categoryOptions: List<GlobalChoice> get() = allItems.mapNotNull { item ->
        item.categoryId?.let { GlobalChoice(it, item.categoryName ?: "未命名分類") }
    }.distinctBy(GlobalChoice::id).sortedBy(GlobalChoice::name)
    val tagOptions: List<GlobalChoice> get() = allItems.flatMap(GlobalLedgerItem::tags)
        .distinctBy(GlobalTag::id).map { GlobalChoice(it.id, it.name) }.sortedBy(GlobalChoice::name)
}

data class GlobalChoice(val id: String, val name: String)

/**
 * Source selection owns the account selection: changing or clearing a source
 * must never retain an account that belongs to the previous source.
 */
fun selectGlobalLedgerExtension(
    filter: GlobalLedgerFilter,
    extensionId: String?,
): GlobalLedgerFilter = filter.copy(extensionId = extensionId, accountId = null)

fun globalLedgerDirection(item: GlobalLedgerItem): GlobalLedgerDirection? = when {
    item.categoryReportingGroup == CategoryReportingGroup.INCOME -> GlobalLedgerDirection.INCOME
    item.categoryReportingGroup == CategoryReportingGroup.EXPENSE -> GlobalLedgerDirection.EXPENSE
    item.categoryReportingGroup == CategoryReportingGroup.EXCLUDED -> GlobalLedgerDirection.EXCLUDED
    else -> when {
        item.amount > 0.0 -> GlobalLedgerDirection.INCOME
        item.amount < 0.0 -> GlobalLedgerDirection.EXPENSE
        else -> null
    }
}

/**
 * Status is deliberately fail-closed: a stale or bank-specific value must not
 * be represented as a settlement fact in the ledger.
 */
fun globalCreditCardTransactionStatus(
    accountKind: AssetKind,
    status: String?,
): GlobalCreditCardTransactionStatus? = when {
    accountKind != AssetKind.CREDIT_CARD -> null
    status == "posted" -> GlobalCreditCardTransactionStatus.POSTED
    status == "pending" -> GlobalCreditCardTransactionStatus.PENDING
    else -> null
}

fun globalCreditCardTransactionStatus(item: GlobalLedgerItem): GlobalCreditCardTransactionStatus? =
    globalCreditCardTransactionStatus(item.accountKind, item.status)

/** Both posted and pending card transactions contribute; only explicitly excluded rows do not. */
fun globalReportableTransactions(items: List<GlobalLedgerItem>): List<GlobalLedgerItem> =
    items.filter { globalLedgerDirection(it) != GlobalLedgerDirection.EXCLUDED }

/**
 * Applies every ledger filter selected on the parent screen, but deliberately
 * replaces its direction with EXCLUDED for the dedicated excluded-details page.
 * Both posted and pending excluded transactions appear on this dedicated page.
 */
fun excludedGlobalLedgerItems(
    items: List<GlobalLedgerItem>,
    filter: GlobalLedgerFilter,
): List<GlobalLedgerItem> =
    filterGlobalLedger(items, filter.copy(direction = GlobalLedgerDirection.EXCLUDED))

/** Report totals exclude categorized "不統計" rows and zero-value rows. */
fun globalLedgerSummary(items: List<GlobalLedgerItem>): GlobalLedgerSummary {
    val reportable = globalReportableTransactions(items).mapNotNull { item ->
        val direction = globalLedgerDirection(item)
        val amountTwd = item.reportingAmountTwd()
        if (direction == null || direction == GlobalLedgerDirection.EXCLUDED || amountTwd == null) null
        else direction to amountTwd
    }
    return GlobalLedgerSummary(
        income = reportable.filter { it.first == GlobalLedgerDirection.INCOME }.sumOf { abs(it.second) },
        expense = reportable.filter { it.first == GlobalLedgerDirection.EXPENSE }.sumOf { abs(it.second) },
        excludedCount = items.count { globalLedgerDirection(it) == GlobalLedgerDirection.EXCLUDED },
    )
}

fun globalCategorySummaries(
    items: List<GlobalLedgerItem>,
    direction: GlobalLedgerDirection,
): List<GlobalCategorySummary> {
    if (direction == GlobalLedgerDirection.EXCLUDED) return emptyList()
    val grouped = globalReportableTransactions(items)
        .filter { globalLedgerDirection(it) == direction && it.reportingAmountTwd() != null }
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

fun GlobalLedgerItem.reportingAmountTwd(): Double? = amountTwd
    ?: amount.takeIf { currency.trim().equals("TWD", ignoreCase = true) && it.isFinite() }

/**
 * Keeps the ledger's amount colour consistent with whether the row contributes
 * to reports. Successfully converted foreign-currency rows retain their
 * signed colour; only unavailable reporting amounts are muted.
 */
fun globalLedgerAmountTone(item: GlobalLedgerItem): GlobalLedgerAmountTone = when {
    globalLedgerDirection(item) == GlobalLedgerDirection.EXCLUDED -> GlobalLedgerAmountTone.MUTED
    item.amount == 0.0 -> GlobalLedgerAmountTone.MUTED
    item.reportingAmountTwd() == null -> GlobalLedgerAmountTone.MUTED
    item.amount > 0.0 -> GlobalLedgerAmountTone.POSITIVE
    else -> GlobalLedgerAmountTone.NEGATIVE
}

fun missingExchangeCurrencies(items: List<GlobalLedgerItem>): List<String> = items.asSequence()
    .filter { globalLedgerDirection(it) !in setOf(null, GlobalLedgerDirection.EXCLUDED) }
    .filter { it.reportingAmountTwd() == null }
    .map { it.currency.trim().uppercase(Locale.ROOT) }
    .filter(String::isNotBlank)
    .distinct()
    .sorted()
    .toList()

fun filterGlobalLedger(
    items: List<GlobalLedgerItem>,
    filter: GlobalLedgerFilter,
): List<GlobalLedgerItem> {
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
        .sortedByDescending(GlobalLedgerItem::transactionDateTime)
        .toList()
}

/**
 * Applies the main screen's explicit conditions, then narrows the result to a
 * category selected from the category report.  A null [categoryId] represents
 * the uncategorized bucket, rather than an omitted category constraint.
 */
fun filterCategoryTransactions(
    items: List<GlobalLedgerItem>,
    filter: GlobalLedgerFilter,
    categoryId: String?,
): List<GlobalLedgerItem> = filterGlobalLedger(items, filter)
    .filter { item -> item.categoryId == categoryId }

/** A category's reporting group is authoritative; uncategorized rows temporarily follow amount sign. */
private fun matchesGlobalDirectionFilter(
    item: GlobalLedgerItem,
    direction: GlobalLedgerDirection?,
): Boolean = when (direction) {
    null -> true
    else -> globalLedgerDirection(item) == direction
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

fun globalLedgerDate(value: String): LocalDate? = try {
    LocalDate.parse(value.take(10))
} catch (_: DateTimeParseException) {
    null
}
