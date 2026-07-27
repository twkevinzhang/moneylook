package tw.kevinzhang.moneylook.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.moneylook.ui.transactions.GlobalLedgerItem
import tw.kevinzhang.moneylook.ui.transactions.GlobalLedgerUiState
import tw.kevinzhang.moneylook.ui.transactions.filterGlobalLedger
import tw.kevinzhang.moneylook.ui.transactions.globalReportableTransactions
import tw.kevinzhang.moneylook.ui.transactions.reportingAmountTwd

/** Bridges the shared global-ledger state into the embeddable Analysis tab. */
@Composable
fun GlobalLedgerAnalysisContent(state: GlobalLedgerUiState) {
    val reportItems = remember(state.allItems, state.filter) {
        globalReportableTransactions(
            filterGlobalLedger(state.allItems, state.filter.copy(direction = null)),
        )
    }
    val presentation = remember(reportItems, state.trendItems, state.dateRange) {
        analysisPresentation(
            summaryTransactions = reportItems.mapNotNull(GlobalLedgerItem::toTwdAnalysisTransaction),
            trendTransactions = state.trendItems.mapNotNull(GlobalLedgerItem::toTwdAnalysisTransaction),
            selectedCurrency = "TWD",
            referenceMonth = AnalysisMonth(state.dateRange.endInclusive.year, state.dateRange.endInclusive.monthValue),
            periodLabel = state.dateRange.label(),
        )
    }
    AnalysisContent(
        presentation = presentation,
        selectedDirection = when (state.categoryDirection) {
            tw.kevinzhang.moneylook.ui.transactions.GlobalLedgerDirection.INCOME -> AnalysisDirection.INCOME
            else -> AnalysisDirection.EXPENSE
        },
    )
}

private fun GlobalLedgerItem.toTwdAnalysisTransaction(): AnalysisTransaction? {
    val amount = reportingAmountTwd() ?: return null
    return AnalysisTransaction(
    txnDateTime = transactionDateTime,
    amount = amount,
    currency = "TWD",
    categoryName = categoryName,
    categoryColor = categoryColor,
    categoryReportingGroup = categoryReportingGroup?.toAnalysisReportingGroup(),
    )
}

private fun CategoryReportingGroup.toAnalysisReportingGroup(): AnalysisReportingGroup = when (this) {
    CategoryReportingGroup.INCOME -> AnalysisReportingGroup.INCOME
    CategoryReportingGroup.EXPENSE -> AnalysisReportingGroup.EXPENSE
    CategoryReportingGroup.EXCLUDED -> AnalysisReportingGroup.EXCLUDED
}
