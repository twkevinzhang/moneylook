package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import tw.kevinzhang.core.data.model.CategoryReportingGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagementContent(
    categories: List<CategoryOption>,
    onNavigateUp: () -> Unit,
    onSave: (id: String?, name: String, color: Long, reportingGroup: CategoryReportingGroup) -> Unit,
    onDelete: (String) -> Unit,
) {
    var activeGroup by remember { mutableStateOf(CategoryReportingGroup.EXPENSE) }
    var editor by remember { mutableStateOf<CategoryOption?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<CategoryOption?>(null) }
    var pendingGroupChange by remember { mutableStateOf<CategorySave?>(null) }
    val visibleCategories = categories.filter { it.reportingGroup == activeGroup }

    if (isAdding || editor != null) {
        CategoryEditorDialog(
            initial = editor,
            initialGroup = if (editor == null) activeGroup else requireNotNull(editor).reportingGroup,
            onDismiss = { isAdding = false; editor = null },
            onSave = { name, color, group ->
                val save = CategorySave(editor?.id, name, color, group, editor?.reportingGroup, editor?.name)
                if (save.previousGroup != null && save.previousGroup != group) {
                    pendingGroupChange = save
                    isAdding = false
                    editor = null
                }
                else {
                    onSave(save.id, save.name, save.color, save.group)
                    isAdding = false
                    editor = null
                }
            },
        )
    }
    pendingGroupChange?.let { save ->
        AlertDialog(
            onDismissRequest = { pendingGroupChange = null },
            title = { Text("變更分類歸屬？") },
            text = { Text("這會立刻影響所有使用「${save.previousName.orEmpty()}」交易的收入、支出與不統計結果。") },
            confirmButton = {
                TextButton(onClick = {
                    onSave(save.id, save.name, save.color, save.group)
                    activeGroup = save.group
                    pendingGroupChange = null
                    isAdding = false
                    editor = null
                }) { Text("變更") }
            },
            dismissButton = { TextButton(onClick = { pendingGroupChange = null }) { Text("取消") } },
        )
    }
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("刪除${item.name}？") },
            text = { Text("已使用這個分類的交易將不再顯示它。") },
            confirmButton = { TextButton(onClick = { onDelete(item.id); deleteTarget = null }) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("分類管理") }, navigationIcon = { BackButton(onNavigateUp) }) },
        floatingActionButton = { FloatingActionButton(onClick = { isAdding = true }) { Icon(Icons.Default.Add, "新增分類") } },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = CategoryReportingGroup.entries.indexOf(activeGroup)) {
                CategoryReportingGroup.entries.forEach { group ->
                    Tab(
                        selected = group == activeGroup,
                        onClick = { activeGroup = group },
                        text = { Text(group.toDisplayName()) },
                    )
                }
            }
            if (visibleCategories.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { Text("尚未建立${activeGroup.toDisplayName()}分類", style = MaterialTheme.typography.bodyLarge) }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visibleCategories, key = CategoryOption::id) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(item.emoji, style = MaterialTheme.typography.titleLarge)
                            Text(item.name, modifier = Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
                            IconButton(onClick = { editor = item }) { Icon(Icons.Default.Edit, "編輯") }
                            IconButton(onClick = { deleteTarget = item }) { Icon(Icons.Default.Delete, "刪除") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

private data class CategorySave(
    val id: String?,
    val name: String,
    val color: Long,
    val group: CategoryReportingGroup,
    val previousGroup: CategoryReportingGroup?,
    val previousName: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManagementContent(
    tags: List<TagOption>,
    onNavigateUp: () -> Unit,
    onSave: (id: String?, name: String, color: Long) -> Unit,
    onDelete: (String) -> Unit,
) {
    ClassificationManagementScaffold(
        title = "標籤管理",
        items = tags.map { ManagedItem(it.id, it.name, it.color) },
        onNavigateUp = onNavigateUp,
        onSave = onSave,
        onDelete = onDelete,
    )
}

private data class ManagedItem(val id: String, val name: String, val color: Long, val emoji: String? = null)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClassificationManagementScaffold(
    title: String,
    items: List<ManagedItem>,
    onNavigateUp: () -> Unit,
    onSave: (id: String?, name: String, color: Long) -> Unit,
    onDelete: (String) -> Unit,
) {
    val itemLabel = title.removeSuffix("管理")
    var editor by remember { mutableStateOf<ManagedItem?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ManagedItem?>(null) }
    if (isAdding || editor != null) {
        ManagedItemDialog(
            title = if (editor == null) "新增$itemLabel" else "編輯$itemLabel",
            initial = editor,
            onDismiss = { isAdding = false; editor = null },
            onSave = { name, color -> onSave(editor?.id, name, color); isAdding = false; editor = null },
        )
    }
    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("刪除${item.name}？") },
            text = { Text("已使用這個項目的交易將不再顯示它。") },
            confirmButton = { TextButton(onClick = { onDelete(item.id); deleteTarget = null }) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(title) }, navigationIcon = { BackButton(onNavigateUp) }) },
        floatingActionButton = { FloatingActionButton(onClick = { isAdding = true }) { Icon(Icons.Default.Add, "新增") } },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text("尚未建立$title", style = MaterialTheme.typography.bodyLarge) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(items, key = ManagedItem::id) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item.emoji?.let { Text(it, style = MaterialTheme.typography.titleLarge) } ?: ColorDot(item.color)
                        Text(item.name, modifier = Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { editor = item }) { Icon(Icons.Default.Edit, "編輯") }
                        IconButton(onClick = { deleteTarget = item }) { Icon(Icons.Default.Delete, "刪除") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
internal fun BackButton(onNavigateUp: () -> Unit) {
    IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
}

@Composable
private fun ManagedItemDialog(
    title: String,
    initial: ManagedItem?,
    onDismiss: () -> Unit,
    onSave: (String, Long) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember(initial) { mutableStateOf(initial?.color ?: defaultCategoryColors.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名稱") }, singleLine = true)
                Text("顏色", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    defaultCategoryColors.forEach { option ->
                        androidx.compose.material3.FilterChip(
                            selected = color == option,
                            onClick = { color = option },
                            label = { ColorDot(option) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), color) }, enabled = name.isNotBlank()) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CategoryEditorDialog(
    initial: CategoryOption?,
    initialGroup: CategoryReportingGroup,
    onDismiss: () -> Unit,
    onSave: (name: String, color: Long, group: CategoryReportingGroup) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember(initial) { mutableStateOf(initial?.color ?: defaultCategoryColors.first()) }
    var group by remember(initial, initialGroup) { mutableStateOf(initialGroup) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(if (initial == null) "新增分類" else "編輯分類") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名稱") }, singleLine = true)
                Text("歸屬", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryReportingGroup.entries.forEach { option ->
                        androidx.compose.material3.FilterChip(
                            selected = group == option,
                            onClick = { group = option },
                            label = { Text(option.toDisplayName()) },
                        )
                    }
                }
                Text("顏色", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    defaultCategoryColors.forEach { option ->
                        androidx.compose.material3.FilterChip(
                            selected = color == option,
                            onClick = { color = option },
                            label = { ColorDot(option) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), color, group) }, enabled = name.isNotBlank()) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

internal fun CategoryReportingGroup.toDisplayName(): String = when (this) {
    CategoryReportingGroup.INCOME -> "收入"
    CategoryReportingGroup.EXPENSE -> "支出"
    CategoryReportingGroup.EXCLUDED -> "不統計"
}
