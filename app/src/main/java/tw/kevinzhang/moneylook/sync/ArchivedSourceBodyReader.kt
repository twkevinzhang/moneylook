package tw.kevinzhang.moneylook.sync

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.kevinzhang.core.data.model.SourceDocument
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

private const val MAX_SOURCE_PREVIEW_BYTES = 256 * 1024

/** Verifies the complete archive off-main, then returns a bounded UI preview. */
suspend fun readArchivedSourceBodyPreview(document: SourceDocument): String =
    withContext(Dispatchers.IO) {
        val bytes = runCatching {
            GZIPInputStream(ByteArrayInputStream(document.bodyGzip)).use { it.readBytes() }
        }.getOrElse {
            return@withContext "[封存解壓縮失敗：${it.javaClass.simpleName}]"
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (bytes.size.toLong() != document.bodyByteCount || sha256 != document.bodySha256) {
            return@withContext "[封存驗證失敗：body size 或 SHA-256 不一致]"
        }
        val preview = bytes.copyOfRange(0, minOf(bytes.size, MAX_SOURCE_PREVIEW_BYTES))
        val rendered = if (document.bodyEncoding == "base64") {
            Base64.encodeToString(preview, Base64.NO_WRAP)
        } else {
            val charset = if (document.representation == "exact_bytes") {
                charsetFromHeaders(document.responseHeadersJson)
            } else {
                Charsets.UTF_8
            }
            preview.toString(charset)
        }
        if (preview.size == bytes.size) rendered else buildString {
            append(rendered)
            append("\n\n[預覽已截斷：顯示 ")
            append(preview.size)
            append(" / ")
            append(bytes.size)
            append(" bytes；完整封存仍保留且 SHA-256 已驗證]")
        }
    }

private fun charsetFromHeaders(headersJson: String): Charset {
    val name = Regex("""charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
        .find(headersJson)
        ?.groupValues
        ?.getOrNull(1)
    return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
}
