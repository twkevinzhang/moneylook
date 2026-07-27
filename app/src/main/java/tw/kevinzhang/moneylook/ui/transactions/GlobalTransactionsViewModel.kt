package tw.kevinzhang.moneylook.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.GlobalTransferListItem
import tw.kevinzhang.core.data.db.TransferAnnotationDao
import tw.kevinzhang.core.network.exchange.TwdExchangeRateRepository
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GlobalTransactionsViewModel @Inject constructor(
    private val transferAnnotationDao: TransferAnnotationDao,
    private val exchangeRateRepository: TwdExchangeRateRepository,
) : ViewModel() {
    private val today = LocalDate.now()
    private val initialRange = GlobalDateRange.thisMonth(today)
    private val periodSelection = MutableStateFlow(PeriodSelection(initialRange, initialRange))
    /**
     * Conditions explicitly selected from the detail filter sheet.  The
     * category report direction deliberately lives in [categoryDirection], so
     * touching a report card cannot silently narrow the detail ledger.
     */
    private val filter = MutableStateFlow(GlobalTransactionsFilter())
    private val activeTab = MutableStateFlow(GlobalTransactionsTab.CATEGORY)
    private val categoryDirection = MutableStateFlow(GlobalTransactionDirection.EXPENSE)
    private val exchangeRateState = MutableStateFlow(ExchangeRateState())

    init {
        viewModelScope.launch {
            val latest = exchangeRateRepository.latestRates()
            exchangeRateState.value = ExchangeRateState(
                ratesPerTwd = latest?.rates.orEmpty() + ("TWD" to 1.0),
                loading = false,
            )
        }
    }

    private val rawItems = periodSelection
        .map { selection: PeriodSelection -> selection.range }
        .distinctUntilChanged()
        .flatMapLatest { range: GlobalDateRange ->
        transferAnnotationDao.observeGlobalBetween(range.startKey, range.endExclusiveKey)
    }
    private val rawTrendItems = periodSelection
        .map { selection: PeriodSelection -> selection.range }
        .distinctUntilChanged()
        .flatMapLatest { range: GlobalDateRange ->
        val endMonth = YearMonth.from(range.endInclusive)
        transferAnnotationDao.observeGlobalBetween(
            endMonth.minusMonths(5).atDay(1).toString(),
            endMonth.plusMonths(1).atDay(1).toString(),
        )
    }

    val state: StateFlow<GlobalTransactionsUiState> = combine(
        combine(rawItems, rawTrendItems) { current, trend -> current to trend },
        periodSelection,
        filter,
        combine(activeTab, categoryDirection) { tab, direction -> tab to direction },
        exchangeRateState,
    ) { itemSets, currentPeriod, currentFilter, tabState, rates ->
        val currentRange = currentPeriod.range
        val allItems = itemSets.first.map { it.toGlobalTransactionItem(rates.ratesPerTwd) }
        val trendItems = itemSets.second.map { it.toGlobalTransactionItem(rates.ratesPerTwd) }
        val sharedFilter = currentFilter.copy(direction = null)
        val reportItems = globalReportableTransactions(filterGlobalTransactions(allItems, sharedFilter))
        val visibleItems = filterGlobalTransactions(allItems, currentFilter)
        GlobalTransactionsUiState(
            dateRange = currentRange,
            datePagerAnchor = currentPeriod.anchor,
            filter = currentFilter,
            activeTab = tabState.first,
            categoryDirection = tabState.second,
            allItems = allItems,
            items = visibleItems,
            // The analysis chart always needs both income and expense series;
            // it shares every other filter with this screen.
            trendItems = globalReportableTransactions(filterGlobalTransactions(trendItems, sharedFilter)),
            summary = globalTransactionsSummary(reportItems),
            categories = globalCategorySummaries(reportItems, tabState.second),
            missingExchangeCurrencies = missingExchangeCurrencies(reportItems),
            exchangeRatesLoading = rates.loading,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        GlobalTransactionsUiState(
            dateRange = periodSelection.value.range,
            filter = filter.value,
            categoryDirection = GlobalTransactionDirection.EXPENSE,
        ),
    )

    fun previousMonth() {
        periodSelection.update { current ->
            val range = moveGlobalMonth(current.range, -1, today)
            PeriodSelection(range, range)
        }
    }
    fun nextMonth() {
        periodSelection.update { current ->
            val range = moveGlobalMonth(current.range, 1, today)
            PeriodSelection(range, range)
        }
    }
    fun setDateRange(start: LocalDate?, end: LocalDate?): Boolean {
        val range = customGlobalDateRange(start, end, today) ?: return false
        periodSelection.value = PeriodSelection(range, range)
        return true
    }
    fun selectDateRange(range: GlobalDateRange) {
        periodSelection.update { it.copy(range = range) }
    }
    fun resetToThisMonth() {
        val range = GlobalDateRange.thisMonth(today)
        periodSelection.value = PeriodSelection(range, range)
    }
    fun setCurrency(value: String) { filter.update { it.copy(currency = value) } }
    fun setQuery(value: String) { filter.update { it.copy(query = value) } }
    fun updateFilter(transform: (GlobalTransactionsFilter) -> GlobalTransactionsFilter) = filter.update(transform)
    fun clearFilters() {
        filter.value = GlobalTransactionsFilter()
    }
    fun selectTab(value: GlobalTransactionsTab) { activeTab.value = value }
    fun selectReportDirection(value: GlobalTransactionDirection) {
        if (value == GlobalTransactionDirection.INCOME || value == GlobalTransactionDirection.EXPENSE) {
            categoryDirection.value = value
        }
    }
}

private data class ExchangeRateState(
    val ratesPerTwd: Map<String, Double> = mapOf("TWD" to 1.0),
    val loading: Boolean = true,
)

private data class PeriodSelection(
    val range: GlobalDateRange,
    val anchor: GlobalDateRange,
)

private fun GlobalTransferListItem.toGlobalTransactionItem(ratesPerTwd: Map<String, Double>) = GlobalTransactionItem(
    transferId = transfer.id,
    transactionDateTime = transfer.txnDateTime,
    description = transfer.description,
    memo = transfer.memo,
    amount = transfer.amount,
    userNote = annotation?.note.orEmpty(),
    categoryId = category?.id,
    categoryName = category?.name,
    categoryReportingGroup = category?.reportingGroup,
    categoryEmoji = category?.emoji,
    categoryColor = category?.color,
    tags = tags.map { GlobalTag(it.id, it.name) },
    accountId = transfer.accountId,
    accountName = accountName,
    extensionId = transfer.extensionId,
    extensionName = extensionName,
    currency = currency,
    accountKind = accountKind,
    cardDisplayLabel = listOfNotNull(
        cardDisplayName?.trim()?.takeIf(String::isNotBlank),
        cardMaskedPan?.trim()?.takeIf(String::isNotBlank)
            ?: cardLastFour?.takeIf { it.matches(Regex("\\d{4}")) }?.let { "•••• $it" },
    ).joinToString(" · ").takeIf(String::isNotBlank),
    status = transfer.status,
    postingDateTime = transfer.postingDateTime,
    amountTwd = transfer.amount.toTwd(currency, ratesPerTwd),
)

private fun Double.toTwd(currency: String, ratesPerTwd: Map<String, Double>): Double? {
    if (!isFinite()) return null
    val normalized = currency.trim().uppercase(Locale.ROOT)
    val rate = if (normalized == "TWD") 1.0 else ratesPerTwd[normalized]
    return rate?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { this / it }
        ?.takeIf(Double::isFinite)
}
