package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class TransactionDetailUiState(
    val title: String,
    val amountText: String,
    val accountName: String,
    val transactionDate: String,
    val postingDate: String?,
    val description: String,
    val bankMemo: String?,
    val selectedCategoryId: String?,
    val selectedTagIds: Set<String>,
    val userNote: String,
    val categories: List<CategoryOption>,
    val tags: List<TagOption>,
    val isManualOverride: Boolean = false,
    val isSaving: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailContent(
    state: TransactionDetailUiState,
    onNavigateUp: () -> Unit,
    onSave: (categoryId: String?, tagIds: Set<String>, userNote: String) -> Unit,
    onCreateTag: (String) -> Unit,
    onResumeAutomatic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var categoryId by remember(state.selectedCategoryId) { mutableStateOf(state.selectedCategoryId) }
    var tagIds by remember(state.selectedTagIds) { mutableStateOf(state.selectedTagIds) }
    var userNote by remember(state.userNote) { mutableStateOf(state.userNote) }
    var newTag by remember { mutableStateOf("") }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("交易明細") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            SurfaceSaveBar(
                enabled = !state.isSaving,
                onSave = { onSave(categoryId, tagIds, userNote.trim()) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            TransactionSummary(state)
            ReadOnlyFacts(state)
            HorizontalDivider()
            Text("分類", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = categoryId == null, onClick = { categoryId = null }, label = { Text("尚未分類") })
                state.categories.forEach { category ->
                    FilterChip(
                        selected = categoryId == category.id,
                        onClick = { categoryId = category.id },
                        label = { Text(category.name) },
                        leadingIcon = { ColorDot(category.color) },
                    )
                }
            }
            HorizontalDivider()
            Text("標籤", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.tags.forEach { tag ->
                    FilterChip(
                        selected = tag.id in tagIds,
                        onClick = { tagIds = tagIds.toggle(tag.id) },
                        label = { Text(tag.name) },
                        leadingIcon = { ColorDot(tag.color) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = newTag,
                    onValueChange = { newTag = it },
                    label = { Text("建立標籤") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { newTag.trim().takeIf(String::isNotEmpty)?.let(onCreateTag); newTag = "" },
                    enabled = newTag.isNotBlank(),
                ) { Icon(Icons.Default.Add, contentDescription = "新增標籤") }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = userNote,
                onValueChange = { userNote = it },
                label = { Text("備註") },
                placeholder = { Text("加入自己的備註") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.isManualOverride) {
                OutlinedButton(onClick = onResumeAutomatic, modifier = Modifier.fillMaxWidth()) {
                    Text("恢復自動分類")
                }
            }
        }
    }
}

@Composable
private fun TransactionSummary(state: TransactionDetailUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.title.ifBlank { "交易" }, style = MaterialTheme.typography.titleMedium)
            Text(state.amountText, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ReadOnlyFacts(state: TransactionDetailUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ReadOnlyFact("帳戶", state.accountName)
        ReadOnlyFact("交易日", state.transactionDate)
        state.postingDate?.takeIf(String::isNotBlank)?.let { ReadOnlyFact("入帳日", it) }
        ReadOnlyFact("明細描述", state.description.ifBlank { "未提供交易說明" })
        state.bankMemo?.takeIf(String::isNotBlank)?.let { ReadOnlyFact("銀行備註", it) }
    }
}

@Composable
private fun ReadOnlyFact(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SurfaceSaveBar(enabled: Boolean, onSave: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.End,
    ) { Button(onClick = onSave, enabled = enabled) { Text("儲存") } }
}

@Composable
internal fun ColorDot(color: Long) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .padding(end = 2.dp)
            .background(Color(color), CircleShape)
            .then(Modifier.padding(4.dp)),
    )
}

private fun Set<String>.toggle(id: String): Set<String> =
    if (id in this) this - id else this + id
