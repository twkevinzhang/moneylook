package tw.kevinzhang.extension_runtime.capture

import com.google.gson.Gson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.extension_runtime.bridge.ResponseCaptureOptions

class ResponseCaptureCollectorTest {
    @Test
    fun `authenticated capture preserves exact bytes and metadata`() {
        val collector = ResponseCaptureCollector(Gson())
        val bytes = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())

        val id = collector.capture(
            options = ResponseCaptureOptions("account-history", true, "application/octet-stream"),
            transport = "native_http",
            method = "POST",
            url = "https://bank.invalid/history",
            statusCode = 200,
            headers = mapOf("Set-Cookie" to listOf("fictional=session")),
            body = "",
            bodyEncoding = "base64",
            representation = "exact_bytes",
            exactBodyBytes = bytes,
        )

        assertTrue(!id.isNullOrBlank())
        collector.snapshot().single().also {
            assertEquals(id, it.id)
            assertEquals("account-history", it.stage)
            assertTrue(it.responseHeadersJson.contains("Set-Cookie"))
            assertArrayEquals(bytes, it.bodyBytes)
        }
    }

    @Test
    fun `unauthenticated response is never captured`() {
        val collector = ResponseCaptureCollector(Gson())
        assertNull(
            collector.capture(
                ResponseCaptureOptions("login", false, "text/html"),
                "browser_open",
                "GET",
                "https://bank.invalid/login",
                200,
                emptyMap(),
                "<html>login</html>",
                "text",
                "serialized_dom",
            ),
        )
        assertTrue(collector.snapshot().isEmpty())
    }
}
