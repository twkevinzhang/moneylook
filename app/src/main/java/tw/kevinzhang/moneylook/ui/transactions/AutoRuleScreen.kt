package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign
import tw.kevinzhang.moneylook.sync.ClassificationResetStage

data class AccountOption(val id: String, val name: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoRuleListContent(
    rules: List<AutoRuleDraft>,
    categories: List<CategoryOption>,
    tags: List<TagOption>,
    accounts: List<AccountOption>,
    onNavigateUp: () -> Unit,
    onSave: (AutoRuleDraft) -> Unit,
    onDelete: (String) -> Unit,
    applyAllRulesUiState: ApplyAllRulesUiState,
    onShowApplyAllRulesConfirmation: () -> Unit,
    onCancelApplyAllRules: () -> Unit,
    onStartApplyAllRules: () -> Unit,
    onRetryApplyAllRules: () -> Unit,
    onDismissApplyAllRules: () -> Unit,
    classificationResetUiState: ClassificationResetUiState,
    onShowResetClassificationConfirmation: () -> Unit,
    onCancelClassificationReset: () -> Unit,
    onStartClassificationReset: () -> Unit,
    onRetryClassificationReset: () -> Unit,
    onDismissClassificationReset: () -> Unit,
) {
    var editing by remember { mutableStateOf<AutoRuleDraft?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    val classificationSystemBusy =
        applyAllRulesUiState != ApplyAllRulesUiState.Idle ||
            classificationResetUiState != ClassificationResetUiState.Idle
    editing?.let { draft ->
        AutoRuleEditorDialog(
            initial = draft,
            categories = categories,
            tags = tags,
            accounts = accounts,
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
        )
    }
    deleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteId = null }, title = { Text("刪除自動分類規則？") },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            text = { Text("不會變更既有交易的分類。") },
            confirmButton = { TextButton(onClick = { onDelete(id); deleteId = null }) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } },
        )
    }
    ApplyAllRulesDialog(
        state = applyAllRulesUiState,
        onCancel = onCancelApplyAllRules,
        onStart = onStartApplyAllRules,
        onRetry = onRetryApplyAllRules,
        onDismiss = onDismissApplyAllRules,
    )
    ClassificationResetDialog(
        state = classificationResetUiState,
        onCancel = onCancelClassificationReset,
        onStart = onStartClassificationReset,
        onRetry = onRetryClassificationReset,
        onDismiss = onDismissClassificationReset,
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自動分類規則") },
                navigationIcon = { BackButton(onNavigateUp) },
                actions = {
                    IconButton(
                        onClick = { moreMenuExpanded = true },
                        enabled = !classificationSystemBusy,
                    ) {
                        Icon(Icons.Default.MoreVert, "更多選項")
                    }
                    DropdownMenu(
                        expanded = moreMenuExpanded,
                        onDismissRequest = { moreMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("套用所有規則") },
                            onClick = {
                                moreMenuExpanded = false
                                onShowApplyAllRulesConfirmation()
                            },
                            enabled = rules.any(AutoRuleDraft::enabled) && !classificationSystemBusy,
                        )
                        DropdownMenuItem(
                            text = { Text("清除並回到預設規則") },
                            onClick = {
                                moreMenuExpanded = false
                                onShowResetClassificationConfirmation()
                            },
                            enabled = !classificationSystemBusy,
                        )
                    }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { editing = AutoRuleDraft(priority = nextUserRulePriority(rules)) }) { Icon(Icons.Default.Add, "新增規則") } },
    ) { padding ->
        if (rules.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("尚未設定規則", style = MaterialTheme.typography.bodyLarge)
                Text("新同步的交易會依規則由上而下比對。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rules.sortedWith(autoRuleDraftComparator), key = { it.id }) { rule ->
                RuleCard(rule, categories, tags, onToggle = { onSave(rule.copy(enabled = it)) }, onEdit = { editing = rule }, onDelete = { deleteId = rule.id })
            }
        }
    }
}

@Composable
private fun ApplyAllRulesDialog(
    state: ApplyAllRulesUiState,
    onCancel: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var applyStartRequested by remember(state) { mutableStateOf(false) }
    when (state) {
        ApplyAllRulesUiState.Idle -> Unit
        ApplyAllRulesUiState.Confirming -> AlertDialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("套用所有規則？") },
            text = {
                Text(
                    "會依優先順序，將所有已啟用規則套用到全部交易明細。" +
                        "手動設定的分類、標籤與備註會保留，不會被覆蓋。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        applyStartRequested = true
                        onStart()
                    },
                    enabled = !applyStartRequested,
                ) { Text("套用") }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
        )
        ApplyAllRulesUiState.Preparing -> ClassificationOperationRunningDialog(
            title = "正在準備交易資料",
            message = "正在載入交易與規則，請勿關閉此視窗。",
        )
        is ApplyAllRulesUiState.Applying -> {
            val total = state.totalTransferCount.coerceAtLeast(0)
            val processed = state.processedTransferCount.coerceIn(0, total)
            ClassificationOperationRunningDialog(
                title = "正在套用所有規則",
                message = "已處理 $processed / $total 筆交易",
                progress = if (total == 0) 0f else processed.toFloat() / total,
                progressDescription = "套用規則進度",
            )
        }
        is ApplyAllRulesUiState.Success -> AlertDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("所有規則已套用") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已處理 ${state.processedTransferCount} 筆交易")
                    Text("符合 ${state.matchedTransferCount} 筆規則")
                    Text("保留 ${state.preservedManualOverrideCount} 筆手動調整")
                }
            },
            confirmButton = { Button(onClick = onDismiss) { Text("完成") } },
        )
        is ApplyAllRulesUiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("套用所有規則失敗") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.message)
                    Text(
                        state.lastStageDescription(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = onRetry) { Text("重試") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
        )
    }
}

private fun ApplyAllRulesUiState.Error.lastStageDescription(): String =
    when (lastStage) {
        ApplyAllRulesStage.PREPARING -> "失敗階段：正在準備交易資料"
        ApplyAllRulesStage.APPLYING ->
            "失敗階段：正在套用規則（已處理 ${processedTransferCount.coerceAtLeast(0)} / " +
                "${totalTransferCount.coerceAtLeast(0)} 筆交易）"
    }

@Composable
private fun ClassificationResetDialog(
    state: ClassificationResetUiState,
    onCancel: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    var resetStartRequested by remember(state) { mutableStateOf(false) }
    when (state) {
        ClassificationResetUiState.Idle -> Unit
        ClassificationResetUiState.Confirming -> AlertDialog(
            onDismissRequest = onCancel,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("清除並回到預設規則？") },
            text = {
                Text(
                    "這會永久清除所有自動分類規則、分類與標籤，並移除所有交易的手動分類與標籤指派。" +
                        "交易與備註會保留，接著會重新分類所有交易。此操作無法復原。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        resetStartRequested = true
                        onStart()
                    },
                    enabled = !resetStartRequested,
                ) { Text("清除並恢復") }
            },
            dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
        )
        ClassificationResetUiState.ResettingCatalog -> ClassificationOperationRunningDialog(
            title = "正在清除分類資料",
            message = "正在恢復內建分類、標籤與自動規則，請勿關閉此視窗。",
        )
        is ClassificationResetUiState.Reclassifying -> {
            val total = state.totalTransferCount.coerceAtLeast(0)
            val processed = state.processedTransferCount.coerceIn(0, total)
            ClassificationOperationRunningDialog(
                title = "正在重新分類交易",
                message = "已處理 $processed / $total 筆交易",
                progress = if (total == 0) 0f else processed.toFloat() / total,
                progressDescription = "重新分類進度",
            )
        }
        is ClassificationResetUiState.Success -> AlertDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("分類系統已重設") },
            text = { Text("已重新分類 ${state.processedTransferCount} 筆交易，符合 ${state.matchedTransferCount} 筆規則。") },
            confirmButton = { Button(onClick = onDismiss) { Text("完成") } },
        )
        is ClassificationResetUiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("重設分類系統失敗") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.message)
                    Text(
                        state.lastStageDescription(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { Button(onClick = onRetry) { Text("重試") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
        )
    }
}

private fun ClassificationResetUiState.Error.lastStageDescription(): String =
    when (lastStage) {
        ClassificationResetStage.RESETTING_CATALOG -> "失敗階段：正在清除分類資料"
        ClassificationResetStage.RECLASSIFYING_TRANSACTIONS ->
            "失敗階段：正在重新分類交易（已處理 ${processedTransferCount.coerceAtLeast(0)} / " +
                "${totalTransferCount.coerceAtLeast(0)} 筆交易）"
    }

@Composable
private fun ClassificationOperationRunningDialog(
    title: String,
    message: String,
    progress: Float? = null,
    progressDescription: String = title,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(28.dp)
                            .semantics { contentDescription = title },
                    )
                    Text(message)
                }
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = progressDescription },
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun RuleCard(
    rule: AutoRuleDraft,
    categories: List<CategoryOption>,
    tags: List<TagOption>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(rule.name.ifBlank { "未命名規則" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("優先順序 ${rule.priority + 1}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onEdit, enabled = rule.isEditableInLegacyEditor()) {
                    Icon(Icons.Default.Edit, "編輯")
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "刪除") }
            }
            Text(ruleSummary(rule, categories, tags), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoRuleEditorDialog(
    initial: AutoRuleDraft,
    categories: List<CategoryOption>,
    tags: List<TagOption>,
    accounts: List<AccountOption>,
    onDismiss: () -> Unit,
    onSave: (AutoRuleDraft) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(if (initial.id.isBlank()) "新增自動分類規則" else "編輯自動分類規則") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("規則名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("符合以下所有條件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(draft.descriptionContains, { draft = draft.copy(descriptionContains = it) }, label = { Text("交易文字包含") }, placeholder = { Text("例如：全聯") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (draft.descriptionContains.isNotBlank()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.descriptionMatchMode == AutoCategoryRuleDescriptionMatchMode.CONTAINS,
                            onClick = { draft = draft.copy(descriptionMatchMode = AutoCategoryRuleDescriptionMatchMode.CONTAINS) },
                            label = { Text("包含文字") },
                        )
                        FilterChip(
                            selected = draft.descriptionMatchMode == AutoCategoryRuleDescriptionMatchMode.EXACT,
                            onClick = { draft = draft.copy(descriptionMatchMode = AutoCategoryRuleDescriptionMatchMode.EXACT) },
                            label = { Text("完全相同") },
                        )
                    }
                }
                Text("金額方向", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(draft.amountSign == AutoCategoryRuleAmountSign.ANY, { draft = draft.copy(amountSign = AutoCategoryRuleAmountSign.ANY) }, { Text("不限") })
                    FilterChip(draft.amountSign == AutoCategoryRuleAmountSign.POSITIVE, { draft = draft.copy(amountSign = AutoCategoryRuleAmountSign.POSITIVE) }, { Text("正額") })
                    FilterChip(draft.amountSign == AutoCategoryRuleAmountSign.NEGATIVE, { draft = draft.copy(amountSign = AutoCategoryRuleAmountSign.NEGATIVE) }, { Text("負額") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft.minAbsoluteAmount, { draft = draft.copy(minAbsoluteAmount = it) }, label = { Text("最低金額") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(draft.maxAbsoluteAmount, { draft = draft.copy(maxAbsoluteAmount = it) }, label = { Text("最高金額") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.weight(1f))
                }
                if (accounts.isNotEmpty()) {
                    Text("帳戶", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(draft.accountId == null, { draft = draft.copy(accountId = null) }, { Text("不限") })
                        accounts.forEach { FilterChip(draft.accountId == it.id, { draft = draft.copy(accountId = it.id) }, { Text(it.name) }) }
                    }
                }
                HorizontalDivider()
                Text("套用動作", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(draft.categoryId == null, { draft = draft.copy(categoryId = null) }, { Text("不設定分類") })
                    categories.forEach { category -> FilterChip(draft.categoryId == category.id, { draft = draft.copy(categoryId = category.id) }, { Text(category.name) }, leadingIcon = { ColorDot(category.color) }) }
                }
                Text("新增標籤", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag -> FilterChip(tag.id in draft.tagIds, { draft = draft.copy(tagIds = if (tag.id in draft.tagIds) draft.tagIds - tag.id else draft.tagIds + tag.id) }, { Text(tag.name) }, leadingIcon = { ColorDot(tag.color) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("啟用規則", modifier = Modifier.weight(1f)); Switch(draft.enabled, { draft = draft.copy(enabled = it) }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("儲存後套用到既有交易", modifier = Modifier.weight(1f)); Switch(draft.applyExisting, { draft = draft.copy(applyExisting = it) }) }
                OutlinedTextField(draft.priority.toString(), { draft = draft.copy(priority = it.toIntOrNull()?.coerceAtLeast(0) ?: draft.priority) }, label = { Text("優先順序（數字愈小愈優先）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(draft.copy(name = draft.name.trim())) },
                enabled = draft.isValidForSave(),
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun AutoRuleDraft.isValidForSave(): Boolean {
    if (createExactDescriptionCondition && descriptionContains.isBlank()) return false
    val min = minAbsoluteAmount.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
    val max = maxAbsoluteAmount.trim().takeIf(String::isNotEmpty)?.toDoubleOrNull()
    val amountsAreValid =
        (minAbsoluteAmount.isBlank() || min?.isFinite() == true && min >= 0.0) &&
            (maxAbsoluteAmount.isBlank() || max?.isFinite() == true && max >= 0.0) &&
            (min == null || max == null || min <= max)
    val hasCondition = conditions.isNotEmpty() || descriptionContains.isNotBlank() ||
        amountSign != AutoCategoryRuleAmountSign.ANY || min != null || max != null || accountId != null ||
        accountKind != null || extensionId != null
    val hasAction = categoryId != null || tagIds.isNotEmpty()
    return name.isNotBlank() && amountsAreValid && hasCondition && hasAction
}

internal fun ruleSummary(rule: AutoRuleDraft, categories: List<CategoryOption>, tags: List<TagOption>): String {
    if (!rule.isEditableInLegacyEditor()) return rule.structuredRuleSummary()
    val conditions = buildList {
        rule.descriptionContains.takeIf(String::isNotBlank)?.let {
            add(if (rule.descriptionMatchMode == AutoCategoryRuleDescriptionMatchMode.EXACT) "交易文字完全是「$it」" else "交易文字含「$it」")
        }
        rule.amountSign.toRuleAmountSignLabel()?.let(::add)
        rule.minAbsoluteAmount.takeIf(String::isNotBlank)?.let { add("≥ $it") }
        rule.maxAbsoluteAmount.takeIf(String::isNotBlank)?.let { add("≤ $it") }
    }.ifEmpty { listOf("所有交易") }.joinToString("、")
    val actions = buildList {
        categories.firstOrNull { it.id == rule.categoryId }?.let { add("分類為「${it.name}」") }
        tags.filter { it.id in rule.tagIds }.takeIf { it.isNotEmpty() }?.let { add("標籤：${it.joinToString { tag -> tag.name }}") }
    }.ifEmpty { listOf("不變更分類或標籤") }.joinToString("；")
    return "如果 $conditions，則 $actions"
}

/** Imported structured rules cannot safely be edited by the legacy single-text-field editor. */
internal fun AutoRuleDraft.isEditableInLegacyEditor(): Boolean =
    conditions.isEmpty() || createExactDescriptionCondition || updateLegacyAnyTextCondition

internal fun AutoRuleDraft.structuredRuleSummary(): String {
    val fieldSummary = buildList {
        conditions.count { it.field == AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE }
            .takeIf { it > 0 }
            ?.let { add("MCC ${it}項") }
        conditions.count { it.field == AutoCategoryRuleConditionField.MERCHANT_NAME }
            .takeIf { it > 0 }
            ?.let { add("商家 ${it}項") }
        conditions.count { it.field == AutoCategoryRuleConditionField.COUNTERPARTY_NAME }
            .takeIf { it > 0 }
            ?.let { add("對手方 ${it}項") }
        conditions.count { it.field == AutoCategoryRuleConditionField.PURPOSE }
            .takeIf { it > 0 }
            ?.let { add("用途 ${it}項") }
        conditions.count {
            it.field == AutoCategoryRuleConditionField.DESCRIPTION ||
                it.field == AutoCategoryRuleConditionField.MEMO ||
                it.field == AutoCategoryRuleConditionField.TYPE ||
                it.field == AutoCategoryRuleConditionField.STATUS
        }.takeIf { it > 0 }?.let { add("文字條件 ${it}項") }
    }.ifEmpty { listOf("結構化條件") }
    val scopes = buildList {
        amountSign.toRuleAmountSignLabel()?.let(::add)
        accountKind?.let { add(it.toDisplayName()) }
        extensionId?.let { add("指定擴充功能") }
    }
    return (listOf("結構化規則：${fieldSummary.joinToString("、")}") + scopes).joinToString("／")
}

private fun AssetKind.toDisplayName(): String = when (this) {
    AssetKind.DEPOSIT -> "活存"
    AssetKind.TIME_DEPOSIT -> "定存"
    AssetKind.CREDIT_CARD -> "信用卡"
    AssetKind.LOAN -> "貸款"
}

private fun AutoCategoryRuleAmountSign.toRuleAmountSignLabel(): String? = when (this) {
    AutoCategoryRuleAmountSign.ANY -> null
    AutoCategoryRuleAmountSign.POSITIVE -> "正額"
    AutoCategoryRuleAmountSign.NEGATIVE -> "負額"
}
