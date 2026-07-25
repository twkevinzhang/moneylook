package tw.kevinzhang.moneylook.sync

import kotlinx.coroutines.CancellationException
import tw.kevinzhang.core.data.db.SyncDiagnosticDao
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.db.TransferCursorStore
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.SyncDiagnostic
import tw.kevinzhang.extension_runtime.ExtensionCredential
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.ExtensionSyncContext
import tw.kevinzhang.extension_runtime.ExtensionTransferCursor
import tw.kevinzhang.extension_runtime.data.KindSyncStatus
import tw.kevinzhang.extension_runtime.data.SyncResult
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BankSyncCoordinator @Inject constructor(
    private val extensionRunner: ExtensionRunner,
    private val transferCursorStore: TransferCursorStore,
    private val syncDiagnosticDao: SyncDiagnosticDao,
) {
    suspend fun sync(
        extension: InstalledExtension,
        profile: CredentialProfile,
    ): SyncResult {
        val transferCursors = transferCursorStore.latestByExtension(extension.id).map { cursor ->
            ExtensionTransferCursor(
                sourceAccountKey = cursor.sourceAccountKey,
                kind = cursor.kind.sdkValue(),
                currency = cursor.currency,
                latestTxnDateTime = cursor.latestTxnDateTime,
                earliestTxnDateTime = cursor.earliestTxnDateTime,
            )
        }
        val result = try {
            extensionRunner.run(
                extension = extension,
                credential = ExtensionCredential(profile.credential),
                syncContext = ExtensionSyncContext(transferCursors),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            recordDiagnostic(extension.id, "RUNTIME_ERROR", "SYNC_THROWN")
            throw error
        }
        recordResult(extension.id, result)
        return result
    }

    private suspend fun recordResult(extensionId: String, result: SyncResult) {
        when (result) {
            is SyncResult.Error -> recordDiagnostic(
                extensionId = extensionId,
                category = "SCRIPT_ERROR",
                code = result.code ?: "SYNC_FAILED",
                scriptFrame = result.scriptFrame,
            )
            is SyncResult.Success -> {
                recordDiagnostic(
                    extensionId = extensionId,
                    category = "SYNC_RESULT",
                    code = if (result.hasPartialSyncFailure) "SYNC_PARTIAL" else "SYNC_SUCCESS",
                )
                result.kindSync.orEmpty()
                    .filter { it.status == KindSyncStatus.FAILED }
                    .forEach { kindResult ->
                        recordDiagnostic(
                            extensionId = extensionId,
                            category = "PARTIAL_KIND",
                            code = kindResult.code ?: "KIND_FAILED",
                        )
                    }
                if (result.accounts.any { it.transferSync?.complete == false }) {
                    recordDiagnostic(extensionId, "PARTIAL_HISTORY", "HISTORY_INCOMPLETE")
                }
            }
        }
    }

    private suspend fun recordDiagnostic(
        extensionId: String,
        category: String,
        code: String,
        scriptFrame: String? = null,
    ) {
        try {
            syncDiagnosticDao.insert(
                SyncDiagnostic(
                    id = UUID.randomUUID().toString(),
                    extensionId = extensionId,
                    createdAt = System.currentTimeMillis(),
                    category = category,
                    code = code.takeIf(SAFE_CODE::matches),
                    scriptFrame = scriptFrame?.takeIf(SAFE_FRAME::matches),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Diagnostics are best-effort and must not change the sync outcome.
        }
    }

    private companion object {
        val SAFE_CODE = Regex("[A-Za-z0-9_.-]{1,48}")
        val SAFE_FRAME = Regex("line \\d{1,6}, column \\d{1,6}")
    }
}

private fun AssetKind.sdkValue(): String = when (this) {
    AssetKind.DEPOSIT -> "deposit"
    AssetKind.TIME_DEPOSIT -> "time_deposit"
    AssetKind.CREDIT_CARD -> "credit_card"
    AssetKind.LOAN -> "loan"
}
