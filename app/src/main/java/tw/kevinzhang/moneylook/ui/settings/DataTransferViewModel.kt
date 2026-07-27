package tw.kevinzhang.moneylook.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tw.kevinzhang.moneylook.data.transfer.DataTransferRepository
import tw.kevinzhang.moneylook.data.transfer.PrepareDataImportResult
import tw.kevinzhang.moneylook.data.transfer.PreparedDataImport
import tw.kevinzhang.moneylook.schedule.SchedulerManager

@HiltViewModel
class DataTransferViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: DataTransferRepository,
    private val schedulerManager: SchedulerManager,
) : ViewModel() {
    private val _state = MutableStateFlow(DataTransferUiState())
    val state: StateFlow<DataTransferUiState> = _state.asStateFlow()

    /** Credential values never enter Compose state; only aggregate counts are published. */
    private var pendingImport: PreparedDataImport? = null

    fun prepareImport(target: CsvTransferTarget, uri: Uri) {
        viewModelScope.launch {
            cleanupPendingImport()
            _state.value = DataTransferUiState(
                status = CsvTransferStatus.InProgress(target, CsvTransferOperation.IMPORT),
            )
            var newTransactionCache: File? = null
            try {
                val result = when (target) {
                    CsvTransferTarget.AUTO_RULES -> repository.prepareAutoRules(
                        withContext(Dispatchers.IO) { readCsv(uri) },
                    )
                    CsvTransferTarget.CREDENTIALS -> repository.prepareCredentials(
                        withContext(Dispatchers.IO) { readCsv(uri) },
                    )
                    CsvTransferTarget.TRANSACTIONS -> {
                        val cached = withContext(Dispatchers.IO) { cacheTransactionCsv(uri) }
                        newTransactionCache = cached.file
                        repository.prepareTransactions(cached.file, cached.sha256)
                    }
                }
                when (result) {
                    is PrepareDataImportResult.Failure -> {
                        newTransactionCache?.delete()
                        pendingImport = null
                        _state.value = DataTransferUiState(
                            status = CsvTransferStatus.Failure(
                                target,
                                CsvTransferOperation.IMPORT,
                                "CSV 驗證失敗：${result.reason}",
                            ),
                        )
                    }
                    is PrepareDataImportResult.Success -> {
                        pendingImport = result.value
                        val preview = result.value.preview
                        _state.value = DataTransferUiState(
                            status = CsvTransferStatus.Idle,
                            importPreview = CsvImportPreviewUiState(
                                target = target,
                                fileName = displayName(uri),
                                newCount = preview.newCount,
                                overwriteCount = preview.overwriteCount,
                                skippedCount = preview.skippedCount,
                                errorCount = preview.errors.size,
                                errorSummary = preview.errors.takeIf(List<String>::isNotEmpty)
                                    ?.joinToString("\n"),
                                warningCount = preview.warningCount,
                                warningSummary = preview.warnings
                                    .takeIf(List<String>::isNotEmpty)
                                    ?.joinToString("\n"),
                            ),
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                newTransactionCache?.delete()
                pendingImport = null
                _state.value = DataTransferUiState(
                    status = CsvTransferStatus.Failure(
                        target,
                        CsvTransferOperation.IMPORT,
                        "匯入失敗，資料未變更",
                    ),
                )
            }
        }
    }

    fun confirmImport() {
        val pending = pendingImport ?: return
        val target = when (pending) {
            is PreparedDataImport.AutoRules -> CsvTransferTarget.AUTO_RULES
            is PreparedDataImport.Credentials -> CsvTransferTarget.CREDENTIALS
            is PreparedDataImport.Transactions -> CsvTransferTarget.TRANSACTIONS
        }
        viewModelScope.launch {
            _state.value = DataTransferUiState(
                status = CsvTransferStatus.InProgress(target, CsvTransferOperation.IMPORT),
            )
            try {
                when (pending) {
                    is PreparedDataImport.AutoRules -> repository.commitAutoRules(pending)
                    is PreparedDataImport.Credentials -> {
                        repository.commitCredentials(pending).forEach { profile ->
                            if (profile.scheduleEnabled) {
                                schedulerManager.scheduleProfile(profile)
                            } else {
                                schedulerManager.cancelExtension(profile.extensionId)
                            }
                        }
                    }
                    is PreparedDataImport.Transactions -> repository.commitTransactions(pending)
                }
                _state.value = DataTransferUiState(
                    status = CsvTransferStatus.Success(
                        target,
                        CsvTransferOperation.IMPORT,
                        when (target) {
                            CsvTransferTarget.AUTO_RULES ->
                                "規則已匯入；既有交易不會自動重新分類"
                            CsvTransferTarget.CREDENTIALS ->
                                "帳號密碼與排程已匯入；不會自動執行銀行同步"
                            CsvTransferTarget.TRANSACTIONS ->
                                "交易明細已匯入；不會執行銀行同步或重新分類"
                        },
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                pendingImport = null
                _state.value = DataTransferUiState(
                    status = CsvTransferStatus.Failure(
                        target,
                        CsvTransferOperation.IMPORT,
                        "匯入失敗，資料未變更",
                    ),
                )
            } finally {
                if (pending is PreparedDataImport.Transactions) {
                    pending.cachedFile.delete()
                }
                if (pendingImport === pending) pendingImport = null
            }
        }
    }

    fun export(target: CsvTransferTarget, uri: Uri) {
        viewModelScope.launch {
            _state.value = DataTransferUiState(
                status = CsvTransferStatus.InProgress(target, CsvTransferOperation.EXPORT),
            )
            try {
                when (target) {
                    CsvTransferTarget.AUTO_RULES -> {
                        val csv = repository.exportAutoRules()
                        withContext(Dispatchers.IO) { writeCsv(uri, csv) }
                    }
                    CsvTransferTarget.CREDENTIALS -> {
                        val csv = repository.exportCredentials()
                        withContext(Dispatchers.IO) { writeCsv(uri, csv) }
                    }
                    CsvTransferTarget.TRANSACTIONS -> withContext(Dispatchers.IO) {
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
                            "cannot open output"
                        }.bufferedWriter(Charsets.UTF_8).use { writer ->
                            repository.exportTransactions(writer)
                        }
                    }
                }
                _state.value = DataTransferUiState(
                    status = CsvTransferStatus.Success(
                        target,
                        CsvTransferOperation.EXPORT,
                        when (target) {
                            CsvTransferTarget.AUTO_RULES -> "自動化分類規則 CSV 已匯出"
                            CsvTransferTarget.CREDENTIALS ->
                                "明碼帳號密碼 CSV 已匯出；請妥善保管並在使用後刪除"
                            CsvTransferTarget.TRANSACTIONS ->
                                "未加密交易明細 CSV 已匯出；請妥善保管並在使用後刪除"
                        },
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.value = DataTransferUiState(
                    status = CsvTransferStatus.Failure(
                        target,
                        CsvTransferOperation.EXPORT,
                        "匯出失敗，請確認儲存位置後重試",
                    ),
                )
            }
        }
    }

    fun dismissImportPreview() {
        cleanupPendingImport()
        _state.value = DataTransferUiState()
    }

    fun dismissStatus() {
        _state.value = DataTransferUiState()
    }

    fun reportAuthenticationFailure(
        operation: CsvTransferOperation,
        message: String,
    ) {
        _state.value = DataTransferUiState(
            status = CsvTransferStatus.Failure(
                CsvTransferTarget.CREDENTIALS,
                operation,
                message,
            ),
        )
    }

    private fun readCsv(uri: Uri): String {
        val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
            "cannot open input"
        }
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_IMPORT_BYTES) { "CSV is too large" }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
                .removePrefix("\uFEFF")
        }
    }

    private fun writeCsv(uri: Uri, csv: String) {
        require(csv.toByteArray(Charsets.UTF_8).size <= MAX_EXPORT_BYTES) { "CSV is too large" }
        requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) {
            "cannot open output"
        }.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(csv)
            writer.flush()
        }
    }

    private fun cacheTransactionCsv(uri: Uri): CachedTransactionCsv {
        val directory = File(context.cacheDir, TRANSACTION_CACHE_DIRECTORY)
        require(directory.isDirectory || directory.mkdirs()) { "cannot create import cache" }
        val file = File.createTempFile("transaction-import-", ".csv", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            requireNotNull(context.contentResolver.openInputStream(uri)) {
                "cannot open input"
            }.use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_TRANSACTION_IMPORT_BYTES) {
                            "transaction CSV is too large"
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            return CachedTransactionCsv(
                file = file,
                sha256 = digest.digest().joinToString("") { byte ->
                    "%02x".format(byte.toInt() and 0xff)
                },
            )
        } catch (error: CancellationException) {
            file.delete()
            throw error
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun cleanupPendingImport() {
        (pendingImport as? PreparedDataImport.Transactions)?.cachedFile?.delete()
        pendingImport = null
    }

    override fun onCleared() {
        cleanupPendingImport()
        super.onCleared()
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(0).orEmpty().ifBlank { "CSV 檔案" }
            }
        }
        return "CSV 檔案"
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 1_100_000
        const val MAX_EXPORT_BYTES = 1_100_000
        const val MAX_TRANSACTION_IMPORT_BYTES = 100_000_000L
        const val TRANSACTION_CACHE_DIRECTORY = "data-transfer"
    }

    private data class CachedTransactionCsv(
        val file: File,
        val sha256: String,
    )
}
