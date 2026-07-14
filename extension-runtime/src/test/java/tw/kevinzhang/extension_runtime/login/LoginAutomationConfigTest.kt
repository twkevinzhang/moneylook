package tw.kevinzhang.extension_runtime.login

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginAutomationConfigTest {
    private val parser = LoginAutomationConfigParser(Gson())

    @Test
    fun `parses expected extension login schema`() {
        val result = parser.parse(
            """{
              "usernameSelector":"#username",
              "passwordSelector":"#password",
              "captchaImageSelector":"#captcha-image",
              "captchaInputSelector":"#captcha",
              "submitSelector":"button[type=submit]",
              "successUrlContains":"/accounts",
              "postSubmitDelayMs":500
            }""",
        )

        assertTrue(result.isSuccess)
        assertEquals(500L, result.getOrThrow().postSubmitDelayMs)
    }

    @Test
    fun `rejects empty success URL marker and empty selectors`() {
        val emptyMarker = parser.parse(
            """{"usernameSelector":"#u","passwordSelector":"#p","captchaImageSelector":"#i","captchaInputSelector":"#c","submitSelector":"#s","successUrlContains":""}""",
        )
        val emptySelector = parser.parse(
            """{"usernameSelector":"","passwordSelector":"#p","captchaImageSelector":"#i","captchaInputSelector":"#c","submitSelector":"#s","successUrlContains":"ok"}""",
        )

        assertTrue(emptyMarker.isFailure)
        assertTrue(emptySelector.isFailure)
    }
}
