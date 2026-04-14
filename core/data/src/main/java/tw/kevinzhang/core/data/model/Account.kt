package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String,           // "{extensionId}_{accountName}"
    val extensionId: String,
    val extensionName: String,
    val accountName: String,
    val balance: Double,
    val currency: String,
    val lastSyncAt: Long,                 // epoch millis
)
