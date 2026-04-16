package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfers")
data class Transfer(
    @PrimaryKey val id: String,       // "{accountId}_{txnDateTime}"
    val accountId: String,            // FK to accounts.id
    val extensionId: String,
    val txnDateTime: String,
    val description: String,
    val amount: Double,               // positive = income, negative = expend
    val balance: Double,
    val memo: String,
)
