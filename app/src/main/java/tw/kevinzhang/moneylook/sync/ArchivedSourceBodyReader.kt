package tw.kevinzhang.moneylook.sync

import android.util.Base64
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.kevinzhang.core.data.model.SourceDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.inject.Inject

const val MAX_SOURCE_BODY_CHUNK_BYTES = 256 * 1024

data class ArchivedSourceBodyChunk(
    val documentId: String,
    val startOffset: Long,
    val endOffsetExclusive: Long,
    val totalBytes: Long,
    /** Convenience decoding only; multibyte characters may straddle chunk boundaries. */
    val renderedBody: String,
    /** Canonical, lossless representation of exactly this chunk's archived bytes. */
    val exactBytesBase64: String,
    val bodyEncoding: String,
    val integrityVerified: Boolean,
    val errorMessage: String? = null,
) {
    val hasPrevious: Boolean get() = integrityVerified && startOffset > 0
    val hasNext: Boolean get() = integrityVerified && endOffsetExclusive < totalBytes
    val byteCount: Long get() = endOffsetExclusive - startOffset
}

/**
 * Reads one bounded byte range while streaming the complete gzip archive through SHA-256.
 *
 * The full body is verified on every read but is never materialized as one ByteArray or handed to
 * Compose. This keeps even multi-megabyte authenticated responses inspectable on constrained
 * devices while retaining byte-count and digest verification.
 */
class ArchivedSourceBodyReader @Inject constructor() {
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    suspend fun readChunk(
        document: SourceDocument,
        requestedOffset: Long,
        chunkSize: Int = MAX_SOURCE_BODY_CHUNK_BYTES,
    ): ArchivedSourceBodyChunk = withContext(ioDispatcher) {
        require(chunkSize in 1..MAX_SOURCE_BODY_CHUNK_BYTES) {
            "chunkSize must be between 1 and $MAX_SOURCE_BODY_CHUNK_BYTES"
        }
        val lastChunkStart = if (document.bodyByteCount == 0L) {
            0L
        } else {
            ((document.bodyByteCount - 1) / chunkSize) * chunkSize
        }
        val start = requestedOffset.coerceAtLeast(0).coerceAtMost(lastChunkStart)
        val requestedEnd = (start + chunkSize).coerceAtMost(document.bodyByteCount)
        val selected = ByteArrayOutputStream((requestedEnd - start).toInt())
        val digest = MessageDigest.getInstance("SHA-256")
        var observedBytes = 0L

        val failure = runCatching {
            GZIPInputStream(ByteArrayInputStream(document.bodyGzip)).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    val readStart = observedBytes
                    val readEnd = observedBytes + count
                    val copyStart = maxOf(start, readStart)
                    val copyEnd = minOf(requestedEnd, readEnd)
                    if (copyStart < copyEnd) {
                        selected.write(
                            buffer,
                            (copyStart - readStart).toInt(),
                            (copyEnd - copyStart).toInt(),
                        )
                    }
                    observedBytes = readEnd
                }
            }
        }.exceptionOrNull()
        if (failure != null) {
            return@withContext failedChunk(
                document = document,
                start = start,
                message = "封存解壓縮失敗：${failure.javaClass.simpleName}",
            )
        }

        val observedSha256 = digest.digest().toHex()
        if (observedBytes != document.bodyByteCount || observedSha256 != document.bodySha256) {
            return@withContext failedChunk(
                document = document,
                start = start,
                message = "封存驗證失敗：body size 或 SHA-256 不一致",
            )
        }

        val bytes = selected.toByteArray()
        ArchivedSourceBodyChunk(
            documentId = document.id,
            startOffset = start,
            endOffsetExclusive = start + bytes.size,
            totalBytes = observedBytes,
            renderedBody = render(bytes, document),
            exactBytesBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            bodyEncoding = document.bodyEncoding,
            integrityVerified = true,
        )
    }
}

/** Verifies the complete archive off-main, then returns a bounded UI preview. */
suspend fun readArchivedSourceBodyPreview(document: SourceDocument): String =
    ArchivedSourceBodyReader().readChunk(document, requestedOffset = 0).let { chunk ->
        chunk.errorMessage?.let { "[$it]" } ?: if (!chunk.hasNext) {
            chunk.renderedBody
        } else {
            buildString {
                append(chunk.renderedBody)
                append("\n\n[預覽已截斷：顯示 ")
                append(chunk.byteCount)
                append(" / ")
                append(chunk.totalBytes)
                append(" bytes；完整封存仍保留且 SHA-256 已驗證]")
            }
        }
    }

private fun failedChunk(
    document: SourceDocument,
    start: Long,
    message: String,
) = ArchivedSourceBodyChunk(
    documentId = document.id,
    startOffset = start,
    endOffsetExclusive = start,
    totalBytes = document.bodyByteCount,
    renderedBody = "[$message]",
    exactBytesBase64 = "",
    bodyEncoding = document.bodyEncoding,
    integrityVerified = false,
    errorMessage = message,
)

private fun render(bytes: ByteArray, document: SourceDocument): String =
    if (document.bodyEncoding == "base64") {
        Base64.encodeToString(bytes, Base64.NO_WRAP)
    } else {
        val charset = if (document.representation == "exact_bytes") {
            charsetFromHeaders(document.responseHeadersJson)
        } else {
            Charsets.UTF_8
        }
        bytes.toString(charset)
    }

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun charsetFromHeaders(headersJson: String): Charset {
    val name = Regex("""charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
        .find(headersJson)
        ?.groupValues
        ?.getOrNull(1)
    return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
}
