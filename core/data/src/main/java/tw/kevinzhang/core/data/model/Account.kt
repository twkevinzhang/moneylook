package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "accounts",
    indices = [Index(value = ["extensionId", "sourceAccountKey", "kind", "currency"])],
)
data class Account(
    @PrimaryKey val id: String,
    val extensionId: String,
    val extensionName: String,
    val accountName: String,
    val balance: Double,
    val currency: String,
    val lastSyncAt: Long,                 // epoch millis
    val accountNo: String? = null,
    /**
     * Opaque, extension-defined source identity used to match history cursors.
     * It is a lowercase 64-character SHA-256 hex digest, never an account or card number.
     * Null is retained only for legacy extensions.
     */
    val sourceAccountKey: String? = null,
    val kind: AssetKind = AssetKind.DEPOSIT,
    val branchName: String? = null,
    val availableCredit: Double? = null,
    val creditLimit: Double? = null,
    /** Null means the installed extension still uses the legacy transaction snapshot contract. */
    val transferSyncComplete: Boolean? = null,
    val sourceRecordJson: String? = null,
    val sourceFieldsJson: String? = null,
    val sourceFactsJson: String? = null,
    val parserVersion: String? = null,
)
