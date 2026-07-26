package tw.kevinzhang.extension_runtime.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NativeHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, List<String>>,
    val body: String?,
    val bodyEncoding: String,
    val responseEncoding: String,
    val followRedirects: Boolean,
    val timeoutMs: Long,
    val capture: ResponseCaptureOptions? = null,
)

internal data class NativeHttpResponse(
    val status: Int,
    val statusText: String,
    val headers: Map<String, List<String>>,
    val body: String,
    val bodyEncoding: String,
    val sourceDocumentId: String? = null,
    @Transient val exactBodyBytes: ByteArray = ByteArray(0),
)

internal data class ResponseCaptureOptions(
    val stage: String,
    val authenticated: Boolean,
    val mediaKind: String?,
)

internal class SafeHttpException(
    val code: String,
    message: String,
) : Exception(message)

internal object HttpRequestJsonParser {
    fun parse(json: String, gson: Gson): NativeHttpRequest {
        val root = try {
            gson.fromJson(json, JsonObject::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SafeHttpException("INVALID_REQUEST", "request must be valid JSON")
        } ?: throw SafeHttpException("INVALID_REQUEST", "request must be an object")

        val url = root.string("url") ?: throw SafeHttpException("INVALID_REQUEST", "url is required")
        val method = (root.string("method") ?: "GET").uppercase(Locale.US)
        if (!METHOD_PATTERN.matches(method)) {
            throw SafeHttpException("INVALID_METHOD", "HTTP method is invalid")
        }
        val bodyEncoding = root.string("bodyEncoding") ?: "utf8"
        val responseEncoding = root.string("responseEncoding") ?: "text"
        if (bodyEncoding !in BODY_ENCODINGS || responseEncoding !in RESPONSE_ENCODINGS) {
            throw SafeHttpException("INVALID_ENCODING", "body encoding is invalid")
        }
        val timeoutMs = root.get("timeoutMs")?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) {
                throw SafeHttpException("INVALID_TIMEOUT", "timeout must be a number")
            }
            it.asLong
        } ?: DEFAULT_TIMEOUT_MS
        if (timeoutMs !in 1..MAX_TIMEOUT_MS) {
            throw SafeHttpException("INVALID_TIMEOUT", "timeout is outside the allowed range")
        }
        val body = root.get("body")?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
                throw SafeHttpException("INVALID_BODY", "body must be a string")
            }
            it.asString
        }

        return NativeHttpRequest(
            method = method,
            url = url,
            headers = parseHeaders(root.get("headers")?.takeUnless { it.isJsonNull }?.let {
                if (!it.isJsonObject) throw SafeHttpException("INVALID_HEADERS", "headers must be an object")
                it.asJsonObject
            }),
            body = body,
            bodyEncoding = bodyEncoding,
            responseEncoding = responseEncoding,
            followRedirects = root.get("followRedirects")?.takeUnless { it.isJsonNull }?.let {
                if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) {
                    throw SafeHttpException("INVALID_REQUEST", "followRedirects must be a boolean")
                }
                it.asBoolean
            } ?: false,
            timeoutMs = timeoutMs,
            capture = root.getAsJsonObject("capture")?.let { capture ->
                val stage = capture.string("stage")
                    ?.takeIf { it.isNotBlank() && it.length <= 128 }
                    ?: throw SafeHttpException("INVALID_REQUEST", "capture.stage is required")
                ResponseCaptureOptions(
                    stage = stage,
                    authenticated = capture.get("authenticated")?.takeUnless { it.isJsonNull }?.let {
                        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isBoolean) {
                            throw SafeHttpException("INVALID_REQUEST", "capture.authenticated must be a boolean")
                        }
                        it.asBoolean
                    } ?: false,
                    mediaKind = capture.string("mediaKind")?.takeIf { it.length <= 64 },
                )
            },
        )
    }

    private fun parseHeaders(headers: JsonObject?): Map<String, List<String>> {
        if (headers == null) return emptyMap()
        if (headers.size() > MAX_HEADER_COUNT) {
            throw SafeHttpException("HEADERS_TOO_LARGE", "too many request headers")
        }
        return headers.entrySet().associate { (name, element) ->
            if (name.length > MAX_HEADER_NAME_CHARS) {
                throw SafeHttpException("HEADERS_TOO_LARGE", "request header is too large")
            }
            val values = when {
                element.isJsonPrimitive && element.asJsonPrimitive.isString -> listOf(element.asString)
                element.isJsonArray -> element.asJsonArray.map {
                    if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
                        throw SafeHttpException("INVALID_HEADERS", "header values must be strings")
                    }
                    it.asString
                }
                else -> throw SafeHttpException("INVALID_HEADERS", "header values must be strings")
            }
            if (values.isEmpty() || values.any { it.length > MAX_HEADER_VALUE_CHARS }) {
                throw SafeHttpException("HEADERS_TOO_LARGE", "request header is too large")
            }
            name to values
        }
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.let {
        if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) {
            throw SafeHttpException("INVALID_REQUEST", "$name must be a string")
        }
        it.asString
    }

    private val METHOD_PATTERN = Regex("^[!#$%&'*+.^_`|~0-9A-Z-]+$")
    private val BODY_ENCODINGS = setOf("utf8", "base64")
    private val RESPONSE_ENCODINGS = setOf("text", "base64")
    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_TIMEOUT_MS = 30_000L
    private const val MAX_HEADER_COUNT = 64
    private const val MAX_HEADER_NAME_CHARS = 256
    private const val MAX_HEADER_VALUE_CHARS = 16_384
}

internal class NativeHttpTransport(okHttpClient: OkHttpClient) {
    private val baseClient = okHttpClient.newBuilder()
        .apply {
            // URLs, headers, and bodies may contain credentials. Never inherit shared loggers.
            interceptors().clear()
            networkInterceptors().clear()
        }
        .build()
    suspend fun execute(request: NativeHttpRequest): NativeHttpResponse {
        val okRequest = buildRequest(request)
        val client = baseClient.newBuilder()
            .followRedirects(request.followRedirects)
            .followSslRedirects(request.followRedirects)
            .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return client.newCall(okRequest).await().use { response -> response.toNative(request.responseEncoding) }
    }

    internal fun buildRequest(request: NativeHttpRequest): Request {
        if (request.url.length > MAX_URL_CHARS) throw SafeHttpException("URL_TOO_LARGE", "URL exceeds size limit")
        val url = request.url.toHttpUrlOrNull()
            ?: throw SafeHttpException("INVALID_URL", "URL is invalid")
        if (url.scheme != "http" && url.scheme != "https") {
            throw SafeHttpException("INVALID_URL", "only HTTP and HTTPS URLs are supported")
        }
        val bodyBytes = when (request.bodyEncoding) {
            "utf8" -> request.body?.toByteArray(Charsets.UTF_8)
            "base64" -> request.body?.let {
                it.decodeBase64()?.toByteArray()
                    ?: throw SafeHttpException("INVALID_BODY", "request body is not valid base64")
            }
            else -> null
        }
        if ((bodyBytes?.size ?: 0) > MAX_REQUEST_BODY_BYTES) {
            throw SafeHttpException("BODY_TOO_LARGE", "request body exceeds size limit")
        }
        val headers = try {
            Headers.Builder().apply {
                request.headers.forEach { (name, values) -> values.forEach { add(name, it) } }
            }.build()
        } catch (e: IllegalArgumentException) {
            throw SafeHttpException("INVALID_HEADERS", "request headers are invalid")
        }
        val contentType = headers["Content-Type"]?.toMediaTypeOrNull()
        val requestBody = when {
            bodyBytes != null -> bodyBytes.toRequestBody(contentType)
            request.method in METHODS_REQUIRING_BODY -> ByteArray(0).toRequestBody(contentType)
            else -> null
        }
        return try {
            Request.Builder()
                .url(url)
                .headers(headers)
                .method(request.method, requestBody)
                .build()
        } catch (e: IllegalArgumentException) {
            throw SafeHttpException("INVALID_REQUEST", "HTTP request is invalid")
        }
    }

    private fun Response.toNative(responseEncoding: String): NativeHttpResponse {
        val bytes = body?.let {
            if (it.contentLength() > MAX_RESPONSE_BODY_BYTES) {
                throw SafeHttpException("RESPONSE_TOO_LARGE", "response body exceeds size limit")
            }
            val source = it.source()
            source.request(MAX_RESPONSE_BODY_BYTES + 1L)
            if (source.buffer.size > MAX_RESPONSE_BODY_BYTES) {
                throw SafeHttpException("RESPONSE_TOO_LARGE", "response body exceeds size limit")
            }
            source.buffer.clone().readByteArray()
        } ?: ByteArray(0)
        val encodedBody = when (responseEncoding) {
            "base64" -> bytes.toByteString().base64()
            else -> bytes.toString(Charsets.UTF_8)
        }
        return NativeHttpResponse(
            status = code,
            statusText = message,
            headers = headers.toMultimap(),
            body = encodedBody,
            bodyEncoding = responseEncoding,
            exactBodyBytes = bytes,
        )
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(SafeHttpException("NETWORK_ERROR", "network request failed"))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private companion object {
        const val MAX_URL_CHARS = 16_384
        const val MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_RESPONSE_BODY_BYTES = 10L * 1024 * 1024
        val METHODS_REQUIRING_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")
    }
}
