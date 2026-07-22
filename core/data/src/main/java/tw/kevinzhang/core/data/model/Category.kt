package tw.kevinzhang.core.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-managed category shared by every installed extension. */
enum class CategoryKind {
    EXPENSE,
    INCOME,
    TRANSFER,
}

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true), Index(value = ["kind"])],
)
data class Category(
    @PrimaryKey val id: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val color: String,
    /** Displayed in ledger rows and the transaction-detail header. */
    val emoji: String = DEFAULT_EMOJI,
    /** Transfer categories are excluded from income-and-expense reporting. */
    val kind: CategoryKind = CategoryKind.EXPENSE,
) {
    init {
        require(name == name.trim() && name.isNotEmpty()) { "category name must be non-blank and trimmed" }
        require(color.isNotBlank()) { "category color must not be blank" }
        require(emoji.isNotBlank()) { "category emoji must not be blank" }
    }

    companion object {
        const val DEFAULT_EMOJI = "🏷️"
    }
}
