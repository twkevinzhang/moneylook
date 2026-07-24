package tw.kevinzhang.moneylook.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPanProtectorTest {
    @Test
    fun `protected PAN carrier never renders cipher material`() {
        val protected = ProtectedCardPan(
            ciphertext = "fictional-pan-ciphertext".toByteArray(),
            iv = "iv".toByteArray(),
            fingerprint = "f".repeat(64),
        )

        assertTrue(protected.toString().contains("REDACTED"))
        assertFalse(protected.toString().contains("fictional-pan-ciphertext"))
        assertFalse(protected.toString().contains("f".repeat(64)))
    }
}
