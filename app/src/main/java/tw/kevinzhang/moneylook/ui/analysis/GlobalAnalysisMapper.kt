package tw.kevinzhang.moneylook.ui.analysis

import tw.kevinzhang.core.data.db.GlobalTransferListItem
import tw.kevinzhang.core.data.model.CategoryReportingGroup

/** Keeps the Room projection at the screen boundary; charts never receive account identifiers. */
fun GlobalTransferListItem.toAnalysisTransaction(): AnalysisTransaction = AnalysisTransaction(
    txnDateTime = transfer.txnDateTime,
    amount = transfer.amount,
    currency = currency,
    categoryName = category?.name,
    categoryColor = category?.color,
    categoryReportingGroup = category?.reportingGroup?.toAnalysisReportingGroup(),
)

private fun CategoryReportingGroup.toAnalysisReportingGroup(): AnalysisReportingGroup = when (this) {
    CategoryReportingGroup.INCOME -> AnalysisReportingGroup.INCOME
    CategoryReportingGroup.EXPENSE -> AnalysisReportingGroup.EXPENSE
    CategoryReportingGroup.EXCLUDED -> AnalysisReportingGroup.EXCLUDED
}
