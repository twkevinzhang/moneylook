package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

const val LEGACY_CREDENTIAL_FIELDS_JSON =
    "[{\"key\":\"username\",\"label\":\"網銀帳號\",\"type\":\"text\",\"required\":true,\"summary\":true}," +
        "{\"key\":\"password\",\"label\":\"網銀密碼\",\"type\":\"password\",\"required\":true,\"summary\":false}]"

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
    val credentialFieldsJson: String = LEGACY_CREDENTIAL_FIELDS_JSON,
)
