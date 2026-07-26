package tw.kevinzhang.extension_runtime.browser

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.ByteString.Companion.toByteString
import java.nio.charset.StandardCharsets

class BrowserRequestTest {
    private val gson = Gson()

    @Test
    fun parsesOpenAndXhrRequestContract() {
        val open = BrowserRequestJsonParser.parseOpen(
            """{
                "url":"https://example.com/login",
                "timeoutMs":1234,
                "settleMs":500,
                "userAgent":"Mozilla/5.0 Moneylook-Test/1.0",
                "returnAtDocumentStartUrl":"https://example.com/portal",
                "capture":{"stage":"authenticated-home","authenticated":true,"mediaKind":"text/html"}
            }""".trimIndent(),
            gson,
        )
        assertEquals("https://example.com/login", open.url)
        assertEquals(1234L, open.timeoutMs)
        assertEquals(500L, open.settleMs)
        assertEquals("Mozilla/5.0 Moneylook-Test/1.0", open.userAgent)
        assertEquals("https://example.com/portal", open.returnAtDocumentStartUrl)
        assertEquals("authenticated-home", open.capture?.stage)
        assertTrue(open.capture?.authenticated == true)

        val request = BrowserRequestJsonParser.parseRequest(
            """{
                "url":"https://example.com/api/login",
                "method":"post",
                "headers":{"X-One":"one","X-Multi":["two","three"]},
                "body":"aGVsbG8=",
                "bodyEncoding":"base64",
                "responseEncoding":"base64",
                "timeoutMs":2345,
                "withCredentials":false,
                "capture":{"stage":"history","authenticated":true,"mediaKind":"application/json"}
            }""".trimIndent(),
            gson,
        )
        assertEquals("POST", request.method)
        assertEquals(listOf("two", "three"), request.headers["X-Multi"])
        assertEquals("base64", request.bodyEncoding)
        assertEquals("base64", request.responseEncoding)
        assertEquals(2345L, request.timeoutMs)
        assertFalse(request.withCredentials)
        assertEquals("history", request.capture?.stage)
        assertTrue(request.capture?.authenticated == true)
    }

    @Test
    fun openAcceptsOnlyBoundedPrintableAsciiUserAgent() {
        val absent = BrowserRequestJsonParser.parseOpen(
            """{"url":"https://example.com/login"}""",
            gson,
        )
        assertEquals(null, absent.userAgent)
        assertEquals(null, absent.returnAtDocumentStartUrl)

        listOf(" ", "A".repeat(512)).forEach { userAgent ->
            assertEquals(
                userAgent,
                BrowserRequestJsonParser.parseOpen(
                    gson.toJson(mapOf("url" to "https://example.com/login", "userAgent" to userAgent)),
                    gson,
                ).userAgent,
            )
        }

        listOf(
            "",
            "A".repeat(513),
            "Mozilla/5.0\r\nInjected: true",
            "Mozilla/5.0\tTabbed",
            "control-\u001f",
            "delete-\u007f",
            "非-ASCII",
        ).forEach { userAgent ->
            val error = assertThrows(SafeBrowserException::class.java) {
                BrowserRequestJsonParser.parseOpen(
                    gson.toJson(mapOf("url" to "https://example.com/login", "userAgent" to userAgent)),
                    gson,
                )
            }
            assertEquals("INVALID_REQUEST", error.code)
            if (userAgent.isNotEmpty()) {
                assertFalse(error.message.orEmpty().contains(userAgent))
            }
        }
    }

    @Test
    fun parsesFormPostAsStrictUtf8WithNavigationBounds() {
        val post = BrowserRequestJsonParser.parsePost(
            """{
                "url":"https://example.com/login",
                "body":"customer=%E6%B8%AC%E8%A9%A6&password=encoded%2Bvalue",
                "timeoutMs":4321,
                "settleMs":750
            }""".trimIndent(),
            gson,
        )

        assertEquals("https://example.com/login", post.url)
        assertEquals(
            "customer=%E6%B8%AC%E8%A9%A6&password=encoded%2Bvalue",
            post.body.toString(StandardCharsets.UTF_8),
        )
        assertEquals(4321L, post.timeoutMs)
        assertEquals(750L, post.settleMs)

        val defaults = BrowserRequestJsonParser.parsePost(
            """{"url":"https://example.com/login","body":"a=b"}""",
            gson,
        )
        assertEquals(30_000L, defaults.timeoutMs)
        assertEquals(500L, defaults.settleMs)
    }

    @Test
    fun openAndRequestRequireAbsoluteHttpUrls() {
        listOf("/relative", "data:text/plain,secret", "javascript:alert(1)", "file:///tmp/value").forEach { url ->
            val openError = assertThrows(SafeBrowserException::class.java) {
                BrowserRequestJsonParser.parseOpen(gson.toJson(mapOf("url" to url)), gson)
            }
            val documentStartError = assertThrows(SafeBrowserException::class.java) {
                BrowserRequestJsonParser.parseOpen(
                    gson.toJson(
                        mapOf(
                            "url" to "https://example.com/login",
                            "returnAtDocumentStartUrl" to url,
                        ),
                    ),
                    gson,
                )
            }
            val requestError = assertThrows(SafeBrowserException::class.java) {
                BrowserRequestJsonParser.parseRequest(gson.toJson(mapOf("url" to url)), gson)
            }
            val postError = assertThrows(SafeBrowserException::class.java) {
                BrowserRequestJsonParser.parsePost(gson.toJson(mapOf("url" to url, "body" to "a=b")), gson)
            }
            assertEquals("INVALID_URL", openError.code)
            assertEquals("INVALID_URL", documentStartError.code)
            assertEquals("INVALID_URL", requestError.code)
            assertEquals("INVALID_URL", postError.code)
        }
    }

    @Test
    fun formPostRejectsMissingMalformedOrOversizedUtf8Body() {
        val missing = assertThrows(SafeBrowserException::class.java) {
            BrowserRequestJsonParser.parsePost(
                gson.toJson(mapOf("url" to "https://example.com/login")),
                gson,
            )
        }
        assertEquals("INVALID_REQUEST", missing.code)

        val malformed = assertThrows(SafeBrowserException::class.java) {
            BrowserRequestJsonParser.parsePost(
                gson.toJson(mapOf("url" to "https://example.com/login", "body" to "\uD800")),
                gson,
            )
        }
        assertEquals("INVALID_BODY", malformed.code)

        val oversized = assertThrows(SafeBrowserException::class.java) {
            BrowserRequestJsonParser.parsePost(
                gson.toJson(
                    mapOf(
                        "url" to "https://example.com/login",
                        "body" to "x".repeat(2 * 1024 * 1024 + 1),
                    ),
                ),
                gson,
            )
        }
        assertEquals("BODY_TOO_LARGE", oversized.code)
    }

    @Test
    fun formPostRequiresBoundedNavigationTimeoutAndSettleValues() {
        listOf(
            mapOf("timeoutMs" to 0),
            mapOf("timeoutMs" to 30_001),
            mapOf("settleMs" to -1),
            mapOf("settleMs" to 5_001),
        ).forEach { invalidBounds ->
            val error = assertThrows(SafeBrowserException::class.java) {
                BrowserRequestJsonParser.parsePost(
                    gson.toJson(
                        mapOf(
                            "url" to "https://example.com/login",
                            "body" to "a=b",
                        ) + invalidBounds,
                    ),
                    gson,
                )
            }
            assertEquals("INVALID_TIMEOUT", error.code)
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

    @Test
    fun navigationResponseKeepsCommittedDocumentUrlWithoutPageContent() {
        val json = gson.toJson(
            BrowserOpenResponse(
                url = "https://example.com/account/summary",
                origin = "https://example.com",
                documentUrl = "https://example.com/account?sid=fictional-session-id",
                documentUrls = listOf(
                    "https://example.com/oauth/authorize?code=fictional",
                    "https://example.com/account?sid=fictional-session-id",
                    "https://example.com/account/summary",
                ),
                requestUrls = listOf(
                    "https://example.com/assets/app.js",
                    "https://example.com/api/session/renew?sid=fictional-session-id",
                ),
            ),
        )

        val root = gson.fromJson(json, com.google.gson.JsonObject::class.java)
        assertEquals("https://example.com/account/summary", root.get("url").asString)
        assertEquals("https://example.com", root.get("origin").asString)
        assertEquals(
            "https://example.com/account?sid=fictional-session-id",
            root.get("documentUrl").asString,
        )
        assertEquals(3, root.getAsJsonArray("documentUrls").size())
        assertEquals(2, root.getAsJsonArray("requestUrls").size())
        assertFalse(json.contains("body"))
        assertFalse(json.contains("html"))
    }
}
