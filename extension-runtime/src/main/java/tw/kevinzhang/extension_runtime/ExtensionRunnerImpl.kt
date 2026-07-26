package tw.kevinzhang.extension_runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.browser.BrowserWebViewSession
import tw.kevinzhang.extension_runtime.browser.NativeSdkBrowserBridge
import tw.kevinzhang.extension_runtime.bridge.HttpRequestJsonParser
import tw.kevinzhang.extension_runtime.bridge.NativeHttpTransport
import tw.kevinzhang.extension_runtime.bridge.RunRequestBudget
import tw.kevinzhang.extension_runtime.bridge.SafeHttpException
import tw.kevinzhang.extension_runtime.capture.ResponseCaptureCollector
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.CardData
import tw.kevinzhang.extension_runtime.data.CapturedSourceDocument
import tw.kevinzhang.extension_runtime.data.KindSyncResult
import tw.kevinzhang.extension_runtime.data.KindSyncStatus
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.TransferData
import tw.kevinzhang.extension_runtime.data.TransferSyncData
import tw.kevinzhang.extension_runtime.data.TransferSyncRangeData
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import javax.inject.Inject

class ExtensionRunnerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) : ExtensionRunner {

    override suspend fun run(
        extension: InstalledExtension,
        credential: ExtensionCredential,
        syncContext: ExtensionSyncContext,
    ): SyncResult = withContext(Dispatchers.IO) {
        val runId = UUID.randomUUID().toString()
        val runStartedAt = System.currentTimeMillis()
        val captureCollector = ResponseCaptureCollector(gson)
        val scriptFile = File(extension.syncTriggerCachePath)
        if (!scriptFile.isFile) return@withContext SyncResult.Error(
            message = "extension script file not found",
            origin = "RUNTIME",
            runId = runId,
            runStartedAt = runStartedAt,
        )
        if (scriptFile.length() > MAX_SCRIPT_BYTES) {
            return@withContext SyncResult.Error(
                message = "extension script exceeds size limit",
                origin = "RUNTIME",
                runId = runId,
                runStartedAt = runStartedAt,
            )
        }
        val script = try {
            scriptFile.readText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext SyncResult.Error(
                message = "extension script could not be read",
                origin = "RUNTIME",
                rawMessage = e.message,
                rawStack = e.stackTraceToString(),
                runId = runId,
                runStartedAt = runStartedAt,
            )
        }
        val canonicalCredentialJson = canonicalCredentialJson(credential.json)
            ?: return@withContext SyncResult.Error(
                message = "stored credential JSON is invalid",
                origin = "RUNTIME",
                runId = runId,
                runStartedAt = runStartedAt,
            )
        val outcome = try {
            runInWebView(
                script = script,
                credential = ExtensionCredential(canonicalCredentialJson),
                syncContext = syncContext,
                captureCollector = captureCollector,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runtimeFailure(e, captureCollector)
        }
        outcome.withAttemptIdentity(runId, runStartedAt)
    }

    private suspend fun runInWebView(
        script: String,
        credential: ExtensionCredential,
        syncContext: ExtensionSyncContext,
        captureCollector: ResponseCaptureCollector,
    ): SyncResult = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<SyncResult>()
        val webView = WebView(context)
        val requestBudget = RunRequestBudget()
        val httpBridge = NativeSdkHttpBridge(
            webView = webView,
            transport = NativeHttpTransport(okHttpClient),
            requestBudget = requestBudget,
            gson = gson,
            captureCollector = captureCollector,
        )
        val browserBridge = NativeSdkBrowserBridge(
            controllerWebView = webView,
            session = BrowserWebViewSession(context, gson),
            requestBudget = requestBudget,
            gson = gson,
            captureCollector = captureCollector,
        )
        @Suppress("SetJavaScriptEnabled")
        webView.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        webView.addJavascriptInterface(httpBridge, "__native_http__")
        webView.addJavascriptInterface(browserBridge, "__native_browser__")
        webView.addJavascriptInterface(ScriptResultBridge(deferred, gson), "__result_bridge__")
        val evaluated = AtomicBoolean(false)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

            override fun onPageFinished(view: WebView, url: String) {
                if (evaluated.compareAndSet(false, true)) {
                    view.evaluateJavascript(buildWrappedScript(script, credential, syncContext), null)
                }
            }
        }
        webView.loadData("<html><body></body></html>", "text/html", "utf-8")
        var pendingFailure: Exception? = null
        val outcome: SyncResult? = try {
            withTimeout(SCRIPT_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            SyncResult.Error(
                origin = "RUNTIME",
                message = "extension script timed out",
                rawMessage = e.message,
                rawStack = e.stackTraceToString(),
            )
        } catch (e: CancellationException) {
            pendingFailure = e
            null
        } catch (e: Exception) {
            pendingFailure = e
            null
        }
        val cleanupFailure = cleanupRunResources(browserBridge, httpBridge, webView)
        pendingFailure?.let { failure ->
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
        val completedOutcome = checkNotNull(outcome) { "extension outcome was not completed" }
        if (cleanupFailure != null) {
            if (cleanupFailure is CancellationException) throw cleanupFailure
            return@withContext runtimeCleanupFailure(
                error = cleanupFailure,
                captureCollector = captureCollector,
                completedOutcome = completedOutcome,
            )
        }
        completedOutcome.withCapturedSources(captureCollector)
    }

    private fun cleanupRunResources(
        browserBridge: NativeSdkBrowserBridge,
        httpBridge: NativeSdkHttpBridge,
        webView: WebView,
    ): Exception? {
        var failure: Exception? = null
        fun record(error: Exception) {
            val first = failure
            when {
                first == null -> failure = error
                error is CancellationException && first !is CancellationException -> {
                    error.addSuppressed(first)
                    failure = error
                }
                else -> first.addSuppressed(error)
            }
        }
        fun attempt(block: () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                record(e)
            } catch (e: Exception) {
                record(e)
            }
        }
        attempt(browserBridge::cancel)
        attempt(httpBridge::cancel)
        attempt { webView.removeJavascriptInterface("__native_http__") }
        attempt { webView.removeJavascriptInterface("__native_browser__") }
        attempt { webView.removeJavascriptInterface("__result_bridge__") }
        attempt(webView::stopLoading)
        attempt(webView::destroy)
        return failure
    }

    internal fun buildWrappedScript(
        script: String,
        credential: ExtensionCredential,
        syncContext: ExtensionSyncContext,
    ): String {
        val scriptLiteral = gson.toJson(script)
        val credentialLiteral = requireNotNull(canonicalCredentialJson(credential.json)) {
            "stored credential JSON is invalid"
        }
        val syncContextLiteral = gson.toJson(syncContext)
        return """
            (async function() {
                'use strict';
                window.fetch = undefined;
                window.XMLHttpRequest = undefined;
                const __pending = new Map();
                let __nextRequestId = 1;
                window.__sdkResolve = function(id, payloadJson, errorJson) {
                    const pending = __pending.get(id);
                    if (!pending) return;
                    __pending.delete(id);
                    if (errorJson !== null) pending.reject(JSON.parse(errorJson));
                    else pending.resolve(JSON.parse(payloadJson));
                };
                const request = function(options) {
                    return new Promise(function(resolve, reject) {
                        const id = String(__nextRequestId++);
                        __pending.set(id, { resolve: resolve, reject: reject });
                        try {
                            __native_http__.request(id, JSON.stringify(options || {}));
                        } catch (bridgeError) {
                            __pending.delete(id);
                            reject({
                                origin: 'NATIVE_BRIDGE',
                                code: 'BRIDGE_ERROR',
                                message: 'native HTTP bridge invocation failed',
                                bridgeError: bridgeError
                            });
                        }
                    });
                };
                const http = Object.freeze({
                    request: request,
                    all: function(requests) { return Promise.all(requests.map(request)); },
                    allSettled: function(requests) {
                        return Promise.all(requests.map(function(item) {
                            return request(item).then(
                                function(value) { return { status: 'fulfilled', value: value }; },
                                function(reason) { return { status: 'rejected', reason: reason }; }
                            );
                        }));
                    }
                });
                const __browserPending = new Map();
                let __nextBrowserRequestId = 1;
                window.__sdkBrowserResolve = function(id, payloadJson, errorJson) {
                    const pending = __browserPending.get(id);
                    if (!pending) return;
                    __browserPending.delete(id);
                    if (errorJson !== null) pending.reject(JSON.parse(errorJson));
                    else pending.resolve(JSON.parse(payloadJson));
                };
                const browserCall = function(method, options) {
                    return new Promise(function(resolve, reject) {
                        const id = String(__nextBrowserRequestId++);
                        __browserPending.set(id, { resolve: resolve, reject: reject });
                        try {
                            if (method === 'open') {
                                __native_browser__.open(id, JSON.stringify(options || {}));
                            } else if (method === 'post') {
                                __native_browser__.post(id, JSON.stringify(options || {}));
                            } else {
                                __native_browser__.request(id, JSON.stringify(options || {}));
                            }
                        } catch (bridgeError) {
                            __browserPending.delete(id);
                            reject({
                                origin: 'NATIVE_BRIDGE',
                                code: 'BRIDGE_ERROR',
                                message: 'native browser bridge invocation failed',
                                bridgeError: bridgeError
                            });
                        }
                    });
                };
                const browser = Object.freeze({
                    open: function(options) { return browserCall('open', options); },
                    post: function(options) { return browserCall('post', options); },
                    request: function(options) { return browserCall('request', options); },
                    close: function() {
                        __browserPending.forEach(function(pending) {
                            pending.reject({ code: 'BROWSER_CLOSED', message: 'browser session closed' });
                        });
                        __browserPending.clear();
                        __native_browser__.close();
                    }
                });
                const deepFreeze = function(value) {
                    if (value === null || typeof value !== 'object' || Object.isFrozen(value)) return value;
                    Object.getOwnPropertyNames(value).forEach(function(name) {
                        deepFreeze(value[name]);
                    });
                    return Object.freeze(value);
                };
                const diagnosticText = function(value, fallback) {
                    try {
                        return String(value);
                    } catch (_) {
                        return fallback;
                    }
                };
                const diagnosticRead = function(value, name) {
                    try {
                        return { ok: true, value: value[name] };
                    } catch (readError) {
                        return {
                            ok: false,
                            error: diagnosticText(readError, '[unprintable-property-error]')
                        };
                    }
                };
                const diagnosticValue = function(value, seen, depth) {
                    try {
                        if (value === null || value === undefined) {
                            return value === undefined ? '[undefined]' : null;
                        }
                        const type = typeof value;
                        if (type === 'string' || type === 'boolean') return value;
                        if (type === 'number') return Number.isFinite(value) ? value : diagnosticText(value, '[number]');
                        if (type === 'bigint' || type === 'symbol' || type === 'function') {
                            return diagnosticText(value, '[unprintable-' + type + ']');
                        }
                        if (depth > 12) return '[max-depth]';
                        if (seen.indexOf(value) !== -1) return '[circular]';
                        seen.push(value);
                        let names;
                        try {
                            names = Object.getOwnPropertyNames(value);
                        } catch (propertyNamesError) {
                            seen.pop();
                            return {
                                '[property-names-error]': diagnosticText(
                                    propertyNamesError,
                                    '[unprintable-property-names-error]'
                                )
                            };
                        }
                        let result;
                        try {
                            result = Array.isArray(value) ? [] : {};
                        } catch (arrayCheckError) {
                            result = {
                                '[array-check-error]': diagnosticText(
                                    arrayCheckError,
                                    '[unprintable-array-check-error]'
                                )
                            };
                        }
                        names.forEach(function(name) {
                            const property = diagnosticRead(value, name);
                            result[name] = property.ok
                                ? diagnosticValue(property.value, seen, depth + 1)
                                : '[property-error: ' + property.error + ']';
                        });
                        seen.pop();
                        return result;
                    } catch (diagnosticError) {
                        return {
                            '[diagnostic-error]': diagnosticText(
                                diagnosticError,
                                '[unprintable-diagnostic-error]'
                            )
                        };
                    }
                };
                const sdk = Object.freeze({
                    credential: deepFreeze($credentialLiteral),
                    sync: deepFreeze($syncContextLiteral),
                    http: http,
                    browser: browser
                });
                try {
                    const result = await eval($scriptLiteral);
                    __result_bridge__.onResult(JSON.stringify(result));
                } catch (error) {
                    const isObject = (typeof error === 'object' && error !== null) ||
                        typeof error === 'function';
                    const codeProperty = isObject
                        ? diagnosticRead(error, 'code')
                        : { ok: true, value: null };
                    const messageProperty = isObject
                        ? diagnosticRead(error, 'message')
                        : { ok: true, value: null };
                    const stackProperty = isObject
                        ? diagnosticRead(error, 'stack')
                        : { ok: true, value: null };
                    const originProperty = isObject
                        ? diagnosticRead(error, 'origin')
                        : { ok: true, value: null };
                    const code = codeProperty.ok && typeof codeProperty.value === 'string'
                        ? codeProperty.value : null;
                    const stack = stackProperty.ok && typeof stackProperty.value === 'string'
                        ? stackProperty.value : '';
                    const match = stack.match(/:(\\d+):(\\d+)/);
                    const frame = match ? ('line ' + match[1] + ', column ' + match[2]) : null;
                    const thrown = diagnosticValue(error, [], 0);
                    const message = messageProperty.ok && typeof messageProperty.value === 'string'
                        ? messageProperty.value
                        : (typeof error === 'string'
                            ? error
                            : diagnosticText(error, '[unprintable-thrown-value]'));
                    const origin = originProperty.ok && originProperty.value === 'NATIVE_BRIDGE'
                        ? 'NATIVE_BRIDGE' : 'SCRIPT';
                    let diagnosticJson;
                    try {
                        diagnosticJson = JSON.stringify({
                            origin: origin,
                            code: code,
                            message: message,
                            stack: stack,
                            frame: frame,
                            thrown: thrown
                        });
                    } catch (serializationError) {
                        diagnosticJson = JSON.stringify({
                            origin: 'SCRIPT',
                            code: null,
                            message: '[diagnostic serialization failed]',
                            stack: '',
                            frame: null,
                            thrown: {
                                '[serialization-error]': diagnosticText(
                                    serializationError,
                                    '[unprintable-serialization-error]'
                                )
                            }
                        });
                    }
                    __result_bridge__.onError(diagnosticJson);
                } finally {
                    browser.close();
                }
            })();
        """.trimIndent()
    }

    internal fun canonicalCredentialJson(json: String): String? {
        return try {
            val element = JsonParser.parseString(json)
            if (!element.isJsonObject) return null
            val credential = element.asJsonObject
            if (credential.entrySet().any { (_, value) -> !value.isJsonPrimitive || !value.asJsonPrimitive.isString }) {
                return null
            }
            gson.toJson(credential)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
        const val SCRIPT_TIMEOUT_MS = 60_000L
    }
}

private class NativeSdkHttpBridge(
    private val webView: WebView,
    private val transport: NativeHttpTransport,
    private val requestBudget: RunRequestBudget,
    private val gson: Gson,
    private val captureCollector: ResponseCaptureCollector,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)

    @JavascriptInterface
    fun request(id: String, requestJson: String) {
        if (!active.get()) return
        try {
            requestBudget.acquire()
            if (requestJson.length > MAX_REQUEST_JSON_CHARS) {
                throw SafeHttpException("REQUEST_TOO_LARGE", "HTTP request rejected")
            }
        } catch (e: Exception) {
            postResult(id, null, gson.toJson(safeBridgeError(e)))
            return
        }
        scope.launch {
            try {
                val request = HttpRequestJsonParser.parse(requestJson, gson)
                val response = transport.execute(request)
                val sourceDocumentId = captureCollector.capture(
                    options = request.capture,
                    transport = "native_http",
                    method = request.method,
                    url = request.url,
                    statusCode = response.status,
                    headers = response.headers,
                    body = response.body,
                    bodyEncoding = response.bodyEncoding,
                    representation = "exact_bytes",
                    exactBodyBytes = response.exactBodyBytes,
                )
                postResult(id, gson.toJson(response.copy(sourceDocumentId = sourceDocumentId)), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postResult(id, null, gson.toJson(safeBridgeError(e)))
            }
        }
    }

    fun cancel() {
        active.set(false)
        scope.cancel()
    }

    private fun postResult(id: String, resultJson: String?, errorJson: String?) {
        val idLiteral = gson.toJson(id)
        val resultLiteral = resultJson?.let(gson::toJson) ?: "null"
        val errorLiteral = errorJson?.let(gson::toJson) ?: "null"
        mainHandler.post {
            if (active.get()) {
                webView.evaluateJavascript(
                    "window.__sdkResolve($idLiteral,$resultLiteral,$errorLiteral);",
                    null,
                )
            }
        }
    }

    private companion object {
        const val MAX_REQUEST_JSON_CHARS = 4 * 1024 * 1024
    }
}

internal fun runtimeFailure(
    error: Exception,
    captureCollector: ResponseCaptureCollector,
): SyncResult.Error = SyncResult.Error(
    message = "extension runtime failed",
    origin = "RUNTIME",
    cause = error,
    rawMessage = error.message ?: error.toString(),
    rawStack = error.stackTraceToString(),
    sourceDocuments = captureCollector.snapshot(),
)

internal fun runtimeCleanupFailure(
    error: Exception,
    captureCollector: ResponseCaptureCollector,
    completedOutcome: SyncResult,
): SyncResult.Error {
    if (completedOutcome is SyncResult.Error) {
        completedOutcome.cause
            ?.takeUnless { it === error }
            ?.let(error::addSuppressed)
    }
    val failure = runtimeFailure(error, captureCollector)
    val completedDocuments = when (completedOutcome) {
        is SyncResult.Success -> completedOutcome.sourceDocuments
        is SyncResult.Error -> completedOutcome.sourceDocuments
    }
    return failure.copy(
        rawDiagnosticJson = (completedOutcome as? SyncResult.Error)?.rawDiagnosticJson,
        sourceDocuments = failure.sourceDocuments + completedDocuments,
    )
}

internal fun SyncResult.withCapturedSources(
    captureCollector: ResponseCaptureCollector,
): SyncResult = when (this) {
    is SyncResult.Success -> copy(
        sourceDocuments = captureCollector.snapshot() + sourceDocuments,
    )
    is SyncResult.Error -> copy(
        sourceDocuments = captureCollector.snapshot() + sourceDocuments,
    )
}

internal fun SyncResult.withAttemptIdentity(
    runId: String,
    runStartedAt: Long,
): SyncResult = when (this) {
    is SyncResult.Success -> copy(runId = runId, runStartedAt = runStartedAt)
    is SyncResult.Error -> copy(runId = runId, runStartedAt = runStartedAt)
}

internal data class SafeBridgeError(
    val origin: String = "NATIVE_BRIDGE",
    val code: String,
    val message: String,
    val stack: String,
    val exceptionType: String,
)

internal fun safeBridgeError(error: Exception): SafeBridgeError = SafeBridgeError(
    code = if (error is SafeHttpException) error.code else "HTTP_ERROR",
    message = error.message ?: error.toString(),
    stack = error.stackTraceToString(),
    exceptionType = error.javaClass.name,
)

internal class ScriptResultBridge(
    private val deferred: CompletableDeferred<SyncResult>,
    private val gson: Gson,
) {
    @JavascriptInterface
    fun onResult(json: String) {
        val result = parseAccounts(json, gson) ?: SyncResult.Error("script result missing 'accounts'")
        val rawResultDocument = rawExtensionDocument("extension.result", "result", json)
        deferred.complete(
            when (result) {
                is SyncResult.Error -> result.copy(
                    rawMessage = result.rawMessage ?: result.message,
                    rawDiagnosticJson = result.rawDiagnosticJson ?: json,
                    sourceDocuments = result.sourceDocuments + rawResultDocument,
                )
                is SyncResult.Success -> result.copy(
                    sourceDocuments = result.sourceDocuments + rawResultDocument,
                )
            },
        )
    }

    @JavascriptInterface
    fun onError(diagnosticJson: String) {
        val values = runCatching {
            @Suppress("UNCHECKED_CAST") gson.fromJson(diagnosticJson, Map::class.java) as Map<String, Any?>
        }.getOrNull().orEmpty()
        val origin = values["origin"] as? String ?: "SCRIPT"
        val code = values["code"] as? String
        val frame = values["frame"] as? String
        val rawMessage = values["message"] as? String
        val rawStack = values["stack"] as? String
        val rawErrorDocument = rawExtensionDocument("extension.error", "error", diagnosticJson)
        deferred.complete(
            SyncResult.Error(
                origin = origin,
                message = "extension script failed",
                code = code,
                scriptFrame = frame,
                rawMessage = rawMessage,
                rawStack = rawStack,
                rawDiagnosticJson = diagnosticJson,
                sourceDocuments = listOf(rawErrorDocument),
            ),
        )
    }

    private fun rawExtensionDocument(
        stage: String,
        path: String,
        json: String,
    ) = CapturedSourceDocument(
        id = UUID.randomUUID().toString(),
        capturedAt = System.currentTimeMillis(),
        stage = stage,
        transport = "extension_runtime",
        method = "RETURN",
        url = "extension-runtime://script/$path",
        statusCode = null,
        responseHeadersJson = "{}",
        mediaKind = "application/json",
        bodyEncoding = "utf-8",
        representation = "decoded_text",
        bodyBytes = json.toByteArray(Charsets.UTF_8),
    )
}

internal fun parseAccounts(json: String, gson: Gson): SyncResult? = try {
    val type = object : TypeToken<Map<String, Any>>() {}.type
    val map: Map<String, Any> = gson.fromJson(json, type)
    val rawList = map["accounts"] as? List<*> ?: return null
    val accounts = rawList.map { item ->
        val account = item as? Map<*, *> ?: return SyncResult.Error("script result contains invalid account")
        val name = account["name"] as? String
            ?: return SyncResult.Error("script result contains invalid account")
        val balance = finiteNumber(account["balance"])
            ?: return SyncResult.Error("script result contains invalid account balance")
        val currency = account["currency"] as? String ?: "TWD"
        val kind = (if ("kind" in account) parseAssetKind(account["kind"]) else AssetKind.DEPOSIT)
            ?: return SyncResult.Error("script result contains invalid account kind")
        val no = optionalString(account, "no")
            ?: return SyncResult.Error("script result contains invalid account")
        val sourceAccountKey = optionalNonBlankString(account, "sourceAccountKey")
            ?: return SyncResult.Error("script result contains invalid source account key")
        if (sourceAccountKey.value != null &&
            (!SOURCE_ACCOUNT_KEY_PATTERN.matches(sourceAccountKey.value) || sourceAccountKey.value == no.value)
        ) {
            return SyncResult.Error("script result contains invalid source account key")
        }
        val branchName = optionalString(account, "branchName")
            ?: return SyncResult.Error("script result contains invalid account")
        val availableCredit = optionalFiniteNumber(account, "availableCredit")
            ?: return SyncResult.Error("script result contains invalid available credit")
        val creditLimit = optionalFiniteNumber(account, "creditLimit")
            ?: return SyncResult.Error("script result contains invalid credit limit")
        if (kind != AssetKind.CREDIT_CARD && (availableCredit.value != null || creditLimit.value != null)) {
            return SyncResult.Error("script result contains credit fields for a non-card account")
        }
        val cards = if ("cards" in account) {
            if (kind != AssetKind.CREDIT_CARD) {
                return SyncResult.Error("script result contains cards for a non-card account")
            }
            parseCards(account) ?: return SyncResult.Error("script result contains invalid card")
        } else {
            emptyList()
        }
        val cardsComplete = when {
            "cardsComplete" !in account -> null
            kind != AssetKind.CREDIT_CARD -> {
                return SyncResult.Error("script result contains card status for a non-card account")
            }
            "cards" !in account -> return SyncResult.Error("script result contains card status without cards")
            account["cardsComplete"] is Boolean -> account["cardsComplete"] as Boolean
            else -> return SyncResult.Error("script result contains invalid card status")
        }
        if (kind != AssetKind.DEPOSIT && balance < 0) {
            return SyncResult.Error("script result contains invalid debt balance")
        }
        val sourceRecord = optionalMap(account, "sourceRecord")
            ?: return SyncResult.Error("script result contains invalid account source record")
        val sourceFields = optionalMap(account, "sourceFields")
            ?: return SyncResult.Error("script result contains invalid account source fields")
        val sourceFacts = optionalMap(account, "sourceFacts")
            ?: return SyncResult.Error("script result contains invalid account source facts")
        val parserVersion = optionalNonBlankString(account, "parserVersion")
            ?: return SyncResult.Error("script result contains invalid account parser version")

        val transferSync = if ("transferSync" in account) {
            parseTransferSync(account) ?: return SyncResult.Error("script result contains invalid transfer sync")
        } else {
            null
        }
        val transfers = parseTransfers(account, requireIsoDate = transferSync != null, cards = cards)
            ?: return SyncResult.Error("script result contains invalid transfer")
        if (transferSync != null && transfers.any { transfer ->
                !transferDateInRequestedRange(transfer.txnDateTime, transferSync)
            }
        ) {
            return SyncResult.Error("script result contains transfer outside requested range")
        }
        AccountData(
            name = name,
            balance = balance,
            currency = currency,
            no = no.value,
            sourceAccountKey = sourceAccountKey.value,
            kind = kind,
            branchName = branchName.value,
            availableCredit = availableCredit.value,
            creditLimit = creditLimit.value,
            cards = cards,
            cardsComplete = cardsComplete,
            transfers = transfers,
            transferSync = transferSync,
            sourceRecord = sourceRecord.value,
            sourceFields = sourceFields.value,
            sourceFacts = sourceFacts.value,
            parserVersion = parserVersion.value,
        )
    }
    val kindSync = if ("kindSync" in map) {
        parseKindSync(map, accounts) ?: return SyncResult.Error("script result contains invalid kind sync")
    } else {
        null
    }
    val cursorIdentities = accounts.mapNotNull { account ->
        account.sourceAccountKey?.let { key -> listOf(key, account.kind.name, account.currency) }
    }
    if (cursorIdentities.size != cursorIdentities.distinct().size) {
        SyncResult.Error("script result contains duplicate source account key")
    } else {
        SyncResult.Success(accounts, kindSync)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    SyncResult.Error(
        message = "failed to parse script result",
        origin = "PARSER",
        cause = e,
        rawMessage = e.message ?: e.toString(),
        rawStack = e.stackTraceToString(),
        rawDiagnosticJson = json,
    )
}

private data class OptionalValue<T>(val value: T?)

/** Source keys are deliberately constrained so raw account/card numbers cannot enter sdk.sync. */
private val SOURCE_ACCOUNT_KEY_PATTERN = Regex("[0-9a-f]{64}")
private val KIND_SYNC_CODE_PATTERN = Regex("[A-Z0-9_]{1,32}")
private val CARD_REF_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.:-]{0,127}")
private val LAST_FOUR_PATTERN = Regex("[0-9]{4}")
private val MERCHANT_CATEGORY_CODE_PATTERN = Regex("[0-9]{4}")

private fun parseKindSync(
    root: Map<String, Any>,
    accounts: List<AccountData>,
): List<KindSyncResult>? {
    val rawResults = root["kindSync"] as? List<*> ?: return null
    val results = rawResults.map { raw ->
        val result = raw as? Map<*, *> ?: return null
        val kind = parseAssetKind(result["kind"]) ?: return null
        val status = parseKindSyncStatus(result["status"]) ?: return null
        val code = when {
            "code" !in result -> null
            result["code"] is String && KIND_SYNC_CODE_PATTERN.matches(result["code"] as String) -> result["code"] as String
            else -> return null
        }
        val rawMessage = optionalString(result, "rawMessage") ?: return null
        val rawStack = optionalString(result, "rawStack") ?: return null
        val rawDiagnosticJson = optionalString(result, "rawDiagnosticJson") ?: return null
        if (
            status == KindSyncStatus.FAILED &&
            (rawMessage.value == null || rawDiagnosticJson.value == null)
        ) {
            return null
        }
        KindSyncResult(
            kind = kind,
            status = status,
            code = code,
            rawMessage = rawMessage.value,
            rawStack = rawStack.value,
            rawDiagnosticJson = rawDiagnosticJson.value,
        )
    }
    if (results.map { it.kind }.distinct().size != results.size) return null
    if (results.none { it.status == KindSyncStatus.COMPLETE }) return null
    val completeKinds = results
        .filter { it.status == KindSyncStatus.COMPLETE }
        .map { it.kind }
        .toSet()
    if (accounts.any { it.kind !in completeKinds }) return null
    return results
}

private fun parseKindSyncStatus(value: Any?): KindSyncStatus? = when (value) {
    "complete" -> KindSyncStatus.COMPLETE
    "failed" -> KindSyncStatus.FAILED
    "not_applicable" -> KindSyncStatus.NOT_APPLICABLE
    else -> null
}

private fun optionalString(account: Map<*, *>, key: String): OptionalValue<String>? = when {
    key !in account -> OptionalValue(null)
    account[key] is String -> OptionalValue(account[key] as String)
    else -> null
}

private fun optionalNonBlankString(account: Map<*, *>, key: String): OptionalValue<String>? = when {
    key !in account -> OptionalValue(null)
    account[key] is String && (account[key] as String).isNotBlank() -> OptionalValue(account[key] as String)
    else -> null
}

private fun optionalFiniteNumber(account: Map<*, *>, key: String): OptionalValue<Double>? = when {
    key !in account -> OptionalValue(null)
    else -> finiteNumber(account[key])?.let(::OptionalValue)
}

private fun optionalInt(account: Map<*, *>, key: String): OptionalValue<Int>? = when {
    key !in account -> OptionalValue(null)
    else -> finiteNumber(account[key])
        ?.takeIf { it == it.toInt().toDouble() }
        ?.toInt()
        ?.let(::OptionalValue)
}

private fun optionalBoolean(account: Map<*, *>, key: String): OptionalValue<Boolean>? = when {
    key !in account -> OptionalValue(null)
    account[key] is Boolean -> OptionalValue(account[key] as Boolean)
    else -> null
}

@Suppress("UNCHECKED_CAST")
private fun optionalMap(account: Map<*, *>, key: String): OptionalValue<Map<String, Any?>>? = when {
    key !in account -> OptionalValue(null)
    account[key] is Map<*, *> && (account[key] as Map<*, *>).keys.all { it is String } ->
        OptionalValue(account[key] as Map<String, Any?>)
    else -> null
}

private fun finiteNumber(value: Any?): Double? =
    (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

private fun parseTransfers(
    account: Map<*, *>,
    requireIsoDate: Boolean,
    cards: List<CardData>,
): List<TransferData>? {
    if ("transfers" !in account) return emptyList()
    val rawTransfers = account["transfers"] as? List<*> ?: return null
    return rawTransfers.map { rawTransfer ->
        val transfer = rawTransfer as? Map<*, *> ?: return null
        val txnDateTime = (transfer["txnDateTime"] as? String)
            ?.takeIf { it.isNotBlank() && (!requireIsoDate || parseTransferDate(it) != null) }
            ?: return null
        val description = optionalString(transfer, "description") ?: return null
        val memo = optionalString(transfer, "memo") ?: return null
        val amount = finiteNumber(transfer["amount"]) ?: return null
        val balance = optionalFiniteNumber(transfer, "balance") ?: return null
        val id = optionalString(transfer, "id")?.value
        if (id != null && id.isBlank()) return null
        val type = optionalNonBlankString(transfer, "type") ?: return null
        val status = optionalNonBlankString(transfer, "status") ?: return null
        val postingDateTime = optionalNonBlankString(transfer, "postingDateTime") ?: return null
        if (postingDateTime.value != null && parseTransferDate(postingDateTime.value) == null) {
            return null
        }
        val cardRef = optionalNonBlankString(transfer, "cardRef") ?: return null
        if (cardRef.value != null && cardRef.value !in cards.map(CardData::ref).toSet()) return null
        val merchantName = optionalNonBlankString(transfer, "merchantName") ?: return null
        val merchantCategoryCode = optionalNonBlankString(transfer, "merchantCategoryCode") ?: return null
        if (merchantCategoryCode.value != null &&
            !MERCHANT_CATEGORY_CODE_PATTERN.matches(merchantCategoryCode.value)
        ) {
            return null
        }
        val counterpartyName = optionalNonBlankString(transfer, "counterpartyName") ?: return null
        val purpose = optionalNonBlankString(transfer, "purpose") ?: return null
        val authorizationDateTime = optionalNonBlankString(transfer, "authorizationDateTime") ?: return null
        val valueDateTime = optionalNonBlankString(transfer, "valueDateTime") ?: return null
        val referenceNumber = optionalNonBlankString(transfer, "referenceNumber") ?: return null
        val authorizationCode = optionalNonBlankString(transfer, "authorizationCode") ?: return null
        val channel = optionalNonBlankString(transfer, "channel") ?: return null
        val direction = optionalNonBlankString(transfer, "direction") ?: return null
        if (direction.value != null && direction.value !in setOf("debit", "credit")) return null
        val transactionCode = optionalNonBlankString(transfer, "transactionCode") ?: return null
        val originalAmount = optionalFiniteNumber(transfer, "originalAmount") ?: return null
        val originalCurrency = optionalNonBlankString(transfer, "originalCurrency") ?: return null
        val settlementAmount = optionalFiniteNumber(transfer, "settlementAmount") ?: return null
        val settlementCurrency = optionalNonBlankString(transfer, "settlementCurrency") ?: return null
        val exchangeRate = optionalFiniteNumber(transfer, "exchangeRate") ?: return null
        val feeAmount = optionalFiniteNumber(transfer, "feeAmount") ?: return null
        val feeCurrency = optionalNonBlankString(transfer, "feeCurrency") ?: return null
        val taxAmount = optionalFiniteNumber(transfer, "taxAmount") ?: return null
        val taxCurrency = optionalNonBlankString(transfer, "taxCurrency") ?: return null
        val merchantLocation = optionalNonBlankString(transfer, "merchantLocation") ?: return null
        val counterpartyAccount = optionalNonBlankString(transfer, "counterpartyAccount") ?: return null
        val counterpartyBank = optionalNonBlankString(transfer, "counterpartyBank") ?: return null
        val installmentNumber = optionalInt(transfer, "installmentNumber") ?: return null
        val installmentTotal = optionalInt(transfer, "installmentTotal") ?: return null
        if (
            installmentNumber.value != null &&
            (installmentNumber.value <= 0 || installmentTotal.value == null ||
                installmentTotal.value <= 0 || installmentNumber.value > installmentTotal.value)
        ) return null
        val isRefund = optionalBoolean(transfer, "isRefund") ?: return null
        val isReversal = optionalBoolean(transfer, "isReversal") ?: return null
        val originalTransactionSourceId =
            optionalNonBlankString(transfer, "originalTransactionSourceId") ?: return null
        val sourceRecord = optionalMap(transfer, "sourceRecord") ?: return null
        val sourceFields = optionalMap(transfer, "sourceFields") ?: return null
        val sourceFacts = optionalMap(transfer, "sourceFacts") ?: return null
        val parserVersion = optionalNonBlankString(transfer, "parserVersion") ?: return null
        TransferData(
            txnDateTime = txnDateTime,
            description = description.value ?: "",
            amount = amount,
            balance = balance.value,
            memo = memo.value ?: "",
            type = type.value,
            status = status.value,
            id = id,
            postingDateTime = postingDateTime.value,
            cardRef = cardRef.value,
            merchantName = merchantName.value,
            merchantCategoryCode = merchantCategoryCode.value,
            counterpartyName = counterpartyName.value,
            purpose = purpose.value,
            authorizationDateTime = authorizationDateTime.value,
            valueDateTime = valueDateTime.value,
            referenceNumber = referenceNumber.value,
            authorizationCode = authorizationCode.value,
            channel = channel.value,
            direction = direction.value,
            transactionCode = transactionCode.value,
            originalAmount = originalAmount.value,
            originalCurrency = originalCurrency.value,
            settlementAmount = settlementAmount.value,
            settlementCurrency = settlementCurrency.value,
            exchangeRate = exchangeRate.value,
            feeAmount = feeAmount.value,
            feeCurrency = feeCurrency.value,
            taxAmount = taxAmount.value,
            taxCurrency = taxCurrency.value,
            merchantLocation = merchantLocation.value,
            counterpartyAccount = counterpartyAccount.value,
            counterpartyBank = counterpartyBank.value,
            installmentNumber = installmentNumber.value,
            installmentTotal = installmentTotal.value,
            isRefund = isRefund.value,
            isReversal = isReversal.value,
            originalTransactionSourceId = originalTransactionSourceId.value,
            sourceRecord = sourceRecord.value,
            sourceFields = sourceFields.value,
            sourceFacts = sourceFacts.value,
            parserVersion = parserVersion.value,
        )
    }
}

private fun parseCards(account: Map<*, *>): List<CardData>? {
    val rawCards = account["cards"] as? List<*> ?: return null
    val cards = rawCards.map { rawCard ->
        val card = rawCard as? Map<*, *> ?: return null
        if (card.keys.any { key -> isForbiddenCardField(key as? String) }) return null
        val ref = (card["ref"] as? String)?.takeIf(CARD_REF_PATTERN::matches) ?: return null
        val sourceCardKey = optionalNonBlankString(card, "sourceCardKey") ?: return null
        if (sourceCardKey.value != null && !SOURCE_ACCOUNT_KEY_PATTERN.matches(sourceCardKey.value)) return null
        val pan = optionalNonBlankString(card, "pan") ?: return null
        if (pan.value != null && !isValidPan(pan.value)) return null
        val maskedPan = optionalNonBlankString(card, "maskedPan") ?: return null
        if (maskedPan.value != null && maskedPan.value.all(Char::isDigit)) return null
        val lastFour = optionalNonBlankString(card, "lastFour") ?: return null
        if (lastFour.value != null && !LAST_FOUR_PATTERN.matches(lastFour.value)) return null
        if (pan.value != null && lastFour.value != null && !pan.value.endsWith(lastFour.value)) return null
        val displayName = optionalNonBlankString(card, "displayName") ?: return null
        val network = optionalNonBlankString(card, "network") ?: return null
        val productType = optionalNonBlankString(card, "productType") ?: return null
        val holderRole = optionalNonBlankString(card, "holderRole") ?: return null
        if (holderRole.value != null && holderRole.value !in setOf("primary", "supplementary")) return null
        val holderName = optionalNonBlankString(card, "holderName") ?: return null
        val status = optionalNonBlankString(card, "status") ?: return null
        val expiryMonth = optionalInt(card, "expiryMonth") ?: return null
        val expiryYear = optionalInt(card, "expiryYear") ?: return null
        if ((expiryMonth.value == null) != (expiryYear.value == null)) return null
        if (expiryMonth.value != null && expiryMonth.value !in 1..12) return null
        if (expiryYear.value != null && expiryYear.value !in 2000..2200) return null
        val creditLimit = optionalFiniteNumber(card, "creditLimit") ?: return null
        val availableCredit = optionalFiniteNumber(card, "availableCredit") ?: return null
        if (creditLimit.value != null && creditLimit.value < 0.0) return null
        if (availableCredit.value != null && availableCredit.value < 0.0) return null
        val sourceRecord = optionalMap(card, "sourceRecord") ?: return null
        val sourceFields = optionalMap(card, "sourceFields") ?: return null
        val sourceFacts = optionalMap(card, "sourceFacts") ?: return null
        val parserVersion = optionalNonBlankString(card, "parserVersion") ?: return null
        CardData(
            ref = ref,
            sourceCardKey = sourceCardKey.value,
            pan = pan.value,
            maskedPan = maskedPan.value,
            lastFour = lastFour.value,
            displayName = displayName.value,
            network = network.value,
            productType = productType.value,
            holderRole = holderRole.value,
            holderName = holderName.value,
            status = status.value,
            expiryMonth = expiryMonth.value,
            expiryYear = expiryYear.value,
            creditLimit = creditLimit.value,
            availableCredit = availableCredit.value,
            sourceRecord = sourceRecord.value,
            sourceFields = sourceFields.value,
            sourceFacts = sourceFacts.value,
            parserVersion = parserVersion.value,
        )
    }
    val uniqueRefs = cards.map(CardData::ref).distinct().size == cards.size
    val sourceKeys = cards.mapNotNull(CardData::sourceCardKey)
    val uniqueSourceKeys = sourceKeys.distinct().size == sourceKeys.size
    val pans = cards.mapNotNull(CardData::pan)
    val uniquePans = pans.distinct().size == pans.size
    return cards.takeIf { uniqueRefs && uniqueSourceKeys && uniquePans }
}

private fun isForbiddenCardField(key: String?): Boolean {
    val normalized = key?.lowercase() ?: return true
    return listOf("cvv", "cvc", "pin", "track", "magstripe", "securitycode")
        .any(normalized::contains)
}

private fun isValidPan(value: String): Boolean {
    if (value.length !in 12..19 || !value.all(Char::isDigit)) return false
    val sum = value.reversed().mapIndexed { index, character ->
        val digit = character.digitToInt()
        if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
    }.sum()
    return sum % 10 == 0
}

private fun parseTransferSync(account: Map<*, *>): TransferSyncData? {
    val rawSync = account["transferSync"] as? Map<*, *> ?: return null
    val requestedStart = rawSync["requestedStart"] as? String ?: return null
    val requestedEnd = rawSync["requestedEnd"] as? String ?: return null
    val requestedStartDate = parseCalendarDate(requestedStart) ?: return null
    val requestedEndDate = parseCalendarDate(requestedEnd) ?: return null
    if (requestedStartDate > requestedEndDate) return null
    val complete = rawSync["complete"] as? Boolean ?: return null
    val rawRanges = rawSync["completedRanges"] as? List<*> ?: return null
    val completedRanges = rawRanges.map { rawRange ->
        val range = rawRange as? Map<*, *> ?: return null
        val start = range["start"] as? String ?: return null
        val end = range["end"] as? String ?: return null
        val startDate = parseCalendarDate(start) ?: return null
        val endDate = parseCalendarDate(end) ?: return null
        if (startDate > endDate || startDate < requestedStartDate || endDate > requestedEndDate) return null
        TransferSyncRangeData(start, end)
    }
    if (complete && !rangesCoverRequest(completedRanges, requestedStartDate, requestedEndDate)) return null
    return TransferSyncData(requestedStart, requestedEnd, completedRanges, complete)
}

private fun parseTransferDate(value: String): LocalDate? =
    value.take(10).takeIf { it.length == 10 }?.let(::parseCalendarDate)

private fun parseCalendarDate(value: String): LocalDate? = try {
    LocalDate.parse(value)
} catch (_: Exception) {
    null
}

private fun transferDateInRequestedRange(
    txnDateTime: String,
    sync: TransferSyncData,
): Boolean {
    val date = parseTransferDate(txnDateTime) ?: return false
    val start = parseCalendarDate(sync.requestedStart) ?: return false
    val end = parseCalendarDate(sync.requestedEnd) ?: return false
    return date in start..end
}

private fun rangesCoverRequest(
    ranges: List<TransferSyncRangeData>,
    requestedStart: LocalDate,
    requestedEnd: LocalDate,
): Boolean {
    var nextRequired = requestedStart
    ranges.sortedBy { it.start }.forEach { range ->
        val start = parseCalendarDate(range.start) ?: return false
        val end = parseCalendarDate(range.end) ?: return false
        if (start > nextRequired) return false
        if (end >= requestedEnd) return true
        if (end >= nextRequired) nextRequired = end.plusDays(1)
    }
    return false
}

private fun parseAssetKind(value: Any?): AssetKind? = when (value) {
    "deposit" -> AssetKind.DEPOSIT
    "time_deposit" -> AssetKind.TIME_DEPOSIT
    "credit_card" -> AssetKind.CREDIT_CARD
    "loan" -> AssetKind.LOAN
    else -> null
}
