package tw.kevinzhang.marketplace.data

import com.google.gson.annotations.SerializedName

data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val description: String,
    val syncTrigger: SyncTriggerConfig,
    val schedule: ScheduleConfig?,
    val iconUrl: String?,
) {
    data class SyncTriggerConfig(
        val scriptPath: String = "sync-trigger.min.js",
    )

    data class ScheduleConfig(
        @SerializedName(value = "suggestedCron", alternate = ["cron"])
        val suggestedCron: String = "",
        val suggestedTimezone: String = "Asia/Taipei",
    )
}
