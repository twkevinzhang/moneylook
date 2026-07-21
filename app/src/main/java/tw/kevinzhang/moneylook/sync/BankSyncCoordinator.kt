package tw.kevinzhang.moneylook.sync

import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.db.TransferCursorStore
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.extension_runtime.ExtensionCredential
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.ExtensionSyncContext
import tw.kevinzhang.extension_runtime.ExtensionTransferCursor
import tw.kevinzhang.extension_runtime.data.SyncResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BankSyncCoordinator @Inject constructor(
    private val extensionRunner: ExtensionRunner,
    private val transferCursorStore: TransferCursorStore,
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
            )
        }
        return extensionRunner.run(
            extension = extension,
            credential = ExtensionCredential(profile.credential),
            syncContext = ExtensionSyncContext(transferCursors),
        )
    }
}

private fun AssetKind.sdkValue(): String = when (this) {
    AssetKind.DEPOSIT -> "deposit"
    AssetKind.CREDIT_CARD -> "credit_card"
    AssetKind.LOAN -> "loan"
}
