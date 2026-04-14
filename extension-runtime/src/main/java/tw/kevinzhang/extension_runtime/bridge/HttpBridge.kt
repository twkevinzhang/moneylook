package tw.kevinzhang.extension_runtime.bridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tw.kevinzhang.extension_runtime.data.HttpResult
import tw.kevinzhang.extension_runtime.session.SessionStore

/**
 * Synchronous HTTP bridge exposed to JS scripts via SDK.
 * Automatically injects session (cookies + tokens) for targetDomains.
 * Must be called from a background thread (Dispatchers.IO).
 */
class HttpBridge(
    private val okHttpClient: OkHttpClient,
    private val sessionStore: SessionStore,
    private val extensionId: String,
    private val targetDomains: List<String>,
) {

    fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult =
        execute(buildRequest(url, extraHeaders, body = null))

    fun post(url: String, body: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult {
        val requestBody = body.toRequestBody("application/json".toMediaType())
        return execute(buildRequest(url, extraHeaders, body = requestBody))
    }

    private fun buildRequest(
        url: String,
        extraHeaders: Map<String, String>,
        body: okhttp3.RequestBody?,
    ): Request {
        val builder = if (body != null) {
            Request.Builder().url(url).post(body)
        } else {
            Request.Builder().url(url).get()
        }

        // Inject session only for targetDomains — use host-segment match to prevent exfiltration
        // via crafted URLs like "https://evil-example.com.attacker.com/"
        val urlHost = runCatching { java.net.URL(url).host }.getOrNull()
        if (urlHost != null && targetDomains.any { domain ->
                urlHost == domain || urlHost.endsWith(".$domain")
            }) {
            sessionStore.getCookies(extensionId)?.let { builder.header("Cookie", it) }
            sessionStore.getTokens(extensionId).forEach { (k, v) -> builder.header(k, v) }
        }

        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun execute(request: Request): HttpResult {
        okHttpClient.newCall(request).execute().use { response ->
            val responseHeaders = response.headers.toMap()
            // Detect session expiry: propagate 401/403 as-is so ExtensionRunner can handle it
            return HttpResult(
                status = response.code,
                body = response.body?.string() ?: "",
                headers = responseHeaders,
            )
        }
    }
}
