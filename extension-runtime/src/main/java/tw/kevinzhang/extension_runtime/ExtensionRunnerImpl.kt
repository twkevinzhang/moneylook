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
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.TransferData
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

class ExtensionRunnerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) : ExtensionRunner {

    override suspend fun run(
        extension: InstalledExtension,
        credential: ExtensionCredential,
    ): SyncResult = withContext(Dispatchers.IO) {
        val scriptFile = File(extension.syncTriggerCachePath)
        if (!scriptFile.isFile) return@withContext SyncResult.Error("extension script file not found")
        if (scriptFile.length() > MAX_SCRIPT_BYTES) {
            return@withContext SyncResult.Error("extension script exceeds size limit")
        }
        val script = try {
            scriptFile.readText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext SyncResult.Error("extension script could not be read")
        }
        val canonicalCredentialJson = canonicalCredentialJson(credential.json)
            ?: return@withContext SyncResult.Error("stored credential JSON is invalid")
        runInWebView(script, ExtensionCredential(canonicalCredentialJson))
    }

    private suspend fun runInWebView(
        script: String,
        credential: ExtensionCredential,
    ): SyncResult = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<SyncResult>()
        val webView = WebView(context)
        val requestBudget = RunRequestBudget()
        val httpBridge = NativeSdkHttpBridge(
            webView = webView,
            transport = NativeHttpTransport(okHttpClient),
            requestBudget = requestBudget,
            gson = gson,
        )
        val browserBridge = NativeSdkBrowserBridge(
            controllerWebView = webView,
            session = BrowserWebViewSession(context, gson),
            requestBudget = requestBudget,
            gson = gson,
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
                    view.evaluateJavascript(buildWrappedScript(script, credential), null)
                }
            }
        }
        webView.loadData("<html><body></body></html>", "text/html", "utf-8")
        try {
            withTimeout(SCRIPT_TIMEOUT_MS) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            SyncResult.Error("extension script timed out")
        } finally {
            browserBridge.cancel()
            httpBridge.cancel()
            webView.removeJavascriptInterface("__native_http__")
            webView.removeJavascriptInterface("__native_browser__")
            webView.removeJavascriptInterface("__result_bridge__")
            webView.stopLoading()
            webView.destroy()
        }
    }

    internal fun buildWrappedScript(script: String, credential: ExtensionCredential): String {
        val scriptLiteral = gson.toJson(script)
        val credentialLiteral = requireNotNull(canonicalCredentialJson(credential.json)) {
            "stored credential JSON is invalid"
        }
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
                        } catch (_) {
                            __pending.delete(id);
                            reject({ code: 'BRIDGE_ERROR', message: 'native HTTP bridge failed' });
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
                        } catch (_) {
                            __browserPending.delete(id);
                            reject({ code: 'BRIDGE_ERROR', message: 'native browser bridge failed' });
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
                const sdk = Object.freeze({
                    credential: deepFreeze($credentialLiteral),
                    http: http,
                    browser: browser
                });
                try {
                    const result = await eval($scriptLiteral);
                    __result_bridge__.onResult(JSON.stringify(result));
                } catch (_) {
                    __result_bridge__.onError();
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
                postResult(id, gson.toJson(response), null)
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

internal data class SafeBridgeError(val code: String, val message: String)

internal fun safeBridgeError(error: Exception): SafeBridgeError = when (error) {
    is SafeHttpException -> SafeBridgeError(error.code, "HTTP request rejected")
    else -> SafeBridgeError("HTTP_ERROR", "HTTP request failed")
}

private class ScriptResultBridge(
    private val deferred: CompletableDeferred<SyncResult>,
    private val gson: Gson,
) {
    @JavascriptInterface
    fun onResult(json: String) {
        deferred.complete(parseAccounts(json, gson) ?: SyncResult.Error("script result missing 'accounts'"))
    }

    @JavascriptInterface
    fun onError() {
        // Extension exceptions may embed credentials, request headers, or response bodies.
        deferred.complete(SyncResult.Error("extension script failed"))
    }
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
        val branchName = optionalString(account, "branchName")
            ?: return SyncResult.Error("script result contains invalid account")
        val availableCredit = optionalFiniteNumber(account, "availableCredit")
            ?: return SyncResult.Error("script result contains invalid available credit")
        val creditLimit = optionalFiniteNumber(account, "creditLimit")
            ?: return SyncResult.Error("script result contains invalid credit limit")
        if (kind != AssetKind.CREDIT_CARD && (availableCredit.value != null || creditLimit.value != null)) {
            return SyncResult.Error("script result contains credit fields for a non-card account")
        }
        if (kind != AssetKind.DEPOSIT && balance < 0) {
            return SyncResult.Error("script result contains invalid debt balance")
        }

        val transfers = (account["transfers"] as? List<*>)?.mapNotNull { transfer ->
            (transfer as? Map<*, *>)?.let { transferMap ->
                val txnDateTime = transferMap["txnDateTime"] as? String ?: return@mapNotNull null
                val description = transferMap["description"] as? String ?: ""
                val amount = (transferMap["amount"] as? Number)?.toDouble() ?: 0.0
                val balanceAtTransfer = (transferMap["balance"] as? Number)?.toDouble() ?: 0.0
                val memo = transferMap["memo"] as? String ?: ""
                TransferData(txnDateTime, description, amount, balanceAtTransfer, memo)
            }
        } ?: emptyList()
        AccountData(
            name = name,
            balance = balance,
            currency = currency,
            no = no.value,
            kind = kind,
            branchName = branchName.value,
            availableCredit = availableCredit.value,
            creditLimit = creditLimit.value,
            transfers = transfers,
        )
    }
    SyncResult.Success(accounts)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    SyncResult.Error("failed to parse script result")
}

private data class OptionalValue<T>(val value: T?)

private fun optionalString(account: Map<*, *>, key: String): OptionalValue<String>? = when {
    key !in account -> OptionalValue(null)
    account[key] is String -> OptionalValue(account[key] as String)
    else -> null
}

private fun optionalFiniteNumber(account: Map<*, *>, key: String): OptionalValue<Double>? = when {
    key !in account -> OptionalValue(null)
    else -> finiteNumber(account[key])?.let(::OptionalValue)
}

private fun finiteNumber(value: Any?): Double? =
    (value as? Number)?.toDouble()?.takeIf { it.isFinite() }

private fun parseAssetKind(value: Any?): AssetKind? = when (value) {
    "deposit" -> AssetKind.DEPOSIT
    "credit_card" -> AssetKind.CREDIT_CARD
    "loan" -> AssetKind.LOAN
    else -> null
}
