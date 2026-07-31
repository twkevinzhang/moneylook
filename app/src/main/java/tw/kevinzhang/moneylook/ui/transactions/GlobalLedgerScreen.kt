package tw.kevinzhang.moneylook.ui.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun GlobalLedgerScreen(
    onNavigateToTransaction: (String) -> Unit,
    onNavigateToCategoryTransactions: (String?) -> Unit,
    onNavigateToExcludedTransactions: () -> Unit,
    analysisContent: @Composable (GlobalLedgerUiState) -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    viewModel: GlobalLedgerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlobalLedgerContent(
        state = state,
        onNavigateToTransaction = onNavigateToTransaction,
        onSelectDateRange = viewModel::selectDateRange,
        onResetToThisMonth = viewModel::resetToThisMonth,
        onSetDateRange = viewModel::setDateRange,
        onSetQuery = viewModel::setQuery,
        onUpdateFilter = viewModel::updateFilter,
        onClearFilters = viewModel::clearFilters,
        onSelectTab = viewModel::selectTab,
        onSelectReportDirection = viewModel::selectReportDirection,
        onCategoryClick = onNavigateToCategoryTransactions,
        onExcludedSummaryClick = onNavigateToExcludedTransactions,
        analysisContent = analysisContent,
        bottomBar = bottomBar,
    )
}

/**
 * A focused, pushed destination for a category-report row.  It observes the
 * same parent ledger state, so changing the parent date range, search query,
 * or explicit filter is immediately reflected here without encoding those
 * transient conditions in the navigation route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryTransactionsScreen(
    categoryId: String?,
    onNavigateUp: () -> Unit,
    onNavigateToTransaction: (String) -> Unit,
    viewModel: GlobalLedgerViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val categoryItems = remember(state.allItems, state.filter, state.categoryDirection, categoryId) {
        filterCategoryTransactions(
            state.allItems,
            state.filter.copy(direction = state.categoryDirection),
            categoryId,
        )
    }
    val categoryName = remember(state.allItems, categoryId) {
        if (categoryId == null) {
            "尚未分類"
        } else {
            state.allItems.firstOrNull { it.categoryId == categoryId }
                ?.categoryName
                ?.takeIf(String::isNotBlank)
                ?: "分類明細"
        }
    }

    CategoryTransactionsContent(
        categoryName = categoryName,
        items = categoryItems,
        onNavigateUp = onNavigateUp,
        onNavigateToTransaction = onNavigateToTransaction,
    )
}

/** Stateless category-detail content, intentionally reusable by Compose tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryTransactionsContent(
    categoryName: String,
    items: List<GlobalLedgerItem>,
    onNavigateUp: () -> Unit,
    onNavigateToTransaction: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$categoryName 明細") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("這個條件下沒有${categoryName}明細", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(items, key = GlobalLedgerItem::transferId) { item ->
                    GlobalLedgerRow(item, onClick = { onNavigateToTransaction(item.transferId) })
                }
            }
        }
    }
}

/** A pushed ledger subpage that shares the parent filter state without mutating it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcludedTransactionsScreen(
    onNavigateUp: () -> Unit,
    onNavigateToTransaction: (String) -> Unit,
    viewModel: GlobalLedgerViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val excludedItems = remember(state.allItems, state.filter) {
        excludedGlobalLedgerItems(state.allItems, state.filter)
    }
    ExcludedTransactionsContent(
        items = excludedItems,
        onNavigateUp = onNavigateUp,
        onNavigateToTransaction = onNavigateToTransaction,
    )
}

/** Stateless excluded-detail content, kept separate to make navigation behavior testable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcludedTransactionsContent(
    items: List<GlobalLedgerItem>,
    onNavigateUp: () -> Unit,
    onNavigateToTransaction: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("不統計明細") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("這個條件下沒有不統計明細", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(items, key = GlobalLedgerItem::transferId) { item ->
                    GlobalLedgerRow(item, onClick = { onNavigateToTransaction(item.transferId) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalLedgerContent(
    state: GlobalLedgerUiState,
    onNavigateToTransaction: (String) -> Unit,
    onSelectDateRange: (GlobalDateRange) -> Unit,
    onResetToThisMonth: () -> Unit,
    onSetDateRange: (LocalDate?, LocalDate?) -> Boolean,
    onSetQuery: (String) -> Unit,
    onUpdateFilter: ((GlobalLedgerFilter) -> GlobalLedgerFilter) -> Unit,
    onClearFilters: () -> Unit,
    onSelectTab: (GlobalLedgerTab) -> Unit,
    onSelectReportDirection: (GlobalLedgerDirection) -> Unit,
    onCategoryClick: (String?) -> Unit,
    onExcludedSummaryClick: () -> Unit = {},
    analysisContent: @Composable (GlobalLedgerUiState) -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showFilters by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val exitSearch: () -> Unit = {
        onSetQuery("")
        searchActive = false
        keyboardController?.hide()
        Unit
    }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }
    BackHandler(enabled = searchActive, onBack = exitSearch)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (searchActive) {
                        IconButton(onClick = exitSearch) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "關閉搜尋")
                        }
                    }
                },
                title = {
                    if (searchActive) {
                        TextField(
                            value = state.filter.query,
                            onValueChange = onSetQuery,
                            placeholder = {
                                Text(
                                    text = "搜尋交易",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (state.filter.query.isNotEmpty()) {
                                    IconButton(onClick = { onSetQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "清除搜尋")
                                    }
                                }
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboardController?.hide() }),
                            shape = RoundedCornerShape(28.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(searchFocusRequester)
                                .testTag("transaction-search-field"),
                        )
                    } else {
                        Text("帳本")
                    }
                },
                actions = {
                    if (!searchActive) {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Default.Search, contentDescription = "搜尋")
                        }
                        IconButton(onClick = { showFilters = true }) {
                            Icon(Icons.Default.FilterList, contentDescription = "進階篩選")
                        }
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = "選擇日期區間")
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("controls") {
                GlobalLedgerControls(
                    dateRange = state.dateRange,
                    datePagerAnchor = state.datePagerAnchor,
                    onSelectDateRange = onSelectDateRange,
                    onResetToThisMonth = onResetToThisMonth,
                )
            }
            item("summary") {
                GlobalSummaryCards(
                    summary = state.summary,
                    currency = "TWD",
                    selectedDirection = state.categoryDirection,
                    onIncome = { onSelectReportDirection(GlobalLedgerDirection.INCOME) },
                    onExpense = { onSelectReportDirection(GlobalLedgerDirection.EXPENSE) },
                    onExcludedSummaryClick = onExcludedSummaryClick,
                )
            }
            item("exchange-rate-notice") {
                ExchangeRateNotice(
                    loading = state.exchangeRatesLoading,
                    hasMissingCurrencies = state.missingExchangeCurrencies.isNotEmpty(),
                )
            }
            item("tabs") {
                GlobalTabs(selected = state.activeTab, onSelect = onSelectTab)
            }
            when (state.activeTab) {
                GlobalLedgerTab.CATEGORY -> {
                    if (state.categories.isEmpty()) item("empty-category") { GlobalEmptyState("這個條件下沒有可統計的收支分類") }
                    else items(state.categories, key = { "category-${it.id}-${it.name}" }) { category ->
                        CategorySummaryRow(category, "TWD", onClick = { onCategoryClick(category.id) })
                    }
                }
                GlobalLedgerTab.DETAILS -> {
                    if (state.items.isEmpty()) item("empty-details") { GlobalEmptyState("這個條件下沒有交易明細") }
                    else items(state.items, key = GlobalLedgerItem::transferId) { item ->
                        GlobalLedgerRow(item, onClick = { onNavigateToTransaction(item.transferId) })
                    }
                }
                GlobalLedgerTab.ANALYSIS -> item("analysis") { analysisContent(state) }
            }
            item("bottom-space") { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (showFilters) {
        GlobalLedgerFilterSheet(
            state = state,
            onDismiss = { showFilters = false },
            onUpdate = onUpdateFilter,
            onClear = onClearFilters,
        )
    }
    if (showDatePicker) {
        GlobalDateRangeDialog(
            range = state.dateRange,
            onDismiss = { showDatePicker = false },
            onConfirm = onSetDateRange,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlobalLedgerControls(
    dateRange: GlobalDateRange,
    datePagerAnchor: GlobalDateRange,
    onSelectDateRange: (GlobalDateRange) -> Unit,
    onResetToThisMonth: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val model = remember(datePagerAnchor, today) {
        globalDateRangePager(datePagerAnchor, today)
    }
    val activePage = remember(model, dateRange) {
        model.activePageFor(dateRange) ?: model.selectedPage
    }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = activePage,
    )
    val scope = rememberCoroutineScope()
    var settledCenterPage by remember(model, activePage) { mutableIntStateOf(activePage) }

    LaunchedEffect(model, activePage) {
        listState.scrollToItem(activePage)
    }

    LaunchedEffect(listState, model, activePage) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .filter { isScrolling -> !isScrolling }
            .collect {
                val layoutInfo = listState.layoutInfo
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                settledCenterPage = layoutInfo.visibleItemsInfo
                    .minByOrNull { item -> abs((item.offset + item.size / 2) - viewportCenter) }
                    ?.index
                    ?: activePage
            }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val pageWidth = maxWidth / 3
        val showReturnToThisMonth = datePagerAnchor.isCustom || settledCenterPage != model.pageCount - 1
        Box(Modifier.fillMaxWidth()) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth().testTag("date-period-pager"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = pageWidth),
                flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Center),
                overscrollEffect = null,
            ) {
                items(
                    count = model.pageCount,
                    key = { page -> model.rangeAt(page).startKey },
                ) { page ->
                    val range = model.rangeAt(page)
                    val selected = range == dateRange
                    Column(
                        modifier = Modifier
                            .width(pageWidth)
                            .heightIn(min = 48.dp)
                            .testTag("date-period-${range.startKey}")
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                onClick = {
                                    scope.launch {
                                        listState.animateScrollToItem(page)
                                        onSelectDateRange(range)
                                    }
                                },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = range.tabYearLabel(),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Text(
                            text = range.tabDateRangeLabel(),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }
            if (showReturnToThisMonth) {
                FilledTonalIconButton(
                    onClick = {
                        scope.launch {
                            listState.scrollToItem(model.pageCount - 1)
                            onResetToThisMonth()
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(Icons.AutoMirrored.Filled.LastPage, contentDescription = "回到本月")
                }
            }
        }
    }
}

@Composable
private fun ExchangeRateNotice(loading: Boolean, hasMissingCurrencies: Boolean) {
    val text = when {
        loading -> "正在取得最新匯率 · 匯率資料：ExchangeRate-API"
        hasMissingCurrencies -> "統計已換算為 TWD · 部分幣別未計入 · 匯率資料：ExchangeRate-API"
        else -> "統計已依最新匯率換算為 TWD · 匯率資料：ExchangeRate-API"
    }
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GlobalSummaryCards(
    summary: GlobalLedgerSummary,
    currency: String,
    selectedDirection: GlobalLedgerDirection?,
    onIncome: () -> Unit,
    onExpense: () -> Unit,
    onExcludedSummaryClick: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), horizontalAlignment = Alignment.End) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("收入", summary.income, currency, MaterialTheme.colorScheme.primary, selectedDirection == GlobalLedgerDirection.INCOME, onIncome, Modifier.weight(1f).testTag("summary-income"))
            SummaryCard("支出", summary.expense, currency, MaterialTheme.colorScheme.error, selectedDirection == GlobalLedgerDirection.EXPENSE, onExpense, Modifier.weight(1f).testTag("summary-expense"))
        }
        if (summary.excludedCount > 0) {
            TextButton(
                onClick = onExcludedSummaryClick,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .heightIn(min = 48.dp)
                    .testTag("summary-excluded-count")
                    .semantics { contentDescription = "查看不統計 ${summary.excludedCount} 筆明細" },
            ) {
                Text(
                    text = "不統計 ${summary.excludedCount} 筆 ›",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                text = "不統計 0 筆",
                modifier = Modifier.padding(top = 6.dp).testTag("summary-excluded-count"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryCard(label: String, amount: Double, currency: String, color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Card(
        modifier = modifier.semantics { this.selected = selected }.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (selected) CardDefaults.outlinedCardBorder().copy(width = 2.dp, brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(globalMoney(amount, currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun GlobalTabs(selected: GlobalLedgerTab, onSelect: (GlobalLedgerTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == GlobalLedgerTab.CATEGORY, { onSelect(GlobalLedgerTab.CATEGORY) }, { Text("分類") }, Modifier.weight(1f))
        FilterChip(selected == GlobalLedgerTab.DETAILS, { onSelect(GlobalLedgerTab.DETAILS) }, { Text("明細") }, Modifier.weight(1f))
        FilterChip(selected == GlobalLedgerTab.ANALYSIS, { onSelect(GlobalLedgerTab.ANALYSIS) }, { Text("分析") }, Modifier.weight(1f))
    }
}

@Composable
private fun CategorySummaryRow(item: GlobalCategorySummary, currency: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.emoji, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row { Text(item.name, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(8.dp)); Text("${item.transactionCount} 筆", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            LinearProgressIndicator(progress = { item.percentage }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(14.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(globalMoney(item.amount, currency), fontWeight = FontWeight.Bold)
            Text("${(item.percentage * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun GlobalLedgerRow(item: GlobalLedgerItem, onClick: () -> Unit) {
    val amountColor = when (globalLedgerAmountTone(item)) {
        GlobalLedgerAmountTone.POSITIVE -> MaterialTheme.colorScheme.primary
        GlobalLedgerAmountTone.NEGATIVE -> MaterialTheme.colorScheme.error
        GlobalLedgerAmountTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.description.ifBlank { "未提供交易說明" },
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                globalCreditCardTransactionStatus(item)?.let { status ->
                    CreditCardTransactionStatusChip(status)
                }
            }
            Text(listOfNotNull(item.categoryName ?: "尚未分類", item.accountName, item.extensionName.takeIf(String::isNotBlank)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.cardDisplayLabel?.let { card ->
                Text(card, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (item.tags.isNotEmpty()) Text(item.tags.joinToString(" · ") { "#${it.name}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(item.transactionDateTime.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(globalMoney(item.amount, item.currency), fontWeight = FontWeight.Bold, color = amountColor)
        }
    }
    HorizontalDivider()
}

@Composable
fun CreditCardTransactionStatusChip(status: GlobalCreditCardTransactionStatus) {
    val (containerColor, contentColor) = when (status) {
        GlobalCreditCardTransactionStatus.POSTED -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        GlobalCreditCardTransactionStatus.PENDING -> Color(0xFFFFE0B2) to Color(0xFF7A4100)
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = status.label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GlobalEmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalLedgerFilterSheet(
    state: GlobalLedgerUiState,
    onDismiss: () -> Unit,
    onUpdate: ((GlobalLedgerFilter) -> GlobalLedgerFilter) -> Unit,
    onClear: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("進階篩選", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("銀行／資料來源與帳戶", fontWeight = FontWeight.SemiBold)
                    FilterOptions(
                        title = "資料來源",
                        options = state.extensions,
                        selected = state.filter.extensionId,
                        onSelect = { id -> onUpdate { selectGlobalLedgerExtension(it, id) } },
                    )
                    FilterOptions(
                        title = "帳戶",
                        options = state.accountsForExtension(state.filter.extensionId),
                        selected = state.filter.accountId,
                        onSelect = { id -> onUpdate { it.copy(accountId = id) } },
                        emptyMessage = "請先選擇資料來源",
                    )
                }
            }
            item { FilterOptions("分類", state.categoryOptions, state.filter.categoryId, { id -> onUpdate { it.copy(categoryId = id) } }) }
            item { FilterOptions("標籤", state.tagOptions, state.filter.tagId, { id -> onUpdate { it.copy(tagId = id) } }) }
            item {
                Text("分類狀態", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssignmentChip("已分類", GlobalCategoryAssignment.CATEGORIZED, state.filter.assignment, onUpdate)
                    AssignmentChip("未分類", GlobalCategoryAssignment.UNCATEGORIZED, state.filter.assignment, onUpdate)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(state.filter.minimumAmount, { value -> onUpdate { it.copy(minimumAmount = value) } }, label = { Text("最低金額") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(state.filter.maximumAmount, { value -> onUpdate { it.copy(maximumAmount = value) } }, label = { Text("最高金額") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClear) { Text("清除全部條件") }
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterOptions(
    title: String,
    options: List<GlobalChoice>,
    selected: String?,
    onSelect: (String?) -> Unit,
    emptyMessage: String? = null,
) {
    if (options.isEmpty() && emptyMessage == null) return
    Text(title, fontWeight = FontWeight.SemiBold)
    if (options.isEmpty()) {
        emptyMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected == null, { onSelect(null) }, { Text("全部") })
        options.forEach { option -> FilterChip(selected == option.id, { onSelect(if (selected == option.id) null else option.id) }, { Text(option.name) }) }
    }
}

@Composable
private fun AssignmentChip(label: String, assignment: GlobalCategoryAssignment, selected: GlobalCategoryAssignment, onUpdate: ((GlobalLedgerFilter) -> GlobalLedgerFilter) -> Unit) {
    FilterChip(selected == assignment, { onUpdate { it.copy(assignment = if (it.assignment == assignment) GlobalCategoryAssignment.ALL else assignment) } }, { Text(label) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalDateRangeDialog(range: GlobalDateRange, onDismiss: () -> Unit, onConfirm: (LocalDate?, LocalDate?) -> Boolean) {
    var validationMessage by remember { mutableStateOf<String?>(null) }
    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = range.startInclusive.toUtcMillis(),
        initialSelectedEndDateMillis = range.endInclusive.toUtcMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        confirmButton = {
            TextButton(onClick = {
                val valid = onConfirm(pickerState.selectedStartDateMillis?.utcDate(), pickerState.selectedEndDateMillis?.utcDate())
                validationMessage = if (valid) null else "請選擇完整區間，且結束日不得晚於今天。"
                if (valid) onDismiss()
            }) { Text("套用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        Column {
            DateRangePicker(state = pickerState, title = { Text("選擇日期區間") })
            validationMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
        }
    }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
private fun globalMoney(amount: Double, currency: String): String = when (currency.uppercase()) {
    "TWD" -> "$ ${String.format("%,.0f", amount)}"
    else -> "$currency ${String.format("%,.2f", amount)}"
}
