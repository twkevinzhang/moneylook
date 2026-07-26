package tw.kevinzhang.moneylook.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.TextButton
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
import tw.kevinzhang.core.data.db.IngestionRunSummary
import tw.kevinzhang.core.data.db.SourceDocumentSummary
import tw.kevinzhang.core.data.model.IngestionRun
import tw.kevinzhang.core.data.model.SyncDiagnostic
import tw.kevinzhang.moneylook.sync.ArchivedSourceBodyChunk
import tw.kevinzhang.moneylook.sync.ArchivedSourceBodyReader
import tw.kevinzhang.moneylook.sync.MAX_SOURCE_BODY_CHUNK_BYTES
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SyncLogViewModel @Inject constructor(
    private val diagnosticDao: SyncDiagnosticDao,
    private val provenanceDao: IngestionProvenanceDao,
    private val bodyReader: ArchivedSourceBodyReader,
) : ViewModel() {
    private companion object {
        const val RUN_PAGE_SIZE = 20
    }

    fun diagnostics(extensionId: String) = diagnosticDao.observeByExtension(extensionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _runs = MutableStateFlow<List<IngestionRunSummary>>(emptyList())
    val runs: StateFlow<List<IngestionRunSummary>> = _runs
    private val _hasMoreRuns = MutableStateFlow(false)
    val hasMoreRuns: StateFlow<Boolean> = _hasMoreRuns
    private val _loadingRuns = MutableStateFlow(false)
    val loadingRuns: StateFlow<Boolean> = _loadingRuns
    private val _selectedRunId = MutableStateFlow<String?>(null)
    val selectedRunId: StateFlow<String?> = _selectedRunId
    private val _selectedRunDetail = MutableStateFlow<IngestionRun?>(null)
    val selectedRunDetail: StateFlow<IngestionRun?> = _selectedRunDetail
    private val _documents = MutableStateFlow<Map<String, List<SourceDocumentSummary>>>(emptyMap())
    val documents: StateFlow<Map<String, List<SourceDocumentSummary>>> = _documents
    private val _bodyChunks = MutableStateFlow<Map<String, ArchivedSourceBodyChunk>>(emptyMap())
    val bodyChunks: StateFlow<Map<String, ArchivedSourceBodyChunk>> = _bodyChunks
    private val _loadingBodies = MutableStateFlow<Set<String>>(emptySet())
    val loadingBodies: StateFlow<Set<String>> = _loadingBodies
    private var loadedExtensionId: String? = null
    private var runLoadGeneration: Long = 0

    fun loadRuns(extensionId: String) {
        if (loadedExtensionId == extensionId && _runs.value.isNotEmpty()) return
        loadedExtensionId = extensionId
        runLoadGeneration += 1
        _loadingRuns.value = false
        _runs.value = emptyList()
        _hasMoreRuns.value = false
        loadRunPage(reset = true)
    }

    fun loadMoreRuns() = loadRunPage(reset = false)

    private fun loadRunPage(reset: Boolean) {
        val extensionId = loadedExtensionId ?: return
        if (_loadingRuns.value || (!reset && !_hasMoreRuns.value)) return
        val generation = runLoadGeneration
        _loadingRuns.value = true
        viewModelScope.launch {
            try {
                val offset = if (reset) 0 else _runs.value.size
                val page = provenanceDao.getRunSummaries(
                    extensionId = extensionId,
                    limit = RUN_PAGE_SIZE + 1,
                    offset = offset,
                )
                val visiblePage = page.take(RUN_PAGE_SIZE)
                if (generation == runLoadGeneration && extensionId == loadedExtensionId) {
                    _runs.value = if (reset) visiblePage else _runs.value + visiblePage
                    _hasMoreRuns.value = page.size > RUN_PAGE_SIZE
                }
            } finally {
                if (generation == runLoadGeneration) _loadingRuns.value = false
            }
        }
    }

    fun selectRun(runId: String) {
        if (_selectedRunId.value == runId) {
            _selectedRunId.value = null
            _selectedRunDetail.value = null
            _documents.value = emptyMap()
            _bodyChunks.value = emptyMap()
            return
        }
        _selectedRunId.value = runId
        _selectedRunDetail.value = null
        _documents.value = emptyMap()
        _bodyChunks.value = emptyMap()
        viewModelScope.launch {
            val detail = provenanceDao.getIngestionRun(runId)
            val documents = provenanceDao.getSourceDocumentsForRun(runId)
            if (_selectedRunId.value == runId) {
                _selectedRunDetail.value = detail
                _documents.value = mapOf(runId to documents)
            }
        }
    }

    fun loadBody(documentId: String, offset: Long = 0) {
        if (documentId in _loadingBodies.value) return
        _loadingBodies.update { it + documentId }
        viewModelScope.launch {
            val chunk = try {
                val document = provenanceDao.getSourceDocument(documentId) ?: return@launch
                bodyReader.readChunk(document, requestedOffset = offset)
            } finally {
                _loadingBodies.update { it - documentId }
            }
            _bodyChunks.update { it + (documentId to chunk) }
        }
    }

    fun loadPreviousBodyChunk(documentId: String) {
        val current = _bodyChunks.value[documentId] ?: return
        loadBody(documentId, (current.startOffset - MAX_SOURCE_BODY_CHUNK_BYTES).coerceAtLeast(0))
    }

    fun loadNextBodyChunk(documentId: String) {
        val current = _bodyChunks.value[documentId] ?: return
        if (current.hasNext) loadBody(documentId, current.endOffsetExclusive)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncLogScreen(extensionId: String, onNavigateUp: () -> Unit, viewModel: SyncLogViewModel = hiltViewModel()) {
    val entries by viewModel.diagnostics(extensionId).collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val bodyChunks by viewModel.bodyChunks.collectAsStateWithLifecycle()
    val loadingBodies by viewModel.loadingBodies.collectAsStateWithLifecycle()
    val hasMoreRuns by viewModel.hasMoreRuns.collectAsStateWithLifecycle()
    val loadingRuns by viewModel.loadingRuns.collectAsStateWithLifecycle()
    val selectedRunId by viewModel.selectedRunId.collectAsStateWithLifecycle()
    val selectedRunDetail by viewModel.selectedRunDetail.collectAsStateWithLifecycle()
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
                    selected = selectedRunId == run.id,
                    detail = selectedRunDetail?.takeIf { selectedRunId == run.id },
                    documents = documents[run.id],
                    bodyChunks = bodyChunks,
                    loadingBodies = loadingBodies,
                    onSelectRun = viewModel::selectRun,
                    onLoadBody = viewModel::loadBody,
                    onPreviousBodyChunk = viewModel::loadPreviousBodyChunk,
                    onNextBodyChunk = viewModel::loadNextBodyChunk,
                )
            }
            if (hasMoreRuns || loadingRuns) {
                item(key = "load-more-runs") {
                    TextButton(
                        onClick = viewModel::loadMoreRuns,
                        enabled = hasMoreRuns && !loadingRuns,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (loadingRuns) "正在載入…" else "載入更多同步紀錄")
                    }
                }
            }
            items(entries, key = { "diagnostic-${it.id}" }) { entry -> SyncDiagnosticCard(entry) }
        }
    }
}

@Composable
private fun IngestionRunCard(
    run: IngestionRunSummary,
    selected: Boolean,
    detail: IngestionRun?,
    documents: List<SourceDocumentSummary>?,
    bodyChunks: Map<String, ArchivedSourceBodyChunk>,
    loadingBodies: Set<String>,
    onSelectRun: (String) -> Unit,
    onLoadBody: (String, Long) -> Unit,
    onPreviousBodyChunk: (String) -> Unit,
    onNextBodyChunk: (String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${run.status} · ${run.trigger}", style = MaterialTheme.typography.titleSmall)
            Text("run ${run.id} · ${run.extensionId} v${run.extensionVersion}", style = MaterialTheme.typography.bodySmall)
            Text("started ${run.startedAt} · completed ${run.completedAt}", style = MaterialTheme.typography.bodySmall)
            run.failureOrigin?.let { Text("failure origin：$it", style = MaterialTheme.typography.bodySmall) }
            run.failureCode?.let { Text("failure code：$it", style = MaterialTheme.typography.bodySmall) }
            run.failureScriptFrame?.let {
                Text("script frame：$it", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (selected) "收合完整 run 明細" else "載入完整 failure 與來源文件",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onSelectRun(run.id) },
            )
            if (selected && detail == null) {
                Text("正在 lazy load 完整 run…", style = MaterialTheme.typography.bodySmall)
            }
            detail?.failureMessage?.let { RawFailureText("完整錯誤訊息", it) }
            detail?.failureStack?.let { RawFailureText("完整 stack", it) }
            detail?.failureDiagnosticJson?.let { RawFailureText("完整 diagnostic JSON", it) }
            if (selected && documents != null) {
                Text("來源文件：${documents.size}", style = MaterialTheme.typography.labelMedium)
            }
            if (selected) documents.orEmpty().forEach { document ->
                SourceDocumentMetadata(document)
                val chunk = bodyChunks[document.id]
                if (chunk == null) {
                    Text(
                        if (document.id in loadingBodies) {
                            "正在背景驗證並載入第 1 段…"
                        } else {
                            "載入 authenticated response 完整內容（每段最多 256 KiB）"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = document.id !in loadingBodies) {
                            onLoadBody(document.id, 0)
                        },
                    )
                } else {
                    SourceBodyChunk(
                        chunk = chunk,
                        loading = document.id in loadingBodies,
                        onPrevious = { onPreviousBodyChunk(document.id) },
                        onNext = { onNextBodyChunk(document.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceDocumentMetadata(document: SourceDocumentSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(document.stage, style = MaterialTheme.typography.titleSmall)
        Text("method：${document.method}", style = MaterialTheme.typography.bodySmall)
        SelectionContainer { Text("URL：${document.url}", style = MaterialTheme.typography.bodySmall) }
        Text("transport：${document.transport}", style = MaterialTheme.typography.bodySmall)
        Text("media kind：${document.mediaKind ?: "null"}", style = MaterialTheme.typography.bodySmall)
        Text("body encoding：${document.bodyEncoding}", style = MaterialTheme.typography.bodySmall)
        Text("representation：${document.representation}", style = MaterialTheme.typography.bodySmall)
        Text("status：${document.statusCode ?: "不可取得"}", style = MaterialTheme.typography.bodySmall)
        SelectionContainer { Text("SHA-256：${document.bodySha256}", style = MaterialTheme.typography.bodySmall) }
        Text("size：${document.bodyByteCount} bytes", style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            Text("headers：${document.responseHeadersJson}", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "capture time：${document.capturedAt} (${DateFormat.getDateTimeInstance().format(Date(document.capturedAt))})",
            style = MaterialTheme.typography.bodySmall,
        )
        SelectionContainer {
            Text(
                "run：${document.runId} · extension：${document.extensionId} · doc：${document.id}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SourceBodyChunk(
    chunk: ArchivedSourceBodyChunk,
    loading: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val range = if (chunk.byteCount == 0L) {
        "0 / ${chunk.totalBytes} bytes"
    } else {
        "${chunk.startOffset}–${chunk.endOffsetExclusive - 1} / ${chunk.totalBytes} bytes"
    }
    Text(
        "內容範圍：$range · ${if (chunk.integrityVerified) "SHA-256 已驗證" else "驗證失敗"}",
        style = MaterialTheme.typography.labelMedium,
    )
    if (!chunk.integrityVerified) {
        SelectionContainer {
            Text(chunk.renderedBody, style = MaterialTheme.typography.bodySmall)
        }
    } else if (chunk.bodyEncoding == "base64") {
        Text("Exact archived bytes（各段分別 Base64 decode 後依 range 拼接）", style = MaterialTheme.typography.labelMedium)
        SelectionContainer { Text(chunk.exactBytesBase64, style = MaterialTheme.typography.bodySmall) }
    } else {
        Text(
            "便讀文字檢視（多位元字元跨段邊界時可能顯示替代字；不作為 exact evidence）",
            style = MaterialTheme.typography.labelMedium,
        )
        SelectionContainer {
            Text(chunk.renderedBody, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Exact archived bytes（各段分別 Base64 decode 後依 range 拼接）",
            style = MaterialTheme.typography.labelMedium,
        )
        SelectionContainer {
            Text(chunk.exactBytesBase64, style = MaterialTheme.typography.bodySmall)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onPrevious, enabled = chunk.hasPrevious && !loading) {
            Text("上一段")
        }
        Text(if (loading) "正在背景載入…" else "每段最多 256 KiB", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onNext, enabled = chunk.hasNext && !loading) {
            Text("下一段")
        }
    }
}

@Composable
private fun RawFailureText(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelMedium)
    SelectionContainer {
        Text(value, style = MaterialTheme.typography.bodySmall)
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
