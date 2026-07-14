package tw.kevinzhang.extension_runtime.login

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tw.kevinzhang.extension_runtime.captcha.CaptchaSolveResult
import tw.kevinzhang.extension_runtime.captcha.CaptchaSolver
import tw.kevinzhang.extension_runtime.session.EphemeralSession
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LoginCredentials(
    val username: String,
    val password: String,
) {
    override fun toString(): String = "LoginCredentials([REDACTED])"
}

data class NativeLoginRequest(
    val credentials: LoginCredentials,
    val config: LoginAutomationConfig,
    val loginUrl: String,
    /** Domains already approved by the native app/user, never raw unreviewed extension input. */
    val targetDomains: List<String>,
    val timeoutMillis: Long = 60_000,
)

sealed interface NativeLoginResult {
    data class Success(val session: EphemeralSession) : NativeLoginResult
    data class Error(val message: String, val cause: Throwable? = null) : NativeLoginResult
}

fun interface NativeLoginRunner {
    suspend fun login(request: NativeLoginRequest): NativeLoginResult
}

/**
 * Runs a native-controlled login WebView that never loads the extension script.
 *
 * Credentials are inserted only after the top-level HTTPS origin passes the approved-domain
 * check. The resulting CookieManager data is copied into an immutable [EphemeralSession], then
 * the process-global WebView cookie jar is cleared before returning.
 *
 * Captcha extraction uses an in-page canvas. Sites that taint the canvas with cross-origin image
 * data are intentionally reported as unsupported instead of bypassing browser origin policy.
 */
class WebViewNativeLoginRunner(
    private val context: Context,
    private val captchaSolver: CaptchaSolver,
    private val gson: Gson,
) : NativeLoginRunner {
    override suspend fun login(request: NativeLoginRequest): NativeLoginResult = LOGIN_MUTEX.withLock {
        try {
            validateRequest(request)
            withTimeout(request.timeoutMillis) {
                withContext(Dispatchers.Main) { runWebViewLogin(request) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NativeLoginResult.Error(e.message ?: "native login failed", e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runWebViewLogin(request: NativeLoginRequest): NativeLoginResult {
        val cookieManager = android.webkit.CookieManager.getInstance()
        removeAllCookies(cookieManager)
        val result = CompletableDeferred<NativeLoginResult>()
        val webView = WebView(context)
        val scope = CoroutineScope(Job() + Dispatchers.Main.immediate)
        val allowedDomains = request.targetDomains.map(::normalizeDomain)
        val successUrlContains = request.config.successUrlContains
        var automationStarted = false
        var submitted = false

        fun isAllowed(url: String): Boolean {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            if (!uri.scheme.equals("https", ignoreCase = true)) return false
            val host = uri.host?.lowercase(Locale.US)?.trimEnd('.') ?: return false
            return allowedDomains.any { domain -> host == domain || host.endsWith(".$domain") }
        }

        fun completeSuccess(currentUrl: String) {
            if (!submitted || result.isCompleted) return
            scope.launch {
                delay(request.config.postSubmitDelayMs)
                if (result.isCompleted) return@launch
                val cookieHeaders = buildMap {
                    allowedDomains.forEach { domain ->
                        cookieManager.getCookie("https://$domain/")
                            ?.takeIf(String::isNotBlank)
                            ?.let { put(domain, it) }
                    }
                    val currentHost = Uri.parse(currentUrl).host?.let(::normalizeDomain)
                    if (currentHost != null) {
                        cookieManager.getCookie(currentUrl)
                            ?.takeIf(String::isNotBlank)
                            ?.let { put(currentHost, it) }
                    }
                }
                val session = EphemeralSession.of(cookieHeaders)
                if (session.isEmpty) result.complete(NativeLoginResult.Error("login succeeded but no cookies were captured"))
                else result.complete(NativeLoginResult.Success(session))
            }
        }

        val bridge = object {
            @JavascriptInterface
            fun onCaptcha(dataUrl: String) {
                if (submitted || result.isCompleted) return
                val bytes = decodeCaptchaDataUrl(dataUrl)
                if (bytes == null) {
                    result.complete(NativeLoginResult.Error("captcha image could not be captured"))
                    return
                }
                scope.launch(Dispatchers.IO) {
                    when (val solved = captchaSolver.solve(bytes)) {
                        is CaptchaSolveResult.Error -> result.complete(
                            NativeLoginResult.Error(solved.message, solved.cause),
                        )
                        is CaptchaSolveResult.Success -> withContext(Dispatchers.Main) {
                            if (result.isCompleted) return@withContext
                            submitted = true
                            webView.evaluateJavascript(buildSubmitScript(request.config, solved.text), null)
                        }
                    }
                }
            }

            @JavascriptInterface
            fun onLocation(url: String) {
                if (isAllowed(url) && url.contains(successUrlContains)) completeSuccess(url)
            }

            @JavascriptInterface
            fun onError(message: String) {
                result.complete(NativeLoginResult.Error("login automation failed: ${message.take(200)}"))
            }
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        webView.addJavascriptInterface(bridge, NATIVE_BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !isAllowed(request.url.toString())

            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !isAllowed(url)

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
                result.complete(NativeLoginResult.Error("TLS validation failed during login"))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!isAllowed(url)) view.stopLoading()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!isAllowed(url)) {
                    result.complete(NativeLoginResult.Error("login navigated to a non-approved domain"))
                    return
                }
                if (submitted && url.contains(successUrlContains)) {
                    completeSuccess(url)
                    return
                }
                if (!automationStarted) {
                    automationStarted = true
                    view.evaluateJavascript(buildCredentialAndCaptchaScript(request), null)
                }
            }
        }

        webView.loadUrl(request.loginUrl)
        return try {
            result.await()
        } finally {
            scope.coroutineContext[Job]?.cancel()
            webView.stopLoading()
            webView.removeJavascriptInterface(NATIVE_BRIDGE_NAME)
            webView.destroy()
            removeAllCookies(cookieManager)
        }
    }

    private fun buildCredentialAndCaptchaScript(request: NativeLoginRequest): String {
        val config = request.config
        return """
            (function() {
              try {
                const username = document.querySelector(${gson.toJson(config.usernameSelector)});
                const password = document.querySelector(${gson.toJson(config.passwordSelector)});
                const image = document.querySelector(${gson.toJson(config.captchaImageSelector)});
                if (!username || !password || !image) {
                  $NATIVE_BRIDGE_NAME.onError('required login element not found');
                  return;
                }
                const setValue = function(element, value) {
                  element.value = value;
                  element.dispatchEvent(new Event('input', { bubbles: true }));
                  element.dispatchEvent(new Event('change', { bubbles: true }));
                };
                setValue(username, ${gson.toJson(request.credentials.username)});
                setValue(password, ${gson.toJson(request.credentials.password)});
                const canvas = document.createElement('canvas');
                canvas.width = image.naturalWidth || image.width;
                canvas.height = image.naturalHeight || image.height;
                canvas.getContext('2d').drawImage(image, 0, 0);
                $NATIVE_BRIDGE_NAME.onCaptcha(canvas.toDataURL('image/png'));
              } catch (error) {
                $NATIVE_BRIDGE_NAME.onError(error.message || String(error));
              }
            })();
        """.trimIndent()
    }

    private fun buildSubmitScript(config: LoginAutomationConfig, captcha: String): String = """
        (function() {
          try {
            const input = document.querySelector(${gson.toJson(config.captchaInputSelector)});
            const submit = document.querySelector(${gson.toJson(config.submitSelector)});
            if (!input || !submit) {
              $NATIVE_BRIDGE_NAME.onError('captcha input or submit element not found');
              return;
            }
            input.value = ${gson.toJson(captcha)};
            input.dispatchEvent(new Event('input', { bubbles: true }));
            input.dispatchEvent(new Event('change', { bubbles: true }));
            window.setInterval(function() { $NATIVE_BRIDGE_NAME.onLocation(location.href); }, 500);
            submit.click();
          } catch (error) {
            $NATIVE_BRIDGE_NAME.onError(error.message || String(error));
          }
        })();
    """.trimIndent()

    private suspend fun removeAllCookies(cookieManager: android.webkit.CookieManager) {
        suspendCoroutine { continuation ->
            cookieManager.removeAllCookies {
                continuation.resume(Unit)
            }
        }
    }

    private fun validateRequest(request: NativeLoginRequest) {
        request.config.validate()
        require(request.credentials.username.isNotBlank()) { "username must not be empty" }
        require(request.credentials.password.isNotEmpty()) { "password must not be empty" }
        require(request.targetDomains.isNotEmpty()) { "targetDomains must not be empty" }
        require(request.targetDomains.map(::normalizeDomain).none(String::isEmpty)) {
            "targetDomains contains an invalid domain"
        }
        require(request.timeoutMillis in 5_000..180_000) { "timeoutMillis is out of range" }
        val loginUri = Uri.parse(request.loginUrl)
        require(loginUri.scheme.equals("https", ignoreCase = true)) { "loginUrl must use HTTPS" }
        val loginHost = loginUri.host?.let(::normalizeDomain).orEmpty()
        require(request.targetDomains.map(::normalizeDomain).any { domain ->
            loginHost == domain || loginHost.endsWith(".$domain")
        }) { "loginUrl domain is not approved" }
    }

    private fun decodeCaptchaDataUrl(dataUrl: String): ByteArray? {
        return try {
            if (!dataUrl.startsWith("data:image/") || dataUrl.length > MAX_CAPTCHA_DATA_URL_CHARS) return null
            val separator = dataUrl.indexOf(',')
            if (separator < 0 || !dataUrl.substring(0, separator).endsWith(";base64")) return null
            Base64.decode(dataUrl.substring(separator + 1), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun normalizeDomain(domain: String): String = domain
        .trim().removePrefix(".").lowercase(Locale.US).trimEnd('.')
        .takeIf { it.isNotEmpty() && !it.contains('/') && !it.contains(':') }.orEmpty()

    private companion object {
        const val NATIVE_BRIDGE_NAME = "__moneylook_native_login__"
        const val MAX_CAPTCHA_DATA_URL_CHARS = 3 * 1024 * 1024
        val LOGIN_MUTEX = Mutex()
    }
}
