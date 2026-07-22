package tw.kevinzhang.moneylook.ui.analysis

import java.util.Locale
import kotlin.math.abs

/** A safe, UI-owned reporting row. It intentionally excludes account numbers. */
data class AnalysisTransaction(
    val txnDateTime: String,
    val amount: Double,
    val currency: String,
    val categoryName: String?,
    val categoryColor: String?,
    val categoryKind: AnalysisCategoryKind?,
)

enum class AnalysisCategoryKind { INCOME, EXPENSE, TRANSFER }

enum class AnalysisDirection { INCOME, EXPENSE }

data class AnalysisMonth(val year: Int, val month: Int) {
    init {
        require(month in 1..12) { "month must be between 1 and 12" }
    }

    val key: String get() = "%04d-%02d".format(Locale.ROOT, year, month)
    val title: String get() = "${year}年${month}月"
    val shortLabel: String get() = "${month}月"
    val startInclusive: String get() = "$key-01"

    fun plus(months: Int): AnalysisMonth {
        val zeroBased = year * 12 + (month - 1) + months
        return AnalysisMonth(Math.floorDiv(zeroBased, 12), Math.floorMod(zeroBased, 12) + 1)
    }
}

data class AnalysisDateRange(
    val startInclusive: String,
    val endExclusive: String,
)

fun analysisSixMonthRange(referenceMonth: AnalysisMonth): AnalysisDateRange = AnalysisDateRange(
    startInclusive = referenceMonth.plus(-5).startInclusive,
    endExclusive = referenceMonth.plus(1).startInclusive,
)

data class AnalysisSummary(
    val income: Double,
    val expense: Double,
) {
    val balance: Double get() = income - expense
}

data class AnalysisCategorySlice(
    val name: String,
    val amount: Double,
    val color: String?,
) {
    val isUncategorized: Boolean get() = name == UNCATEGORIZED_NAME

    companion object {
        const val UNCATEGORIZED_NAME = "未分類"
    }
}

data class AnalysisTrendPoint(
    val month: AnalysisMonth,
    val income: Double,
    val expense: Double,
)

data class AnalysisPresentation(
    val currency: String,
    val month: AnalysisMonth,
    val periodLabel: String,
    val summary: AnalysisSummary,
    val incomeCategorySlices: List<AnalysisCategorySlice>,
    val expenseCategorySlices: List<AnalysisCategorySlice>,
    val trend: List<AnalysisTrendPoint>,
) {
    fun categorySlices(direction: AnalysisDirection): List<AnalysisCategorySlice> = when (direction) {
        AnalysisDirection.INCOME -> incomeCategorySlices
        AnalysisDirection.EXPENSE -> expenseCategorySlices
    }
}

/**
 * Produces a per-currency analysis without foreign-exchange conversion. Transfer-category rows
 * are excluded from all income/expense aggregates to avoid double-counting account moves.
 */
fun analysisPresentation(
    transactions: List<AnalysisTransaction>,
    selectedCurrency: String,
    referenceMonth: AnalysisMonth,
): AnalysisPresentation = analysisPresentation(
    summaryTransactions = transactions,
    trendTransactions = transactions,
    selectedCurrency = selectedCurrency,
    referenceMonth = referenceMonth,
)

/** Summary and category data may follow a custom ledger range, while trend data is always six months. */
fun analysisPresentation(
    summaryTransactions: List<AnalysisTransaction>,
    trendTransactions: List<AnalysisTransaction>,
    selectedCurrency: String,
    referenceMonth: AnalysisMonth,
    periodLabel: String = referenceMonth.title,
): AnalysisPresentation {
    val currency = selectedCurrency.trim().uppercase(Locale.ROOT)
    fun reportable(items: List<AnalysisTransaction>) = items.asSequence()
        .filter { it.amount.isFinite() && it.currency.trim().uppercase(Locale.ROOT) == currency }
        .filter { it.reportingDirection() != null }
        .toList()
    val currentMonthRows = reportable(summaryTransactions)
    val summary = AnalysisSummary(
        income = currentMonthRows.filter { it.reportingDirection() == AnalysisDirection.INCOME }.sumOf { abs(it.amount) },
        expense = currentMonthRows.filter { it.reportingDirection() == AnalysisDirection.EXPENSE }.sumOf { abs(it.amount) },
    )
    fun categorySlices(direction: AnalysisDirection) = currentMonthRows
        .filter { it.reportingDirection() == direction }
        .groupBy { it.categoryName?.takeIf(String::isNotBlank) ?: AnalysisCategorySlice.UNCATEGORIZED_NAME }
        .map { (name, rows) ->
            AnalysisCategorySlice(
                name = name,
                amount = rows.sumOf { abs(it.amount) },
                color = rows.firstNotNullOfOrNull(AnalysisTransaction::categoryColor),
            )
        }
        .sortedByDescending(AnalysisCategorySlice::amount)
    val trend = (-5..0).map { offset ->
        val month = referenceMonth.plus(offset)
        val rows = reportable(trendTransactions).filter { it.txnDateTime.take(7) == month.key }
        AnalysisTrendPoint(
            month = month,
            income = rows.filter { it.reportingDirection() == AnalysisDirection.INCOME }.sumOf { abs(it.amount) },
            expense = rows.filter { it.reportingDirection() == AnalysisDirection.EXPENSE }.sumOf { abs(it.amount) },
        )
    }
    return AnalysisPresentation(
        currency = currency,
        month = referenceMonth,
        periodLabel = periodLabel,
        summary = summary,
        incomeCategorySlices = categorySlices(AnalysisDirection.INCOME),
        expenseCategorySlices = categorySlices(AnalysisDirection.EXPENSE),
        trend = trend,
    )
}

/** Transfers are excluded; every other row follows its original amount sign. */
fun AnalysisTransaction.reportingDirection(): AnalysisDirection? = when (categoryKind) {
    AnalysisCategoryKind.TRANSFER -> null
    else -> when {
        amount > 0.0 -> AnalysisDirection.INCOME
        amount < 0.0 -> AnalysisDirection.EXPENSE
        else -> null
    }
}
