package tw.kevinzhang.moneylook.ui.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnalysisPresentationTest {
    @Test
    fun assignedReportingGroupControlsDirectionAndExcludedRowsNeverEnterReports() {
        val presentation = analysisPresentation(
            transactions = listOf(
                transaction("2026-07-01", 100.0),
                transaction("2026-07-02", 40.0, "餐飲", AnalysisReportingGroup.EXPENSE, "#FB8C00"),
                transaction("2026-07-03", -20.0, "薪資", AnalysisReportingGroup.INCOME, "#43B96D"),
                transaction("2026-07-04", -999.0, "帳戶移轉", AnalysisReportingGroup.EXCLUDED),
                transaction("2026-07-05", 0.0),
                transaction("2026-07-06", 88.0, currency = "USD"),
            ),
            selectedCurrency = "twd",
            referenceMonth = AnalysisMonth(2026, 7),
        )

        assertEquals(120.0, presentation.summary.income, 0.0)
        assertEquals(40.0, presentation.summary.expense, 0.0)
        assertEquals(80.0, presentation.summary.balance, 0.0)
        assertEquals(listOf("未分類", "薪資"), presentation.categorySlices(AnalysisDirection.INCOME).map { it.name })
        assertEquals(listOf("餐飲"), presentation.categorySlices(AnalysisDirection.EXPENSE).map { it.name })
        assertEquals(40.0, presentation.categorySlices(AnalysisDirection.EXPENSE).single().amount, 0.0)
    }

    @Test
    fun customPeriodUsesProvidedSummaryRowsButKeepsSixCalendarMonthTrend() {
        val presentation = analysisPresentation(
            summaryTransactions = listOf(transaction("2026-06-30", -50.0, "餐飲", AnalysisReportingGroup.EXPENSE)),
            trendTransactions = listOf(
                transaction("2026-02-02", 10.0),
                transaction("2026-06-30", -50.0, "餐飲", AnalysisReportingGroup.EXPENSE),
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
    fun reportDirectionUsesCategoryGroupAndOnlyFallsBackToSignWhenUncategorized() {
        assertEquals(AnalysisDirection.EXPENSE, transaction("2026-07-01", 1.0, categoryReportingGroup = AnalysisReportingGroup.EXPENSE).reportingDirection())
        assertEquals(AnalysisDirection.INCOME, transaction("2026-07-01", -1.0, categoryReportingGroup = AnalysisReportingGroup.INCOME).reportingDirection())
        assertEquals(AnalysisDirection.EXPENSE, transaction("2026-07-01", -1.0).reportingDirection())
        assertNull(transaction("2026-07-01", 1.0, categoryReportingGroup = AnalysisReportingGroup.EXCLUDED).reportingDirection())
    }

    @Test
    fun directionHelpersSelectOnlyTheRequestedSummaryAndTrendValues() {
        val summary = AnalysisSummary(income = 120.0, expense = 40.0)
        val point = AnalysisTrendPoint(
            month = AnalysisMonth(2026, 7),
            income = 80.0,
            expense = 25.0,
        )

        assertEquals(120.0, summary.amount(AnalysisDirection.INCOME), 0.0)
        assertEquals(40.0, summary.amount(AnalysisDirection.EXPENSE), 0.0)
        assertEquals(80.0, point.amount(AnalysisDirection.INCOME), 0.0)
        assertEquals(25.0, point.amount(AnalysisDirection.EXPENSE), 0.0)
    }

    private fun transaction(
        date: String,
        amount: Double,
        categoryName: String? = null,
        categoryReportingGroup: AnalysisReportingGroup? = null,
        categoryColor: String? = null,
        currency: String = "TWD",
    ) = AnalysisTransaction(
        txnDateTime = "${date}T12:00:00",
        amount = amount,
        currency = currency,
        categoryName = categoryName,
        categoryColor = categoryColor,
        categoryReportingGroup = categoryReportingGroup,
    )
}
