package tw.kevinzhang.extension_runtime.data

import tw.kevinzhang.core.data.model.AssetKind

sealed class SyncResult {
    data class Success(
        val accounts: List<AccountData>,
        /** Null means the installed legacy extension did not report per-kind status. */
        val kindSync: List<KindSyncResult>? = null,
    ) : SyncResult()
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val code: String? = null,
        val scriptFrame: String? = null,
    ) : SyncResult()
}

enum class KindSyncStatus {
    COMPLETE,
    FAILED,
    /** The installed extension explicitly does not support this product kind. */
    NOT_APPLICABLE,
}

/** Per-product outcome supplied by an extension without exposing bank response detail. */
data class KindSyncResult(
    val kind: AssetKind,
    val status: KindSyncStatus,
    val code: String? = null,
)
