package tw.kevinzhang.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin

class AutoCategoryRuleCsvCodecTest {
    @Test
    fun `v4 writes amount sign and accepts legacy direction values`() {
        val current = AutoCategoryRuleCsvCodec.encode(
            AutoCategoryRuleCsvImport(
                rules = listOf(
                    AutoCategoryRule(
                        id = "current",
                        name = "Current",
                        amountSign = tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign.POSITIVE,
                    ),
                ),
                conditionsByRuleId = mapOf("current" to emptyList()),
            ),
        )
        assertTrue(current.startsWith("moneylook-auto-category-rules,4"))
        assertTrue(current.contains("amountSign"))

        val legacy = AutoCategoryRuleCsvCodec.encodeV3(
            AutoCategoryRuleCsvImport(
                rules = listOf(
                    AutoCategoryRule(
                        id = "old",
                        name = "Old",
                        amountSign = tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign.NEGATIVE,
                    ),
                ),
                conditionsByRuleId = mapOf("old" to emptyList()),
            ),
        )
        assertTrue(legacy.contains(",EXPENSE,"))
        assertTrue(!legacy.contains(",NEGATIVE,"))
        val decoded = AutoCategoryRuleCsvCodec.decode(legacy) as AutoCategoryRuleCsvDecodeResult.Success
        assertEquals(
            tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign.NEGATIVE,
            decoded.value.rules.single().amountSign,
        )
    }

    @Test
    fun `v3 supports an empty custom-rule backup`() {
        val csv = AutoCategoryRuleCsvCodec.encode(
            AutoCategoryRuleCsvImport(
                rules = emptyList(),
                conditionsByRuleId = emptyMap(),
                tagsByRuleId = emptyMap(),
            ),
        )

        val decoded = AutoCategoryRuleCsvCodec.decode(csv)

        assertTrue(decoded is AutoCategoryRuleCsvDecodeResult.Success)
        decoded as AutoCategoryRuleCsvDecodeResult.Success
        assertTrue(decoded.value.rules.isEmpty())
        assertTrue(decoded.value.conditionsByRuleId.isEmpty())
        assertTrue(decoded.value.tagsByRuleId.isEmpty())
    }

    @Test
    fun `v3 round trip retains tags and rules with no structured conditions`() {
        val noTextRule = AutoCategoryRule(
            id = "amount-only",
            name = "Only amount and account",
            amountSign = tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign.NEGATIVE,
            minAbsoluteAmount = 500.0,
            accountId = "account-1",
            categoryId = "expense-shopping",
        )
        val structuredRule = AutoCategoryRule(id = "text", name = "Text")
        val source = AutoCategoryRuleCsvImport(
            rules = listOf(noTextRule, structuredRule),
            conditionsByRuleId = mapOf(
                noTextRule.id to emptyList(),
                structuredRule.id to listOf(
                    AutoCategoryRuleCondition(
                        structuredRule.id,
                        7,
                        AutoCategoryRuleConditionGroup.INCLUDE_ALL,
                        AutoCategoryRuleConditionField.DESCRIPTION,
                        AutoCategoryRuleConditionMatchMode.CONTAINS,
                        "quoted, \"merchant\"",
                    ),
                ),
            ),
            tagsByRuleId = mapOf(
                noTextRule.id to listOf("tag-household", "tag-2026"),
                structuredRule.id to emptyList(),
            ),
        )

        val result = AutoCategoryRuleCsvCodec.decode(AutoCategoryRuleCsvCodec.encode(source))

        assertTrue(result is AutoCategoryRuleCsvDecodeResult.Success)
        val value = (result as AutoCategoryRuleCsvDecodeResult.Success).value
        assertEquals(source.rules.sortedBy { it.id }, value.rules)
        assertEquals(emptyList<AutoCategoryRuleCondition>(), value.conditionsByRuleId.getValue(noTextRule.id))
        assertEquals(
            source.conditionsByRuleId.getValue(structuredRule.id),
            value.conditionsByRuleId.getValue(structuredRule.id),
        )
        assertEquals(
            source.tagsByRuleId.getValue(noTextRule.id).sorted(),
            value.tagsByRuleId.getValue(noTextRule.id),
        )
    }

    @Test
    fun `v2 round trip retains positioned structured conditions`() {
        val rule = AutoCategoryRule(id = "rule", name = "quoted, rule")
        val source = AutoCategoryRuleCsvImport(
            rules = listOf(rule),
            conditionsByRuleId = mapOf(
                rule.id to listOf(
                    AutoCategoryRuleCondition(rule.id, 3, AutoCategoryRuleConditionGroup.INCLUDE_ANY, AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE, AutoCategoryRuleConditionMatchMode.EXACT, "5411"),
                ),
            ),
        )
        val result = AutoCategoryRuleCsvCodec.decode(AutoCategoryRuleCsvCodec.encodeV2(source))
        assertTrue(result is AutoCategoryRuleCsvDecodeResult.Success)
        val success = result as AutoCategoryRuleCsvDecodeResult.Success
        assertEquals(source.rules, success.value.rules)
        assertEquals(source.conditionsByRuleId, success.value.conditionsByRuleId)
    }

    @Test
    fun `v1 and v1 point one become legacy any text conditions`() {
        val v1 = """
            moneylook-auto-category-rules,1
            id,name,descriptionContains,direction,minAbsoluteAmount,maxAbsoluteAmount,accountId,categoryId,enabled,priority
            old,Old rule,refund,INCOME,,,,income-refund,true,5
        """.trimIndent()
        val v11 = v1.replace(",1\n", ",1.1\n").replace(
            "enabled,priority\n",
            "enabled,priority,descriptionMatchMode,isDefault\n",
        ).replace("true,5", "true,5,EXACT,false")
        listOf(v1, v11).forEach { csv ->
            val result = AutoCategoryRuleCsvCodec.decode(csv)
            assertTrue(result is AutoCategoryRuleCsvDecodeResult.Success)
            val success = result as AutoCategoryRuleCsvDecodeResult.Success
            assertEquals(AutoCategoryRuleConditionField.LEGACY_ANY_TEXT, success.value.conditionsByRuleId.getValue("old").single().field)
        }
    }

    @Test
    fun `original rules csv v1 contract remains importable`() {
        val legacy = """
            schema_version,rule_id,rule_name,description_pattern,description_match_mode,direction,min_absolute_amount,max_absolute_amount,category_id,enabled,priority
            1,fictional-rule-001,Fictional rule,fictional merchant,CONTAINS,EXPENSE,,,expense-food,true,10
        """.trimIndent()

        val result = AutoCategoryRuleCsvCodec.decode(legacy)

        assertTrue(result is AutoCategoryRuleCsvDecodeResult.Success)
        val value = (result as AutoCategoryRuleCsvDecodeResult.Success).value
        assertEquals(AutoCategoryRuleOrigin.IMPORTED, value.rules.single().origin)
        assertEquals(
            AutoCategoryRuleConditionField.LEGACY_ANY_TEXT,
            value.conditionsByRuleId.getValue("fictional-rule-001").single().field,
        )
    }

    @Test
    fun `invalid mcc and partial condition fail closed`() {
        val invalidMcc = """
            moneylook-auto-category-rules,2
            id,name,descriptionContains,direction,minAbsoluteAmount,maxAbsoluteAmount,accountId,categoryId,enabled,priority,descriptionMatchMode,isDefault,ruleSetId,extensionId,accountKind,origin,action,conditionGroup,conditionPosition,conditionField,conditionMatchMode,conditionPattern
            rule,Rule,,EXPENSE,,,,expense-food,true,1,CONTAINS,false,,,CREDIT_CARD,PUBLIC_DEFAULT,AUTO_APPLY,INCLUDE_ANY,0,MERCHANT_CATEGORY_CODE,EXACT,not-mcc
        """.trimIndent()
        assertTrue(AutoCategoryRuleCsvCodec.decode(invalidMcc) is AutoCategoryRuleCsvDecodeResult.Failure)
    }

    @Test
    fun `non exact mcc and non finite amount fail closed`() {
        val nonExactMcc = """
            moneylook-auto-category-rules,2
            id,name,descriptionContains,direction,minAbsoluteAmount,maxAbsoluteAmount,accountId,categoryId,enabled,priority,descriptionMatchMode,isDefault,ruleSetId,extensionId,accountKind,origin,action,conditionGroup,conditionPosition,conditionField,conditionMatchMode,conditionPattern
            rule,Rule,,EXPENSE,,,,expense-food,true,1,CONTAINS,false,,,CREDIT_CARD,PUBLIC_DEFAULT,AUTO_APPLY,INCLUDE_ANY,0,MERCHANT_CATEGORY_CODE,CONTAINS,5411
        """.trimIndent()
        val infiniteLegacy = """
            schema_version,rule_id,rule_name,description_pattern,description_match_mode,direction,min_absolute_amount,max_absolute_amount,category_id,enabled,priority
            1,fictional-rule-001,Fictional rule,fictional merchant,CONTAINS,EXPENSE,Infinity,,expense-food,true,10
        """.trimIndent()

        assertTrue(
            AutoCategoryRuleCsvCodec.decode(nonExactMcc) is AutoCategoryRuleCsvDecodeResult.Failure,
        )
        assertTrue(
            AutoCategoryRuleCsvCodec.decode(infiniteLegacy) is AutoCategoryRuleCsvDecodeResult.Failure,
        )
    }

    @Test
    fun `marker v1 rejects empty files and oversized patterns`() {
        val empty = """
            moneylook-auto-category-rules,1
            id,name,descriptionContains,direction,minAbsoluteAmount,maxAbsoluteAmount,accountId,categoryId,enabled,priority
        """.trimIndent()
        val oversizedPattern = "x".repeat(257)
        val oversized = """
            moneylook-auto-category-rules,1.1
            id,name,descriptionContains,direction,minAbsoluteAmount,maxAbsoluteAmount,accountId,categoryId,enabled,priority,descriptionMatchMode,isDefault
            old,Fictional rule,$oversizedPattern,EXPENSE,,,,expense-food,true,5,CONTAINS,false
        """.trimIndent()

        assertTrue(
            AutoCategoryRuleCsvCodec.decode(empty) is AutoCategoryRuleCsvDecodeResult.Failure,
        )
        assertTrue(
            AutoCategoryRuleCsvCodec.decode(oversized) is AutoCategoryRuleCsvDecodeResult.Failure,
        )
    }

    @Test
    fun `v3 rejects orphan records unknown columns and duplicate tags`() {
        val header = """
            moneylook-auto-category-rules,3
            recordType,id,name,descriptionContains,direction,minAbsoluteAmount,maxAbsoluteAmount,accountId,categoryId,enabled,priority,descriptionMatchMode,isDefault,ruleSetId,extensionId,accountKind,origin,action,conditionGroup,conditionPosition,conditionField,conditionMatchMode,conditionPattern,tagId
        """.trimIndent()
        val orphanTag = "$header\nTAG,missing,,,,,,,,,,,,,,,,,,,,,,tag"
        val unknownColumn = header.replace("tagId", "tagId,unexpected") +
            "\nRULE,rule,Rule,,ANY,,,,,true,0,CONTAINS,false,,,," +
            "LEGACY,AUTO_APPLY,,,,,,,payload"
        val duplicateTag = "$header\n" +
            "RULE,rule,Rule,,ANY,,,,,true,0,CONTAINS,false,,,,LEGACY,AUTO_APPLY,,,,,,\n" +
            "TAG,rule,,,,,,,,,,,,,,,,,,,,,,tag\n" +
            "TAG,rule,,,,,,,,,,,,,,,,,,,,,,tag"

        assertTrue(AutoCategoryRuleCsvCodec.decode(orphanTag) is AutoCategoryRuleCsvDecodeResult.Failure)
        assertTrue(AutoCategoryRuleCsvCodec.decode(unknownColumn) is AutoCategoryRuleCsvDecodeResult.Failure)
        assertTrue(AutoCategoryRuleCsvCodec.decode(duplicateTag) is AutoCategoryRuleCsvDecodeResult.Failure)
    }

    @Test
    fun `hostile malformed quoting and excessive rows fail closed`() {
        val quoteInUnquotedCell = """
            moneylook-auto-category-rules,1
            id,name,descriptionContains,direction,minAbsoluteAmount,maxAbsoluteAmount,accountId,categoryId,enabled,priority
            old,bad"name,refund,INCOME,,,,income-refund,true,5
        """.trimIndent()
        val charactersAfterQuote = """
            moneylook-auto-category-rules,1
            id,name,descriptionContains,direction,minAbsoluteAmount,maxAbsoluteAmount,accountId,categoryId,enabled,priority
            old,"name"payload,refund,INCOME,,,,income-refund,true,5
        """.trimIndent()
        val excessiveRows = buildString {
            append("moneylook-auto-category-rules,1\n")
            repeat(10_000) { append("\n") }
        }

        assertTrue(
            AutoCategoryRuleCsvCodec.decode(quoteInUnquotedCell) is
                AutoCategoryRuleCsvDecodeResult.Failure,
        )
        assertTrue(
            AutoCategoryRuleCsvCodec.decode(charactersAfterQuote) is
                AutoCategoryRuleCsvDecodeResult.Failure,
        )
        assertTrue(
            AutoCategoryRuleCsvCodec.decode(excessiveRows) is AutoCategoryRuleCsvDecodeResult.Failure,
        )
    }
}
