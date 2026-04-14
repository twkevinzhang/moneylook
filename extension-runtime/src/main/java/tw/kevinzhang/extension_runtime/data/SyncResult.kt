package tw.kevinzhang.extension_runtime.data

sealed class SyncResult {
    data class Success(val accounts: List<AccountData>) : SyncResult()
    data class Error(val message: String, val cause: Throwable? = null) : SyncResult()
}
