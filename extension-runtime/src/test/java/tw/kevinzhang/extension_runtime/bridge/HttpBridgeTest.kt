package tw.kevinzhang.extension_runtime.bridge

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tw.kevinzhang.extension_runtime.session.EphemeralSession

class HttpBridgeTest {
    @Test
    fun `injects native cookie after exact host validation`() {
        val bridge = HttpBridge(
            OkHttpClient(),
            EphemeralSession.of(mapOf("bank.example" to "sid=secret")),
            listOf("bank.example"),
        )

        val request = bridge.buildRequest("https://api.bank.example/accounts", emptyMap(), null)

        assertEquals("sid=secret", request.header("Cookie"))
    }

    @Test
    fun `rejects HTTP and host suffix confusion`() {
        val bridge = HttpBridge(OkHttpClient(), EphemeralSession.Empty, listOf("bank.example"))

        assertThrows(SecurityException::class.java) {
            bridge.get("http://bank.example/accounts")
        }
        assertThrows(SecurityException::class.java) {
            bridge.get("https://bank.example.attacker.test/accounts")
        }
    }

    @Test
    fun `rejects credential and proxy headers case insensitively`() {
        val bridge = HttpBridge(OkHttpClient(), EphemeralSession.Empty, listOf("bank.example"))

        listOf("Cookie", "AUTHORIZATION", "Proxy-Authorization", "Proxy-Foo").forEach { header ->
            assertThrows(SecurityException::class.java) {
                bridge.get("https://bank.example/accounts", mapOf(header to "attacker-value"))
            }
        }
    }

    @Test
    fun `rejects unrecognized methods`() {
        val bridge = HttpBridge(OkHttpClient(), EphemeralSession.Empty, listOf("bank.example"))

        assertThrows(SecurityException::class.java) {
            bridge.execute(HttpRequest(method = "DELETE", url = "https://bank.example/accounts"))
        }
    }

    @Test
    fun `rejects unreasonable request sizes`() {
        val bridge = HttpBridge(OkHttpClient(), EphemeralSession.Empty, listOf("bank.example"))

        assertThrows(SecurityException::class.java) {
            bridge.post("https://bank.example/query", "x".repeat(2 * 1024 * 1024 + 1))
        }
        assertThrows(SecurityException::class.java) {
            bridge.buildRequest(
                "https://bank.example/query",
                (1..33).associate { "X-Test-$it" to "value" },
                null,
            )
        }
    }

}
