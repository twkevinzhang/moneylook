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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagementContent(
    categories: List<CategoryOption>,
    onNavigateUp: () -> Unit,
    onSave: (id: String?, name: String, color: Long) -> Unit,
    onDelete: (String) -> Unit,
) {
    ClassificationManagementScaffold(
        title = "分類管理",
        items = categories.map { ManagedItem(it.id, it.name, it.color) },
        onNavigateUp = onNavigateUp,
        onSave = onSave,
        onDelete = onDelete,
    )
}

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

private data class ManagedItem(val id: String, val name: String, val color: Long)

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
                        ColorDot(item.color)
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
