package tw.kevinzhang.moneylook.ui.transactions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.CategoryReportingGroup

data class TransactionDetailUiState(
    val title: String,
    val amountText: String,
    val amount: Double,
    val accountName: String,
    val extensionId: String = "",
    /** Redaction-safe bank-provided card name/mask; complete PANs never enter this state. */
    val cardDisplayLabel: String? = null,
    val transactionDate: String,
    val postingDate: String?,
    val description: String,
    val bankMemo: String?,
    val selectedCategoryId: String?,
    val selectedTagIds: Set<String>,
    val userNote: String,
    val categories: List<CategoryOption>,
    val tags: List<TagOption>,
    val accountKind: AssetKind = AssetKind.DEPOSIT,
    val status: String? = null,
    val isManualOverride: Boolean = false,
    val isSaving: Boolean = false,
    val sourceFields: List<SourceFieldAuditUi> = emptyList(),
    val auditTimeline: List<String> = emptyList(),
    val sourceDocuments: List<SourceDocumentAuditUi> = emptyList(),
)

data class SourceFieldAuditUi(
    val fieldName: String,
    val valueJson: String,
    val sourcePath: String?,
    val parserVersion: String?,
    val sourceDocumentId: String?,
    val runId: String,
    val extensionId: String,
    val observedAt: Long,
    val sourceFieldJson: String?,
)

data class SourceDocumentAuditUi(
    val id: String,
    val stage: String,
    val method: String,
    val url: String,
    val statusCode: Int?,
    val capturedAt: Long,
    val byteCount: Long,
    val sha256: String,
    val bodyEncoding: String,
    val runId: String,
    val extensionId: String,
    val transport: String,
    val mediaKind: String?,
    val representation: String,
    val responseHeadersJson: String,
    val bodyText: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailContent(
    state: TransactionDetailUiState,
    onNavigateUp: () -> Unit,
    onSave: (TransactionDetailDraft) -> Unit,
    onLoadSourceBody: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var draft by remember(state.selectedCategoryId, state.selectedTagIds, state.userNote) {
        mutableStateOf(
            TransactionDetailDraft(
                categoryId = state.selectedCategoryId,
                tagIds = state.selectedTagIds,
                note = state.userNote,
            ),
        )
    }
    var newTagName by remember { mutableStateOf("") }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<AutoRuleDraft?>(null) }

    val requestExit = {
        if (draft.isDirtyComparedWith(state)) showDiscardDialog = true else onNavigateUp()
    }
    BackHandler(onBack = requestExit)

    val selectedCategory = state.categories.firstOrNull { it.id == draft.categoryId }
    val visibleTags = state.tags + draft.newTagNames.map { name ->
        TagOption(draftTagId(name), name, defaultCategoryColors.first())
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("放棄變更？") },
            text = { Text("你尚未儲存分類、標籤或備註的變更。") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("繼續編輯") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false; onNavigateUp() }) { Text("放棄變更") }
            },
        )
    }
    if (showCategoryPicker) {
        CategoryPickerSheet(
            categories = state.categories,
            selectedCategoryId = draft.categoryId,
            amount = state.amount,
            description = state.description,
            accountKind = state.accountKind,
            extensionId = state.extensionId,
            currentRule = draft.matchingRule,
            onDismiss = { showCategoryPicker = false },
            onSelectCategory = { categoryId ->
                draft = draft.copy(
                    categoryId = categoryId,
                    matchingRule = draft.matchingRule?.copy(categoryId = categoryId),
                )
            },
            onRuleChange = { rule -> draft = draft.copy(matchingRule = rule?.copy(categoryId = draft.categoryId)) },
            onEditRule = {
                editingRule = draft.matchingRule ?: defaultExactDescriptionRule(
                    description = state.description,
                    categoryId = draft.categoryId,
                    amountSign = state.amount.toAutoRuleAmountSign(),
                    accountKind = state.accountKind,
                    extensionId = state.extensionId,
                )
            },
        )
    }
    editingRule?.let { rule ->
        AutoRuleEditorDialog(
            initial = rule,
            categories = state.categories,
            tags = visibleTags,
            accounts = emptyList(),
            onDismiss = { editingRule = null },
            onSave = { edited ->
                draft = draft.copy(matchingRule = edited.copy(categoryId = draft.categoryId))
                editingRule = null
            },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("交易明細") },
                navigationIcon = {
                    IconButton(onClick = requestExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            DetailSaveBar(
                enabled = !state.isSaving,
                onCancel = requestExit,
                onSave = { onSave(draft.copy(note = draft.note.trim())) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TransactionSummary(
                state = state,
                category = selectedCategory,
                onCategoryClick = { showCategoryPicker = true },
            )
            ReadOnlyFacts(state)
            TraceabilitySections(state, onLoadSourceBody)
            HorizontalDivider()
            Text("標籤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                visibleTags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in draft.tagIds,
                        onClick = { draft = draft.copy(tagIds = draft.tagIds.toggle(tag.id)) },
                        label = { Text(tag.name) },
                        leadingIcon = { ColorDot(tag.color) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = { newTagName = it },
                    label = { Text("建立標籤") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        val name = newTagName.trim()
                        val existing = visibleTags.firstOrNull { it.name.equals(name, ignoreCase = true) }
                        draft = if (existing == null) {
                            draft.copy(
                                newTagNames = draft.newTagNames + name,
                                tagIds = draft.tagIds + draftTagId(name),
                            )
                        } else draft.copy(tagIds = draft.tagIds + existing.id)
                        newTagName = ""
                    },
                    enabled = newTagName.trim().isNotEmpty(),
                ) { Icon(Icons.Default.Add, contentDescription = "新增草稿標籤") }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = draft.note,
                onValueChange = { draft = draft.copy(note = it) },
                label = { Text("備註") },
                placeholder = { Text("加入自己的備註") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.isManualOverride) {
                TextButton(onClick = { draft = draft.copy(resumeAutomatic = true) }) {
                    Text(if (draft.resumeAutomatic) "已在儲存時恢復自動分類" else "恢復自動分類")
                }
            }
        }
    }
}

@Composable
private fun TraceabilitySections(
    state: TransactionDetailUiState,
    onLoadSourceBody: (String) -> Unit,
) {
    var fieldsExpanded by remember { mutableStateOf(false) }
    var timelineExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        TextButton(onClick = { fieldsExpanded = !fieldsExpanded }) {
            Text(if (fieldsExpanded) "收合來源欄位" else "來源欄位（${state.sourceFields.size}）")
        }
        if (fieldsExpanded) {
            state.sourceFields.forEach { field ->
                ReadOnlyFact(
                    field.fieldName,
                    buildString {
                        append(field.valueJson)
                        field.sourcePath?.let { append("\n來源：").append(it) }
                        field.sourceDocumentId?.let { append("\n文件：").append(it) }
                        field.parserVersion?.let { append("\nParser：").append(it) }
                        append("\nrun：").append(field.runId)
                        append("\nextension：").append(field.extensionId)
                        append("\nobservedAt：").append(field.observedAt)
                        field.sourceFieldJson?.let { append("\nSourceField：").append(it) }
                    },
                )
            }
            state.sourceDocuments.forEach { document ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("${document.stage} · ${document.transport} · ${document.method} · HTTP ${document.statusCode ?: "不可取得"}")
                        Text(document.url, style = MaterialTheme.typography.bodySmall)
                        Text("文件 ${document.id} · run ${document.runId} · ${document.extensionId}")
                        Text("${document.capturedAt} · ${document.mediaKind ?: "unknown"} · ${document.bodyEncoding} · ${document.representation}")
                        Text("Response headers：${document.responseHeadersJson}", style = MaterialTheme.typography.bodySmall)
                        Text("${document.byteCount} bytes · SHA-256 ${document.sha256}")
                        if (document.bodyText == null) {
                            TextButton(onClick = { onLoadSourceBody(document.id) }) {
                                Text("載入 authenticated response 預覽（完整封存保留）")
                            }
                        } else {
                            SelectionContainer {
                                Text(document.bodyText, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick = { timelineExpanded = !timelineExpanded }) {
            Text(if (timelineExpanded) "收合稽核時間線" else "稽核時間線（${state.auditTimeline.size}）")
        }
        if (timelineExpanded) {
            state.auditTimeline.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun TransactionSummary(
    state: TransactionDetailUiState,
    category: CategoryOption?,
    onCategoryClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier.size(68.dp).background(Color(category?.color ?: 0xFF607D8B), CircleShape).clickable(onClick = onCategoryClick),
                contentAlignment = Alignment.Center,
            ) { Text(category?.emoji ?: "🏷️", style = MaterialTheme.typography.headlineMedium) }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        category?.name ?: "尚未分類",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    globalCreditCardTransactionStatus(state.accountKind, state.status)?.let { status ->
                        CreditCardTransactionStatusChip(status)
                    }
                }
                Text(state.amountText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            IconButton(onClick = onCategoryClick) { Icon(Icons.Default.Edit, contentDescription = "更改分類") }
        }
    }
}

@Composable
private fun ReadOnlyFacts(state: TransactionDetailUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ReadOnlyFact("帳戶", state.accountName)
        state.cardDisplayLabel?.takeIf(String::isNotBlank)?.let { ReadOnlyFact("信用卡", it) }
        ReadOnlyFact("交易日", state.transactionDate)
        state.postingDate?.takeIf(String::isNotBlank)
            ?.takeIf { globalCreditCardTransactionStatus(state.accountKind, state.status) == GlobalCreditCardTransactionStatus.POSTED }
            ?.let { ReadOnlyFact("入帳日", it) }
        ReadOnlyFact("明細描述", state.description.ifBlank { "未提供交易說明" })
        state.bankMemo?.takeIf(String::isNotBlank)?.let { ReadOnlyFact("銀行備註", it) }
    }
}

@Composable private fun ReadOnlyFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DetailSaveBar(enabled: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
    ) {
        TextButton(onClick = onCancel) { Text("取消") }
        Button(onClick = onSave, enabled = enabled) { Text("儲存") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPickerSheet(
    categories: List<CategoryOption>,
    selectedCategoryId: String?,
    amount: Double,
    description: String,
    accountKind: AssetKind,
    extensionId: String,
    currentRule: AutoRuleDraft?,
    onDismiss: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    onRuleChange: (AutoRuleDraft?) -> Unit,
    onEditRule: () -> Unit,
) {
    var kind by remember(selectedCategoryId) {
        mutableStateOf(categories.firstOrNull { it.id == selectedCategoryId }?.reportingGroup ?: allowedKinds(amount).first())
    }
    val appliesToMatches = currentRule != null
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("更改分類", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            ApplyScopeRow(
                appliesToMatches = appliesToMatches,
                editEnabled = appliesToMatches,
                onCurrentOnly = { onRuleChange(null) },
                onSameDescription = {
                    onRuleChange(
                        currentRule ?: defaultExactDescriptionRule(
                            description = description,
                            categoryId = selectedCategoryId,
                            amountSign = amount.toAutoRuleAmountSign(),
                            accountKind = accountKind,
                            extensionId = extensionId,
                        ),
                    )
                },
                onEditRule = onEditRule,
            )
            HorizontalDivider()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryReportingGroup.entries.forEach { item ->
                    FilterChip(
                        selected = kind == item,
                        onClick = { kind = item },
                        enabled = item in allowedKinds(amount),
                        label = { Text(item.toDisplayName()) },
                    )
                }
            }
            if (kind in allowedKinds(amount)) {
                CategoryGrid(
                    categories = categories.filter { it.reportingGroup == kind },
                    selectedCategoryId = selectedCategoryId,
                    onSelect = onSelectCategory,
                )
            }
        }
    }
}

@Composable
private fun ApplyScopeRow(
    appliesToMatches: Boolean,
    editEnabled: Boolean,
    onCurrentOnly: () -> Unit,
    onSameDescription: () -> Unit,
    onEditRule: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(
            selected = !appliesToMatches,
            onClick = onCurrentOnly,
            label = { Text("套用這筆明細") },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = appliesToMatches,
                onClick = onSameDescription,
                label = { Text("套用過去及未來的相同明細") },
            )
            Spacer(Modifier.weight(1f))
            FilterChip(
                selected = appliesToMatches,
                onClick = onEditRule,
                enabled = editEnabled,
                label = { Text("編輯規則") },
            )
        }
    }
}

@Composable
private fun CategoryGrid(categories: List<CategoryOption>, selectedCategoryId: String?, onSelect: (String?) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CategoryTile(
            emoji = UNCATEGORIZED_EMOJI,
            name = "尚未分類",
            color = UNCATEGORIZED_COLOR,
            selected = selectedCategoryId == null,
            onClick = { onSelect(null) },
        )
        categories.forEach { category ->
            CategoryTile(
                emoji = category.emoji,
                name = category.name,
                color = category.color,
                selected = category.id == selectedCategoryId,
                onClick = { onSelect(category.id) },
            )
        }
    }
    if (categories.isEmpty()) {
        Text("尚無此類別，請先到設定新增。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CategoryTile(
    emoji: String,
    name: String,
    color: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.size(width = 78.dp, height = 98.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(56.dp).background(Color(color), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
internal fun ColorDot(color: Long) {
    Box(modifier = Modifier.padding(end = 2.dp).background(Color(color), CircleShape).then(Modifier.padding(4.dp)))
}

internal fun allowedKinds(amount: Double): Set<CategoryReportingGroup> = when {
    amount > 0.0 -> setOf(CategoryReportingGroup.INCOME, CategoryReportingGroup.EXCLUDED)
    amount < 0.0 -> setOf(CategoryReportingGroup.EXPENSE, CategoryReportingGroup.EXCLUDED)
    else -> setOf(CategoryReportingGroup.EXCLUDED)
}

internal const val UNCATEGORIZED_EMOJI = "🏷️"
private const val UNCATEGORIZED_COLOR = 0xFF607D8B

internal fun defaultExactDescriptionRule(
    description: String,
    categoryId: String?,
    amountSign: AutoCategoryRuleAmountSign = AutoCategoryRuleAmountSign.ANY,
    accountKind: AssetKind? = null,
    extensionId: String? = null,
): AutoRuleDraft = AutoRuleDraft(
    name = "${description.trim().take(24).ifBlank { "相同明細" }}分類",
    descriptionContains = description.trim(),
    descriptionMatchMode = AutoCategoryRuleDescriptionMatchMode.EXACT,
    amountSign = amountSign,
    accountKind = accountKind,
    extensionId = extensionId,
    origin = AutoCategoryRuleOrigin.USER_CONFIRMED,
    action = AutoCategoryRuleAction.AUTO_APPLY,
    createExactDescriptionCondition = true,
    categoryId = categoryId,
    applyExisting = true,
)

private fun Double.toAutoRuleAmountSign(): AutoCategoryRuleAmountSign = when {
    this > 0.0 -> AutoCategoryRuleAmountSign.POSITIVE
    this < 0.0 -> AutoCategoryRuleAmountSign.NEGATIVE
    else -> AutoCategoryRuleAmountSign.ANY
}

internal fun draftTagId(name: String): String = "draft:$name"
private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id
