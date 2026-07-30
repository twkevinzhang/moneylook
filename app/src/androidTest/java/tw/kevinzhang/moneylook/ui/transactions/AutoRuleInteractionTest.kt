package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tw.kevinzhang.moneylook.sync.ClassificationResetStage
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme

@RunWith(AndroidJUnit4::class)
class AutoRuleInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun applyAllActionUsesOverflowMenuAndCancelDoesNotInvokeCallback() {
        var startCount = 0
        var applyState by mutableStateOf<ApplyAllRulesUiState>(ApplyAllRulesUiState.Idle)
        setAutoRuleContent(
            rules = listOf(enabledRule()),
            applyState = { applyState },
            onShowApply = { applyState = ApplyAllRulesUiState.Confirming },
            onCancelApply = { applyState = ApplyAllRulesUiState.Idle },
            onStartApply = { startCount += 1 },
        )

        composeRule.onNodeWithContentDescription("套用所有規則到所有交易明細").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("更多選項").performClick()
        composeRule.onNodeWithText("套用所有規則").performClick()
        composeRule.onNodeWithText("套用所有規則？").assertExists()
        composeRule.onNodeWithText(
            "會依優先順序，將所有已啟用規則套用到全部交易明細。" +
                "手動設定的分類、標籤與備註會保留，不會被覆蓋。",
        ).assertExists()
        composeRule.onNodeWithText("取消").performClick()

        composeRule.runOnIdle { assertEquals(0, startCount) }
    }

    @Test
    fun applyAllConfirmationStartsPersistentDialogAndOnlyInvokesOnce() {
        var startCount = 0
        var applyState by mutableStateOf<ApplyAllRulesUiState>(ApplyAllRulesUiState.Idle)
        setAutoRuleContent(
            rules = listOf(enabledRule()),
            applyState = { applyState },
            onShowApply = { applyState = ApplyAllRulesUiState.Confirming },
            onStartApply = {
                startCount += 1
                applyState = ApplyAllRulesUiState.Preparing
            },
        )

        composeRule.onNodeWithContentDescription("更多選項").performClick()
        composeRule.onNodeWithText("套用所有規則").performClick()
        composeRule.onNodeWithText("套用").performClick()

        composeRule.runOnIdle { assertEquals(1, startCount) }
        composeRule.onNodeWithText("正在準備交易資料").assertExists()
        composeRule.onNodeWithContentDescription("正在準備交易資料").assertExists()
        composeRule.onNodeWithText("套用").assertDoesNotExist()
        composeRule.onNodeWithText("取消").assertDoesNotExist()
        composeRule.onNodeWithText("關閉").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("更多選項").assertIsNotEnabled()
    }

    @Test
    fun applyAllMenuItemIsVisibleButDisabledWithoutEnabledRule() {
        setAutoRuleContent(rules = listOf(enabledRule().copy(enabled = false)))

        composeRule.onNodeWithContentDescription("更多選項").performClick()
        composeRule.onNodeWithText("套用所有規則").assertExists().assertIsNotEnabled()
        composeRule.onNodeWithText("清除並回到預設規則").assertExists()
    }

    @Test
    fun applyAllDialogRendersZeroAndNonzeroProgress() {
        var applyState by mutableStateOf<ApplyAllRulesUiState>(
            ApplyAllRulesUiState.Applying(processedTransferCount = 0, totalTransferCount = 0),
        )
        setAutoRuleContent(
            rules = listOf(enabledRule()),
            applyState = { applyState },
        )

        composeRule.onNodeWithText("正在套用所有規則").assertExists()
        composeRule.onNodeWithText("已處理 0 / 0 筆交易").assertExists()
        composeRule.onNodeWithContentDescription("套用規則進度").assertExists()

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
            rules = listOf(enabledRule()),
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
        composeRule.onNodeWithText("符合 73 筆規則").assertExists()
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
        composeRule.onNodeWithText("失敗階段：正在套用規則（已處理 25 / 100 筆交易）").assertExists()
        composeRule.onNodeWithText("重試").performClick()
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
        composeRule.onNodeWithText("清除並回到預設規則").performClick()
        composeRule.onNodeWithText("清除並回到預設規則？").assertExists()
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
        composeRule.onNodeWithText("清除並回到預設規則").performClick()
        composeRule.onNodeWithText("清除並恢復").performClick()
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

        composeRule.onNodeWithText("已重新分類 12 筆交易，符合 9 筆規則。").assertExists()
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

    private fun setAutoRuleContent(
        rules: List<AutoRuleDraft> = emptyList(),
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
    ) {
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                AutoRuleListContent(
                    rules = rules,
                    categories = emptyList(),
                    tags = emptyList(),
                    accounts = emptyList(),
                    onNavigateUp = {},
                    onSave = {},
                    onDelete = {},
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

    private fun enabledRule() = AutoRuleDraft(
        id = "fictional-rule",
        name = "虛構規則",
        descriptionContains = "FICTIONAL",
        categoryId = "fictional-category",
    )
}
