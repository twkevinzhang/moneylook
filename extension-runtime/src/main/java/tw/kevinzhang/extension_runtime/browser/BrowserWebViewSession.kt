package tw.kevinzhang.extension_runtime.browser

import android.content.Context
import android.net.http.SslError
import android.os.Looper
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.ByteString.Companion.decodeBase64
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class BrowserWebViewSession(
    private val context: Context,
    private val gson: Gson,
) {
    private var state = State.NEW
    private var webView: WebView? = null
    private var profileName: String? = null
    private var openEvents: Channel<OpenEvent>? = null
    private var currentUrl: String? = null
    private var generation: Long = 0
    private val documentUrlTracker = BrowserDocumentUrlTracker()
    private val resourceUrlTracker = BrowserResourceUrlTracker()
    private val documentStartToken = UUID.randomUUID().toString()
    private var documentStartSignalInstalled = false
    private val targetNavigationInterceptor = BrowserTargetNavigationInterceptor { url ->
        openEvents?.trySend(OpenEvent.TargetIntercepted(url))
    }

    suspend fun open(request: BrowserOpenRequest): BrowserOpenResponse = navigate(
        timeoutMs = request.timeoutMs,
        settleMs = request.settleMs,
        returnAtDocumentStartUrl = request.returnAtDocumentStartUrl,
        captureAuthenticatedHtml = request.capture?.authenticated == true,
    ) { view ->
        startOpenNavigation(view, request)
    }

    suspend fun post(request: BrowserFormPostRequest): BrowserOpenResponse = navigate(
        timeoutMs = request.timeoutMs,
        settleMs = request.settleMs,
        returnAtDocumentStartUrl = null,
        captureAuthenticatedHtml = request.capture?.authenticated == true,
    ) { view ->
        startFormPostNavigation(view, request)
    }

    private suspend fun navigate(
        timeoutMs: Long,
        settleMs: Long,
        returnAtDocumentStartUrl: String?,
        captureAuthenticatedHtml: Boolean,
        start: (WebView) -> Unit,
    ): BrowserOpenResponse = withContext(Dispatchers.Main.immediate) {
        when (state) {
            State.REQUESTING, State.OPENING -> throw SafeBrowserException("BROWSER_BUSY", "browser session is busy")
            State.CLOSED -> throw SafeBrowserException("BROWSER_CLOSED", "browser session is closed")
            State.FAILED -> throw SafeBrowserException("BROWSER_FAILED", "browser session failed")
            State.NEW, State.READY -> Unit
        }
        val view = ensureWebView()
        val events = Channel<OpenEvent>(Channel.CONFLATED)
        openEvents = events
        state = State.OPENING
        targetNavigationInterceptor.arm(returnAtDocumentStartUrl)
        var interceptedAtDocumentStart = false
        try {
            val finalUrl = withTimeout<String>(timeoutMs) {
                documentUrlTracker.reset()
                resourceUrlTracker.reset()
                start(view)
                while (true) {
                    when (val event = events.receive()) {
                        is OpenEvent.Failed -> throw event.error
                        is OpenEvent.TargetIntercepted -> {
                            documentUrlTracker.onNavigationCandidate(event.url)
                            interceptedAtDocumentStart = true
                            return@withTimeout event.url
                        }
                        is OpenEvent.Finished -> {
                            if (!navigationMayCompleteFromPageFinished(returnAtDocumentStartUrl)) {
                                continue
                            }
                            if (settleMs > 0) delay(settleMs)
                            if (state != State.OPENING) {
                                if (state == State.CLOSED) {
                                    throw SafeBrowserException(
                                        "BROWSER_CLOSED",
                                        "browser session is closed",
                                    )
                                }
                                throw SafeBrowserException(
                                    "BROWSER_NAVIGATION",
                                    "browser navigation did not complete",
                                )
                            }
                            if (generation == event.generation) {
                                return@withTimeout view.url ?: currentUrl
                                ?: throw SafeBrowserException(
                                    "BROWSER_NAVIGATION",
                                    "browser returned no URL",
                                )
                            }
                            // A new main-frame navigation started during settle.
                            // Its most recent finish event is conflated in the channel;
                            // wait for it and restart the full settle window.
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                throw SafeBrowserException("BROWSER_NAVIGATION", "browser navigation did not complete")
            }
            val parsed = finalUrl.toHttpUrlOrNull()
                ?: throw SafeBrowserException("BROWSER_NAVIGATION", "browser returned an invalid URL")
            if (parsed.scheme !in SUPPORTED_SCHEMES) {
                throw SafeBrowserException("BROWSER_NAVIGATION", "browser returned an unsupported URL")
            }
            val documentUrl = documentUrlTracker.resolve(finalUrl)
            currentUrl = finalUrl
            state = State.READY
            // A return-at-document-start interception deliberately prevents the target document
            // from loading. Evaluating here would serialize the previous page and falsely label it
            // as the intercepted target URL, so no target capture is emitted.
            val html = if (captureAuthenticatedHtml && !interceptedAtDocumentStart) {
                decodeEvaluateResult(evaluate(view, "document.documentElement ? document.documentElement.outerHTML : '';"))
                    ?: ""
            } else {
                null
            }
            if (html != null && html.toByteArray(Charsets.UTF_8).size > BrowserResponseValidator.MAX_RESPONSE_BODY_BYTES) {
                throw SafeBrowserException("RESPONSE_TOO_LARGE", "browser document exceeds size limit")
            }
            BrowserOpenResponse(
                url = finalUrl,
                origin = parsed.origin(),
                documentUrl = documentUrl,
                documentUrls = documentUrlTracker.candidates(finalUrl),
                requestUrls = resourceUrlTracker.snapshot(),
                body = html,
                bodyEncoding = html?.let { "text" },
            )
        } catch (e: CancellationException) {
            view.stopLoading()
            if (e is TimeoutCancellationException) {
                if (state != State.CLOSED) state = State.FAILED
                throw SafeBrowserException("BROWSER_TIMEOUT", "browser navigation timed out", e)
            }
            throw e
        } catch (e: SafeBrowserException) {
            if (state != State.CLOSED) state = State.FAILED
            throw e
        } catch (e: Exception) {
            if (state != State.CLOSED) state = State.FAILED
            throw SafeBrowserException("BROWSER_NAVIGATION", "browser navigation failed", e)
        } finally {
            if (openEvents === events) openEvents = null
            events.cancel()
            targetNavigationInterceptor.clear()
        }
    }

    suspend fun request(request: BrowserXhrRequest): BrowserXhrResponse =
        withContext(Dispatchers.Main.immediate) {
            if (state != State.READY) {
                val code = if (state == State.CLOSED) "BROWSER_CLOSED" else "BROWSER_NOT_READY"
                throw SafeBrowserException(code, "browser session is not ready")
            }
            val view = webView ?: throw SafeBrowserException("BROWSER_CLOSED", "browser session is closed")
            val baseUrl = currentUrl?.toHttpUrlOrNull()
                ?: throw SafeBrowserException("BROWSER_NOT_READY", "browser origin is unavailable")
            val resolvedUrl = baseUrl.resolve(request.url)
                ?: throw SafeBrowserException("INVALID_URL", "URL is invalid")
            val resolvedRequest = request.copy(url = resolvedUrl.toString())
            val requestGeneration = generation
            val slot = "__moneylook_xhr_${UUID.randomUUID().toString().replace("-", "")}"
            state = State.REQUESTING
            try {
                withTimeout(request.timeoutMs) {
                    evaluate(view, BrowserXhrScriptBuilder.start(resolvedRequest, slot, gson))
                    val payloadMetadata = awaitPayloadMetadata(view, slot, requestGeneration)
                    if (
                        payloadMetadata.bodyBytes > BrowserResponseValidator.MAX_RESPONSE_BODY_BYTES ||
                        payloadMetadata.metadataLength > MAX_METADATA_CHARS ||
                        payloadMetadata.bodyLength > MAX_BODY_CHARS
                    ) {
                        throw SafeBrowserException("RESPONSE_TOO_LARGE", "browser response exceeds size limit")
                    }
                    val metadata = readSlotString(
                        view,
                        slot,
                        "metadata",
                        payloadMetadata.metadataLength,
                        requestGeneration,
                    )
                    val body = readSlotString(
                        view,
                        slot,
                        "body",
                        payloadMetadata.bodyLength,
                        requestGeneration,
                    )
                    parseEnvelope(metadata, body)
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    throw SafeBrowserException("BROWSER_TIMEOUT", "browser request timed out", e)
                }
                throw e
            } catch (e: SafeBrowserException) {
                throw e
            } catch (e: Exception) {
                throw SafeBrowserException("BROWSER_ERROR", "browser request failed", e)
            } finally {
                if (webView === view) {
                    view.evaluateJavascript(BrowserXhrScriptBuilder.cleanup(slot, gson), null)
                }
                if (state == State.REQUESTING) state = State.READY
            }
        }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "BrowserWebViewSession.close must run on the main thread"
        }
        if (state == State.CLOSED) return
        state = State.CLOSED
        openEvents?.cancel()
        openEvents = null
        val view = webView
        webView = null
        currentUrl = null
        documentUrlTracker.reset()
        resourceUrlTracker.reset()
        if (view != null) {
            view.stopLoading()
            removeDocumentStartSignal(view)
            view.webViewClient = WebViewClient()
            view.destroy()
        }
        profileName?.let(BrowserProfileLifecycle::delete)
        profileName = null
    }

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            throw SafeBrowserException(
                "BROWSER_PROFILE_UNSUPPORTED",
                "installed WebView does not support isolated profiles",
            )
        }
        BrowserProfileLifecycle.cleanupOrphansOnce()
        val name = "$PROFILE_PREFIX${UUID.randomUUID()}"
        val view = WebView(context)
        try {
            WebViewCompat.setProfile(view, name)
            @Suppress("SetJavaScriptEnabled")
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                blockNetworkLoads = false
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            if (
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            ) {
                WebViewCompat.addWebMessageListener(
                    view,
                    BrowserDocumentStartSignal.OBJECT_NAME,
                    setOf("*"),
                ) { _, message, sourceOrigin, isMainFrame, _ ->
                    if (isMainFrame) {
                        message.data
                            ?.let {
                                BrowserDocumentStartSignal.parse(
                                    it,
                                    documentStartToken,
                                    sourceOrigin.toString(),
                                    gson,
                                )
                            }
                            ?.let { url ->
                                documentUrlTracker.onNavigationCandidate(url)
                            }
                    }
                }
                WebViewCompat.addDocumentStartJavaScript(
                    view,
                    BrowserDocumentStartSignal.script(
                        BrowserDocumentStartSignal.OBJECT_NAME,
                        documentStartToken,
                        gson,
                    ),
                    setOf("*"),
                )
                documentStartSignalInstalled = true
            }
            view.webViewClient = SessionWebViewClient()
        } catch (e: Exception) {
            view.destroy()
            BrowserProfileLifecycle.delete(name)
            throw SafeBrowserException(
                "BROWSER_PROFILE_UNSUPPORTED",
                "browser profile could not be created",
                e,
            )
        }
        profileName = name
        webView = view
        return view
    }

    private fun removeDocumentStartSignal(view: WebView) {
        if (!documentStartSignalInstalled) return
        documentStartSignalInstalled = false
        runCatching {
            WebViewCompat.removeWebMessageListener(view, BrowserDocumentStartSignal.OBJECT_NAME)
        }
    }

    private suspend fun awaitPayloadMetadata(
        view: WebView,
        slot: String,
        expectedGeneration: Long,
    ): PayloadMetadata {
        while (true) {
            ensureRequestDocument(view, expectedGeneration)
            val raw = decodeEvaluateResult(evaluate(view, BrowserXhrScriptBuilder.poll(slot, gson)))
                ?: throw SafeBrowserException("BROWSER_ERROR", "browser request state is unavailable")
            val poll = try {
                gson.fromJson(raw, JsonObject::class.java)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw SafeBrowserException("BROWSER_ERROR", "browser request state is invalid", e)
            }
            when (poll.get("state")?.asString) {
                "done" -> return PayloadMetadata(
                    metadataLength = poll.get("metadataLength")?.asInt ?: 0,
                    bodyLength = poll.get("bodyLength")?.asInt ?: 0,
                    bodyBytes = poll.get("bodyBytes")?.asInt ?: 0,
                )
                "pending" -> delay(POLL_INTERVAL_MS)
                else -> throw SafeBrowserException("BROWSER_ERROR", "browser request state was lost")
            }
        }
    }

    private suspend fun readSlotString(
        view: WebView,
        slot: String,
        field: String,
        valueLength: Int,
        expectedGeneration: Long,
    ): String {
        val value = StringBuilder(valueLength)
        var offset = 0
        while (offset < valueLength) {
            ensureRequestDocument(view, expectedGeneration)
            val length = minOf(CHUNK_CHARS, valueLength - offset)
            val chunk = decodeEvaluateResult(
                evaluate(view, BrowserXhrScriptBuilder.readChunk(slot, field, offset, length, gson)),
            ) ?: throw SafeBrowserException("BROWSER_ERROR", "browser response chunk is unavailable")
            if (chunk.length != length) {
                throw SafeBrowserException("BROWSER_ERROR", "browser response chunk is incomplete")
            }
            value.append(chunk)
            offset += length
        }
        return value.toString()
    }

    private fun parseEnvelope(metadata: String, body: String): BrowserXhrResponse {
        val root = try {
            gson.fromJson(metadata, JsonObject::class.java)
        } catch (e: Exception) {
            throw SafeBrowserException("BROWSER_ERROR", "browser response is invalid", e)
        }
        if (root.get("ok")?.asBoolean != true) {
            val code = root.get("code")?.asString?.takeIf { it in SAFE_XHR_ERROR_CODES } ?: "BROWSER_ERROR"
            throw SafeBrowserException(code, "browser request failed")
        }
        val response = try {
            gson.fromJson(root.getAsJsonObject("response"), BrowserXhrResponse::class.java).copy(body = body)
        } catch (e: Exception) {
            throw SafeBrowserException("BROWSER_ERROR", "browser response is invalid", e)
        }
        return BrowserResponseValidator.validate(response)
    }

    private fun ensureRequestDocument(view: WebView, expectedGeneration: Long) {
        if (webView !== view || state == State.CLOSED) {
            throw SafeBrowserException("BROWSER_CLOSED", "browser session is closed")
        }
        if (generation != expectedGeneration || state == State.FAILED) {
            throw SafeBrowserException("BROWSER_NAVIGATED", "browser navigated during request")
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String? =
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    private fun decodeEvaluateResult(result: String?): String? {
        if (result == null || result == "null") return null
        return try {
            gson.fromJson(result, String::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private inner class SessionWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (request.isForMainFrame) {
                documentUrlTracker.onNavigationCandidate(url)
            }
            if (targetNavigationInterceptor.shouldIntercept(url, request.isForMainFrame)) return true
            return request.url.scheme !in SUPPORTED_SCHEMES
        }

        @Deprecated("Deprecated in Android")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            documentUrlTracker.onNavigationCandidate(url)
            if (targetNavigationInterceptor.shouldIntercept(url, isForMainFrame = true)) return true
            return url.toHttpUrlOrNull() == null
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            generation += 1
            currentUrl = url
            documentUrlTracker.onPageStarted(url)
            if (targetNavigationInterceptor.shouldIntercept(url, isForMainFrame = true)) {
                view.stopLoading()
                return
            }
            if (state == State.REQUESTING) state = State.FAILED
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            documentUrlTracker.onPageCommitVisible(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            currentUrl = url
            documentUrlTracker.onNavigationCandidate(url)
            if (state == State.OPENING) openEvents?.trySend(OpenEvent.Finished(generation))
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            documentUrlTracker.onNavigationCandidate(url)
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): android.webkit.WebResourceResponse? {
            resourceUrlTracker.record(request.url.toString())
            return null
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                failNavigation(
                    "BROWSER_NAVIGATION",
                    IOException("WebView error ${error.errorCode}: ${error.description}"),
                )
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            failNavigation(
                "BROWSER_SSL",
                IOException("WebView SSL error ${error.primaryError}: ${error.url}"),
            )
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            failNavigation(
                "BROWSER_RENDERER_GONE",
                IllegalStateException(
                    "WebView renderer gone: didCrash=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()}",
                ),
            )
            state = State.FAILED
            return true
        }

        private fun failNavigation(
            code: String,
            cause: Throwable,
        ) {
            state = State.FAILED
            openEvents?.trySend(
                OpenEvent.Failed(SafeBrowserException(code, "browser navigation failed", cause)),
            )
        }
    }

    private sealed interface OpenEvent {
        data class TargetIntercepted(val url: String) : OpenEvent
        data class Finished(val generation: Long) : OpenEvent
        data class Failed(val error: SafeBrowserException) : OpenEvent
    }

    private enum class State { NEW, OPENING, READY, REQUESTING, FAILED, CLOSED }

    private data class PayloadMetadata(
        val metadataLength: Int,
        val bodyLength: Int,
        val bodyBytes: Int,
    )

    private companion object {
        const val PROFILE_PREFIX = "moneylook-run-"
        const val POLL_INTERVAL_MS = 25L
        const val CHUNK_CHARS = 128 * 1024
        const val MAX_METADATA_CHARS = 256 * 1024
        const val MAX_BODY_CHARS = 14 * 1024 * 1024
        val SUPPORTED_SCHEMES = setOf("http", "https")
        val SAFE_XHR_ERROR_CODES = setOf(
            "BROWSER_NETWORK",
            "BROWSER_TIMEOUT",
            "BROWSER_ABORTED",
            "BROWSER_REQUEST",
            "RESPONSE_TOO_LARGE",
        )
    }
}

internal class BrowserDocumentUrlTracker {
    private var startedUrl: String? = null
    private var committedUrl: String? = null
    private val navigationUrls = mutableListOf<String>()

    fun reset() {
        startedUrl = null
        committedUrl = null
        navigationUrls.clear()
    }

    fun onPageStarted(url: String) {
        validatedUrlOrNull(url)?.let {
            startedUrl = it
            record(it)
        }
    }

    fun onPageCommitVisible(url: String) {
        validatedUrlOrNull(url)?.let {
            committedUrl = it
            record(it)
        }
    }

    fun onNavigationCandidate(url: String) {
        validatedUrlOrNull(url)?.let(::record)
    }

    fun resolve(finalUrl: String): String =
        startedUrl ?: committedUrl ?: validatedUrlOrNull(finalUrl) ?: finalUrl

    fun candidates(finalUrl: String): List<String> {
        val final = validatedUrlOrNull(finalUrl)
        return if (final == null || navigationUrls.lastOrNull() == final) {
            navigationUrls.toList()
        } else {
            navigationUrls.toList() + final
        }
    }

    private fun record(url: String) {
        if (navigationUrls.lastOrNull() == url) return
        if (navigationUrls.size == MAX_CANDIDATES) navigationUrls.removeAt(0)
        navigationUrls += url
    }

    private fun validatedUrlOrNull(value: String): String? {
        val parsed = value.toHttpUrlOrNull() ?: return null
        return value.takeIf { parsed.scheme == "http" || parsed.scheme == "https" }
    }

    private companion object {
        const val MAX_CANDIDATES = 32
    }
}

internal object BrowserDocumentStartSignal {
    const val OBJECT_NAME = "__moneylook_document_start_signal__"

    fun script(objectName: String, token: String, gson: Gson): String {
        val objectNameLiteral = gson.toJson(objectName)
        val tokenLiteral = gson.toJson(token)
        return """
            (function() {
                try {
                    window[$objectNameLiteral].postMessage(JSON.stringify({
                        token: $tokenLiteral,
                        url: String(window.location.href)
                    }));
                } catch (_) {}
            })();
        """.trimIndent()
    }

    fun message(token: String, url: String, gson: Gson): String =
        gson.toJson(mapOf("token" to token, "url" to url))

    fun parse(
        message: String,
        expectedToken: String,
        sourceOrigin: String,
        gson: Gson,
    ): String? {
        if (message.length > MAX_MESSAGE_CHARS) return null
        val root = try {
            gson.fromJson(message, JsonObject::class.java)
        } catch (e: Exception) {
            return null
        } ?: return null
        val token = root.get("token")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
        if (token != expectedToken) return null
        val url = root.get("url")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?: return null
        return url.takeIf { sameOrigin(it, sourceOrigin) }
    }

    private const val MAX_MESSAGE_CHARS = 20_000
}

internal class BrowserTargetNavigationInterceptor(
    private val onIntercept: (String) -> Unit,
) {
    @Volatile
    private var expectedUrl: String? = null

    fun arm(expectedUrl: String?) {
        this.expectedUrl = expectedUrl
    }

    fun clear() {
        expectedUrl = null
    }

    @Synchronized
    fun shouldIntercept(candidate: String, isForMainFrame: Boolean): Boolean {
        val expected = expectedUrl ?: return false
        if (!isForMainFrame || !sameDocumentRoute(candidate, expected)) return false
        expectedUrl = null
        onIntercept(candidate)
        return true
    }
}

internal class BrowserResourceUrlTracker {
    private val urls = mutableListOf<String>()

    @Synchronized
    fun reset() {
        urls.clear()
    }

    @Synchronized
    fun record(url: String) {
        val parsed = url.toHttpUrlOrNull() ?: return
        if (parsed.scheme != "http" && parsed.scheme != "https") return
        if (urls.lastOrNull() == url) return
        if (urls.size == MAX_CANDIDATES) urls.removeAt(0)
        urls += url
    }

    @Synchronized
    fun snapshot(): List<String> = urls.toList()

    private companion object {
        const val MAX_CANDIDATES = 256
    }
}

internal fun startOpenNavigation(view: WebView, request: BrowserOpenRequest) {
    request.userAgent?.let { view.settings.userAgentString = it }
    view.loadUrl(request.url)
}

internal fun startFormPostNavigation(view: WebView, request: BrowserFormPostRequest) {
    view.postUrl(request.url, request.body)
}

internal fun navigationMayCompleteFromPageFinished(returnAtDocumentStartUrl: String?): Boolean =
    returnAtDocumentStartUrl == null

internal fun sameDocumentRoute(candidate: String, expected: String): Boolean {
    val candidateUrl = candidate.toHttpUrlOrNull() ?: return false
    val expectedUrl = expected.toHttpUrlOrNull() ?: return false
    return candidateUrl.scheme == expectedUrl.scheme &&
        candidateUrl.host == expectedUrl.host &&
        candidateUrl.port == expectedUrl.port &&
        candidateUrl.encodedPath == expectedUrl.encodedPath &&
        expectedUrl.queryParameterNames.all { name ->
            !candidateUrl.queryParameter(name).isNullOrEmpty()
        }
}

internal fun sameOrigin(candidate: String, expectedOrigin: String): Boolean {
    val candidateUrl = candidate.toHttpUrlOrNull() ?: return false
    val expectedUrl = expectedOrigin.toHttpUrlOrNull() ?: return false
    return candidateUrl.scheme == expectedUrl.scheme &&
        candidateUrl.host == expectedUrl.host &&
        candidateUrl.port == expectedUrl.port
}

private fun HttpUrl.origin(): String {
    val defaultPort = if (scheme == "https") 443 else 80
    val displayHost = if (host.contains(':')) "[$host]" else host
    return "$scheme://$displayHost${if (port == defaultPort) "" else ":$port"}"
}

private object BrowserProfileLifecycle {
    private val cleaned = AtomicBoolean(false)

    fun cleanupOrphansOnce() {
        if (!cleaned.compareAndSet(false, true)) return
        try {
            val store = ProfileStore.getInstance()
            store.allProfileNames
                .filter { it.startsWith("moneylook-run-") }
                .forEach { name -> runCatching { store.deleteProfile(name) } }
        } catch (_: Exception) {
            // Unsupported or currently-live profiles are left for the next process start.
        }
    }

    fun delete(name: String) {
        try {
            ProfileStore.getInstance().deleteProfile(name)
        } catch (_: Exception) {
            // A unique profile is never reused; an orphan is retried on the next process start.
        }
    }
}
