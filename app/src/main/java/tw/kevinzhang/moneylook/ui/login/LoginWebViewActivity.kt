package tw.kevinzhang.moneylook.ui.login

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint
import tw.kevinzhang.extension_runtime.session.SessionStore
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme
import javax.inject.Inject

@AndroidEntryPoint
class LoginWebViewActivity : ComponentActivity() {

    @Inject lateinit var sessionStore: SessionStore

    private lateinit var extensionId: String
    private lateinit var loginUrl: String
    private lateinit var extensionName: String
    private lateinit var targetDomains: List<String>

    companion object {
        private const val EXTRA_EXTENSION_ID = "extension_id"
        private const val EXTRA_LOGIN_URL = "login_url"
        private const val EXTRA_EXTENSION_NAME = "extension_name"
        private const val EXTRA_TARGET_DOMAINS = "target_domains"

        fun newIntent(
            context: Context,
            extensionId: String,
            loginUrl: String,
            extensionName: String,
            targetDomains: List<String>,
        ): Intent = Intent(context, LoginWebViewActivity::class.java).apply {
            putExtra(EXTRA_EXTENSION_ID, extensionId)
            putExtra(EXTRA_LOGIN_URL, loginUrl)
            putExtra(EXTRA_EXTENSION_NAME, extensionName)
            putStringArrayListExtra(EXTRA_TARGET_DOMAINS, ArrayList(targetDomains))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extensionId = intent.getStringExtra(EXTRA_EXTENSION_ID) ?: return finish()
        loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL) ?: return finish()
        extensionName = intent.getStringExtra(EXTRA_EXTENSION_NAME) ?: extensionId
        targetDomains = intent.getStringArrayListExtra(EXTRA_TARGET_DOMAINS) ?: emptyList()

        setContent {
            MoneylookTheme {
                LoginWebViewScreen(
                    extensionName = extensionName,
                    loginUrl = loginUrl,
                    onClose = { finish() },
                    onPageFinished = { url -> captureSession(url) },
                    onUrlOverride = { url -> captureUrlTokens(url) },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Final capture on close — catches any accumulated cookies
        targetDomains.forEach { domain ->
            val cookies = CookieManager.getInstance().getCookie("https://$domain")
            if (!cookies.isNullOrBlank()) {
                sessionStore.putCookies(extensionId, cookies)
            }
        }
    }

    /** Layer 1: capture cookies on every page load */
    private fun captureSession(url: String) {
        val cookies = CookieManager.getInstance().getCookie(url)
        if (!cookies.isNullOrBlank()) {
            sessionStore.putCookies(extensionId, cookies)
        }
    }

    /** Layer 2: capture tokens from OAuth redirect URLs */
    private fun captureUrlTokens(url: String) {
        val uri = Uri.parse(url)
        listOf("access_token", "token", "auth_token", "code", "id_token").forEach { key ->
            uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let { value ->
                sessionStore.putToken(extensionId, key, value)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginWebViewScreen(
    extensionName: String,
    loginUrl: String,
    onClose: () -> Unit,
    onPageFinished: (String) -> Unit,
    onUrlOverride: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(extensionName) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "關閉")
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                onPageFinished(url)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                onUrlOverride(request.url.toString())
                                return false
                            }

                            /** Layer 3: response header capture deferred to post-v1 */
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        loadUrl(loginUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
