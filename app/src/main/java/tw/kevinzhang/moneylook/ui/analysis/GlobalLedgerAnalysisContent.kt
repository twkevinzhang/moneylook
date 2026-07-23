package tw.kevinzhang.moneylook.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import tw.kevinzhang.core.data.model.CategoryKind
import tw.kevinzhang.moneylook.ui.transactions.GlobalTransactionItem
import tw.kevinzhang.moneylook.ui.transactions.GlobalTransactionsUiState
import tw.kevinzhang.moneylook.ui.transactions.filterGlobalTransactions
import tw.kevinzhang.moneylook.ui.transactions.globalReportableTransactions
import tw.kevinzhang.moneylook.ui.transactions.reportingAmountTwd

/** Bridges the shared global-ledger state into the embeddable Analysis tab. */
@Composable
fun GlobalLedgerAnalysisContent(state: GlobalTransactionsUiState) {
    val reportItems = remember(state.allItems, state.filter) {
        globalReportableTransactions(
            filterGlobalTransactions(state.allItems, state.filter.copy(direction = null)),
        )
    }
    val presentation = remember(reportItems, state.trendItems, state.dateRange) {
        analysisPresentation(
            summaryTransactions = reportItems.mapNotNull(GlobalTransactionItem::toTwdAnalysisTransaction),
            trendTransactions = state.trendItems.mapNotNull(GlobalTransactionItem::toTwdAnalysisTransaction),
            selectedCurrency = "TWD",
            referenceMonth = AnalysisMonth(state.dateRange.endInclusive.year, state.dateRange.endInclusive.monthValue),
            periodLabel = state.dateRange.label(),
        )
    }
    AnalysisContent(
        presentation = presentation,
        selectedDirection = when (state.categoryDirection) {
            tw.kevinzhang.moneylook.ui.transactions.GlobalTransactionDirection.INCOME -> AnalysisDirection.INCOME
            else -> AnalysisDirection.EXPENSE
        },
    )
}

private fun GlobalTransactionItem.toTwdAnalysisTransaction(): AnalysisTransaction? {
    val amount = reportingAmountTwd() ?: return null
    return AnalysisTransaction(
    txnDateTime = transactionDateTime,
    amount = amount,
    currency = "TWD",
    categoryName = categoryName,
    categoryColor = categoryColor,
    categoryKind = categoryKind?.toAnalysisKind(),
    )
}

private fun CategoryKind.toAnalysisKind(): AnalysisCategoryKind = when (this) {
    CategoryKind.INCOME -> AnalysisCategoryKind.INCOME
    CategoryKind.EXPENSE -> AnalysisCategoryKind.EXPENSE
    CategoryKind.TRANSFER -> AnalysisCategoryKind.TRANSFER
}
