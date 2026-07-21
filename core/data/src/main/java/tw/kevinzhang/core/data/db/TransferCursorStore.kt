package tw.kevinzhang.core.data.db

import tw.kevinzhang.core.data.model.AssetKind

data class TransferSyncCursor(
    /** Opaque source identity; never an account/card number. */
    val sourceAccountKey: String,
    val kind: AssetKind,
    val currency: String,
    val latestTxnDateTime: String,
) {
    override fun toString(): String = "TransferSyncCursor([REDACTED])"
}

interface TransferCursorStore {
    suspend fun latestByExtension(extensionId: String): List<TransferSyncCursor>
}
