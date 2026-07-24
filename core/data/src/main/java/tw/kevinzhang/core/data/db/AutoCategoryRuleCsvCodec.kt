package tw.kevinzhang.core.data.db

import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleDirection
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin

/** A fully validated, database-independent import that callers can persist in one transaction. */
data class AutoCategoryRuleCsvImport(
    val rules: List<AutoCategoryRule>,
    val conditionsByRuleId: Map<String, List<AutoCategoryRuleCondition>>,
)

sealed interface AutoCategoryRuleCsvDecodeResult {
    data class Success(val value: AutoCategoryRuleCsvImport) : AutoCategoryRuleCsvDecodeResult
    data class Failure(val reason: String) : AutoCategoryRuleCsvDecodeResult
}

/**
 * Strict offline codec for shareable rule files. It intentionally rejects partial rows, unknown
 * columns and versions, so an invalid export cannot be partially imported into Room.
 */
object AutoCategoryRuleCsvCodec {
    private const val MARKER = "moneylook-auto-category-rules"
    private const val V2 = "2"
    private const val V1 = "1"
    private const val V1_1 = "1.1"
    private const val MAX_RULES = 2_000
    private const val MAX_CONDITIONS_PER_RULE = 100
    private const val MAX_PATTERN_LENGTH = 256

    fun encodeV2(import: AutoCategoryRuleCsvImport): String {
        require(import.rules.size <= MAX_RULES) { "too many rules" }
        require(import.rules.map { it.id }.toSet() == import.conditionsByRuleId.keys) {
            "every v2 rule must have exactly one condition collection"
        }
        val rows = mutableListOf<List<String>>()
        rows += listOf(MARKER, V2)
        rows += V2_HEADER
        import.rules.sortedBy { it.id }.forEach { rule ->
            val conditions = import.conditionsByRuleId[rule.id].orEmpty().sortedBy { it.position }
            require(conditions.isNotEmpty()) { "v2 rules require at least one condition" }
            require(conditions.size <= MAX_CONDITIONS_PER_RULE) { "too many conditions for ${rule.id}" }
            require(conditions.all { it.ruleId == rule.id }) { "condition belongs to a different rule" }
            conditions.forEach { condition ->
                require(condition.pattern.length <= MAX_PATTERN_LENGTH) { "condition pattern is too long" }
                rows += ruleColumns(rule) + listOf(
                    condition.conditionGroup.name,
                    condition.position.toString(),
                    condition.field.name,
                    condition.matchMode.name,
                    condition.pattern,
                )
            }
        }
        return rows.joinToString("\n") { row -> row.joinToString(",") { escape(it) } }
    }

    fun decode(csv: String): AutoCategoryRuleCsvDecodeResult = try {
        val rows = parse(csv)
        when {
            rows.firstOrNull() == LEGACY_RULES_CSV_V1_HEADER -> decodeLegacyRulesCsvV1(rows)
            rows.size < 2 || rows[0] != listOf(MARKER, rows[0].getOrNull(1)) ->
                fail("missing marker")
            rows[0].getOrNull(1) == V2 -> decodeV2(rows.drop(1))
            rows[0].getOrNull(1) in setOf(V1, V1_1) ->
                decodeV1(rows[0][1], rows.drop(1))
            else -> fail("unsupported version")
        }
    } catch (error: IllegalArgumentException) {
        AutoCategoryRuleCsvDecodeResult.Failure(error.message ?: "invalid CSV")
    }

    private fun decodeV2(rows: List<List<String>>): AutoCategoryRuleCsvDecodeResult {
        require(rows.firstOrNull() == V2_HEADER) { "unexpected v2 header" }
        val byId = linkedMapOf<String, AutoCategoryRule>()
        val conditions = linkedMapOf<String, MutableList<AutoCategoryRuleCondition>>()
        rows.drop(1).forEachIndexed { index, row ->
            require(row.size == V2_HEADER.size) { "row ${index + 2} has an unexpected column count" }
            val rule = ruleFromColumns(row.take(17))
            val existing = byId.putIfAbsent(rule.id, rule)
            require(existing == null || existing == rule) { "inconsistent duplicate rule ${rule.id}" }
            if (row.drop(17).all(String::isEmpty)) return@forEachIndexed
            require(row.drop(17).none(String::isEmpty)) { "partial condition for ${rule.id}" }
            val field = enumValueOf<AutoCategoryRuleConditionField>(row[19])
            val pattern = row[21]
            require(pattern.length <= MAX_PATTERN_LENGTH) { "condition pattern is too long" }
            if (field == AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE) {
                require(pattern.matches(Regex("\\d{4}"))) { "invalid MCC" }
                require(row[20] == AutoCategoryRuleConditionMatchMode.EXACT.name) {
                    "MCC conditions must use exact matching"
                }
            }
            val condition = AutoCategoryRuleCondition(
                ruleId = rule.id,
                position = row[18].toIntOrNull()?.takeIf { it >= 0 }
                    ?: throw IllegalArgumentException("invalid condition position"),
                conditionGroup = enumValueOf(row[17]),
                field = field,
                matchMode = enumValueOf<AutoCategoryRuleConditionMatchMode>(row[20]),
                pattern = pattern,
            )
            require(conditions.getOrPut(rule.id) { mutableListOf() }.none { it.position == condition.position }) {
                "duplicate condition position"
            }
            conditions.getValue(rule.id) += condition
        }
        require(byId.isNotEmpty()) { "no rules" }
        require(byId.size <= MAX_RULES) { "too many rules" }
        require(byId.keys.all { conditions[it].orEmpty().isNotEmpty() }) { "v2 rules require at least one condition" }
        require(conditions.values.all { it.size <= MAX_CONDITIONS_PER_RULE }) {
            "too many conditions for one rule"
        }
        return AutoCategoryRuleCsvDecodeResult.Success(AutoCategoryRuleCsvImport(byId.values.toList(), conditions))
    }

    private fun decodeV1(version: String, rows: List<List<String>>): AutoCategoryRuleCsvDecodeResult {
        val header = rows.firstOrNull() ?: fail("missing header")
        val expected = if (version == V1) V1_HEADER else V1_1_HEADER
        require(header == expected) { "unexpected v1 header" }
        val rules = rows.drop(1).mapIndexed { index, row ->
            require(row.size == expected.size) { "row ${index + 2} has an unexpected column count" }
            val padded = if (version == V1) row + listOf("CONTAINS", "false") else row
            ruleFromColumns(padded + List(5) { "" })
        }
        require(rules.isNotEmpty()) { "no rules" }
        require(rules.size <= MAX_RULES) { "too many rules" }
        require(rules.map { it.id }.distinct().size == rules.size) { "duplicate rule id" }
        val conditions = rules.mapNotNull { rule ->
            rule.descriptionContains?.takeIf(String::isNotBlank)?.let { description ->
                require(description.length <= MAX_PATTERN_LENGTH) {
                    "condition pattern is too long"
                }
                rule.id to listOf(
                    AutoCategoryRuleCondition(
                        ruleId = rule.id,
                        position = 0,
                        conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                        field = AutoCategoryRuleConditionField.LEGACY_ANY_TEXT,
                        matchMode = AutoCategoryRuleConditionMatchMode.valueOf(rule.descriptionMatchMode.name),
                        pattern = description,
                    ),
                )
            }
        }.toMap()
        return AutoCategoryRuleCsvDecodeResult.Success(AutoCategoryRuleCsvImport(rules, conditions))
    }

    /**
     * Compatibility for the original user-facing rules.csv contract. Keep this decoder for two
     * releases after v2 ships; the legacy file has no marker row and carries schema_version per
     * rule.
     */
    private fun decodeLegacyRulesCsvV1(rows: List<List<String>>): AutoCategoryRuleCsvDecodeResult {
        val rules = rows.drop(1).mapIndexed { index, row ->
            require(row.size == LEGACY_RULES_CSV_V1_HEADER.size) {
                "row ${index + 2} has an unexpected column count"
            }
            require(row[0] == V1) { "unsupported legacy row version" }
            AutoCategoryRule(
                id = required(row[1], "rule_id"),
                name = required(row[2], "rule_name"),
                descriptionContains = nullable(row[3]),
                descriptionMatchMode = enumValueOf(row[4]),
                direction = enumValueOf(row[5]),
                minAbsoluteAmount = nullableDouble(row[6], "min_absolute_amount"),
                maxAbsoluteAmount = nullableDouble(row[7], "max_absolute_amount"),
                categoryId = nullable(row[8]),
                enabled = bool(row[9]),
                priority = row[10].toIntOrNull()
                    ?: throw IllegalArgumentException("invalid priority"),
                origin = AutoCategoryRuleOrigin.IMPORTED,
                action = AutoCategoryRuleAction.AUTO_APPLY,
            )
        }
        require(rules.isNotEmpty()) { "no rules" }
        require(rules.size <= MAX_RULES) { "too many rules" }
        require(rules.map { it.id }.distinct().size == rules.size) { "duplicate rule id" }
        val conditions = rules.mapNotNull { rule ->
            rule.descriptionContains?.let { pattern ->
                require(pattern.length <= MAX_PATTERN_LENGTH) { "condition pattern is too long" }
                rule.id to listOf(
                    AutoCategoryRuleCondition(
                        ruleId = rule.id,
                        position = 0,
                        conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                        field = AutoCategoryRuleConditionField.LEGACY_ANY_TEXT,
                        matchMode = AutoCategoryRuleConditionMatchMode.valueOf(
                            rule.descriptionMatchMode.name,
                        ),
                        pattern = pattern,
                    ),
                )
            }
        }.toMap()
        return AutoCategoryRuleCsvDecodeResult.Success(
            AutoCategoryRuleCsvImport(rules, conditions),
        )
    }

    private fun ruleFromColumns(columns: List<String>): AutoCategoryRule = AutoCategoryRule(
        id = required(columns[0], "id"),
        name = required(columns[1], "name"),
        descriptionContains = nullable(columns[2]),
        direction = enumValueOf(columns[3]),
        minAbsoluteAmount = nullableDouble(columns[4], "minAbsoluteAmount"),
        maxAbsoluteAmount = nullableDouble(columns[5], "maxAbsoluteAmount"),
        accountId = nullable(columns[6]),
        categoryId = nullable(columns[7]),
        enabled = bool(columns[8]),
        priority = columns[9].toIntOrNull() ?: throw IllegalArgumentException("invalid priority"),
        descriptionMatchMode = enumValueOf(columns[10]),
        isDefault = bool(columns[11]),
        ruleSetId = nullable(columns[12]),
        extensionId = nullable(columns[13]),
        accountKind = nullable(columns[14])?.let { enumValueOf<AssetKind>(it) },
        origin = nullable(columns[15])?.let { enumValueOf<AutoCategoryRuleOrigin>(it) }
            ?: AutoCategoryRuleOrigin.LEGACY,
        action = nullable(columns[16])?.let { raw ->
            require(raw != "SUGGEST") { "SUGGEST is no longer supported; use AUTO_APPLY or ABSTAIN" }
            enumValueOf<AutoCategoryRuleAction>(raw)
        }
            ?: AutoCategoryRuleAction.AUTO_APPLY,
    )

    private fun ruleColumns(rule: AutoCategoryRule) = listOf(
        rule.id, rule.name, rule.descriptionContains.orEmpty(), rule.direction.name,
        rule.minAbsoluteAmount?.toString().orEmpty(), rule.maxAbsoluteAmount?.toString().orEmpty(),
        rule.accountId.orEmpty(), rule.categoryId.orEmpty(), rule.enabled.toString(), rule.priority.toString(),
        rule.descriptionMatchMode.name, rule.isDefault.toString(), rule.ruleSetId.orEmpty(),
        rule.extensionId.orEmpty(), rule.accountKind?.name.orEmpty(), rule.origin.name, rule.action.name,
    )

    private fun parse(csv: String): List<List<String>> {
        require(csv.length <= 1_000_000) { "CSV is too large" }
        val rows = mutableListOf<MutableList<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < csv.length) {
            when (val char = csv[index]) {
                '"' -> if (quoted && csv.getOrNull(index + 1) == '"') { cell.append(char); index++ } else quoted = !quoted
                ',' -> if (quoted) cell.append(char) else { row += cell.toString(); cell.clear() }
                '\n' -> if (quoted) cell.append(char) else { row += cell.toString(); cell.clear(); rows += row; row = mutableListOf() }
                '\r' -> Unit
                else -> cell.append(char)
            }
            index++
        }
        require(!quoted) { "unterminated quoted cell" }
        row += cell.toString()
        if (row.any(String::isNotEmpty) || rows.isEmpty()) rows += row
        require(rows.size <= 10_000) { "too many rows" }
        return rows.map { it.toList() }
    }

    private fun nullable(value: String): String? = value.ifBlank { null }
    private fun nullableDouble(value: String, name: String): Double? = nullable(value)?.let {
        it.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: throw IllegalArgumentException("invalid $name")
    }
    private fun required(value: String, name: String): String = value.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("missing $name")
    private fun bool(value: String): Boolean = when (value) { "true" -> true; "false" -> false; else -> throw IllegalArgumentException("invalid boolean") }
    private fun fail(reason: String): Nothing = throw IllegalArgumentException(reason)
    private fun escape(value: String): String = if (value.any { it == ',' || it == '"' || it == '\n' }) "\"${value.replace("\"", "\"\"")}\"" else value

    private val V1_HEADER = listOf("id", "name", "descriptionContains", "direction", "minAbsoluteAmount", "maxAbsoluteAmount", "accountId", "categoryId", "enabled", "priority")
    private val V1_1_HEADER = V1_HEADER + listOf("descriptionMatchMode", "isDefault")
    private val V2_HEADER = V1_1_HEADER + listOf("ruleSetId", "extensionId", "accountKind", "origin", "action", "conditionGroup", "conditionPosition", "conditionField", "conditionMatchMode", "conditionPattern")
    private val LEGACY_RULES_CSV_V1_HEADER = listOf(
        "schema_version",
        "rule_id",
        "rule_name",
        "description_pattern",
        "description_match_mode",
        "direction",
        "min_absolute_amount",
        "max_absolute_amount",
        "category_id",
        "enabled",
        "priority",
    )
}
