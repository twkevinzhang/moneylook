package tw.kevinzhang.extension_runtime.browser

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tw.kevinzhang.extension_runtime.bridge.RunRequestBudget
import tw.kevinzhang.extension_runtime.bridge.SafeHttpException
import java.util.concurrent.atomic.AtomicBoolean

internal class NativeSdkBrowserBridge(
    private val controllerWebView: WebView,
    private val session: BrowserWebViewSession,
    private val requestBudget: RunRequestBudget,
    private val gson: Gson,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(true)

    @JavascriptInterface
    fun open(id: String, requestJson: String) {
        execute(id, requestJson) {
            session.open(BrowserRequestJsonParser.parseOpen(requestJson, gson))
        }
    }

    @JavascriptInterface
    fun request(id: String, requestJson: String) {
        execute(id, requestJson) {
            session.request(BrowserRequestJsonParser.parseRequest(requestJson, gson))
        }
    }

    @JavascriptInterface
    fun close() {
        if (!active.get()) return
        mainHandler.post {
            if (active.get()) session.close()
        }
    }

    fun cancel() {
        active.set(false)
        scope.cancel()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            session.close()
        } else {
            mainHandler.post { session.close() }
        }
    }

    private fun execute(id: String, requestJson: String, block: suspend () -> Any) {
        if (!active.get()) return
        try {
            requestBudget.acquire()
            BrowserBridgeRequestValidator.validate(requestJson)
        } catch (e: Exception) {
            postResult(id, null, gson.toJson(safeBrowserBridgeError(e)))
            return
        }
        scope.launch {
            try {
                postResult(id, gson.toJson(block()), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postResult(id, null, gson.toJson(safeBrowserBridgeError(e)))
            }
        }
    }

    private fun postResult(id: String, resultJson: String?, errorJson: String?) {
        val idLiteral = gson.toJson(id)
        val resultLiteral = resultJson?.let(gson::toJson) ?: "null"
        val errorLiteral = errorJson?.let(gson::toJson) ?: "null"
        mainHandler.post {
            if (active.get()) {
                controllerWebView.evaluateJavascript(
                    "window.__sdkBrowserResolve($idLiteral,$resultLiteral,$errorLiteral);",
                    null,
                )
            }
        }
    }
}

internal data class SafeBrowserBridgeError(val code: String, val message: String)

internal fun safeBrowserBridgeError(error: Exception): SafeBrowserBridgeError = when (error) {
    is SafeBrowserException -> SafeBrowserBridgeError(error.code, "browser request failed")
    is SafeHttpException -> SafeBrowserBridgeError(error.code, "browser request failed")
    else -> SafeBrowserBridgeError("BROWSER_ERROR", "browser request failed")
}
