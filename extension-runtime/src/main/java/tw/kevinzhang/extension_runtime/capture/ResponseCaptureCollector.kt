package tw.kevinzhang.extension_runtime.capture

import com.google.gson.Gson
import okio.ByteString.Companion.decodeBase64
import tw.kevinzhang.extension_runtime.bridge.ResponseCaptureOptions
import tw.kevinzhang.extension_runtime.data.CapturedSourceDocument
import java.util.UUID

/** Per-invocation, in-memory collector. Only explicitly authenticated responses enter the ledger. */
internal class ResponseCaptureCollector(private val gson: Gson) {
    private val documents = mutableListOf<CapturedSourceDocument>()

    @Synchronized
    fun capture(
        options: ResponseCaptureOptions?,
        transport: String,
        method: String,
        url: String,
        statusCode: Int?,
        headers: Map<String, List<String>>,
        body: String,
        bodyEncoding: String,
        representation: String,
        exactBodyBytes: ByteArray? = null,
    ): String? {
        if (options?.authenticated != true) return null
        val bytes = exactBodyBytes ?: when (bodyEncoding) {
            "base64" -> body.decodeBase64()?.toByteArray()
                ?: throw IllegalArgumentException("captured response body is invalid base64")
            else -> body.toByteArray(Charsets.UTF_8)
        }
        val id = UUID.randomUUID().toString()
        documents += CapturedSourceDocument(
            id = id,
            capturedAt = System.currentTimeMillis(),
            stage = options.stage,
            transport = transport,
            method = method,
            url = url,
            statusCode = statusCode,
            responseHeadersJson = gson.toJson(headers),
            mediaKind = options.mediaKind,
            bodyEncoding = bodyEncoding,
            representation = representation,
            bodyBytes = bytes.copyOf(),
        )
        return id
    }

    @Synchronized
    fun snapshot(): List<CapturedSourceDocument> =
        documents.map { it.copy(bodyBytes = it.bodyBytes.copyOf()) }
}
