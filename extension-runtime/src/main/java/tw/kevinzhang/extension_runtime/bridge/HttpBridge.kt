package tw.kevinzhang.extension_runtime.bridge

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tw.kevinzhang.extension_runtime.data.HttpResult
import tw.kevinzhang.extension_runtime.session.EphemeralSession
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class HttpRequest(
    val method: String,
    val url: String,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * Synchronous, policy-enforcing HTTP bridge exposed to untrusted extension scripts.
 *
 * The session is an immutable snapshot owned by this bridge instance. JavaScript cannot read
 * the snapshot or provide credential-bearing headers; cookies are injected only after the HTTPS
 * URL and its host have passed the allowlist. Automatic redirects are disabled so every redirect
 * destination must return to the bridge for a fresh policy check.
 */
class HttpBridge(
    okHttpClient: OkHttpClient,
    private val session: EphemeralSession,
    targetDomains: List<String>,
) {
    private val client = okHttpClient.newBuilder()
        .apply {
            // Bank request URLs and native-injected cookies must never reach shared loggers.
            interceptors().clear()
            networkInterceptors().clear()
        }
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val targetDomains = targetDomains.map(::normalizeDomain).also { domains ->
        require(domains.isNotEmpty()) { "targetDomains must not be empty" }
        require(domains.none(String::isEmpty)) { "targetDomains contains an invalid domain" }
    }.distinct()
    private val requestCount = AtomicInteger(0)

    fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult =
        executeNetwork(buildRequest(url, extraHeaders, body = null))

    fun post(url: String, body: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BODY_BYTES) {
            throw SecurityException("request body exceeds size limit")
        }
        val requestBody = body.toRequestBody("application/json".toMediaType())
        return executeNetwork(buildRequest(url, extraHeaders, body = requestBody))
    }

    fun execute(request: HttpRequest): HttpResult = when (request.method.lowercase(Locale.US)) {
        "get" -> get(request.url, request.headers)
        "post" -> post(request.url, request.body, request.headers)
        else -> throw SecurityException("HTTP method not allowed: ${request.method}")
    }

    internal fun buildRequest(
        url: String,
        extraHeaders: Map<String, String>,
        body: okhttp3.RequestBody?,
    ): Request {
        if (url.length > MAX_URL_CHARS) throw SecurityException("URL exceeds size limit")
        val parsedUrl = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("invalid URL")
        if (!parsedUrl.isHttps) throw SecurityException("only HTTPS requests are allowed")
        if (parsedUrl.username.isNotEmpty() || parsedUrl.password.isNotEmpty()) {
            throw SecurityException("URL user info is not allowed")
        }

        val urlHost = parsedUrl.host.lowercase(Locale.US).trimEnd('.')
        if (!isAllowedHost(urlHost)) throw SecurityException("domain not allowed: $urlHost")
        validateExtensionHeaders(extraHeaders)

        val builder = if (body != null) {
            Request.Builder().url(parsedUrl).post(body)
        } else {
            Request.Builder().url(parsedUrl).get()
        }

        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        session.cookieHeaderFor(urlHost)?.let { builder.header("Cookie", it) }
        return builder.build()
    }

    private fun isAllowedHost(host: String): Boolean = targetDomains.any { domain ->
        host == domain || host.endsWith(".$domain")
    }

    private fun validateExtensionHeaders(headers: Map<String, String>) {
        if (headers.size > MAX_HEADER_COUNT) throw SecurityException("too many headers")
        headers.keys.forEach { rawName ->
            val name = rawName.lowercase(Locale.US)
            if (name in FORBIDDEN_HEADERS || name.startsWith("proxy-")) {
                throw SecurityException("header not allowed: $rawName")
            }
        }
        headers.forEach { (name, value) ->
            if (name.length > MAX_HEADER_NAME_CHARS || value.length > MAX_HEADER_VALUE_CHARS) {
                throw SecurityException("header exceeds size limit: $name")
            }
        }
    }

    private fun executeNetwork(request: Request): HttpResult {
        if (requestCount.incrementAndGet() > MAX_REQUESTS_PER_RUN) {
            throw SecurityException("extension request limit exceeded")
        }
        client.newCall(request).execute().use { response ->
            val safeHeaders = response.headers.toMultimap()
                .filterKeys { name -> !name.equals("Set-Cookie", ignoreCase = true) }
                .mapValues { (_, values) -> values.joinToString(", ") }
            val responseBody = response.body?.let { body ->
                if (body.contentLength() > MAX_RESPONSE_BODY_BYTES) {
                    throw SecurityException("response body exceeds size limit")
                }
                val source = body.source()
                source.request(MAX_RESPONSE_BODY_BYTES + 1L)
                if (source.buffer.size > MAX_RESPONSE_BODY_BYTES) {
                    throw SecurityException("response body exceeds size limit")
                }
                source.buffer.clone().readString(Charsets.UTF_8)
            }.orEmpty()
            return HttpResult(
                status = response.code,
                body = responseBody,
                headers = safeHeaders,
            )
        }
    }

    private companion object {
        const val MAX_URL_CHARS = 8_192
        const val MAX_HEADER_COUNT = 32
        const val MAX_HEADER_NAME_CHARS = 128
        const val MAX_HEADER_VALUE_CHARS = 8_192
        const val MAX_REQUEST_BODY_BYTES = 2 * 1024 * 1024
        const val MAX_RESPONSE_BODY_BYTES = 10L * 1024 * 1024
        const val MAX_REQUESTS_PER_RUN = 100

        val FORBIDDEN_HEADERS = setOf(
            "authorization",
            "cookie",
            "cookie2",
            "host",
            "proxy-authorization",
            "connection",
            "content-length",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
        )

        fun normalizeDomain(domain: String): String {
            val normalized = domain.trim().removePrefix(".").lowercase(Locale.US).trimEnd('.')
            if (normalized.isEmpty() || normalized.contains('/') || normalized.contains(':') ||
                normalized.startsWith("-") || normalized.endsWith("-") ||
                normalized.split('.').any { label -> label.isEmpty() }
            ) {
                return ""
            }
            return normalized
        }
    }
}
