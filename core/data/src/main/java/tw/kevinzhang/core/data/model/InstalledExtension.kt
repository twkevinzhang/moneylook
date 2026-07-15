package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_extensions")
data class InstalledExtension(
    @PrimaryKey val id: String,           // "${manifestId}::${repoUrl}" — unique per (bank, repo)
    val manifestId: String,               // original manifest id, e.g. "tw.com.cathaybk"
    val name: String,
    val version: Int,
    val repoUrl: String,
    val syncTriggerCachePath: String,     // absolute path of sync-trigger script on device
    val iconUrl: String?,
    val suggestedScheduleCron: String? = null,
    val suggestedScheduleTimezone: String = "Asia/Taipei",
    val suggestedScheduleEnabled: Boolean = true,
)
