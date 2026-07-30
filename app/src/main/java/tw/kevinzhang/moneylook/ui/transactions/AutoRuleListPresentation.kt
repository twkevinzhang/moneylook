package tw.kevinzhang.moneylook.ui.transactions

import java.text.Normalizer
import java.util.Locale
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.AssetKind

/** The two top-level ways a rule library can be browsed. */
enum class AutoRuleGroupingMode {
    CATEGORY,
    ORIGIN,
}

/** Explains why a group exists so the UI can render meaningful special buckets. */
enum class AutoRuleGroupKind {
    CATEGORY,
    TAG_ONLY,
    ABSTAIN,
    MISSING_CATEGORY,
    ORIGIN,
}

/**
 * A stable, UI-ready section of the automatic-rule library.
 *
 * [key] preserves expansion state across recompositions while [kind] lets the
 * UI distinguish category, source, and special-result buckets.
 */
data class AutoRuleGroupUi(
    val key: String,
    val label: String,
    val color: Long,
    val kind: AutoRuleGroupKind,
    val rules: List<AutoRuleDraft>,
)

private const val TAG_ONLY_GROUP_KEY = "special:tag-only"
private const val ABSTAIN_GROUP_KEY = "special:abstain"
private const val MISSING_CATEGORY_GROUP_KEY = "special:missing-category"
private const val NEUTRAL_GROUP_COLOR = 0xFF607D8BL

/**
 * Builds the rule-library sections after applying the user's search and
 * enabled-state filter. Category ordering follows [categories] exactly; source
 * ordering is intentionally fixed so imported/default rules do not jump around.
 */
fun buildAutoRuleGroups(
    rules: List<AutoRuleDraft>,
    categories: List<CategoryOption>,
    tags: List<TagOption>,
    groupingMode: AutoRuleGroupingMode,
    query: String,
    disabledOnly: Boolean,
): List<AutoRuleGroupUi> {
    val matchingRules = rules.asSequence()
        .filter { !disabledOnly || !it.enabled }
        .filter { it.matchesAutoRuleLibraryQuery(query, categories, tags) }
        .toList()
    return when (groupingMode) {
        AutoRuleGroupingMode.CATEGORY -> matchingRules.groupByCategory(categories)
        AutoRuleGroupingMode.ORIGIN -> matchingRules.groupByOrigin()
    }
}

private fun List<AutoRuleDraft>.groupByCategory(categories: List<CategoryOption>): List<AutoRuleGroupUi> {
    val categoriesById = categories.associateBy(CategoryOption::id)
    val grouped = linkedMapOf<String, MutableList<AutoRuleDraft>>()
    categories.forEach { grouped["category:${it.id}"] = mutableListOf() }
    val tagOnly = mutableListOf<AutoRuleDraft>()
    val abstain = mutableListOf<AutoRuleDraft>()
    val missing = mutableListOf<AutoRuleDraft>()

    forEach { rule ->
        when {
            rule.action == AutoCategoryRuleAction.ABSTAIN -> abstain += rule
            rule.categoryId == null && rule.tagIds.isNotEmpty() -> tagOnly += rule
            rule.categoryId != null && categoriesById.containsKey(rule.categoryId) ->
                grouped.getValue("category:${rule.categoryId}") += rule
            else -> missing += rule
        }
    }

    return buildList {
        categories.forEach { category ->
            val groupRules = grouped.getValue("category:${category.id}")
            if (groupRules.isNotEmpty()) {
                add(
                    AutoRuleGroupUi(
                        key = "category:${category.id}",
                        label = category.name,
                        color = category.color,
                        kind = AutoRuleGroupKind.CATEGORY,
                        rules = groupRules.sortedWith(autoRuleDraftComparator),
                    ),
                )
            }
        }
        tagOnly.toGroup(TAG_ONLY_GROUP_KEY, "僅套用標籤", AutoRuleGroupKind.TAG_ONLY)?.let(::add)
        abstain.toGroup(ABSTAIN_GROUP_KEY, "放棄自動分類", AutoRuleGroupKind.ABSTAIN)?.let(::add)
        missing.toGroup(MISSING_CATEGORY_GROUP_KEY, "分類已刪除", AutoRuleGroupKind.MISSING_CATEGORY)?.let(::add)
    }
}

private fun List<AutoRuleDraft>.groupByOrigin(): List<AutoRuleGroupUi> = buildList {
    AUTO_RULE_ORIGIN_ORDER.forEach { origin ->
        this@groupByOrigin.filter { it.origin == origin }
            .toGroup(
                key = "origin:${origin.name}",
                label = origin.originLabel(),
                kind = AutoRuleGroupKind.ORIGIN,
            )
            ?.let(::add)
    }
}

private fun List<AutoRuleDraft>.toGroup(
    key: String,
    label: String,
    kind: AutoRuleGroupKind,
    color: Long = NEUTRAL_GROUP_COLOR,
): AutoRuleGroupUi? = takeIf(List<AutoRuleDraft>::isNotEmpty)?.let {
    AutoRuleGroupUi(
        key = key,
        label = label,
        color = color,
        kind = kind,
        rules = sortedWith(autoRuleDraftComparator),
    )
}

private val AUTO_RULE_ORIGIN_ORDER = listOf(
    AutoCategoryRuleOrigin.USER_CONFIRMED,
    AutoCategoryRuleOrigin.PRIVATE_LEARNED,
    AutoCategoryRuleOrigin.IMPORTED,
    AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
    AutoCategoryRuleOrigin.LEGACY,
)

fun AutoCategoryRuleOrigin.originLabel(): String = when (this) {
    AutoCategoryRuleOrigin.USER_CONFIRMED -> "我建立的"
    AutoCategoryRuleOrigin.PRIVATE_LEARNED -> "個人學習"
    AutoCategoryRuleOrigin.IMPORTED -> "匯入規則"
    AutoCategoryRuleOrigin.PUBLIC_DEFAULT -> "內建規則"
    AutoCategoryRuleOrigin.LEGACY -> "舊版規則"
}

fun AutoRuleDraft.originLabel(): String = origin.originLabel()

/** A concise, non-algorithmic list-row summary. It never implies global execution order. */
fun AutoRuleDraft.compactConditionSummary(): String = buildList {
    descriptionContains.takeIf(String::isNotBlank)?.let { add("交易文字「$it」") }
    conditions.groupBy { it.field }.forEach { (field, fieldConditions) ->
        add("${field.displayLabel()} ${fieldConditions.size} 項")
    }
    amountSign.displayLabel()?.let(::add)
    minAbsoluteAmount.takeIf(String::isNotBlank)?.let { add("金額 ≥ $it") }
    maxAbsoluteAmount.takeIf(String::isNotBlank)?.let { add("金額 ≤ $it") }
    accountId?.let { add("指定帳戶") }
    accountKind?.let { add(it.displayLabel()) }
    extensionId?.let { add("指定擴充功能") }
}.ifEmpty { listOf("所有交易") }.joinToString(" · ")

/** Detail text for an IF/THEN view. [categories] and [tags] supply user-facing action names. */
fun AutoRuleDraft.readableConditionLines(): List<String> = buildList {
    descriptionContains.takeIf(String::isNotBlank)?.let { description ->
        add("交易文字${if (descriptionMatchMode == tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode.EXACT) "完全為" else "包含"}「$description」")
    }
    conditions.sortedBy { it.position }.forEach { condition ->
        add("${condition.conditionGroup.displayLabel()} ${condition.field.displayLabel()}${condition.matchMode.displayLabel()}「${condition.pattern}」")
    }
    amountSign.displayLabel()?.let(::add)
    minAbsoluteAmount.takeIf(String::isNotBlank)?.let { add("金額至少 $it") }
    maxAbsoluteAmount.takeIf(String::isNotBlank)?.let { add("金額至多 $it") }
    accountId?.let { add("限定指定帳戶") }
    accountKind?.let { add("限定${it.displayLabel()}") }
    extensionId?.let { add("限定指定擴充功能") }
}.ifEmpty { listOf("所有交易") }

fun AutoRuleDraft.readableActionSummary(
    categories: List<CategoryOption>,
    tags: List<TagOption>,
): String = when (action) {
    AutoCategoryRuleAction.ABSTAIN -> "放棄自動分類"
    AutoCategoryRuleAction.AUTO_APPLY -> buildList {
        categories.firstOrNull { it.id == categoryId }?.let { add("分類為「${it.name}」") }
        tags.filter { it.id in tagIds }.takeIf(List<TagOption>::isNotEmpty)
            ?.let { add("套用標籤：${it.joinToString { tag -> tag.name }}") }
    }.ifEmpty { listOf("不變更分類或標籤") }.joinToString("；")
}

fun AutoRuleDraft.readableSourceSummary(): String = buildList {
    add(origin.originLabel())
    ruleSetId?.let { add("規則集") }
    if (isDefault) add("內建")
    if (!enabled) add("已停用")
    add("同分時優先：$priority")
}.joinToString(" · ")

/** Duplicating an imported/default rule creates a safe editable user-owned draft. */
fun AutoRuleDraft.duplicateAsUserRule(priority: Int): AutoRuleDraft = copy(
    id = "",
    name = "$name 副本",
    enabled = true,
    priority = priority,
    isDefault = false,
    ruleSetId = null,
    origin = AutoCategoryRuleOrigin.USER_CONFIRMED,
)

private fun AutoRuleDraft.matchesAutoRuleLibraryQuery(
    query: String,
    categories: List<CategoryOption>,
    tags: List<TagOption>,
): Boolean {
    val normalizedQuery = query.autoRuleSearchNormalized()
    if (normalizedQuery.isEmpty()) return true
    val categoryName = categories.firstOrNull { it.id == categoryId }?.name.orEmpty()
    val tagNames = tags.filter { it.id in tagIds }.joinToString(" ", transform = TagOption::name)
    val searchable = listOf(
        name,
        descriptionContains,
        conditions.joinToString(" ") { it.pattern },
        categoryName,
        tagNames,
        origin.originLabel(),
        compactConditionSummary(),
        readableConditionLines().joinToString(" "),
        readableSourceSummary(),
    ).joinToString(" ").autoRuleSearchNormalized()
    return normalizedQuery in searchable
}

private fun String.autoRuleSearchNormalized(): String = Normalizer.normalize(trim(), Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")

private fun AutoCategoryRuleConditionField.displayLabel(): String = when (this) {
    AutoCategoryRuleConditionField.LEGACY_ANY_TEXT -> "交易文字"
    AutoCategoryRuleConditionField.SEARCHABLE_TEXT -> "可搜尋文字"
    AutoCategoryRuleConditionField.DESCRIPTION -> "交易說明"
    AutoCategoryRuleConditionField.MEMO -> "備註"
    AutoCategoryRuleConditionField.TYPE -> "類型"
    AutoCategoryRuleConditionField.STATUS -> "狀態"
    AutoCategoryRuleConditionField.MERCHANT_NAME -> "商家"
    AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE -> "MCC"
    AutoCategoryRuleConditionField.COUNTERPARTY_NAME -> "對手方"
    AutoCategoryRuleConditionField.PURPOSE -> "用途"
    AutoCategoryRuleConditionField.CHANNEL -> "通路"
    AutoCategoryRuleConditionField.TRANSACTION_CODE -> "交易代碼"
    AutoCategoryRuleConditionField.REFERENCE_NUMBER -> "參考編號"
    AutoCategoryRuleConditionField.MERCHANT_LOCATION -> "商家地點"
}

private fun AutoCategoryRuleConditionGroup.displayLabel(): String = when (this) {
    AutoCategoryRuleConditionGroup.INCLUDE_ANY -> "符合任一"
    AutoCategoryRuleConditionGroup.INCLUDE_ALL -> "全部符合"
    AutoCategoryRuleConditionGroup.EXCLUDE_ANY -> "排除"
}

private fun AutoCategoryRuleConditionMatchMode.displayLabel(): String = when (this) {
    AutoCategoryRuleConditionMatchMode.CONTAINS -> "包含"
    AutoCategoryRuleConditionMatchMode.EXACT -> "等於"
    AutoCategoryRuleConditionMatchMode.TOKEN -> "含詞"
}

private fun AutoCategoryRuleAmountSign.displayLabel(): String? = when (this) {
    AutoCategoryRuleAmountSign.ANY -> null
    AutoCategoryRuleAmountSign.POSITIVE -> "收入"
    AutoCategoryRuleAmountSign.NEGATIVE -> "支出"
}

private fun AssetKind.displayLabel(): String = when (this) {
    AssetKind.DEPOSIT -> "活存"
    AssetKind.TIME_DEPOSIT -> "定存"
    AssetKind.CREDIT_CARD -> "信用卡"
    AssetKind.LOAN -> "貸款"
}
