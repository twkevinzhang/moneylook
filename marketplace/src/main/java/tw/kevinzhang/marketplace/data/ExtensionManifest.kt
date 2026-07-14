package tw.kevinzhang.marketplace.data

import com.google.gson.annotations.SerializedName

data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val description: String,
    val loginUrl: String,
    val targetDomains: List<String>,
    val loginAutomation: LoginAutomationConfig,
    val syncTrigger: SyncTriggerConfig,
    val schedule: ScheduleConfig?,
    val iconUrl: String?,
) {
    data class SyncTriggerConfig(
        val scriptPath: String = "sync-trigger.min.js",
    )

    /**
     * Declarative selectors used by the app-owned login flow.
     *
     * Credentials are filled by native code and are never exposed to extension scripts.
     */
    data class LoginAutomationConfig(
        val usernameSelector: String,
        val passwordSelector: String,
        val captchaImageSelector: String,
        val captchaInputSelector: String,
        val submitSelector: String,
        val successUrlContains: String,
        val postSubmitDelayMs: Long? = null,
    )

    data class ScheduleConfig(
        @SerializedName(value = "suggestedCron", alternate = ["cron"])
        val suggestedCron: String = "",
        val suggestedTimezone: String = "Asia/Taipei",
    )
}
