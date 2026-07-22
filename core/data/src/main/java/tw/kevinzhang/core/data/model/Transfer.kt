package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transfers",
    indices = [
        Index(value = ["accountId", "txnDateTime"]),
        // Global ledgers and reports constrain dates across every account.
        Index(value = ["txnDateTime"]),
    ],
)
data class Transfer(
    @PrimaryKey val id: String,       // "{accountId}_{txnDateTime}"
    val accountId: String,            // FK to accounts.id
    val extensionId: String,
    val txnDateTime: String,
    val description: String,
    val amount: Double,               // positive = income, negative = expend
    val balance: Double?,
    val memo: String,
    /** Bank-provided transaction category, when available. */
    val type: String? = null,
    /** Bank-provided posting / settlement status, when available. */
    val status: String? = null,
)
