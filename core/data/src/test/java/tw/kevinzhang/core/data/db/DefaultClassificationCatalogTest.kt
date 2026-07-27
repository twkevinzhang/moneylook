package tw.kevinzhang.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin

class DefaultClassificationCatalogTest {
    @Test
    fun `public rules contain generic fragments only`() {
        val privacyLocationOrBankPrefix = Regex(
            "台北|新北|桃園|台中|台南|高雄|基隆|新竹|嘉義|彰化|中壢|內湖|信義|板橋|新店|" +
                "扣款|簽帳|轉帳|帳號|卡號|訂單|序號|分店|門市",
            RegexOption.IGNORE_CASE,
        )
        val digits = Regex("\\d")
        val categoryIds = DefaultClassificationCatalog.categories.mapTo(mutableSetOf()) { it.id }

        assertEquals(9, DefaultClassificationCatalog.publicAutoCategoryRules.size)
        assertEquals(
            DefaultClassificationCatalog.publicAutoCategoryRules.size,
            DefaultClassificationCatalog.publicAutoCategoryRules.map { it.id }.toSet().size,
        )
        DefaultClassificationCatalog.publicAutoCategoryRules.forEach { rule ->
            val description = requireNotNull(rule.descriptionContains)
            assertTrue(rule.isDefault)
            assertEquals(AutoCategoryRuleDescriptionMatchMode.CONTAINS, rule.descriptionMatchMode)
            assertFalse("rule ${rule.id} must not contain digits", digits.containsMatchIn(description))
            assertFalse(
                "rule ${rule.id} must not contain private context, a location, or a raw bank prefix",
                privacyLocationOrBankPrefix.containsMatchIn("${rule.name} $description"),
            )
            assertFalse("rule ${rule.id} must not contain an email", '@' in description)
            assertTrue("rule ${rule.id} must reference a bundled category", rule.categoryId in categoryIds)
        }
    }

    @Test
    fun `public v2 rules contain only generic structured facts`() {
        val privateContext = Regex(
            "台北|新北|桃園|台中|台南|高雄|基隆|新竹|嘉義|彰化|中壢|內湖|信義|板橋|新店|" +
                "帳號|卡號|訂單|序號|分店|門市",
            RegexOption.IGNORE_CASE,
        )
        val categoryIds = DefaultClassificationCatalog.categories.mapTo(mutableSetOf()) { it.id }
        val collections = listOf(
            DefaultClassificationCatalog.publicMccRules,
            DefaultClassificationCatalog.publicStructuralRules,
        )

        collections.flatten().forEach { publicRule ->
            assertTrue(publicRule.rule.isDefault)
            assertEquals(AutoCategoryRuleOrigin.PUBLIC_DEFAULT, publicRule.rule.origin)
            assertTrue(publicRule.rule.categoryId in categoryIds)
            assertTrue(publicRule.conditions.isNotEmpty())
            publicRule.conditions.forEach { condition ->
                assertFalse('@' in condition.pattern)
                assertFalse(
                    "rule ${publicRule.rule.id} must not contain private context or a location",
                    privateContext.containsMatchIn(condition.pattern),
                )
                if (condition.field == AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE) {
                    assertTrue(condition.pattern.matches(Regex("\\d{4}")))
                } else {
                    assertFalse(condition.pattern.any(Char::isDigit))
                }
            }
        }
    }

    @Test
    fun `public generic v5 rules exclude private scopes and search structured merchant facts`() {
        val privateContext = Regex(
            "台北|新北|桃園|台中|台南|高雄|基隆|新竹|嘉義|彰化|中壢|內湖|信義|板橋|新店|" +
                "分行|分店|門市|卡號|訂單|序號|先生|小姐",
            RegexOption.IGNORE_CASE,
        )
        val sensitiveNumber = Regex(
            "(?:19|20)\\d{2}[-/.年]\\d{1,2}[-/.月]?(?:\\d{1,2}日?)?|" +
                "(?:NT\\$|TWD|USD|新台幣)\\s*\\d+",
            RegexOption.IGNORE_CASE,
        )
        val categoryIds = DefaultClassificationCatalog.categories.mapTo(mutableSetOf()) { it.id }

        assertEquals(72, DefaultClassificationCatalog.publicGenericRules.size)
        assertEquals(
            DefaultClassificationCatalog.publicGenericRules.size,
            DefaultClassificationCatalog.publicGenericRules.map { it.rule.id }.toSet().size,
        )
        val allPublicRuleIds = buildList {
            addAll(DefaultClassificationCatalog.publicAutoCategoryRules.map { it.id })
            addAll(DefaultClassificationCatalog.publicMccRules.map { it.rule.id })
            addAll(DefaultClassificationCatalog.publicStructuralRules.map { it.rule.id })
            addAll(DefaultClassificationCatalog.publicGenericRules.map { it.rule.id })
        }
        assertEquals(allPublicRuleIds.size, allPublicRuleIds.toSet().size)
        DefaultClassificationCatalog.publicGenericRules.forEach { publicRule ->
            val rule = publicRule.rule
            assertTrue(rule.isDefault)
            assertTrue(rule.enabled)
            assertEquals(AutoCategoryRuleOrigin.PUBLIC_DEFAULT, rule.origin)
            assertEquals(AutoCategoryRuleAction.AUTO_APPLY, rule.action)
            assertTrue(rule.categoryId in categoryIds)
            assertEquals(DefaultClassificationCatalog.PUBLIC_GENERIC_RULE_SET_ID, rule.ruleSetId)
            assertEquals(null, rule.accountId)
            assertEquals(null, rule.extensionId)
            assertEquals(null, rule.minAbsoluteAmount)
            assertEquals(null, rule.maxAbsoluteAmount)
            assertEquals(null, rule.descriptionContains)
            if (rule.id == "public-v4-income-credit-card-refund-fallback") {
                assertTrue(publicRule.conditions.isEmpty())
                assertEquals(
                    tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign.POSITIVE,
                    rule.amountSign,
                )
                assertEquals(tw.kevinzhang.core.data.model.AssetKind.CREDIT_CARD, rule.accountKind)
            } else {
                assertTrue(publicRule.conditions.isNotEmpty())
            }
            publicRule.conditions.forEach { condition ->
                assertTrue(
                    "rule ${rule.id} must only inspect public transaction text",
                    condition.field in setOf(
                        AutoCategoryRuleConditionField.DESCRIPTION,
                        AutoCategoryRuleConditionField.MEMO,
                        AutoCategoryRuleConditionField.TYPE,
                        AutoCategoryRuleConditionField.SEARCHABLE_TEXT,
                    ),
                )
                assertFalse(privateContext.containsMatchIn(condition.pattern))
                assertFalse(sensitiveNumber.containsMatchIn(condition.pattern))
                assertFalse(condition.pattern.any(Char::isDigit))
                assertFalse('@' in condition.pattern)
            }
        }
        assertTrue(
            DefaultClassificationCatalog.publicGenericRules
                .filter { it.rule.priority >= 29 }
                .all { publicRule ->
                    publicRule.conditions.all {
                        it.field == AutoCategoryRuleConditionField.SEARCHABLE_TEXT
                    }
                },
        )
    }

    @Test
    fun `v24 catalog uses precise reporting groups and removes superseded defaults`() {
        assertEquals(
            setOf("income-interest", "income-cashback"),
            DefaultClassificationCatalog.categories
                .filter { it.id in setOf("income-interest", "income-cashback") }
                .mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(
            tw.kevinzhang.core.data.model.CategoryReportingGroup.EXCLUDED,
            DefaultClassificationCatalog.categories.single { it.id == "transfer-account" }.reportingGroup,
        )
        val allIds = DefaultClassificationCatalog.publicAutoCategoryRules.map { it.id }.toSet() +
            DefaultClassificationCatalog.publicStructuralRules.map { it.rule.id } +
            DefaultClassificationCatalog.publicGenericRules.map { it.rule.id }
        assertTrue(
            setOf(
                "public-rule-003", "public-rule-006", "public-rule-010", "public-rule-011",
                "public-rule-015", "public-rule-017", "public-rule-019",
                "public-structural-auto-deposit-cash-withdrawal-v2",
            ).intersect(allIds).isEmpty(),
        )
        val easycard = DefaultClassificationCatalog.publicStructuralRules.single {
            it.rule.id == "public-structural-auto-easycard-topup-v3"
        }.rule
        assertEquals(tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign.ANY, easycard.amountSign)
        assertEquals(null, easycard.accountKind)
    }

    @Test
    fun `v25 aggressive catalog keeps every new category in exactly one reporting group`() {
        val digitalWallet = DefaultClassificationCatalog.categories.single {
            it.id == "expense-digital-wallet"
        }
        val subsidy = DefaultClassificationCatalog.categories.single { it.id == "income-subsidy" }
        assertEquals(
            tw.kevinzhang.core.data.model.CategoryReportingGroup.EXPENSE,
            digitalWallet.reportingGroup,
        )
        assertEquals(
            tw.kevinzhang.core.data.model.CategoryReportingGroup.INCOME,
            subsidy.reportingGroup,
        )

        val v4Rules = DefaultClassificationCatalog.publicGenericRules.filter {
            it.rule.id.startsWith("public-v4-")
        }
        assertEquals(17, v4Rules.size)
        assertTrue(v4Rules.all { it.rule.categoryId != null })
        assertTrue(
            v4Rules.mapNotNull { it.rule.categoryId }.all {
                categoryId -> DefaultClassificationCatalog.categories.any { it.id == categoryId }
            },
        )

        val wallet = v4Rules.single { it.rule.id == "public-v4-expense-digital-wallet" }
        assertTrue(wallet.conditions.any {
            it.conditionGroup ==
                tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup.EXCLUDE_ANY
        })
    }
}
