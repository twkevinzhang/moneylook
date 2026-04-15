package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_extensions")
data class InstalledExtension(
    @PrimaryKey val id: String,
    val name: String,
    val version: Int,
    val repoUrl: String,
    val syncTriggerCachePath: String,     // absolute path of sync-trigger script on device
    val loginUrl: String,
    val targetDomainsJson: String,        // JSON array string e.g. ["mybank.com"]
    val iconUrl: String?,
    val scheduleCachePath: String?,       // absolute path of schedule script; null if no schedule
    val scheduleCron: String?,            // cron expression; null if no schedule
)
