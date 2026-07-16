package tw.kevinzhang.extension_runtime.browser

import android.content.Context
import android.webkit.WebView
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class BrowserWebViewSessionTest {
    @Test
    fun openNavigationSetsOptionalUserAgentBeforeLoadingUrl() {
        val view = RecordingWebView(RuntimeEnvironment.getApplication())
        val userAgent = "Mozilla/5.0 Moneylook-Test/1.0"

        startOpenNavigation(
            view,
            BrowserOpenRequest(
                url = "https://example.com/login",
                timeoutMs = 1_000,
                settleMs = 0,
                userAgent = userAgent,
            ),
        )

        assertEquals("https://example.com/login", view.loadedUrl)
        assertEquals(userAgent, view.userAgentAtLoad)
    }

    @Test
    fun openNavigationKeepsWebViewDefaultUserAgentWhenOptionIsAbsent() {
        val view = RecordingWebView(RuntimeEnvironment.getApplication())
        val defaultUserAgent = view.settings.userAgentString

        startOpenNavigation(
            view,
            BrowserOpenRequest(
                url = "https://example.com/login",
                timeoutMs = 1_000,
                settleMs = 0,
                userAgent = null,
            ),
        )

        assertEquals(defaultUserAgent, view.userAgentAtLoad)
    }

    @Test
    fun formPostNavigationUsesWebViewPostUrlWithExactUtf8Bytes() {
        val view = RecordingWebView(RuntimeEnvironment.getApplication())
        val request = BrowserRequestJsonParser.parsePost(
            """{"url":"https://example.com/login","body":"name=%E6%B8%AC%E8%A9%A6&mode=web"}""",
            Gson(),
        )

        startFormPostNavigation(view, request)

        assertEquals("https://example.com/login", view.postedUrl)
        assertArrayEquals(
            "name=%E6%B8%AC%E8%A9%A6&mode=web".toByteArray(StandardCharsets.UTF_8),
            view.postedBody,
        )
    }

    @Test
    fun closeIsIdempotentAndRejectsFurtherFormPostNavigation() = runBlocking {
        val session = BrowserWebViewSession(RuntimeEnvironment.getApplication(), Gson())
        session.close()
        session.close()

        val error = try {
            session.post(
                BrowserFormPostRequest(
                    url = "https://example.com/login",
                    body = "a=b".toByteArray(StandardCharsets.UTF_8),
                    timeoutMs = 1_000,
                    settleMs = 0,
                ),
            )
            null
        } catch (e: SafeBrowserException) {
            e
        }

        assertEquals("BROWSER_CLOSED", error?.code)
    }

    private class RecordingWebView(context: Context) : WebView(context) {
        var loadedUrl: String? = null
        var userAgentAtLoad: String? = null
        var postedUrl: String? = null
        var postedBody: ByteArray? = null

        override fun loadUrl(url: String) {
            loadedUrl = url
            userAgentAtLoad = settings.userAgentString
        }

        override fun postUrl(url: String, postData: ByteArray) {
            postedUrl = url
            postedBody = postData.copyOf()
        }
    }
}
