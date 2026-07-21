package tw.kevinzhang.core.data.db

data class TransferSyncCursor(
    val accountNo: String,
    val currency: String,
    val latestTxnDateTime: String,
) {
    override fun toString(): String = "TransferSyncCursor([REDACTED])"
}

interface TransferCursorStore {
    suspend fun latestByExtension(extensionId: String): List<TransferSyncCursor>
}
