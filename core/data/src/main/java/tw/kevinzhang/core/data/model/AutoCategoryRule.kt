package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.Normalizer
import java.util.Locale

enum class AutoCategoryRuleDirection {
    ANY,
    INCOME,
    EXPENSE,
}

/** Determines whether a rule matches a fragment of, or the whole normalized, transaction text. */
enum class AutoCategoryRuleDescriptionMatchMode {
    CONTAINS,
    EXACT,
}

/** Who supplied a rule. Public and legacy rows are retained for explainable classification. */
enum class AutoCategoryRuleOrigin {
    LEGACY,
    PUBLIC_DEFAULT,
    USER_CONFIRMED,
    PRIVATE_LEARNED,
    IMPORTED,
}

/** Whether a matching rule may write a category or deliberately leave it unchanged. */
enum class AutoCategoryRuleAction {
    AUTO_APPLY,
    ABSTAIN,
}

/** Stable fields that a v2 structured rule may inspect. */
enum class AutoCategoryRuleConditionField {
    /** Preserves v1 behaviour: description, memo, and bank transaction type are all candidates. */
    LEGACY_ANY_TEXT,
    /** V2-normalized search across all non-MCC transaction text fields. */
    SEARCHABLE_TEXT,
    DESCRIPTION,
    MEMO,
    TYPE,
    STATUS,
    MERCHANT_NAME,
    MERCHANT_CATEGORY_CODE,
    COUNTERPARTY_NAME,
    PURPOSE,
}

enum class AutoCategoryRuleConditionMatchMode {
    CONTAINS,
    EXACT,
    TOKEN,
}

/** Conditions in the same group are ORed; groups can be added without changing the row key. */
enum class AutoCategoryRuleConditionGroup {
    INCLUDE_ANY,
    INCLUDE_ALL,
    EXCLUDE_ANY,
}

/**
 * Canonical text form used by automatic-category rules.
 *
 * Bank descriptions, counterparties, memos, and transaction types often differ only in Unicode
 * width, case, or punctuation. Keep letters and digits only so matching has the same semantics
 * across all transaction-text fields.
 */
fun normalizeAutoCategoryRuleText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)

/** V2 canonicalization retains word boundaries so token matching cannot concatenate words. */
fun normalizeAutoCategoryRuleTextV2(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .trim()
        .replace(Regex("\\s+"), " ")

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
    indices = [
        Index(value = ["enabled", "priority", "id"]),
        Index(value = ["isDefault", "enabled", "priority", "id"]),
        Index(value = ["accountId"]),
        Index(value = ["categoryId"]),
        Index(value = ["ruleSetId"]),
        Index(value = ["extensionId"]),
        Index(value = ["accountKind", "extensionId"]),
        Index(value = ["origin", "action", "enabled", "priority", "id"]),
    ],
)
data class AutoCategoryRule(
    @PrimaryKey val id: String,
    val name: String,
    /** Matches each normalized transaction-text field: description, memo, and type. */
    val descriptionContains: String? = null,
    val direction: AutoCategoryRuleDirection = AutoCategoryRuleDirection.ANY,
    val minAbsoluteAmount: Double? = null,
    val maxAbsoluteAmount: Double? = null,
    val accountId: String? = null,
    val categoryId: String? = null,
    val enabled: Boolean = true,
    /** Smaller values run first; ties are deterministically resolved by [id]. */
    val priority: Int = 0,
    /** Existing rules retain their original substring behaviour after the v12 migration. */
    val descriptionMatchMode: AutoCategoryRuleDescriptionMatchMode =
        AutoCategoryRuleDescriptionMatchMode.CONTAINS,
    /** Bundled rules remain identifiable so user-created rules always run before them. */
    val isDefault: Boolean = false,
    /** Nullable on purpose: RuleSet imports are additive and may be removed independently. */
    val ruleSetId: String? = null,
    /** Narrows a rule to one installed extension without creating a dependency on installed data. */
    val extensionId: String? = null,
    /** Narrows a rule to an account product kind when a bank supplies structured metadata. */
    val accountKind: AssetKind? = null,
    @ColumnInfo(defaultValue = "'LEGACY'")
    val origin: AutoCategoryRuleOrigin = if (isDefault) {
        AutoCategoryRuleOrigin.PUBLIC_DEFAULT
    } else {
        AutoCategoryRuleOrigin.LEGACY
    },
    @ColumnInfo(defaultValue = "'AUTO_APPLY'")
    val action: AutoCategoryRuleAction = AutoCategoryRuleAction.AUTO_APPLY,
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

/**
 * Versioned public/private rule collection metadata. AutoCategoryRule deliberately has no
 * foreign key to this entity so importing or deleting a collection cannot delete user rules.
 */
@Entity(
    tableName = "auto_category_rule_sets",
    indices = [Index(value = ["origin"]), Index(value = ["isActive"])],
)
data class AutoCategoryRuleSet(
    @PrimaryKey val id: String,
    val name: String,
    val origin: AutoCategoryRuleOrigin,
    val version: String,
    val canonicalizerVersion: String,
    val contentSha256: String,
    val isActive: Boolean = true,
)

/**
 * A structured matching clause. [position] provides deterministic evaluation and is scoped to a
 * rule so an imported ruleset can retain its source ordering without globally allocated IDs.
 */
@Entity(
    tableName = "auto_category_rule_conditions",
    primaryKeys = ["ruleId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = AutoCategoryRule::class,
            parentColumns = ["id"],
            childColumns = ["ruleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["field", "matchMode"]), Index(value = ["ruleId", "conditionGroup", "position"])],
)
data class AutoCategoryRuleCondition(
    val ruleId: String,
    val position: Int,
    val conditionGroup: AutoCategoryRuleConditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
    val field: AutoCategoryRuleConditionField,
    val matchMode: AutoCategoryRuleConditionMatchMode,
    val pattern: String,
) {
    init {
        require(position >= 0) { "condition position must be non-negative" }
        require(pattern == pattern.trim() && pattern.isNotEmpty()) { "condition pattern must be non-blank and trimmed" }
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
