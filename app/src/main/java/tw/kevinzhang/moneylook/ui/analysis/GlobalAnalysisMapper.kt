package tw.kevinzhang.moneylook.ui.analysis

import tw.kevinzhang.core.data.db.GlobalTransferListItem
import tw.kevinzhang.core.data.model.CategoryKind

/** Keeps the Room projection at the screen boundary; charts never receive account identifiers. */
fun GlobalTransferListItem.toAnalysisTransaction(): AnalysisTransaction = AnalysisTransaction(
    txnDateTime = transfer.txnDateTime,
    amount = transfer.amount,
    currency = currency,
    categoryName = category?.name,
    categoryColor = category?.color,
    categoryKind = category?.kind?.toAnalysisKind(),
)

private fun CategoryKind.toAnalysisKind(): AnalysisCategoryKind = when (this) {
    CategoryKind.INCOME -> AnalysisCategoryKind.INCOME
    CategoryKind.EXPENSE -> AnalysisCategoryKind.EXPENSE
    CategoryKind.TRANSFER -> AnalysisCategoryKind.TRANSFER
}
