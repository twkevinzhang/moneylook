package tw.kevinzhang.moneylook.ui.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoRulePresentationTest {
    private val candidate = TransactionRuleCandidate("全聯福利中心", TransactionDirection.EXPENSE, 240.0, "a")

    @Test fun `all configured conditions must match`() {
        val rule = AutoRuleDraft(descriptionContains = "全聯", direction = TransactionDirection.EXPENSE, minAbsoluteAmount = "200", maxAbsoluteAmount = "300", accountId = "a")
        assertTrue(rule.matches(candidate))
        assertFalse(rule.copy(maxAbsoluteAmount = "200").matches(candidate))
        assertFalse(rule.copy(direction = TransactionDirection.INCOME).matches(candidate))
    }

    @Test fun `enabled matches are ordered by ascending priority`() {
        val rules = listOf(
            AutoRuleDraft(id = "late", priority = 9),
            AutoRuleDraft(id = "disabled", priority = 1, enabled = false),
            AutoRuleDraft(id = "first", priority = 2),
        )
        assertEquals(listOf("first", "late"), orderedMatchingRules(rules, candidate).map { it.id })
    }

    @Test fun `saved rule requires a name condition action and valid amount range`() {
        val valid = AutoRuleDraft(name = "日常消費", direction = TransactionDirection.EXPENSE, categoryId = "food")
        assertTrue(valid.isValidForSave())
        assertFalse(valid.copy(name = "").isValidForSave())
        assertFalse(valid.copy(direction = null).isValidForSave())
        assertFalse(valid.copy(categoryId = null).isValidForSave())
        assertFalse(valid.copy(minAbsoluteAmount = "200", maxAbsoluteAmount = "100").isValidForSave())
        assertFalse(valid.copy(minAbsoluteAmount = "NaN").isValidForSave())
    }
}
