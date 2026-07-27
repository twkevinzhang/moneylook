package tw.kevinzhang.moneylook.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class CsvTransferTarget {
    AUTO_RULES,
    CREDENTIALS,
}

enum class CsvTransferOperation {
    IMPORT,
    EXPORT,
}

sealed interface CsvTransferStatus {
    data object Idle : CsvTransferStatus

    data class InProgress(
        val target: CsvTransferTarget,
        val operation: CsvTransferOperation,
    ) : CsvTransferStatus

    data class Success(
        val target: CsvTransferTarget,
        val operation: CsvTransferOperation,
        val message: String,
    ) : CsvTransferStatus

    data class Failure(
        val target: CsvTransferTarget,
        val operation: CsvTransferOperation,
        val message: String,
    ) : CsvTransferStatus
}

data class CsvImportPreviewUiState(
    val target: CsvTransferTarget,
    val fileName: String,
    val newCount: Int,
    val overwriteCount: Int,
    val skippedCount: Int,
    val errorCount: Int,
    val errorSummary: String? = null,
) {
    val canConfirm: Boolean
        get() = errorCount == 0
}

data class DataTransferUiState(
    val status: CsvTransferStatus = CsvTransferStatus.Idle,
    val importPreview: CsvImportPreviewUiState? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataTransferScreen(
    state: DataTransferUiState = DataTransferUiState(),
    onNavigateUp: () -> Unit = {},
    onImportAutoRules: () -> Unit = {},
    onExportAutoRules: () -> Unit = {},
    onImportCredentials: () -> Unit = {},
    onExportCredentials: () -> Unit = {},
    onConfirmImport: () -> Unit = {},
    onDismissImportPreview: () -> Unit = {},
    onDismissStatus: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("資料匯入與匯出") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回設定")
                    }
                },
            )
        },
    ) { innerPadding ->
        DataTransferContent(
            state = state,
            onImportAutoRules = onImportAutoRules,
            onExportAutoRules = onExportAutoRules,
            onImportCredentials = onImportCredentials,
            onExportCredentials = onExportCredentials,
            onConfirmImport = onConfirmImport,
            onDismissImportPreview = onDismissImportPreview,
            onDismissStatus = onDismissStatus,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
fun DataTransferContent(
    state: DataTransferUiState,
    onImportAutoRules: () -> Unit,
    onExportAutoRules: () -> Unit,
    onImportCredentials: () -> Unit,
    onExportCredentials: () -> Unit,
    onConfirmImport: () -> Unit,
    onDismissImportPreview: () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "將資料備份成 CSV，或從另一台手機匯入。匯入前會先顯示變更預覽。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TransferCard(
                target = CsvTransferTarget.AUTO_RULES,
                title = "自動化分類規則.csv",
                description = "完整條件、分類、標籤與規則順序",
                icon = Icons.AutoMirrored.Filled.Rule,
                status = state.status,
                onImport = onImportAutoRules,
                onExport = onExportAutoRules,
            )

            TransferCard(
                target = CsvTransferTarget.CREDENTIALS,
                title = "帳號密碼.csv",
                description = "擴充登入欄位與排程設定",
                icon = Icons.Default.Lock,
                status = state.status,
                warning = "密碼會以明碼寫入 CSV。請妥善保管檔案，使用後立即移至安全位置或刪除。",
                onImport = onImportCredentials,
                onExport = onExportCredentials,
            )
        }

        state.importPreview?.let { preview ->
            ImportPreviewDialog(
                preview = preview,
                onConfirm = onConfirmImport,
                onDismiss = onDismissImportPreview,
            )
        } ?: when (val status = state.status) {
            is CsvTransferStatus.Success -> StatusDialog(
                title = operationTitle(status.target, status.operation, succeeded = true),
                message = status.message,
                isError = false,
                onDismiss = onDismissStatus,
            )
            is CsvTransferStatus.Failure -> StatusDialog(
                title = operationTitle(status.target, status.operation, succeeded = false),
                message = status.message,
                isError = true,
                onDismiss = onDismissStatus,
            )
            CsvTransferStatus.Idle,
            is CsvTransferStatus.InProgress,
            -> Unit
        }
    }
}

@Composable
private fun TransferCard(
    target: CsvTransferTarget,
    title: String,
    description: String,
    icon: ImageVector,
    status: CsvTransferStatus,
    warning: String? = null,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    val inProgress = status as? CsvTransferStatus.InProgress
    val isBusy = inProgress != null
    val isThisCardBusy = inProgress?.target == target

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title 資料卡" },
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            warning?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "明碼密碼安全警告" },
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (isThisCardBusy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "${target.displayName()}正在${inProgress.operation.displayName()}"
                        },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = "正在${inProgress.operation.displayName()}，請稍候…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onImport,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "匯入${target.displayName()} CSV"
                        },
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null)
                    Text("匯入 CSV", modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    onClick = onExport,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "匯出${target.displayName()} CSV"
                        },
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Text("匯出 CSV", modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: CsvImportPreviewUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("匯入預覽：${preview.target.displayName()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = preview.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PreviewCountRow("新增", preview.newCount)
                PreviewCountRow("覆蓋", preview.overwriteCount)
                PreviewCountRow("略過", preview.skippedCount)
                PreviewCountRow("錯誤", preview.errorCount, isError = preview.errorCount > 0)
                preview.errorSummary?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (preview.target == CsvTransferTarget.CREDENTIALS) {
                    Text(
                        text = "確認後只會儲存登入資料與排程，不會立即登入或同步銀行。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = preview.canConfirm,
                modifier = Modifier.semantics {
                    contentDescription = "確認匯入${preview.target.displayName()}"
                },
            ) {
                Text("確認匯入")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "取消匯入預覽" },
            ) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun PreviewCountRow(label: String, count: Int, isError: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text(
            text = count.toString(),
            fontWeight = FontWeight.SemiBold,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun StatusDialog(
    title: String,
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (isError) {
            { Icon(Icons.Default.ErrorOutline, contentDescription = null) }
        } else {
            null
        },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "關閉操作結果" },
            ) {
                Text("完成")
            }
        },
    )
}

private fun CsvTransferTarget.displayName(): String = when (this) {
    CsvTransferTarget.AUTO_RULES -> "自動化分類規則"
    CsvTransferTarget.CREDENTIALS -> "帳號密碼"
}

private fun CsvTransferOperation.displayName(): String = when (this) {
    CsvTransferOperation.IMPORT -> "匯入"
    CsvTransferOperation.EXPORT -> "匯出"
}

private fun operationTitle(
    target: CsvTransferTarget,
    operation: CsvTransferOperation,
    succeeded: Boolean,
): String = "${target.displayName()}${operation.displayName()}${if (succeeded) "完成" else "失敗"}"
