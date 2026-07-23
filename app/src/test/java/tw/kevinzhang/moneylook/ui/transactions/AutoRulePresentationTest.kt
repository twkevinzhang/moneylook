package tw.kevinzhang.moneylook.ui.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.CategoryKind

class AutoRulePresentationTest {
    private val candidate = TransactionRuleCandidate("全聯福利中心", TransactionDirection.EXPENSE, 240.0, "a")

    @Test fun `all configured conditions must match`() {
        val rule = AutoRuleDraft(descriptionContains = "全聯", direction = TransactionDirection.EXPENSE, minAbsoluteAmount = "200", maxAbsoluteAmount = "300", accountId = "a")
        assertTrue(rule.matches(candidate))
        assertFalse(rule.copy(maxAbsoluteAmount = "200").matches(candidate))
        assertFalse(rule.copy(direction = TransactionDirection.INCOME).matches(candidate))
    }

    @Test fun `enabled user rules run before defaults then by ascending priority`() {
        val rules = listOf(
            AutoRuleDraft(id = "late", priority = 9),
            AutoRuleDraft(id = "disabled", priority = 1, enabled = false),
            AutoRuleDraft(id = "first", priority = 2),
            AutoRuleDraft(id = "default", priority = 0, isDefault = true),
        )
        assertEquals(listOf("first", "late", "default"), orderedMatchingRules(rules, candidate).map { it.id })
    }

    @Test fun `new user priority ignores bundled defaults`() {
        val rules = listOf(
            AutoRuleDraft(id = "user", priority = 4),
            AutoRuleDraft(id = "default", priority = 190, isDefault = true),
        )
        assertEquals(5, nextUserRulePriority(rules))
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

    @Test fun `detail draft only becomes dirty after an explicit editor change`() {
        val state = detailState()
        val initial = TransactionDetailDraft("food", setOf("tag"), "原本備註")
        assertFalse(initial.isDirtyComparedWith(state))
        assertTrue(initial.copy(note = "尚未儲存").isDirtyComparedWith(state))
        assertTrue(initial.copy(newTagNames = listOf("草稿標籤")).isDirtyComparedWith(state))
        assertTrue(initial.copy(resumeAutomatic = true).isDirtyComparedWith(state))
    }

    @Test fun `same detail scope starts an exact description rule`() {
        val rule = defaultExactDescriptionRule("  全聯福利中心  ", "food")
        assertEquals(AutoCategoryRuleDescriptionMatchMode.EXACT, rule.descriptionMatchMode)
        assertEquals("全聯福利中心", rule.descriptionContains)
        assertTrue(rule.applyExisting)
        assertTrue(rule.matches(candidate))
        assertFalse(rule.matches(candidate.copy(description = "全聯福利中心 台北店")))
    }

    @Test fun `preview normalizes all transaction text fields without joining them`() {
        val contains = AutoRuleDraft(descriptionContains = "ＣＯＦＦＥＥ－ＳＨＯＰ")
        val exact = AutoRuleDraft(
            descriptionContains = "薪資入帳",
            descriptionMatchMode = AutoCategoryRuleDescriptionMatchMode.EXACT,
        )

        assertTrue(contains.matches(candidate.copy(description = "一般扣款", memo = "Coffee shop 台北店")))
        assertTrue(exact.matches(candidate.copy(description = "一般扣款", type = "薪資－入帳")))
        assertFalse(contains.matches(candidate.copy(description = "Coffee", memo = "shop")))
        assertFalse(exact.matches(candidate.copy(description = "薪資", memo = "入帳")))
    }

    @Test fun `only matching income or expense kind is enabled while transfer always remains available`() {
        assertEquals(setOf(CategoryKind.EXPENSE, CategoryKind.TRANSFER), allowedKinds(-20.0))
        assertEquals(setOf(CategoryKind.INCOME, CategoryKind.TRANSFER), allowedKinds(20.0))
        assertEquals(setOf(CategoryKind.TRANSFER), allowedKinds(0.0))
    }

    @Test fun `uncategorized picker tile uses the agreed tag emoji`() {
        assertEquals("🏷️", UNCATEGORIZED_EMOJI)
    }

    private fun detailState() = TransactionDetailUiState(
        title = "餐飲",
        amountText = "- 100",
        amount = -100.0,
        accountName = "帳戶",
        transactionDate = "2026-07-22",
        postingDate = null,
        description = "全聯福利中心",
        bankMemo = null,
        selectedCategoryId = "food",
        selectedTagIds = setOf("tag"),
        userNote = "原本備註",
        categories = emptyList(),
        tags = emptyList(),
    )
}
