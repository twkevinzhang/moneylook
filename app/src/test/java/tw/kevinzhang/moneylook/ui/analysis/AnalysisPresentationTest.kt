package tw.kevinzhang.moneylook.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisPresentationTest {
    @Test
    fun amountSignControlsDirectionAndTransfersNeverEnterReports() {
        val presentation = analysisPresentation(
            transactions = listOf(
                transaction("2026-07-01", 100.0),
                transaction("2026-07-02", 40.0, "餐飲", AnalysisCategoryKind.EXPENSE, "#FB8C00"),
                transaction("2026-07-03", -20.0, "薪資", AnalysisCategoryKind.INCOME, "#43B96D"),
                transaction("2026-07-04", -999.0, "帳戶移轉", AnalysisCategoryKind.TRANSFER),
                transaction("2026-07-05", 0.0),
                transaction("2026-07-06", 88.0, currency = "USD"),
            ),
            selectedCurrency = "twd",
            referenceMonth = AnalysisMonth(2026, 7),
        )

        assertEquals(140.0, presentation.summary.income, 0.0)
        assertEquals(20.0, presentation.summary.expense, 0.0)
        assertEquals(120.0, presentation.summary.balance, 0.0)
        assertEquals(listOf("未分類", "餐飲"), presentation.categorySlices(AnalysisDirection.INCOME).map { it.name })
        assertEquals(listOf("薪資"), presentation.categorySlices(AnalysisDirection.EXPENSE).map { it.name })
        assertEquals(20.0, presentation.categorySlices(AnalysisDirection.EXPENSE).single().amount, 0.0)
    }

    @Test
    fun customPeriodUsesProvidedSummaryRowsButKeepsSixCalendarMonthTrend() {
        val presentation = analysisPresentation(
            summaryTransactions = listOf(transaction("2026-06-30", -50.0, "餐飲", AnalysisCategoryKind.EXPENSE)),
            trendTransactions = listOf(
                transaction("2026-02-02", 10.0),
                transaction("2026-06-30", -50.0, "餐飲", AnalysisCategoryKind.EXPENSE),
                transaction("2026-07-02", 80.0),
            ),
            selectedCurrency = "TWD",
            referenceMonth = AnalysisMonth(2026, 7),
            periodLabel = "2026/6/30 ～ 2026/6/30",
        )

        assertEquals("2026/6/30 ～ 2026/6/30", presentation.periodLabel)
        assertEquals(50.0, presentation.summary.expense, 0.0)
        assertEquals(listOf("2026-02", "2026-03", "2026-04", "2026-05", "2026-06", "2026-07"), presentation.trend.map { it.month.key })
        assertEquals(10.0, presentation.trend.first().income, 0.0)
        assertEquals(80.0, presentation.trend.last().income, 0.0)
    }

    @Test
    fun reportDirectionUsesSignExceptForTransfers() {
        assertEquals(AnalysisDirection.INCOME, transaction("2026-07-01", 1.0, categoryKind = AnalysisCategoryKind.EXPENSE).reportingDirection())
        assertEquals(AnalysisDirection.EXPENSE, transaction("2026-07-01", -1.0, categoryKind = AnalysisCategoryKind.INCOME).reportingDirection())
        assertEquals(AnalysisDirection.EXPENSE, transaction("2026-07-01", -1.0).reportingDirection())
        assertNull(transaction("2026-07-01", 1.0, categoryKind = AnalysisCategoryKind.TRANSFER).reportingDirection())
    }

    private fun transaction(
        date: String,
        amount: Double,
        categoryName: String? = null,
        categoryKind: AnalysisCategoryKind? = null,
        categoryColor: String? = null,
        currency: String = "TWD",
    ) = AnalysisTransaction(
        txnDateTime = "${date}T12:00:00",
        amount = amount,
        currency = currency,
        categoryName = categoryName,
        categoryColor = categoryColor,
        categoryKind = categoryKind,
    )
}
