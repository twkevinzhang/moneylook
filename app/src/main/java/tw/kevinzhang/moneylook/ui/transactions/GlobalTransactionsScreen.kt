package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

@Composable
fun GlobalTransactionsScreen(
    onNavigateToTransaction: (String) -> Unit,
    analysisContent: @Composable (GlobalTransactionsUiState) -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    viewModel: GlobalTransactionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlobalTransactionsContent(
        state = state,
        onNavigateToTransaction = onNavigateToTransaction,
        onPreviousMonth = viewModel::previousMonth,
        onNextMonth = viewModel::nextMonth,
        onResetToThisMonth = viewModel::resetToThisMonth,
        onSetDateRange = viewModel::setDateRange,
        onSetCurrency = viewModel::setCurrency,
        onSetQuery = viewModel::setQuery,
        onUpdateFilter = viewModel::updateFilter,
        onClearFilters = viewModel::clearFilters,
        onSelectTab = viewModel::selectTab,
        onSelectReportDirection = viewModel::selectReportDirection,
        onCategoryClick = viewModel::showCategoryDetails,
        analysisContent = analysisContent,
        bottomBar = bottomBar,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalTransactionsContent(
    state: GlobalTransactionsUiState,
    onNavigateToTransaction: (String) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onResetToThisMonth: () -> Unit,
    onSetDateRange: (LocalDate?, LocalDate?) -> Boolean,
    onSetCurrency: (String) -> Unit,
    onSetQuery: (String) -> Unit,
    onUpdateFilter: ((GlobalTransactionsFilter) -> GlobalTransactionsFilter) -> Unit,
    onClearFilters: () -> Unit,
    onSelectTab: (GlobalTransactionsTab) -> Unit,
    onSelectReportDirection: (GlobalTransactionDirection) -> Unit,
    onCategoryClick: (String?) -> Unit,
    analysisContent: @Composable (GlobalTransactionsUiState) -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showFilters by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("明細") }) },
        bottomBar = bottomBar,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("controls") {
                GlobalTransactionControls(
                    state = state,
                    onPreviousMonth = onPreviousMonth,
                    onNextMonth = onNextMonth,
                    onResetToThisMonth = onResetToThisMonth,
                    onShowDatePicker = { showDatePicker = true },
                    onSetCurrency = onSetCurrency,
                    onSetQuery = onSetQuery,
                    onShowFilters = { showFilters = true },
                )
            }
            item("summary") {
                GlobalSummaryCards(
                    summary = state.summary,
                    currency = state.filter.currency,
                    onIncome = { onSelectReportDirection(GlobalTransactionDirection.INCOME) },
                    onExpense = { onSelectReportDirection(GlobalTransactionDirection.EXPENSE) },
                )
            }
            item("tabs") {
                GlobalTabs(selected = state.activeTab, onSelect = onSelectTab)
            }
            when (state.activeTab) {
                GlobalTransactionsTab.CATEGORY -> {
                    item("category-direction") {
                        CategoryDirectionSelector(
                            selected = state.categoryDirection,
                            onSelect = onSelectReportDirection,
                        )
                    }
                    if (state.categories.isEmpty()) item("empty-category") { GlobalEmptyState("這個條件下沒有可統計的收支分類") }
                    else items(state.categories, key = { "category-${it.id}-${it.name}" }) { category ->
                        CategorySummaryRow(category, state.filter.currency, onClick = { onCategoryClick(category.id) })
                    }
                }
                GlobalTransactionsTab.DETAILS -> {
                    if (state.items.isEmpty()) item("empty-details") { GlobalEmptyState("這個條件下沒有交易明細") }
                    else items(state.items, key = GlobalTransactionItem::transferId) { item ->
                        GlobalTransactionRow(item, onClick = { onNavigateToTransaction(item.transferId) })
                    }
                }
                GlobalTransactionsTab.ANALYSIS -> item("analysis") { analysisContent(state) }
            }
            item("bottom-space") { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (showFilters) {
        GlobalTransactionFilterSheet(
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlobalTransactionControls(
    state: GlobalTransactionsUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onResetToThisMonth: () -> Unit,
    onShowDatePicker: () -> Unit,
    onSetCurrency: (String) -> Unit,
    onSetQuery: (String) -> Unit,
    onShowFilters: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPreviousMonth) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "上個月") }
            Text(state.dateRange.label(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            IconButton(onClick = onNextMonth) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "下個月") }
            IconButton(onClick = onShowDatePicker) { Icon(Icons.Default.CalendarMonth, "選擇日期區間") }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(onClick = onResetToThisMonth, label = { Text("本月") })
            state.currencies.forEach { currency ->
                FilterChip(selected = currency == state.filter.currency, onClick = { onSetCurrency(currency) }, label = { Text(currency) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.filter.query,
                onValueChange = onSetQuery,
                label = { Text("搜尋交易、帳戶、分類或標籤") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onShowFilters) { Icon(Icons.Default.FilterList, "進階篩選") }
        }
    }
}

@Composable
private fun GlobalSummaryCards(
    summary: GlobalTransactionsSummary,
    currency: String,
    onIncome: () -> Unit,
    onExpense: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SummaryCard("收入", summary.income, currency, MaterialTheme.colorScheme.primary, onIncome, Modifier.weight(1f))
        SummaryCard("支出", summary.expense, currency, MaterialTheme.colorScheme.error, onExpense, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCard(label: String, amount: Double, currency: String, color: Color, onClick: () -> Unit, modifier: Modifier) {
    Card(modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(globalMoney(amount, currency), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun GlobalTabs(selected: GlobalTransactionsTab, onSelect: (GlobalTransactionsTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == GlobalTransactionsTab.CATEGORY, { onSelect(GlobalTransactionsTab.CATEGORY) }, { Text("分類") }, Modifier.weight(1f))
        FilterChip(selected == GlobalTransactionsTab.DETAILS, { onSelect(GlobalTransactionsTab.DETAILS) }, { Text("明細") }, Modifier.weight(1f))
        FilterChip(selected == GlobalTransactionsTab.ANALYSIS, { onSelect(GlobalTransactionsTab.ANALYSIS) }, { Text("分析") }, Modifier.weight(1f))
    }
}

@Composable
private fun CategoryDirectionSelector(
    selected: GlobalTransactionDirection,
    onSelect: (GlobalTransactionDirection) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == GlobalTransactionDirection.INCOME,
            onClick = { onSelect(GlobalTransactionDirection.INCOME) },
            label = { Text("收入") },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = selected == GlobalTransactionDirection.EXPENSE,
            onClick = { onSelect(GlobalTransactionDirection.EXPENSE) },
            label = { Text("支出") },
            modifier = Modifier.weight(1f),
        )
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
private fun GlobalTransactionRow(item: GlobalTransactionItem, onClick: () -> Unit) {
    val direction = globalTransactionDirection(item)
    val amountColor = when (direction) {
        GlobalTransactionDirection.INCOME -> MaterialTheme.colorScheme.primary
        GlobalTransactionDirection.EXPENSE -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.description.ifBlank { "未提供交易說明" }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(item.categoryName ?: "尚未分類", item.accountName, item.extensionName.takeIf(String::isNotBlank)).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun GlobalEmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobalTransactionFilterSheet(
    state: GlobalTransactionsUiState,
    onDismiss: () -> Unit,
    onUpdate: ((GlobalTransactionsFilter) -> GlobalTransactionsFilter) -> Unit,
    onClear: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("進階篩選", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            item { FilterOptions("銀行／資料來源", state.extensions, state.filter.extensionId, { id -> onUpdate { it.copy(extensionId = id) } }) }
            item { FilterOptions("帳戶", state.accounts, state.filter.accountId, { id -> onUpdate { it.copy(accountId = id) } }) }
            item { FilterOptions("分類", state.categoryOptions, state.filter.categoryId, { id -> onUpdate { it.copy(categoryId = id) } }) }
            item { FilterOptions("標籤", state.tagOptions, state.filter.tagId, { id -> onUpdate { it.copy(tagId = id) } }) }
            item {
                Text("收支類型", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DirectionChip("收入", GlobalTransactionDirection.INCOME, state.filter.direction, onUpdate)
                    DirectionChip("支出", GlobalTransactionDirection.EXPENSE, state.filter.direction, onUpdate)
                    DirectionChip("移轉", GlobalTransactionDirection.TRANSFER, state.filter.direction, onUpdate)
                }
            }
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
private fun FilterOptions(title: String, options: List<GlobalChoice>, selected: String?, onSelect: (String?) -> Unit) {
    if (options.isEmpty()) return
    Text(title, fontWeight = FontWeight.SemiBold)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected == null, { onSelect(null) }, { Text("全部") })
        options.forEach { option -> FilterChip(selected == option.id, { onSelect(if (selected == option.id) null else option.id) }, { Text(option.name) }) }
    }
}

@Composable
private fun DirectionChip(label: String, direction: GlobalTransactionDirection, selected: GlobalTransactionDirection?, onUpdate: ((GlobalTransactionsFilter) -> GlobalTransactionsFilter) -> Unit) {
    FilterChip(selected == direction, { onUpdate { it.copy(direction = if (it.direction == direction) null else direction) } }, { Text(label) })
}

@Composable
private fun AssignmentChip(label: String, assignment: GlobalCategoryAssignment, selected: GlobalCategoryAssignment, onUpdate: ((GlobalTransactionsFilter) -> GlobalTransactionsFilter) -> Unit) {
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
