package tw.kevinzhang.extension_runtime.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EphemeralSessionTest {
    @Test
    fun `cookie is selected only for exact domain or subdomain`() {
        val session = EphemeralSession.of(mapOf("bank.example" to "sid=secret"))

        assertEquals("sid=secret", session.cookieHeaderFor("bank.example"))
        assertEquals("sid=secret", session.cookieHeaderFor("api.bank.example"))
        assertNull(session.cookieHeaderFor("bank.example.attacker.test"))
        assertNull(session.cookieHeaderFor("attacker-bank.example"))
    }

    @Test
    fun `most specific cookie domain wins`() {
        val session = EphemeralSession.of(
            mapOf(
                "bank.example" to "root=1",
                "api.bank.example" to "api=1",
            ),
        )

        assertEquals("api=1", session.cookieHeaderFor("api.bank.example"))
    }
}
