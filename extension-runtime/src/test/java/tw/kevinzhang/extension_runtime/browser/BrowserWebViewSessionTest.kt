package tw.kevinzhang.extension_runtime.browser

import android.content.Context
import android.webkit.WebView
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                returnAtDocumentStartUrl = null,
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
                returnAtDocumentStartUrl = null,
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
    fun committedNavigationTrackerPreservesTheDocumentUrlBeforeSpaReplacement() {
        val tracker = BrowserDocumentUrlTracker()
        val authorize = "https://example.com/oauth/authorize?code=fictional"
        val committed = "https://example.com/portal?sid=fictional-session-id"
        val spaRoute = "https://example.com/portal/account/summary"

        tracker.reset()
        tracker.onPageStarted(authorize)
        tracker.onPageStarted(committed)
        tracker.onPageCommitVisible(spaRoute)

        assertTrue(tracker.candidates(spaRoute).contains(committed))
        assertEquals(committed, tracker.resolve(spaRoute))
    }

    @Test
    fun committedNavigationTrackerFallsBackWithoutACommittedHttpDocument() {
        val tracker = BrowserDocumentUrlTracker()
        val finalUrl = "https://example.com/account/summary"

        tracker.reset()
        tracker.onPageStarted("javascript:alert(1)")
        tracker.onPageCommitVisible("data:text/html,ignored")

        assertEquals(finalUrl, tracker.resolve(finalUrl))
    }

    @Test
    fun documentStartSignalAuthenticatesAndPreservesTheImmediateUrl() {
        val gson = Gson()
        val token = "fictional-document-token"
        val committed = "https://example.com/portal?sid=fictional-session-id"
        val signal = BrowserDocumentStartSignal.message(token, committed, gson)
        val script = BrowserDocumentStartSignal.script("__fixture_document_signal__", token, gson)

        assertEquals(
            committed,
            BrowserDocumentStartSignal.parse(signal, token, "https://example.com", gson),
        )
        assertEquals(
            null,
            BrowserDocumentStartSignal.parse(signal, "wrong-token", "https://example.com", gson),
        )
        assertEquals(
            null,
            BrowserDocumentStartSignal.parse(signal, token, "https://other.example.com", gson),
        )
        assertTrue(script.contains("window.location.href"))
        assertTrue(script.contains(".postMessage("))
        assertTrue(!script.contains("addJavascriptInterface"))
    }

    @Test
    fun targetNavigationInterceptorCancelsOnlyTheMatchingMainFrame() {
        val intercepted = mutableListOf<String>()
        val interceptor = BrowserTargetNavigationInterceptor(intercepted::add)
        val target = "https://example.com/portal?sid="
        val candidate = "https://example.com/portal?sid=fictional-session-id"

        interceptor.arm(target)

        assertTrue(!interceptor.shouldIntercept(candidate, isForMainFrame = false))
        assertTrue(!interceptor.shouldIntercept("https://example.com/portal", isForMainFrame = true))
        assertTrue(interceptor.shouldIntercept(candidate, isForMainFrame = true))
        assertEquals(listOf(candidate), intercepted)
        assertTrue(!interceptor.shouldIntercept(candidate, isForMainFrame = true))

        interceptor.clear()
        assertTrue(!interceptor.shouldIntercept(candidate, isForMainFrame = true))
    }

    @Test
    fun targetedNavigationWaitsForTheTargetInsteadOfCompletingOnAnIntermediatePageFinish() {
        assertTrue(navigationMayCompleteFromPageFinished(returnAtDocumentStartUrl = null))
        assertTrue(
            !navigationMayCompleteFromPageFinished(
                returnAtDocumentStartUrl = "https://example.com/portal?sid=",
            ),
        )
    }

    @Test
    fun resourceUrlTrackerKeepsOnlyABoundedHttpHistory() {
        val tracker = BrowserResourceUrlTracker()
        tracker.record("javascript:alert(1)")
        repeat(300) { index ->
            tracker.record("https://example.com/resource/$index")
        }

        val urls = tracker.snapshot()
        assertEquals(256, urls.size)
        assertEquals("https://example.com/resource/44", urls.first())
        assertEquals("https://example.com/resource/299", urls.last())
    }

    @Test
    fun documentStartRouteMatchRequiresDeclaredNonEmptyQueryKeys() {
        val target = "https://example.com/portal?sid="

        assertTrue(sameDocumentRoute("https://example.com/portal?sid=fictional#route", target))
        assertTrue(!sameDocumentRoute("https://example.com/portal", target))
        assertTrue(!sameDocumentRoute("https://example.com/portal?sid=", target))
        assertTrue(!sameDocumentRoute("https://example.com/portal/child?sid=fictional", target))
        assertTrue(!sameDocumentRoute("https://other.example.com/portal?sid=fictional", target))
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
