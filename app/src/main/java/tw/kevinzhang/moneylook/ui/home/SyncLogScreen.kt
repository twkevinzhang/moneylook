package tw.kevinzhang.moneylook.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.SyncDiagnosticDao
import tw.kevinzhang.core.data.db.IngestionProvenanceDao
import tw.kevinzhang.core.data.db.SourceDocumentSummary
import tw.kevinzhang.core.data.model.IngestionRun
import tw.kevinzhang.core.data.model.SyncDiagnostic
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SyncLogViewModel @Inject constructor(
    private val diagnosticDao: SyncDiagnosticDao,
    private val provenanceDao: IngestionProvenanceDao,
) : ViewModel() {
    fun diagnostics(extensionId: String) = diagnosticDao.observeByExtension(extensionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _runs = MutableStateFlow<List<IngestionRun>>(emptyList())
    val runs: StateFlow<List<IngestionRun>> = _runs
    private val _documents = MutableStateFlow<Map<String, List<SourceDocumentSummary>>>(emptyMap())
    val documents: StateFlow<Map<String, List<SourceDocumentSummary>>> = _documents
    private val _bodies = MutableStateFlow<Map<String, String>>(emptyMap())
    val bodies: StateFlow<Map<String, String>> = _bodies

    fun loadRuns(extensionId: String) {
        viewModelScope.launch { _runs.value = provenanceDao.getRecentRuns(extensionId) }
    }

    fun loadDocuments(runId: String) {
        if (runId in _documents.value) return
        viewModelScope.launch {
            _documents.update { it + (runId to provenanceDao.getSourceDocumentsForRun(runId)) }
        }
    }

    fun loadBody(documentId: String) {
        if (documentId in _bodies.value) return
        viewModelScope.launch {
            val document = provenanceDao.getSourceDocument(documentId) ?: return@launch
            _bodies.update {
                it + (documentId to tw.kevinzhang.moneylook.sync.readArchivedSourceBodyPreview(document))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogScreen(extensionId: String, onNavigateUp: () -> Unit, viewModel: SyncLogViewModel = hiltViewModel()) {
    val entries by viewModel.diagnostics(extensionId).collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val bodies by viewModel.bodies.collectAsStateWithLifecycle()
    LaunchedEffect(extensionId) { viewModel.loadRuns(extensionId) }
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
        if (entries.isEmpty() && runs.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text("尚無可顯示的同步診斷紀錄")
            }
        } else LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(runs, key = { "run-${it.id}" }) { run ->
                IngestionRunCard(
                    run = run,
                    documents = documents[run.id],
                    bodies = bodies,
                    onLoadDocuments = viewModel::loadDocuments,
                    onLoadBody = viewModel::loadBody,
                )
            }
            items(entries, key = { "diagnostic-${it.id}" }) { entry -> SyncDiagnosticCard(entry) }
        }
    }
}

@Composable
private fun IngestionRunCard(
    run: IngestionRun,
    documents: List<SourceDocumentSummary>?,
    bodies: Map<String, String>,
    onLoadDocuments: (String) -> Unit,
    onLoadBody: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${run.status} · ${run.trigger}", style = MaterialTheme.typography.titleSmall)
            Text("run ${run.id} · ${run.extensionId} v${run.extensionVersion}", style = MaterialTheme.typography.bodySmall)
            Text("started ${run.startedAt} · completed ${run.completedAt}", style = MaterialTheme.typography.bodySmall)
            Text(
                if (documents == null) "載入此 run 的來源文件" else "來源文件：${documents.size}",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onLoadDocuments(run.id) },
            )
            documents.orEmpty().forEach { document ->
                Text("${document.stage} · ${document.representation} · HTTP ${document.statusCode ?: "不可取得"}")
                Text("${document.id} · ${document.capturedAt} · ${document.bodyByteCount} bytes", style = MaterialTheme.typography.bodySmall)
                Text("headers ${document.responseHeadersJson}", style = MaterialTheme.typography.bodySmall)
                val body = bodies[document.id]
                if (body == null) {
                    Text(
                        "載入 authenticated response 預覽（完整封存保留）",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onLoadBody(document.id) },
                    )
                } else {
                    SelectionContainer { Text(body, style = MaterialTheme.typography.bodySmall) }
                }
            }
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
