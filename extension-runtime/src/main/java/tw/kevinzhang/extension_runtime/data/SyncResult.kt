package tw.kevinzhang.extension_runtime.data

import tw.kevinzhang.core.data.model.AssetKind

sealed class SyncResult {
    data class Success(
        val accounts: List<AccountData>,
        /** Null means the installed legacy extension did not report per-kind status. */
        val kindSync: List<KindSyncResult>? = null,
        val sourceDocuments: List<CapturedSourceDocument> = emptyList(),
        val runId: String? = null,
        val runStartedAt: Long? = null,
    ) : SyncResult()
    data class Error(
        val message: String,
        val cause: Throwable? = null,
        val code: String? = null,
        val scriptFrame: String? = null,
        val sourceDocuments: List<CapturedSourceDocument> = emptyList(),
        val runId: String? = null,
        val runStartedAt: Long? = null,
    ) : SyncResult()
}

/**
 * In-memory response evidence persisted by the app under the ingestion run identity.
 *
 * [representation] is explicit because only native HTTP and binary browser XHR captures are exact
 * response bytes. WebView navigation exposes a serialized DOM, not the original response stream.
 */
data class CapturedSourceDocument(
    val id: String,
    val capturedAt: Long,
    val stage: String,
    val transport: String,
    val method: String,
    val url: String,
    val statusCode: Int?,
    val responseHeadersJson: String,
    val mediaKind: String?,
    val bodyEncoding: String,
    val representation: String,
    val bodyBytes: ByteArray,
)

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
