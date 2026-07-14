package tw.kevinzhang.extension_runtime.login

import org.junit.Assert.assertFalse
import org.junit.Test

class LoginCredentialsTest {
    @Test
    fun `toString redacts username and password`() {
        val credentials = LoginCredentials("sensitive-user", "sensitive-password")

        assertFalse(credentials.toString().contains("sensitive-user"))
        assertFalse(credentials.toString().contains("sensitive-password"))
    }
}
