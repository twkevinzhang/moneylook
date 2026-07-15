package tw.kevinzhang.core.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialProfileTest {
    @Test
    fun `new profile enables suggested schedule and has no run result`() {
        val profile = CredentialProfile(
            extensionId = "tw.bot::https://github.com/example/extensions",
            username = "user123",
            password = "plain-text-password",
            scheduleCron = "0 8 * * *",
            timezoneId = "Asia/Taipei",
        )

        assertEquals("tw.bot::https://github.com/example/extensions", profile.extensionId)
        assertTrue(profile.scheduleEnabled)
        assertEquals("0 8 * * *", profile.scheduleCron)
        assertEquals("Asia/Taipei", profile.timezoneId)
        assertNull(profile.lastRunAt)
        assertNull(profile.lastRunStatus)
        assertTrue(!profile.toString().contains("plain-text-password"))
        assertTrue(!profile.toString().contains("user123"))
    }
}
