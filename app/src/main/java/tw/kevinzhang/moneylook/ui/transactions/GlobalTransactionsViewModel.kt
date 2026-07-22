package tw.kevinzhang.moneylook.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import tw.kevinzhang.core.data.db.GlobalTransferListItem
import tw.kevinzhang.core.data.db.TransferAnnotationDao
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GlobalTransactionsViewModel @Inject constructor(
    private val transferAnnotationDao: TransferAnnotationDao,
) : ViewModel() {
    private val today = LocalDate.now()
    private val dateRange = MutableStateFlow(GlobalDateRange.thisMonth(today))
    private val filter = MutableStateFlow(GlobalTransactionsFilter())
    private val activeTab = MutableStateFlow(GlobalTransactionsTab.CATEGORY)
    private val categoryDirection = MutableStateFlow(GlobalTransactionDirection.EXPENSE)

    private val rawItems = dateRange.flatMapLatest { range ->
        transferAnnotationDao.observeGlobalBetween(range.startKey, range.endExclusiveKey)
    }
    private val rawTrendItems = dateRange.flatMapLatest { range ->
        val endMonth = YearMonth.from(range.endInclusive)
        transferAnnotationDao.observeGlobalBetween(
            endMonth.minusMonths(5).atDay(1).toString(),
            endMonth.plusMonths(1).atDay(1).toString(),
        )
    }

    val state: StateFlow<GlobalTransactionsUiState> = combine(
        combine(rawItems, rawTrendItems) { current, trend -> current to trend },
        dateRange,
        filter,
        activeTab,
        categoryDirection,
    ) { itemSets, currentRange, currentFilter, currentTab, currentCategoryDirection ->
        val allItems = itemSets.first.map(GlobalTransferListItem::toGlobalTransactionItem)
        val trendItems = itemSets.second.map(GlobalTransferListItem::toGlobalTransactionItem)
        val sharedFilter = currentFilter.copy(direction = null)
        val reportItems = filterGlobalTransactions(allItems, sharedFilter)
        val visibleItems = filterGlobalTransactions(allItems, currentFilter)
        GlobalTransactionsUiState(
            dateRange = currentRange,
            filter = currentFilter,
            activeTab = currentTab,
            categoryDirection = currentCategoryDirection,
            allItems = allItems,
            items = visibleItems,
            // The analysis chart always needs both income and expense series;
            // it shares every other filter with this screen.
            trendItems = filterGlobalTransactions(trendItems, sharedFilter),
            summary = globalTransactionsSummary(reportItems),
            categories = globalCategorySummaries(reportItems, currentCategoryDirection),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GlobalTransactionsUiState(dateRange = dateRange.value),
    )

    fun previousMonth() { dateRange.update { moveGlobalMonth(it, -1, today) } }
    fun nextMonth() { dateRange.update { moveGlobalMonth(it, 1, today) } }
    fun setDateRange(start: LocalDate?, end: LocalDate?): Boolean {
        val range = customGlobalDateRange(start, end, today) ?: return false
        dateRange.value = range
        return true
    }
    fun resetToThisMonth() { dateRange.value = GlobalDateRange.thisMonth(today) }
    fun setCurrency(value: String) { filter.update { it.copy(currency = value) } }
    fun setQuery(value: String) { filter.update { it.copy(query = value) } }
    fun updateFilter(transform: (GlobalTransactionsFilter) -> GlobalTransactionsFilter) { filter.update(transform) }
    fun clearFilters() { filter.value = GlobalTransactionsFilter(currency = filter.value.currency) }
    fun selectTab(value: GlobalTransactionsTab) { activeTab.value = value }
    fun selectReportDirection(value: GlobalTransactionDirection) { categoryDirection.value = value }
    fun showCategoryDetails(categoryId: String?) {
        filter.update { it.copy(categoryId = categoryId) }
        activeTab.value = GlobalTransactionsTab.DETAILS
    }
}

private fun GlobalTransferListItem.toGlobalTransactionItem() = GlobalTransactionItem(
    transferId = transfer.id,
    transactionDateTime = transfer.txnDateTime,
    description = transfer.description,
    memo = transfer.memo,
    amount = transfer.amount,
    userNote = annotation?.note.orEmpty(),
    categoryId = category?.id,
    categoryName = category?.name,
    categoryKind = category?.kind,
    categoryEmoji = category?.emoji,
    categoryColor = category?.color,
    tags = tags.map { GlobalTag(it.id, it.name) },
    accountId = transfer.accountId,
    accountName = accountName,
    extensionId = transfer.extensionId,
    extensionName = extensionName,
    currency = currency,
)
