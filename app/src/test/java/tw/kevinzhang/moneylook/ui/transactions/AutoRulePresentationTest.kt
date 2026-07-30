package tw.kevinzhang.moneylook.ui.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.AutoCategoryRuleDescriptionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleAction
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionGroup
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.core.data.model.AutoCategoryRuleAmountSign

class AutoRulePresentationTest {
    private val candidate = TransactionRuleCandidate("全聯福利中心", AutoCategoryRuleAmountSign.NEGATIVE, 240.0, "a")

    @Test fun `all configured conditions must match`() {
        val rule = AutoRuleDraft(descriptionContains = "全聯", amountSign = AutoCategoryRuleAmountSign.NEGATIVE, minAbsoluteAmount = "200", maxAbsoluteAmount = "300", accountId = "a")
        assertTrue(rule.matches(candidate))
        assertFalse(rule.copy(maxAbsoluteAmount = "200").matches(candidate))
        assertFalse(rule.copy(amountSign = AutoCategoryRuleAmountSign.POSITIVE).matches(candidate))
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
        val valid = AutoRuleDraft(name = "日常消費", amountSign = AutoCategoryRuleAmountSign.NEGATIVE, categoryId = "food")
        assertTrue(valid.isValidForSave())
        assertFalse(valid.copy(name = "").isValidForSave())
        assertFalse(valid.copy(amountSign = AutoCategoryRuleAmountSign.ANY).isValidForSave())
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
        val rule = defaultExactDescriptionRule(
            description = "  全聯福利中心  ",
            categoryId = "food",
            amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
            accountKind = AssetKind.CREDIT_CARD,
            extensionId = "extension",
        )
        assertEquals(AutoCategoryRuleDescriptionMatchMode.EXACT, rule.descriptionMatchMode)
        assertEquals("全聯福利中心", rule.descriptionContains)
        assertEquals(AutoCategoryRuleAmountSign.NEGATIVE, rule.amountSign)
        assertEquals(AssetKind.CREDIT_CARD, rule.accountKind)
        assertEquals("extension", rule.extensionId)
        assertEquals(AutoCategoryRuleOrigin.USER_CONFIRMED, rule.origin)
        assertEquals(AutoCategoryRuleAction.AUTO_APPLY, rule.action)
        assertTrue(rule.createExactDescriptionCondition)
        assertTrue(rule.applyExisting)
        assertTrue(rule.matches(candidate))
        assertFalse(rule.matches(candidate.copy(description = "全聯福利中心 台北店")))
        assertFalse(rule.matches(candidate.copy(description = "一般扣款", memo = "全聯福利中心")))

        val persisted = rule.normalizedOrNull()!!
        assertEquals("extension", persisted.rule.extensionId)
        assertEquals(AssetKind.CREDIT_CARD, persisted.rule.accountKind)
        assertEquals(AutoCategoryRuleOrigin.USER_CONFIRMED, persisted.rule.origin)
        assertEquals(1, persisted.conditions.size)
        assertEquals(AutoCategoryRuleConditionField.DESCRIPTION, persisted.conditions.single().field)
        assertEquals(AutoCategoryRuleConditionMatchMode.EXACT, persisted.conditions.single().matchMode)
        assertEquals("全聯福利中心", persisted.conditions.single().pattern)

        val edited = rule.copy(
            id = persisted.rule.id,
            conditions = persisted.conditions,
            descriptionContains = "全聯福利中心 台北店",
            descriptionMatchMode = AutoCategoryRuleDescriptionMatchMode.CONTAINS,
        ).normalizedOrNull()!!
        assertEquals(
            AutoCategoryRuleConditionMatchMode.CONTAINS,
            edited.conditions.single().matchMode,
        )
        assertEquals("全聯福利中心 台北店", edited.conditions.single().pattern)
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

    @Test
    fun `migrated legacy condition remains editable and updates its stored pattern`() {
        val migrated = AutoRuleDraft(
            id = "legacy",
            name = "舊規則",
            descriptionContains = "全聯",
            amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
            categoryId = "food",
            updateLegacyAnyTextCondition = true,
            conditions = listOf(
                AutoCategoryRuleCondition(
                    ruleId = "legacy",
                    position = 0,
                    conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    field = AutoCategoryRuleConditionField.LEGACY_ANY_TEXT,
                    matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
                    pattern = "全聯",
                ),
            ),
        )

        assertTrue(migrated.isEditableInLegacyEditor())
        assertTrue(migrated.matches(candidate.copy(description = "一般扣款", memo = "全聯")))

        val edited = migrated.copy(descriptionContains = "市場").normalizedOrNull()!!
        assertEquals("市場", edited.rule.descriptionContains)
        assertEquals(
            AutoCategoryRuleConditionField.LEGACY_ANY_TEXT,
            edited.conditions.single().field,
        )
        assertEquals("市場", edited.conditions.single().pattern)
    }

    @Test fun `category picker allows its matching reporting group plus excluded`() {
        assertEquals(setOf(CategoryReportingGroup.EXPENSE, CategoryReportingGroup.EXCLUDED), allowedKinds(-20.0))
        assertEquals(setOf(CategoryReportingGroup.INCOME, CategoryReportingGroup.EXCLUDED), allowedKinds(20.0))
        assertEquals(setOf(CategoryReportingGroup.EXCLUDED), allowedKinds(0.0))
    }

    @Test fun `uncategorized picker tile uses the agreed tag emoji`() {
        assertEquals("🏷️", UNCATEGORIZED_EMOJI)
    }

    @Test fun `structured public rules show a safe summary and cannot open the legacy editor`() {
        val rule = AutoRuleDraft(
            ruleSetId = "public-v2",
            conditions = listOf(
                AutoCategoryRuleCondition(
                    ruleId = "rule",
                    position = 0,
                    conditionGroup = AutoCategoryRuleConditionGroup.INCLUDE_ANY,
                    field = AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE,
                    matchMode = AutoCategoryRuleConditionMatchMode.EXACT,
                    pattern = "5411",
                ),
            ),
            amountSign = AutoCategoryRuleAmountSign.NEGATIVE,
            accountKind = AssetKind.CREDIT_CARD,
        )

        assertFalse(rule.isEditableInLegacyEditor())
        assertFalse(rule.matches(candidate))
        assertEquals("結構化規則：MCC 1項／負額／信用卡", rule.structuredRuleSummary())
    }

    @Test fun `category groups follow category input order before special buckets`() {
        val categories = listOf(category("food", "餐飲"), category("transport", "交通"))
        val groups = buildAutoRuleGroups(
            rules = listOf(
                rule(id = "transport", categoryId = "transport"),
                rule(id = "food", categoryId = "food"),
            ),
            categories = categories,
            tags = emptyList(),
            groupingMode = AutoRuleGroupingMode.CATEGORY,
            query = "",
            disabledOnly = false,
        )

        assertEquals(listOf("餐飲", "交通"), groups.map(AutoRuleGroupUi::label))
        assertEquals(listOf(AutoRuleGroupKind.CATEGORY, AutoRuleGroupKind.CATEGORY), groups.map(AutoRuleGroupUi::kind))
    }

    @Test fun `category groups keep tag only abstain and missing category buckets`() {
        val categories = listOf(category("food", "餐飲"))
        val groups = buildAutoRuleGroups(
            rules = listOf(
                rule(id = "tag", tagIds = setOf("daily")),
                rule(id = "abstain", categoryId = "food", action = AutoCategoryRuleAction.ABSTAIN),
                rule(id = "missing", categoryId = "removed"),
                rule(id = "no-action"),
            ),
            categories = categories,
            tags = listOf(TagOption("daily", "日常", 0xFF123456)),
            groupingMode = AutoRuleGroupingMode.CATEGORY,
            query = "",
            disabledOnly = false,
        )

        assertEquals(
            listOf("僅套用標籤", "放棄自動分類", "分類已刪除"),
            groups.map(AutoRuleGroupUi::label),
        )
        assertEquals(
            listOf(AutoRuleGroupKind.TAG_ONLY, AutoRuleGroupKind.ABSTAIN, AutoRuleGroupKind.MISSING_CATEGORY),
            groups.map(AutoRuleGroupUi::kind),
        )
        assertEquals(listOf("missing", "no-action"), groups.last().rules.map(AutoRuleDraft::id))
    }

    @Test fun `origin groups use fixed user to legacy order`() {
        val groups = buildAutoRuleGroups(
            rules = listOf(
                rule(id = "legacy", origin = AutoCategoryRuleOrigin.LEGACY),
                rule(id = "default", origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT),
                rule(id = "import", origin = AutoCategoryRuleOrigin.IMPORTED),
                rule(id = "learned", origin = AutoCategoryRuleOrigin.PRIVATE_LEARNED),
                rule(id = "user", origin = AutoCategoryRuleOrigin.USER_CONFIRMED),
            ),
            categories = emptyList(),
            tags = emptyList(),
            groupingMode = AutoRuleGroupingMode.ORIGIN,
            query = "",
            disabledOnly = false,
        )

        assertEquals(listOf("我建立的", "個人學習", "匯入規則", "內建規則", "舊版規則"), groups.map(AutoRuleGroupUi::label))
    }

    @Test fun `search matches readable rule fields with unicode normalization`() {
        val searchable = rule(
            id = "searchable",
            name = "Coffee Shop",
            descriptionContains = "咖啡",
            categoryId = "food",
            tagIds = setOf("daily"),
            accountId = "account",
            accountKind = AssetKind.CREDIT_CARD,
            origin = AutoCategoryRuleOrigin.IMPORTED,
            conditions = listOf(condition("searchable", "5411")),
        )
        val categories = listOf(category("food", "餐飲"))
        val tags = listOf(TagOption("daily", "日常", 0xFF123456))

        listOf("ＣＯＦＦＥＥ", "咖啡", "5411", "餐飲", "日常", "匯入規則", "指定帳戶", "信用卡").forEach { query ->
            val matched = buildAutoRuleGroups(
                rules = listOf(searchable),
                categories = categories,
                tags = tags,
                groupingMode = AutoRuleGroupingMode.CATEGORY,
                query = query,
                disabledOnly = false,
            )
            assertEquals("query=$query", listOf("searchable"), matched.single().rules.map(AutoRuleDraft::id))
        }
    }

    @Test fun `disabled filter only keeps disabled rules`() {
        val groups = buildAutoRuleGroups(
            rules = listOf(rule(id = "enabled", categoryId = "food"), rule(id = "disabled", categoryId = "food", enabled = false)),
            categories = listOf(category("food", "餐飲")),
            tags = emptyList(),
            groupingMode = AutoRuleGroupingMode.CATEGORY,
            query = "",
            disabledOnly = true,
        )

        assertEquals(listOf("disabled"), groups.single().rules.map(AutoRuleDraft::id))
    }

    @Test fun `rules inside each group use the existing stable draft comparator`() {
        val groups = buildAutoRuleGroups(
            rules = listOf(
                rule(id = "late", categoryId = "food", priority = 9),
                rule(id = "default", categoryId = "food", priority = 0, isDefault = true),
                rule(id = "first", categoryId = "food", priority = 2),
            ),
            categories = listOf(category("food", "餐飲")),
            tags = emptyList(),
            groupingMode = AutoRuleGroupingMode.CATEGORY,
            query = "",
            disabledOnly = false,
        )

        assertEquals(listOf("first", "late", "default"), groups.single().rules.map(AutoRuleDraft::id))
    }

    @Test fun `duplicate creates an enabled user owned draft and normalizes condition ownership`() {
        val original = rule(
            id = "default-rule",
            name = "超商",
            categoryId = "food",
            tagIds = setOf("daily"),
            enabled = false,
            priority = 3,
            isDefault = true,
            ruleSetId = "public-v2",
            extensionId = "bank",
            accountKind = AssetKind.CREDIT_CARD,
            origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            conditions = listOf(condition("default-rule", "便利商店")),
        )

        val copy = original.duplicateAsUserRule(priority = 42)
        assertEquals("", copy.id)
        assertEquals("超商 副本", copy.name)
        assertTrue(copy.enabled)
        assertFalse(copy.isDefault)
        assertEquals(null, copy.ruleSetId)
        assertEquals(AutoCategoryRuleOrigin.USER_CONFIRMED, copy.origin)
        assertEquals(42, copy.priority)
        assertEquals(original.categoryId, copy.categoryId)
        assertEquals(original.tagIds, copy.tagIds)
        assertEquals(original.extensionId, copy.extensionId)
        assertEquals(original.accountKind, copy.accountKind)

        val normalized = copy.normalizedOrNull()!!
        assertTrue(normalized.rule.id.isNotBlank())
        assertEquals(normalized.rule.id, normalized.conditions.single().ruleId)
    }

    @Test fun `priority copy is labelled only as tie break priority`() {
        val summary = rule(id = "priority", priority = 6).readableSourceSummary()

        assertTrue(summary.contains("同分時優先：6"))
        assertFalse(summary.contains("執行優先"))
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

    private fun category(id: String, name: String) = CategoryOption(id, name, 0xFF123456)

    private fun rule(
        id: String,
        name: String = id,
        descriptionContains: String = "",
        categoryId: String? = null,
        tagIds: Set<String> = emptySet(),
        enabled: Boolean = true,
        priority: Int = 0,
        isDefault: Boolean = false,
        ruleSetId: String? = null,
        extensionId: String? = null,
        accountId: String? = null,
        accountKind: AssetKind? = null,
        origin: AutoCategoryRuleOrigin = AutoCategoryRuleOrigin.USER_CONFIRMED,
        action: AutoCategoryRuleAction = AutoCategoryRuleAction.AUTO_APPLY,
        conditions: List<AutoCategoryRuleCondition> = emptyList(),
    ) = AutoRuleDraft(
        id = id,
        name = name,
        descriptionContains = descriptionContains,
        categoryId = categoryId,
        tagIds = tagIds,
        enabled = enabled,
        priority = priority,
        isDefault = isDefault,
        ruleSetId = ruleSetId,
        extensionId = extensionId,
        accountId = accountId,
        accountKind = accountKind,
        origin = origin,
        action = action,
        conditions = conditions,
    )

    private fun condition(ruleId: String, pattern: String) = AutoCategoryRuleCondition(
        ruleId = ruleId,
        position = 0,
        field = AutoCategoryRuleConditionField.MERCHANT_CATEGORY_CODE,
        matchMode = AutoCategoryRuleConditionMatchMode.EXACT,
        pattern = pattern,
    )
}
