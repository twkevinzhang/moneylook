package tw.kevinzhang.moneylook.sync

import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.db.TransferCursorStore
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
                accountNo = cursor.accountNo,
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
