package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_extensions")
data class InstalledExtension(
    @PrimaryKey val id: String,
    val name: String,
    val version: Int,
    val repoUrl: String,
    val scriptCachePath: String,          // absolute path on device
    val loginUrl: String,
    val targetDomainsJson: String,        // JSON array string e.g. ["bot.com.tw"]
    val iconUrl: String?,
)
