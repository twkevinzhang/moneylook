package tw.kevinzhang.moneylook.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import tw.kevinzhang.core.data.model.CategoryKind
import tw.kevinzhang.moneylook.ui.transactions.GlobalTransactionItem
import tw.kevinzhang.moneylook.ui.transactions.GlobalTransactionsUiState
import tw.kevinzhang.moneylook.ui.transactions.filterGlobalTransactions

/** Bridges the shared global-ledger state into the embeddable Analysis tab. */
@Composable
fun GlobalLedgerAnalysisContent(state: GlobalTransactionsUiState) {
    val reportItems = remember(state.allItems, state.filter) {
        filterGlobalTransactions(state.allItems, state.filter.copy(direction = null))
    }
    val presentation = remember(reportItems, state.trendItems, state.filter.currency, state.dateRange) {
        analysisPresentation(
            summaryTransactions = reportItems.map(GlobalTransactionItem::toAnalysisTransaction),
            trendTransactions = state.trendItems.map(GlobalTransactionItem::toAnalysisTransaction),
            selectedCurrency = state.filter.currency,
            referenceMonth = AnalysisMonth(state.dateRange.endInclusive.year, state.dateRange.endInclusive.monthValue),
            periodLabel = state.dateRange.label(),
        )
    }
    AnalysisContent(presentation)
}

private fun GlobalTransactionItem.toAnalysisTransaction() = AnalysisTransaction(
    txnDateTime = transactionDateTime,
    amount = amount,
    currency = currency,
    categoryName = categoryName,
    categoryColor = categoryColor,
    categoryKind = categoryKind?.toAnalysisKind(),
)

private fun CategoryKind.toAnalysisKind(): AnalysisCategoryKind = when (this) {
    CategoryKind.INCOME -> AnalysisCategoryKind.INCOME
    CategoryKind.EXPENSE -> AnalysisCategoryKind.EXPENSE
    CategoryKind.TRANSFER -> AnalysisCategoryKind.TRANSFER
}
