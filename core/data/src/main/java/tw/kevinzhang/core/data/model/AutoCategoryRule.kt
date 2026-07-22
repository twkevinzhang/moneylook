package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AutoCategoryRuleDirection {
    ANY,
    INCOME,
    EXPENSE,
}

/** Gmail-style user rule. Rules are global; [accountId] optionally narrows the scope. */
@Entity(
    tableName = "auto_category_rules",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["enabled", "priority", "id"]), Index(value = ["accountId"]), Index(value = ["categoryId"])],
)
data class AutoCategoryRule(
    @PrimaryKey val id: String,
    val name: String,
    val descriptionContains: String? = null,
    val direction: AutoCategoryRuleDirection = AutoCategoryRuleDirection.ANY,
    val minAbsoluteAmount: Double? = null,
    val maxAbsoluteAmount: Double? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val enabled: Boolean = true,
    /** Smaller values run first; ties are deterministically resolved by [id]. */
    val priority: Int = 0,
) {
    init {
        require(name == name.trim() && name.isNotEmpty()) { "rule name must be non-blank and trimmed" }
        require(descriptionContains == null || descriptionContains == descriptionContains.trim()) {
            "descriptionContains must be trimmed"
        }
        require(minAbsoluteAmount == null || minAbsoluteAmount >= 0.0) { "min amount must be non-negative" }
        require(maxAbsoluteAmount == null || maxAbsoluteAmount >= 0.0) { "max amount must be non-negative" }
        require(minAbsoluteAmount == null || maxAbsoluteAmount == null || minAbsoluteAmount <= maxAbsoluteAmount) {
            "min amount must not exceed max amount"
        }
    }
}

@Entity(
    tableName = "auto_category_rule_tag_cross_refs",
    primaryKeys = ["ruleId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = AutoCategoryRule::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = Tag::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["tagId"])],
)
data class AutoCategoryRuleTagCrossRef(
    val ruleId: String,
    val tagId: String,
)
