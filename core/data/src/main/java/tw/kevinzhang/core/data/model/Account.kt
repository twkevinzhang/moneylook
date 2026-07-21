package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String,
    val extensionId: String,
    val extensionName: String,
    val accountName: String,
    val balance: Double,
    val currency: String,
    val lastSyncAt: Long,                 // epoch millis
    val accountNo: String? = null,
    val kind: AssetKind = AssetKind.DEPOSIT,
    val branchName: String? = null,
    val availableCredit: Double? = null,
    val creditLimit: Double? = null,
    /** Null means the installed extension still uses the legacy transaction snapshot contract. */
    val transferSyncComplete: Boolean? = null,
)
