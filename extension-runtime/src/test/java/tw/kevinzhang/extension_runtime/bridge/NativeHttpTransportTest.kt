package tw.kevinzhang.extension_runtime.bridge

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NativeHttpTransportTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parserAcceptsSensitiveAndMultiValueHeadersAndBase64Body() {
        val request = HttpRequestJsonParser.parse(
            """{
                "method":"PATCH",
                "url":"https://any.example/resource",
                "headers":{
                    "Authorization":"Bearer secret",
                    "Cookie":["a=1", "b=2"],
                    "X-Multi":["one", "two"]
                },
                "body":"aGVsbG8=",
                "bodyEncoding":"base64",
                "responseEncoding":"base64",
                "followRedirects":true,
                "timeoutMs":1234,
                "capture":{"stage":"account-history","authenticated":true,"mediaKind":"application/json"}
            }""".trimIndent(),
            Gson(),
        )

        assertEquals("PATCH", request.method)
        assertEquals(listOf("a=1", "b=2"), request.headers["Cookie"])
        assertEquals("base64", request.bodyEncoding)
        assertEquals("base64", request.responseEncoding)
        assertTrue(request.followRedirects)
        assertEquals(1234L, request.timeoutMs)
        assertEquals("account-history", request.capture?.stage)
        assertTrue(request.capture?.authenticated == true)
    }

    @Test
    fun transportSendsCredentialsAndPreservesSetCookieValues() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Set-Cookie", "sid=one; Path=/")
                .addHeader("Set-Cookie", "token=two; Path=/")
                .setBody("hello"),
        )
        val transport = NativeHttpTransport(OkHttpClient())
        val result = transport.execute(
            NativeHttpRequest(
                method = "POST",
                url = server.url("/login").toString(),
                headers = mapOf(
                    "Authorization" to listOf("Basic credential"),
                    "Cookie" to listOf("existing=cookie"),
                    "Content-Type" to listOf("text/plain"),
                ),
                body = "payload",
                bodyEncoding = "utf8",
                responseEncoding = "text",
                followRedirects = false,
                timeoutMs = 5_000,
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("Basic credential", recorded.getHeader("Authorization"))
        assertEquals("existing=cookie", recorded.getHeader("Cookie"))
        assertEquals("payload", recorded.body.readUtf8())
        assertEquals(listOf("sid=one; Path=/", "token=two; Path=/"), result.headers["Set-Cookie"])
        assertEquals("hello", result.body)
        assertEquals("hello".toByteArray().toList(), result.exactBodyBytes.toList())
    }

    @Test
    fun redirectBehaviorIsControlledPerRequestAndBase64ResponseIsSupported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        val noRedirect = NativeHttpTransport(OkHttpClient()).execute(request("/start", followRedirects = false))
        assertEquals(302, noRedirect.status)
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/final"))
        server.enqueue(MockResponse().setBody("hello"))
        val redirected = NativeHttpTransport(OkHttpClient()).execute(
            request("/again", followRedirects = true, responseEncoding = "base64"),
        )
        assertEquals(200, redirected.status)
        assertEquals("aGVsbG8=", redirected.body)
        assertEquals("base64", redirected.bodyEncoding)
    }

    @Test
    fun inheritedInterceptorsAreRemoved() = runBlocking {
        var interceptorCalled = false
        val sharedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                interceptorCalled = true
                chain.proceed(chain.request())
            }
            .build()
        server.enqueue(MockResponse().setBody("ok"))

        NativeHttpTransport(sharedClient).execute(request("/safe", followRedirects = false))

        assertFalse(interceptorCalled)
    }

    @Test
    fun malformedJsonKeepsParserCauseWithoutChangingStableError() {
        val error = try {
            HttpRequestJsonParser.parse("{not-json", Gson())
            null
        } catch (e: SafeHttpException) {
            e
        }

        assertEquals("INVALID_REQUEST", error?.code)
        assertEquals("request must be valid JSON", error?.message)
        assertNotNull(error?.cause)
    }

    @Test
    fun networkFailureKeepsIOExceptionCauseWithoutChangingStableError() = runBlocking {
        val unavailable = MockWebServer()
        unavailable.start()
        val url = unavailable.url("/unavailable").toString()
        unavailable.shutdown()

        val error = try {
            NativeHttpTransport(OkHttpClient()).execute(
                NativeHttpRequest(
                    method = "GET",
                    url = url,
                    headers = emptyMap(),
                    body = null,
                    bodyEncoding = "utf8",
                    responseEncoding = "text",
                    followRedirects = false,
                    timeoutMs = 1_000,
                ),
            )
            null
        } catch (e: SafeHttpException) {
            e
        }

        assertEquals("NETWORK_ERROR", error?.code)
        assertEquals("network request failed", error?.message)
        assertTrue(error?.cause is java.io.IOException)
        assertTrue(requireNotNull(error).stackTraceToString().contains("Caused by: java.net.ConnectException"))
    }

    private fun request(
        path: String,
        followRedirects: Boolean,
        responseEncoding: String = "text",
    ) = NativeHttpRequest(
        method = "GET",
        url = server.url(path).toString(),
        headers = emptyMap(),
        body = null,
        bodyEncoding = "utf8",
        responseEncoding = responseEncoding,
        followRedirects = followRedirects,
        timeoutMs = 5_000,
    )
}
