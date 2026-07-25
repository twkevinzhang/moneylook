package tw.kevinzhang.moneylook.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import tw.kevinzhang.core.data.db.SyncDiagnosticDao
import tw.kevinzhang.core.data.model.SyncDiagnostic
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SyncLogViewModel @Inject constructor(private val diagnosticDao: SyncDiagnosticDao) : ViewModel() {
    fun diagnostics(extensionId: String) = diagnosticDao.observeByExtension(extensionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogScreen(extensionId: String, onNavigateUp: () -> Unit, viewModel: SyncLogViewModel = hiltViewModel()) {
    val entries by viewModel.diagnostics(extensionId).collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("同步紀錄") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("尚無可顯示的同步診斷紀錄")
            }
        } else LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.id }) { entry -> SyncDiagnosticCard(entry) }
        }
    }
}

@Composable
private fun SyncDiagnosticCard(entry: SyncDiagnostic) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(DateFormat.getDateTimeInstance().format(Date(entry.createdAt)), style = MaterialTheme.typography.labelMedium)
            Text(diagnosticLabel(entry.category), style = MaterialTheme.typography.titleSmall)
            entry.code?.let { Text("代碼：$it", style = MaterialTheme.typography.bodySmall) }
            entry.scriptFrame?.let { Text("腳本位置：$it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun diagnosticLabel(category: String): String = when (category) {
    "SYNC_RESULT" -> "同步結果"
    "SCRIPT_ERROR" -> "擴充腳本執行失敗"
    "RUNTIME_ERROR" -> "同步執行失敗"
    "PARTIAL_KIND" -> "部分產品同步失敗"
    "PARTIAL_HISTORY" -> "交易歷史尚未完成"
    else -> "同步失敗"
}
