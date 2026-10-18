package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tw.kevinzhang.moneylook.sync.ClassificationResetStage
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.AiClassificationPreview

@RunWith(AndroidJUnit4::class)
class AutoRuleInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryActionWithZeroRulesAndCancelDoesNotInvokeCallback() {
        var startCount = 0
        var applyState by mutableStateOf<ApplyAllRulesUiState>(ApplyAllRulesUiState.Idle)
        setAutoRuleContent(
            rules = emptyList(),
            applyState = { applyState },
            onShowApply = { applyState = ApplyAllRulesUiState.Confirming },
            onCancelApply = { applyState = ApplyAllRulesUiState.Idle },
            onStartApply = { startCount += 1 },
        )

        composeRule.onNodeWithContentDescription("套用自動分類規則到所有交易明細").assertDoesNotExist()
        composeRule.onNodeWithTag("auto-classification-primary-action").performClick()
        composeRule.onNodeWithText("開始自動分類？").assertExists()
        composeRule.onNodeWithText(
            "只補分類尚未分類的交易；保留手動設定的分類、標籤與備註。",
        ).assertExists()
        composeRule.onNodeWithText("取消").performClick()

        composeRule.runOnIdle { assertEquals(0, startCount) }
    }

    @Test
    fun applyAllConfirmationStartsPersistentDialogAndOnlyInvokesOnce() {
        var startCount = 0
        var applyState by mutableStateOf<ApplyAllRulesUiState>(ApplyAllRulesUiState.Idle)
        setAutoRuleContent(
            rules = emptyList(),
            applyState = { applyState },
            onShowApply = { applyState = ApplyAllRulesUiState.Confirming },
            onStartApply = {
                startCount += 1
                applyState = ApplyAllRulesUiState.Preparing
            },
        )

        composeRule.onNodeWithTag("auto-classification-primary-action").performClick()
        composeRule.onNodeWithText("開始分類").performClick()

        composeRule.runOnIdle { assertEquals(1, startCount) }
        composeRule.onNodeWithText("正在準備交易資料").assertExists()
        composeRule.onNodeWithContentDescription("正在準備交易資料").assertExists()
        composeRule.onNodeWithText("開始分類").assertDoesNotExist()
        composeRule.onNodeWithText("取消").assertDoesNotExist()
        composeRule.onNodeWithText("關閉").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("更多選項").assertIsNotEnabled()
    }

    @Test
    fun classificationActionIsEnabledWithoutEnabledRule() {
        setAutoRuleContent(rules = listOf(enabledRule().copy(enabled = false)))

        composeRule.onNodeWithTag("auto-classification-primary-action").assertIsEnabled()
    }

    @Test fun emptyAndMissingSettingsUseDistinctActionsAndRefresh() {
        var navigations = 0
        var previews = 0
        var overview by mutableStateOf(readyOverview().copy(action = AutoClassificationAction.OPEN_SETTINGS,
            configuration = AiConfigurationSummary.Missing))
        setAutoRuleContent(overview = { overview }, onOpenSettings = { navigations++ }, onShowApply = { previews++ })
        composeRule.onNodeWithText("前往 AI分類設定").performClick()
        composeRule.runOnIdle { assertEquals(1, navigations); assertEquals(0, previews) }
        composeRule.runOnIdle { overview = readyOverview().copy(action = AutoClassificationAction.EMPTY,
            preview = AiClassificationPreview(0, false, true)) }
        composeRule.onNodeWithTag("auto-classification-primary-action").assertIsNotEnabled()
        composeRule.onNodeWithText("目前沒有待分類交易").assertExists()
        composeRule.runOnIdle { overview = readyOverview() }
        composeRule.onNodeWithText("開始自動分類").performClick()
        composeRule.runOnIdle { assertEquals(1, previews) }
    }

    @Test fun resumeRequiresConfirmationAndBusyBlocksDuplicateActions() {
        var previews = 0
        var starts = 0
        var state by mutableStateOf<ApplyAllRulesUiState>(ApplyAllRulesUiState.Idle)
        setAutoRuleContent(overview = { readyOverview().copy(action = AutoClassificationAction.RESUME, resuming = true) },
            applyState = { state }, onShowApply = { previews++; state = ApplyAllRulesUiState.Confirming },
            onStartApply = { starts++; state = ApplyAllRulesUiState.Preparing })
        composeRule.onNodeWithTag("auto-classification-primary-action").performClick()
        composeRule.onNodeWithText("繼續分類？").assertExists()
        composeRule.runOnIdle { assertEquals(1, previews); assertEquals(0, starts) }
        composeRule.onNodeWithText("繼續分類").performClick()
        composeRule.runOnIdle { assertEquals(1, starts) }
        composeRule.onNodeWithContentDescription("更多選項").assertIsNotEnabled()
    }

    @Test fun configurationCardShowsSafeSummaryAndNavigates() {
        var navigations = 0
        setAutoRuleContent(onOpenSettings = { navigations++ })
        composeRule.onNodeWithText("自訂服務 · gpt-6-luna").assertExists()
        composeRule.onNodeWithTag("auto-classification-ai-settings").performClick()
        composeRule.runOnIdle { assertEquals(1, navigations) }
    }

    @Test fun smallScreenLargeTextKeepsActionReachable() {
        setAutoRuleContent(fontScale = 2f, widthDp = 360)
        composeRule.onNodeWithTag("auto-classification-primary-action").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("沒有自訂規則，也能使用 AI 分類。").performScrollTo().assertIsDisplayed()
    }

    @Test fun wideLayoutKeepsRuleDetailsAndPrimaryAction() {
        setAutoRuleContent(rules = listOf(enabledRule()), widthDp = 900, fontScale = 1.5f)
        composeRule.onNodeWithTag("auto-classification-primary-action").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("虛構規則").performScrollTo().performClick()
        composeRule.onNodeWithText("如果").assertExists()
    }

    @Test
    fun applyAllDialogRendersZeroAndNonzeroProgress() {
        var applyState by mutableStateOf<ApplyAllRulesUiState>(
            ApplyAllRulesUiState.Applying(processedTransferCount = 0, totalTransferCount = 0),
        )
        setAutoRuleContent(
            rules = emptyList(),
            applyState = { applyState },
        )

        composeRule.onNodeWithText("正在自動分類").assertExists()
        composeRule.onNodeWithText("已處理 0 / 0 筆交易").assertExists()
        composeRule.onNodeWithContentDescription("自動分類進度").assertExists()

        composeRule.runOnIdle {
            applyState = ApplyAllRulesUiState.Applying(processedTransferCount = 25, totalTransferCount = 100)
        }
        composeRule.onNodeWithText("已處理 25 / 100 筆交易").assertExists()
    }

    @Test
    fun applyAllTerminalDialogsSupportFinishRetryAndClose() {
        var finishCount = 0
        var retryCount = 0
        var applyState by mutableStateOf<ApplyAllRulesUiState>(
            ApplyAllRulesUiState.Success(
                processedTransferCount = 100,
                matchedTransferCount = 73,
                preservedManualOverrideCount = 12,
            ),
        )
        setAutoRuleContent(
            rules = emptyList(),
            applyState = { applyState },
            onRetryApply = {
                retryCount += 1
                applyState = ApplyAllRulesUiState.Preparing
            },
            onDismissApply = {
                finishCount += 1
                applyState = ApplyAllRulesUiState.Idle
            },
        )

        composeRule.onNodeWithText("已處理 100 筆交易").assertExists()
        composeRule.onNodeWithText("已分類 73 筆交易").assertExists()
        composeRule.onNodeWithText("保留 12 筆手動調整").assertExists()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.runOnIdle { assertEquals(1, finishCount) }

        composeRule.runOnIdle {
            applyState = ApplyAllRulesUiState.Error(
                message = "套用規則失敗，請稍後再試。",
                lastStage = ApplyAllRulesStage.APPLYING,
                processedTransferCount = 25,
                totalTransferCount = 100,
            )
        }
        composeRule.onNodeWithText("失敗階段：正在分類（已處理 25 / 100 筆交易）").assertExists()
        composeRule.onNodeWithText("繼續分類").performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }

        composeRule.runOnIdle {
            applyState = ApplyAllRulesUiState.Error("套用規則失敗，請稍後再試。")
        }
        composeRule.onNodeWithText("失敗階段：正在準備交易資料").assertExists()
        composeRule.onNodeWithText("關閉").performClick()
        composeRule.runOnIdle { assertEquals(2, finishCount) }
    }

    @Test
    fun resetActionUsesOverflowMenuAndCancelDoesNotInvokeCallback() {
        var resetCount = 0
        var resetState by mutableStateOf<ClassificationResetUiState>(ClassificationResetUiState.Idle)
        setAutoRuleContent(
            resetState = { resetState },
            onShowReset = { resetState = ClassificationResetUiState.Confirming },
            onCancelReset = { resetState = ClassificationResetUiState.Idle },
            onStartReset = { resetCount += 1 },
        )

        composeRule.onNodeWithContentDescription("更多選項").performClick()
        composeRule.onNodeWithText("重設分類資料").performClick()
        composeRule.onNodeWithText("重設分類資料？").assertExists()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle { assertEquals(0, resetCount) }
    }

    @Test
    fun resetConfirmationStartsPersistentRunningDialogAndOnlyInvokesOnce() {
        var resetCount = 0
        var resetState by mutableStateOf<ClassificationResetUiState>(ClassificationResetUiState.Idle)
        setAutoRuleContent(
            resetState = { resetState },
            onShowReset = { resetState = ClassificationResetUiState.Confirming },
            onStartReset = {
                resetCount += 1
                resetState = ClassificationResetUiState.ResettingCatalog
            },
        )

        composeRule.onNodeWithContentDescription("更多選項").performClick()
        composeRule.onNodeWithText("重設分類資料").performClick()
        composeRule.onNodeWithText("重設並重新分類").performClick()
        composeRule.runOnIdle { assertEquals(1, resetCount) }
        composeRule.onNodeWithText("正在清除分類資料").assertExists()
        composeRule.onNodeWithText("取消").assertDoesNotExist()
        composeRule.onNodeWithText("關閉").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("更多選項").assertIsNotEnabled()
    }

    @Test
    fun resetReclassificationDialogRendersZeroAndNonzeroProgress() {
        var resetState by mutableStateOf<ClassificationResetUiState>(
            ClassificationResetUiState.Reclassifying(processedTransferCount = 0, totalTransferCount = 0),
        )
        setAutoRuleContent(resetState = { resetState })

        composeRule.onNodeWithText("正在重新分類交易").assertExists()
        composeRule.onNodeWithText("已處理 0 / 0 筆交易").assertExists()
        composeRule.onNodeWithContentDescription("重新分類進度").assertExists()
        composeRule.runOnIdle {
            resetState = ClassificationResetUiState.Reclassifying(processedTransferCount = 25, totalTransferCount = 100)
        }
        composeRule.onNodeWithText("已處理 25 / 100 筆交易").assertExists()
    }

    @Test
    fun resetTerminalDialogsSupportFinishRetryAndClose() {
        var finishCount = 0
        var retryCount = 0
        var resetState by mutableStateOf<ClassificationResetUiState>(
            ClassificationResetUiState.Success(processedTransferCount = 12, matchedTransferCount = 9),
        )
        setAutoRuleContent(
            resetState = { resetState },
            onRetryReset = {
                retryCount += 1
                resetState = ClassificationResetUiState.ResettingCatalog
            },
            onDismissReset = {
                finishCount += 1
                resetState = ClassificationResetUiState.Idle
            },
        )

        composeRule.onNodeWithText("已處理 12 筆交易，已分類 9 筆交易。").assertExists()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.runOnIdle { assertEquals(1, finishCount) }

        composeRule.runOnIdle {
            resetState = ClassificationResetUiState.Error(
                message = "重設分類系統失敗，請稍後再試。",
                lastStage = ClassificationResetStage.RECLASSIFYING_TRANSACTIONS,
                processedTransferCount = 25,
                totalTransferCount = 100,
            )
        }
        composeRule.onNodeWithText("失敗階段：正在重新分類交易（已處理 25 / 100 筆交易）").assertExists()
        composeRule.onNodeWithText("重試").performClick()
        composeRule.runOnIdle { assertEquals(1, retryCount) }

        composeRule.runOnIdle {
            resetState = ClassificationResetUiState.Error("重設分類系統失敗，請稍後再試。")
        }
        composeRule.onNodeWithText("關閉").performClick()
        composeRule.runOnIdle { assertEquals(2, finishCount) }
    }

    @Test
    fun categoryViewInitiallyExpandsOnlyFirstGroupAndCanToggleAnotherFromItsTitleBar() {
        setAutoRuleContent(
            rules = listOf(rule("food-rule", "午餐", "food"), rule("transfer-rule", "帳戶移轉", "transfer")),
            categories = testCategories(),
        )

        composeRule.onNodeWithText("午餐").assertExists()
        composeRule.onNodeWithText("帳戶移轉").assertDoesNotExist()
        composeRule.onNodeWithText("轉帳").performClick()
        composeRule.onNodeWithText("午餐").assertExists()
        composeRule.onNodeWithText("帳戶移轉").assertExists()
        composeRule.onNodeWithText("轉帳").performClick()
        composeRule.onNodeWithText("帳戶移轉").assertDoesNotExist()

        composeRule.onNodeWithText("依來源").performClick()
        composeRule.onNodeWithText("我建立的").assertExists()
        composeRule.onNodeWithText("午餐").assertExists()
    }

    @Test
    fun lastCategoryCanScrollAbovePrimaryActionAndExpandFromItsTitleBar() {
        val categories = (1..9).map { index ->
            CategoryOption(id = "category-$index", name = "分類 $index", color = 0xFF607D8B)
        } + CategoryOption(id = "food", name = "餐飲", color = 0xFFF57C00)
        val rules = categories.map { category ->
            rule("rule-${category.id}", "${category.name} 規則", category.id)
        }
        setAutoRuleContent(rules = rules, categories = categories)

        composeRule.onNodeWithTag("auto-rule-groups-list").performScrollToIndex(categories.size + 1)
        val restaurantGroup = composeRule.onNodeWithContentDescription("餐飲 展開")
        val fab = composeRule.onNodeWithTag("auto-classification-primary-action")
        val restaurantBottom = restaurantGroup.fetchSemanticsNode().boundsInRoot.bottom
        val fabTop = fab.fetchSemanticsNode().boundsInRoot.top

        assertTrue(
            "最後一個分類標題列應可捲動到主動作上方",
            restaurantBottom <= fabTop,
        )
        composeRule.onNodeWithText("餐飲").performClick()
        composeRule.onNodeWithText("餐飲 規則").assertExists()
    }

    @Test
    fun searchOriginAndDisabledFiltersUpdateRuleLibrary() {
        setAutoRuleContent(
            rules = listOf(
                rule("food-rule", "午餐", "food", origin = AutoCategoryRuleOrigin.USER_CONFIRMED),
                rule("imported-rule", "匯入早餐", "food", origin = AutoCategoryRuleOrigin.IMPORTED, enabled = false),
            ),
            categories = testCategories(),
        )

        composeRule.onNodeWithContentDescription("搜尋規則").performTextInput("早餐")
        composeRule.onNodeWithText("匯入早餐").assertExists()
        composeRule.onNodeWithText("午餐").assertDoesNotExist()
        composeRule.onNodeWithText("依來源").performClick()
        composeRule.onNodeWithText("匯入規則").assertExists()
        composeRule.onNodeWithText("停用").performClick()
        composeRule.onNodeWithText("匯入早餐").assertExists()
    }

    @Test
    fun rowDetailOverflowDuplicateDeleteAndStructuredEditReasonAreAvailable() {
        var saved: AutoRuleDraft? = null
        var deleted: String? = null
        val structured = rule("structured-rule", "結構化規則", "food").copy(
            conditions = listOf(
                AutoCategoryRuleCondition(
                    ruleId = "structured-rule",
                    position = 0,
                    field = AutoCategoryRuleConditionField.MERCHANT_NAME,
                    matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
                    pattern = "虛構商家",
                ),
            ),
        )
        setAutoRuleContent(
            rules = listOf(structured),
            categories = testCategories(),
            onSaveRule = { saved = it },
            onDeleteRule = { deleted = it },
        )

        composeRule.onNodeWithText("結構化規則").performClick()
        composeRule.onNodeWithText("如果").assertExists()
        composeRule.onNodeWithText("複製為自訂規則").performClick()
        composeRule.runOnIdle { assertEquals("", saved?.id) }

        composeRule.onAllNodesWithContentDescription("規則選項")[0].performClick()
        composeRule.onNodeWithText("此規則含多個結構化條件，請複製為自訂規則後編輯").assertExists()
        composeRule.onNodeWithText("刪除").performClick()
        composeRule.onNodeWithText("刪除自動分類規則？").assertExists()
        composeRule.onNodeWithText("刪除").performClick()
        composeRule.runOnIdle { assertEquals("structured-rule", deleted) }
    }

    private fun setAutoRuleContent(
        rules: List<AutoRuleDraft> = emptyList(),
        categories: List<CategoryOption> = emptyList(),
        tags: List<TagOption> = emptyList(),
        onSaveRule: (AutoRuleDraft) -> Unit = {},
        onDeleteRule: (String) -> Unit = {},
        applyState: () -> ApplyAllRulesUiState = { ApplyAllRulesUiState.Idle },
        resetState: () -> ClassificationResetUiState = { ClassificationResetUiState.Idle },
        onShowApply: () -> Unit = {},
        onCancelApply: () -> Unit = {},
        onStartApply: () -> Unit = {},
        onRetryApply: () -> Unit = {},
        onDismissApply: () -> Unit = {},
        onShowReset: () -> Unit = {},
        onCancelReset: () -> Unit = {},
        onStartReset: () -> Unit = {},
        onRetryReset: () -> Unit = {},
        onDismissReset: () -> Unit = {},
        overview: () -> AutoClassificationOverviewState = { readyOverview() },
        onOpenSettings: () -> Unit = {},
        fontScale: Float = 1f,
        widthDp: Int? = null,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(if (widthDp == 900) 0.5f else density.density, fontScale)) {
            Box(if (widthDp == null) Modifier else Modifier.width(widthDp.dp)) {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                AutoRuleListContent(
                    overview = overview(),
                    onNavigateToAiSettings = onOpenSettings,
                    rules = rules,
                    categories = categories,
                    tags = tags,
                    accounts = emptyList(),
                    onNavigateUp = {},
                    onSave = onSaveRule,
                    onDelete = onDeleteRule,
                    applyAllRulesUiState = applyState(),
                    onShowApplyAllRulesConfirmation = onShowApply,
                    onCancelApplyAllRules = onCancelApply,
                    onStartApplyAllRules = onStartApply,
                    onRetryApplyAllRules = onRetryApply,
                    onDismissApplyAllRules = onDismissApply,
                    classificationResetUiState = resetState(),
                    onShowResetClassificationConfirmation = onShowReset,
                    onCancelClassificationReset = onCancelReset,
                    onStartClassificationReset = onStartReset,
                    onRetryClassificationReset = onRetryReset,
                    onDismissClassificationReset = onDismissReset,
                )
            }
            }
            }
        }
    }

    private fun readyOverview() = AutoClassificationOverviewState(
        AiConfigurationSummary.Configured("自訂服務", "gpt-6-luna"),
        AiClassificationPreview(2, true, false), AutoClassificationAction.START,
    )

    private fun enabledRule() = AutoRuleDraft(
        id = "fictional-rule",
        name = "虛構規則",
        descriptionContains = "FICTIONAL",
        categoryId = "fictional-category",
    )

    private fun testCategories() = listOf(
        CategoryOption(id = "food", name = "餐飲", color = 0xFFF57C00),
        CategoryOption(id = "transfer", name = "轉帳", color = 0xFF1565C0),
    )

    private fun rule(
        id: String,
        name: String,
        categoryId: String,
        origin: AutoCategoryRuleOrigin = AutoCategoryRuleOrigin.USER_CONFIRMED,
        enabled: Boolean = true,
    ) = AutoRuleDraft(
        id = id,
        name = name,
        descriptionContains = name,
        categoryId = categoryId,
        origin = origin,
        enabled = enabled,
    )
}
