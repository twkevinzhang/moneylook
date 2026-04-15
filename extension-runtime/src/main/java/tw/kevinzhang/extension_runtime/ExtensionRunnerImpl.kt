package tw.kevinzhang.extension_runtime

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.bridge.HttpBridge
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.session.SessionStore
import java.io.File
import javax.inject.Inject

class ExtensionRunnerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val sessionStore: SessionStore,
    private val gson: Gson,
) : ExtensionRunner {

    override suspend fun run(extension: InstalledExtension): SyncResult =
        withContext(Dispatchers.IO) {
            // 1. Check session exists
            if (!sessionStore.hasSession(extension.id)) {
                return@withContext SyncResult.Error("session not found — please login first")
            }

            // 2. Load script
            val scriptFile = File(extension.scriptCachePath)
            if (!scriptFile.exists()) {
                return@withContext SyncResult.Error("script file not found: ${extension.scriptCachePath}")
            }
            val script = scriptFile.readText()

            // 3. Parse targetDomains
            val targetDomains: List<String> = try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(extension.targetDomainsJson, type)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext SyncResult.Error("invalid targetDomains JSON: ${e.message}")
            }

            val bridge = HttpBridge(okHttpClient, sessionStore, extension.id, targetDomains)

            // 4. Run in WebView (switches to Main thread internally)
            runInWebView(script, bridge)
        }

    /**
     * Runs the user script inside a headless WebView.
     * Creates the WebView on the Main thread, evaluates the wrapped script,
     * and suspends (without blocking Main) until the script completes or errors.
     */
    private suspend fun runInWebView(script: String, bridge: HttpBridge): SyncResult =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<SyncResult>()
            val webView = WebView(context)

            @Suppress("SetJavaScriptEnabled")
            webView.settings.javaScriptEnabled = true
            webView.addJavascriptInterface(SdkHttpBridge(bridge, gson), "sdk_http")
            webView.addJavascriptInterface(ScriptResultBridge(deferred, gson), "__bridge__")
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript(buildWrappedScript(script), null)
                }
            }
            // Load a blank page; onPageFinished triggers script evaluation
            webView.loadData("<html><body></body></html>", "text/html", "utf-8")

            try {
                deferred.await()
            } finally {
                webView.destroy()
            }
        }

    /**
     * Wraps the user IIFE script with sdk injection and result capture.
     * The user script is expected to be an IIFE: (function() { ... return {...} })()
     */
    private fun buildWrappedScript(userScript: String): String = """
        (function() {
            try {
                var sdk = {
                    http: {
                        get: function(url, headers) {
                            return JSON.parse(sdk_http.get(url, JSON.stringify(headers || {})));
                        },
                        post: function(url, body, headers) {
                            return JSON.parse(sdk_http.post(url, body || '', JSON.stringify(headers || {})));
                        }
                    }
                };
                var result = $userScript;
                __bridge__.onResult(JSON.stringify(result));
            } catch(e) {
                __bridge__.onError(e.message || String(e));
            }
        })();
    """.trimIndent()
}

/**
 * JavascriptInterface that exposes synchronous HTTP calls to the script.
 * Methods are called on a WebView background thread — OkHttp blocking is safe here.
 */
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

    private fun parseHeaders(json: String): Map<String, String> = try {
        val type = object : TypeToken<Map<String, String>>() {}.type
        gson.fromJson(json, type) ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }
}

/**
 * JavascriptInterface that receives the script result or error and completes the deferred.
 * Methods are called on a WebView background thread.
 */
private class ScriptResultBridge(
    private val deferred: CompletableDeferred<SyncResult>,
    private val gson: Gson,
) {
    @JavascriptInterface
    fun onResult(json: String) {
        val result = try {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            val map: Map<String, Any> = gson.fromJson(json, type)
            val rawList = map["accounts"] as? List<*> ?: run {
                deferred.complete(SyncResult.Error("script result missing 'accounts'"))
                return
            }
            val accounts = rawList.mapNotNull { item ->
                (item as? Map<*, *>)?.let {
                    val name = it["name"] as? String ?: return@mapNotNull null
                    val balance = (it["balance"] as? Number)?.toDouble() ?: return@mapNotNull null
                    val currency = it["currency"] as? String ?: "TWD"
                    AccountData(name, balance, currency)
                }
            }
            SyncResult.Success(accounts)
        } catch (e: Exception) {
            SyncResult.Error("failed to parse script result: ${e.message}", cause = e)
        }
        deferred.complete(result)
    }

    @JavascriptInterface
    fun onError(message: String) {
        deferred.complete(SyncResult.Error("script error: $message"))
    }
}
