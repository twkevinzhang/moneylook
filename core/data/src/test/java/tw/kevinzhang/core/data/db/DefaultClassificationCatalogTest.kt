package tw.kevinzhang.core.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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

        assertEquals(16, DefaultClassificationCatalog.publicAutoCategoryRules.size)
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
}
