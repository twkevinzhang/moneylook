package tw.kevinzhang.core.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The one and only reporting group for a category.
 *
 * A category is always part of income, expense, or excluded reporting.  This is intentionally
 * not a transaction-direction hint: an uncategorized transaction continues to use its amount
 * sign for provisional reporting, while a categorized transaction is governed by this group.
 */
enum class CategoryReportingGroup {
    INCOME,
    EXPENSE,
    EXCLUDED,
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
    /**
     * Reporting group for every transaction assigned to this category.
     *
     * Keep the established SQL column name while making the domain name explicit in Kotlin.
     */
    @ColumnInfo(name = "kind")
    val reportingGroup: CategoryReportingGroup = CategoryReportingGroup.EXPENSE,
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
