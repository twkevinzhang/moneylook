package tw.kevinzhang.extension_runtime

import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.data.SyncResult

data class ExtensionCredential(
    val json: String,
) {
    override fun toString(): String = "ExtensionCredential([REDACTED])"
}

data class ExtensionTransferCursor(
    val accountNo: String,
    val currency: String,
    val latestTxnDateTime: String,
) {
    override fun toString(): String = "ExtensionTransferCursor([REDACTED])"
}

data class ExtensionSyncContext(
    val transferCursors: List<ExtensionTransferCursor> = emptyList(),
) {
    override fun toString(): String = "ExtensionSyncContext(transferCursors=${transferCursors.size})"
}

interface ExtensionRunner {
    /**
     * Runs the sync-trigger script for the given extension.
     * The credential JSON and redaction-safe synchronization context are exposed only to this invocation
     * as the deeply frozen `sdk.credential` and `sdk.sync` objects.
     */
    suspend fun run(
        extension: InstalledExtension,
        credential: ExtensionCredential,
        syncContext: ExtensionSyncContext,
    ): SyncResult
}
