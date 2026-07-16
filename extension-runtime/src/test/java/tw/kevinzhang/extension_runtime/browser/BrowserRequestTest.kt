package tw.kevinzhang.extension_runtime.browser

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.ByteString.Companion.toByteString

class BrowserRequestTest {
    private val gson = Gson()

    @Test
    fun parsesOpenAndXhrRequestContract() {
        val open = BrowserRequestJsonParser.parseOpen(
            """{"url":"https://example.com/login","timeoutMs":1234,"settleMs":500}""",
            gson,
        )
        assertEquals("https://example.com/login", open.url)
        assertEquals(1234L, open.timeoutMs)
        assertEquals(500L, open.settleMs)

        val request = BrowserRequestJsonParser.parseRequest(
            """{
                "url":"https://example.com/api/login",
                "method":"post",
                "headers":{"X-One":"one","X-Multi":["two","three"]},
                "body":"aGVsbG8=",
                "bodyEncoding":"base64",
                "responseEncoding":"base64",
                "timeoutMs":2345,
                "withCredentials":false
            }""".trimIndent(),
            gson,
        )
        assertEquals("POST", request.method)
        assertEquals(listOf("two", "three"), request.headers["X-Multi"])
        assertEquals("base64", request.bodyEncoding)
        assertEquals("base64", request.responseEncoding)
        assertEquals(2345L, request.timeoutMs)
        assertFalse(request.withCredentials)
    }

    @Test
    fun openAndRequestRequireAbsoluteHttpUrls() {
        listOf("/relative", "data:text/plain,secret", "javascript:alert(1)", "file:///tmp/value").forEach { url ->
            val openError = assertThrows(SafeBrowserException::class.java) {
                BrowserRequestJsonParser.parseOpen(gson.toJson(mapOf("url" to url)), gson)
            }
            val requestError = assertThrows(SafeBrowserException::class.java) {
                BrowserRequestJsonParser.parseRequest(gson.toJson(mapOf("url" to url)), gson)
            }
            assertEquals("INVALID_URL", openError.code)
            assertEquals("INVALID_URL", requestError.code)
        }
    }

    @Test
    fun parserRejectsOversizedOrInvalidBodiesWithoutEchoingThem() {
        val secret = "%%%credential-that-must-not-escape"
        val invalid = assertThrows(SafeBrowserException::class.java) {
            BrowserRequestJsonParser.parseRequest(
                gson.toJson(
                    mapOf(
                        "url" to "https://example.com/api",
                        "body" to secret,
                        "bodyEncoding" to "base64",
                    ),
                ),
                gson,
            )
        }
        assertEquals("INVALID_BODY", invalid.code)
        assertFalse(invalid.message.orEmpty().contains(secret))

        val tooLarge = assertThrows(SafeBrowserException::class.java) {
            BrowserRequestJsonParser.parseRequest(
                gson.toJson(
                    mapOf(
                        "url" to "https://example.com/api",
                        "body" to "x".repeat(2 * 1024 * 1024 + 1),
                    ),
                ),
                gson,
            )
        }
        assertEquals("BODY_TOO_LARGE", tooLarge.code)

        val commandError = assertThrows(SafeBrowserException::class.java) {
            BrowserBridgeRequestValidator.validate(
                "x".repeat(BrowserBridgeRequestValidator.MAX_REQUEST_JSON_CHARS + 1),
            )
        }
        assertEquals("REQUEST_TOO_LARGE", commandError.code)
    }

    @Test
    fun xhrScriptUsesMainWorldXmlHttpRequestAndChunkMetadata() {
        val script = BrowserXhrScriptBuilder.start(
            BrowserXhrRequest(
                url = "https://example.com/api?value='quoted'",
                method = "POST",
                headers = mapOf("Authorization" to listOf("Bearer secret")),
                body = "payload",
                bodyEncoding = "utf8",
                responseEncoding = "text",
                timeoutMs = 5_000,
                withCredentials = true,
            ),
            "__slot_random",
            gson,
        )

        assertTrue(script.contains("new window.XMLHttpRequest()"))
        assertFalse(script.contains("fetch("))
        assertTrue(script.contains("xhr.withCredentials = options.withCredentials"))
        assertTrue(script.contains("bodyBytes"))
        assertTrue(script.contains("new Blob([body]).size"))
        assertTrue(script.contains("xhr.onprogress"))
        assertTrue(script.contains("event.lengthComputable && event.total > maxResponseBytes"))
        assertTrue(script.contains("event.loaded > maxResponseBytes"))
        assertTrue(script.contains("new Blob([xhr.responseText || '']).size"))
        assertTrue(script.contains("responseTooLarge ? 'RESPONSE_TOO_LARGE'"))
        assertFalse(script.contains("addJavascriptInterface"))
        assertFalse(script.contains("__native_"))
        assertTrue(script.contains("https://example.com/api?value"))
        assertTrue(script.contains("\\u0027quoted\\u0027"))

        val poll = BrowserXhrScriptBuilder.poll("__slot_random", gson)
        assertTrue(poll.contains("bodyBytes"))
        assertTrue(poll.contains("metadataLength"))
        assertTrue(poll.contains("bodyLength"))
        assertFalse(poll.contains("return value.body"))

        val chunk = BrowserXhrScriptBuilder.readChunk("__slot_random", "body", 0, 1024, gson)
        assertTrue(chunk.contains(".slice(0, 1024)"))

        val cleanup = BrowserXhrScriptBuilder.cleanup("__slot_random", gson)
        assertTrue(cleanup.indexOf("value.xhr.onabort = null") < cleanup.indexOf("value.xhr.abort()"))
    }

    @Test
    fun browserBridgeErrorsAreAlwaysRedacted() {
        val secret = "Cookie: session=top-secret; password=hunter2"
        val known = safeBrowserBridgeError(SafeBrowserException("INVALID_REQUEST", secret))
        val unexpected = safeBrowserBridgeError(IllegalStateException(secret))

        assertEquals("INVALID_REQUEST", known.code)
        assertFalse(known.message.contains("secret"))
        assertFalse(unexpected.message.contains("hunter2"))
    }

    @Test
    fun validatesTextAndBase64ResponseSizeBeforeReturningToExtension() {
        val base = BrowserXhrResponse(
            status = 200,
            statusText = "OK",
            headers = emptyMap(),
            body = "ok",
            bodyEncoding = "text",
            url = "https://example.com/api",
        )
        assertEquals(base, BrowserResponseValidator.validate(base))

        val textError = assertThrows(SafeBrowserException::class.java) {
            BrowserResponseValidator.validate(
                base.copy(body = "x".repeat(10 * 1024 * 1024 + 1)),
            )
        }
        assertEquals("RESPONSE_TOO_LARGE", textError.code)

        val base64Error = assertThrows(SafeBrowserException::class.java) {
            BrowserResponseValidator.validate(
                base.copy(
                    body = ByteArray(10 * 1024 * 1024 + 1).toByteString().base64(),
                    bodyEncoding = "base64",
                ),
            )
        }
        assertEquals("RESPONSE_TOO_LARGE", base64Error.code)
    }
}
