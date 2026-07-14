package tw.kevinzhang.extension_runtime

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.bridge.HttpBridge
import tw.kevinzhang.extension_runtime.bridge.HttpRequest
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.TransferData
import tw.kevinzhang.extension_runtime.session.EphemeralSession
import java.io.File
import javax.inject.Inject

class ExtensionRunnerImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) : ExtensionRunner {

    override suspend fun run(
        extension: InstalledExtension,
        session: EphemeralSession,
    ): SyncResult =
        withContext(Dispatchers.IO) {
            if (session.isEmpty) {
                return@withContext SyncResult.Error("fresh login session not found")
            }
            val scriptFile = File(extension.syncTriggerCachePath)
            if (!scriptFile.exists()) {
                return@withContext SyncResult.Error("script file not found: ${extension.syncTriggerCachePath}")
            }
            if (scriptFile.length() > MAX_SCRIPT_BYTES) {
                return@withContext SyncResult.Error("script file exceeds size limit")
            }
            val script = scriptFile.readText()
            val targetDomains: List<String> = parseTargetDomains(extension.targetDomainsJson)
                ?: return@withContext SyncResult.Error("invalid targetDomains JSON")
            val bridge = HttpBridge(okHttpClient, session, targetDomains)
            runInWebView(script, bridge)
        }

    private fun parseTargetDomains(json: String): List<String>? = try {
        val type = object : TypeToken<List<String>>() {}.type
        gson.fromJson(json, type)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun runInWebView(script: String, bridge: HttpBridge): SyncResult =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<SyncResult>()
            val webView = WebView(context)
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
            webView.addJavascriptInterface(SdkHttpBridge(bridge, gson), "sdk_http")
            webView.addJavascriptInterface(ScriptResultBridge(deferred, gson), "__bridge__")
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                @Deprecated("Deprecated in Android")
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(buildWrappedScript(script), null)
                }
            }
            webView.loadData("<html><body></body></html>", "text/html", "utf-8")
            try {
                withTimeout(SCRIPT_TIMEOUT_MS) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                SyncResult.Error("extension script timed out")
            } finally {
                webView.destroy()
            }
        }

    private fun buildWrappedScript(userScript: String): String {
        val escaped = userScript
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
            .replace("\r", "\\n")
        return """
            (function() {
                try {
                    fetch = undefined;
                    XMLHttpRequest = undefined;
                    var sdk = {
                        http: {
                            get: function(url, headers) {
                                return JSON.parse(sdk_http.get(url, JSON.stringify(headers || {})));
                            },
                            post: function(url, body, headers) {
                                return JSON.parse(sdk_http.post(url, body || '', JSON.stringify(headers || {})));
                            },
                            all: function(requests) {
                                return JSON.parse(sdk_http.all(JSON.stringify(requests)));
                            },
                            allSettled: function(requests) {
                                return JSON.parse(sdk_http.allSettled(JSON.stringify(requests)));
                            }
                        }
                    };
                    var result = eval("${escaped}");
                    __bridge__.onResult(JSON.stringify(result));
                } catch(e) {
                    __bridge__.onError(e.message || String(e));
                }
            })();
        """.trimIndent()
    }

    private companion object {
        const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
        const val SCRIPT_TIMEOUT_MS = 60_000L
    }
}

private data class BatchSettledResult(
    val ok: Boolean,
    val status: Int,
    val body: String,
)

private class SdkHttpBridge(
    private val bridge: HttpBridge,
    private val gson: Gson,
) {
    @JavascriptInterface
    fun get(url: String, headersJson: String): String {
        val headers = parseHeaders(headersJson)
        return gson.toJson(bridge.get(url, headers))
    }

    @JavascriptInterface
    fun post(url: String, body: String, headersJson: String): String {
        val headers = parseHeaders(headersJson)
        return gson.toJson(bridge.post(url, body, headers))
    }

    @JavascriptInterface
    fun all(requestsJson: String): String {
        val requests = parseRequests(requestsJson)
        val results = runBlocking {
            requests.map { req ->
                async(Dispatchers.IO) { bridge.execute(req) }
            }.awaitAll()
        }
        val failed = results.firstOrNull { it.status >= 400 }
        if (failed != null) throw RuntimeException("HTTP ${failed.status}: ${failed.body}")
        return gson.toJson(results)
    }

    @JavascriptInterface
    fun allSettled(requestsJson: String): String {
        val requests = parseRequests(requestsJson)
        val results = runBlocking {
            requests.map { req ->
                async(Dispatchers.IO) { runCatching { bridge.execute(req) } }
            }.awaitAll()
        }
        return gson.toJson(results.map { r ->
            r.fold(
                onSuccess = { BatchSettledResult(ok = it.status < 400, status = it.status, body = it.body) },
                onFailure = { BatchSettledResult(ok = false, status = 0, body = it.message ?: "error") },
            )
        })
    }

    private fun parseHeaders(json: String): Map<String, String> = try {
        val type = object : TypeToken<Map<String, String>>() {}.type
        gson.fromJson(json, type) ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun parseRequests(json: String): List<HttpRequest> = try {
        val type = object : TypeToken<List<HttpRequest>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

/** Bridge for sync-trigger: result must be non-null ExtensionResult. */
private class ScriptResultBridge(
    private val deferred: CompletableDeferred<SyncResult>,
    private val gson: Gson,
) {
    @JavascriptInterface
    fun onResult(json: String) {
        deferred.complete(parseAccounts(json, gson) ?: SyncResult.Error("script result missing 'accounts'"))
    }

    @JavascriptInterface
    fun onError(message: String) {
        deferred.complete(SyncResult.Error("script error: $message"))
    }
}

private fun parseAccounts(json: String, gson: Gson): SyncResult? = try {
    val type = object : TypeToken<Map<String, Any>>() {}.type
    val map: Map<String, Any> = gson.fromJson(json, type)
    val rawList = map["accounts"] as? List<*> ?: return null
    val accounts = rawList.mapNotNull { item ->
        (item as? Map<*, *>)?.let {
            val name = it["name"] as? String ?: return@mapNotNull null
            val balance = (it["balance"] as? Number)?.toDouble() ?: return@mapNotNull null
            val currency = it["currency"] as? String ?: "TWD"
            val no = it["no"] as? String
            val transfers = (it["transfers"] as? List<*>)?.mapNotNull { t ->
                (t as? Map<*, *>)?.let { tm ->
                    val txnDateTime = tm["txnDateTime"] as? String ?: return@mapNotNull null
                    val description = tm["description"] as? String ?: ""
                    val amount = (tm["amount"] as? Number)?.toDouble() ?: 0.0
                    val bal = (tm["balance"] as? Number)?.toDouble() ?: 0.0
                    val memo = tm["memo"] as? String ?: ""
                    TransferData(txnDateTime, description, amount, bal, memo)
                }
            } ?: emptyList()
            AccountData(name, balance, currency, no, transfers)
        }
    }
    SyncResult.Success(accounts)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    SyncResult.Error("failed to parse script result: ${e.message}", cause = e)
}
