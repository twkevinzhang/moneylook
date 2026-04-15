package tw.kevinzhang.marketplace.data

data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val description: String,
    val loginUrl: String,
    val targetDomains: List<String>,
    val syncTrigger: SyncTriggerConfig,
    val schedule: ScheduleConfig?,
    val iconUrl: String?,
) {
    data class SyncTriggerConfig(
        val scriptPath: String = "sync-trigger.min.js",
    )

    data class ScheduleConfig(
        val cron: String,
        val scriptPath: String = "schedule.min.js",
    )
}
