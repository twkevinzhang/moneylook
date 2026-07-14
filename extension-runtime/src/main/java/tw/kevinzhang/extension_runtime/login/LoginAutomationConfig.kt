package tw.kevinzhang.extension_runtime.login

import com.google.gson.Gson
import kotlinx.coroutines.CancellationException

data class LoginAutomationConfig(
    val usernameSelector: String,
    val passwordSelector: String,
    val captchaImageSelector: String,
    val captchaInputSelector: String,
    val submitSelector: String,
    val successUrlContains: String,
    val postSubmitDelayMs: Long = 0,
) {
    fun validate(): LoginAutomationConfig {
        val selectors = listOf(
            usernameSelector,
            passwordSelector,
            captchaImageSelector,
            captchaInputSelector,
            submitSelector,
        )
        require(selectors.all { it.isNotBlank() && it.length <= MAX_SELECTOR_LENGTH }) {
            "login automation selectors must be non-empty and at most $MAX_SELECTOR_LENGTH characters"
        }
        require(successUrlContains.isNotBlank() && successUrlContains.length <= MAX_URL_MARKER_LENGTH) {
            "successUrlContains is invalid"
        }
        require(postSubmitDelayMs in 0..MAX_POST_SUBMIT_DELAY_MS) {
            "postSubmitDelayMs is out of range"
        }
        return this
    }

    private companion object {
        const val MAX_SELECTOR_LENGTH = 512
        const val MAX_URL_MARKER_LENGTH = 512
        const val MAX_POST_SUBMIT_DELAY_MS = 30_000L
    }
}

class LoginAutomationConfigParser(private val gson: Gson) {
    fun parse(json: String): Result<LoginAutomationConfig> = try {
        Result.success(gson.fromJson(json, LoginAutomationConfig::class.java).validate())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(IllegalArgumentException("invalid login automation config", e))
    }
}
