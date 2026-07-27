package tw.kevinzhang.moneylook.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.kevinzhang.moneylook.security.authenticateDevice
import tw.kevinzhang.moneylook.security.findFragmentActivity

@Composable
fun DataTransferRoute(
    onNavigateUp: () -> Unit,
    viewModel: DataTransferViewModel = hiltViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    var pendingExportTarget by remember { mutableStateOf<CsvTransferTarget?>(null) }
    var pendingImportTarget by remember { mutableStateOf<CsvTransferTarget?>(null) }
    var showCredentialExportWarning by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val target = pendingExportTarget
        pendingExportTarget = null
        if (uri != null && target != null) viewModel.export(target, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val target = pendingImportTarget
        pendingImportTarget = null
        if (uri != null && target != null) viewModel.prepareImport(target, uri)
    }

    fun launchImport(target: CsvTransferTarget) {
        pendingImportTarget = target
        importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain"))
    }

    fun authenticateCredentialAction(
        operation: CsvTransferOperation,
        onAuthenticated: () -> Unit,
    ) {
        val host = activity
        if (host == null) {
            viewModel.reportAuthenticationFailure(operation, "無法開啟裝置驗證")
            return
        }
        host.authenticateDevice(
            title = when (operation) {
                CsvTransferOperation.IMPORT -> "匯入明碼帳號密碼"
                CsvTransferOperation.EXPORT -> "匯出明碼帳號密碼"
            },
            subtitle = "請先驗證身分以保護銀行登入資料",
            onAuthenticated = onAuthenticated,
            onError = { message ->
                viewModel.reportAuthenticationFailure(operation, message)
            },
        )
    }

    DataTransferScreen(
        state = state,
        onNavigateUp = onNavigateUp,
        onImportAutoRules = { launchImport(CsvTransferTarget.AUTO_RULES) },
        onExportAutoRules = {
            pendingExportTarget = CsvTransferTarget.AUTO_RULES
            exportLauncher.launch("自動化分類規則.csv")
        },
        onImportCredentials = {
            authenticateCredentialAction(CsvTransferOperation.IMPORT) {
                launchImport(CsvTransferTarget.CREDENTIALS)
            }
        },
        onExportCredentials = {
            showCredentialExportWarning = true
        },
        onConfirmImport = viewModel::confirmImport,
        onDismissImportPreview = viewModel::dismissImportPreview,
        onDismissStatus = viewModel::dismissStatus,
    )

    if (showCredentialExportWarning) {
        AlertDialog(
            onDismissRequest = { showCredentialExportWarning = false },
            title = { Text("匯出明碼帳號密碼？") },
            text = {
                Text(
                    "CSV 會包含所有擴充登入欄位的完整明碼值。任何可讀取該檔案的人或 App " +
                        "都可能取得銀行登入資料；請只儲存在你信任的位置，使用後立即刪除。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCredentialExportWarning = false
                        authenticateCredentialAction(CsvTransferOperation.EXPORT) {
                            pendingExportTarget = CsvTransferTarget.CREDENTIALS
                            exportLauncher.launch("帳號密碼.csv")
                        }
                    },
                ) {
                    Text("驗證並繼續")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCredentialExportWarning = false }) {
                    Text("取消")
                }
            },
        )
    }
}
