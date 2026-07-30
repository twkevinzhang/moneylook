package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.material3.SnackbarHostState
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
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme
import tw.kevinzhang.moneylook.sync.ClassificationResetStage

@RunWith(AndroidJUnit4::class)
class AutoRuleInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun applyAllActionConfirmsOnceAndReplacesTheCardLevelAction() {
        var applyCount = 0
        var isApplying by mutableStateOf(false)
        val rule = AutoRuleDraft(
            id = "fictional-rule",
            name = "虛構規則",
            descriptionContains = "FICTIONAL",
            categoryId = "fictional-category",
        )

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                AutoRuleListContent(
                    rules = listOf(rule),
                    categories = emptyList(),
                    tags = emptyList(),
                    accounts = emptyList(),
                    onNavigateUp = {},
                    onSave = {},
                    onDelete = {},
                    isApplyingAllRules = isApplying,
                    onApplyAllRules = {
                        applyCount += 1
                        isApplying = true
                    },
                    classificationResetUiState = ClassificationResetUiState.Idle,
                    onShowResetClassificationConfirmation = {},
                    onCancelClassificationReset = {},
                    onStartClassificationReset = {},
                    onRetryClassificationReset = {},
                    onDismissClassificationReset = {},
                    snackbarHostState = SnackbarHostState(),
                )
            }
        }

        composeRule.onNodeWithText("依所有規則重新套用").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("套用所有規則到所有交易明細").performClick()
        composeRule.onNodeWithText("套用所有規則？").assertExists()
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(0, applyCount)

        composeRule.onNodeWithContentDescription("套用所有規則到所有交易明細").performClick()
        composeRule.onNodeWithText("套用").performClick()
        composeRule.runOnIdle { assertEquals(1, applyCount) }
        composeRule.onNodeWithContentDescription("套用所有規則到所有交易明細").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("正在套用所有規則").assertExists()
    }

    @Test
    fun applyAllActionIsDisabledWithoutAnEnabledRule() {
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                AutoRuleListContent(
                    rules = listOf(AutoRuleDraft(id = "disabled", name = "停用", enabled = false)),
                    categories = emptyList(),
                    tags = emptyList(),
                    accounts = emptyList(),
                    onNavigateUp = {},
                    onSave = {},
                    onDelete = {},
                    isApplyingAllRules = false,
                    onApplyAllRules = {},
                    classificationResetUiState = ClassificationResetUiState.Idle,
                    onShowResetClassificationConfirmation = {},
                    onCancelClassificationReset = {},
                    onStartClassificationReset = {},
                    onRetryClassificationReset = {},
                    onDismissClassificationReset = {},
                    snackbarHostState = SnackbarHostState(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("套用所有規則到所有交易明細")
            .assertIsNotEnabled()
    }

    @Test
    fun resetActionUsesOverflowMenuAndCancelDoesNotInvokeCallback() {
        var resetCount = 0
        var resetUiState by mutableStateOf<ClassificationResetUiState>(ClassificationResetUiState.Idle)
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                AutoRuleListContent(
                    rules = emptyList(),
                    categories = emptyList(),
                    tags = emptyList(),
                    accounts = emptyList(),
                    onNavigateUp = {},
                    onSave = {},
                    onDelete = {},
                    isApplyingAllRules = false,
                    onApplyAllRules = {},
                    classificationResetUiState = resetUiState,
                    onShowResetClassificationConfirmation = { resetUiState = ClassificationResetUiState.Confirming },
                    onCancelClassificationReset = { resetUiState = ClassificationResetUiState.Idle },
                    onStartClassificationReset = { resetCount += 1 },
                    onRetryClassificationReset = {},
                    onDismissClassificationReset = {},
                    snackbarHostState = SnackbarHostState(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("更多選項").performClick()
        composeRule.onNodeWithText("清除並回到預設規則").performClick()
        composeRule.onNodeWithText("清除並回到預設規則？").assertExists()
        composeRule.onNodeWithText(
            "這會永久清除所有自動分類規則、分類與標籤，並移除所有交易的手動分類與標籤指派。" +
                "交易與備註會保留，接著會重新分類所有交易。此操作無法復原。",
        )
            .assertExists()
        composeRule.onNodeWithText("取消").performClick()
        composeRule.runOnIdle { assertEquals(0, resetCount) }
    }

    @Test
    fun resetConfirmationStartsPersistentRunningDialogAndOnlyInvokesOnce() {
        var resetCount = 0
        var resetUiState by mutableStateOf<ClassificationResetUiState>(ClassificationResetUiState.Idle)
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                AutoRuleListContent(
                    rules = emptyList(),
                    categories = emptyList(),
                    tags = emptyList(),
                    accounts = emptyList(),
                    onNavigateUp = {},
                    onSave = {},
                    onDelete = {},
                    isApplyingAllRules = false,
                    onApplyAllRules = {},
                    classificationResetUiState = resetUiState,
                    onShowResetClassificationConfirmation = { resetUiState = ClassificationResetUiState.Confirming },
                    onCancelClassificationReset = {},
                    onStartClassificationReset = {
                        resetCount += 1
                        resetUiState = ClassificationResetUiState.ResettingCatalog
                    },
                    onRetryClassificationReset = {},
                    onDismissClassificationReset = {},
                    snackbarHostState = SnackbarHostState(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("更多選項").performClick()
        composeRule.onNodeWithText("清除並回到預設規則").performClick()
        composeRule.onNodeWithText("清除並恢復").performClick()
        composeRule.runOnIdle { assertEquals(1, resetCount) }
        composeRule.onNodeWithText("正在清除分類資料").assertExists()
        composeRule.onNodeWithContentDescription("正在清除分類資料").assertExists()
        composeRule.onNodeWithText("取消").assertDoesNotExist()
        composeRule.onNodeWithText("關閉").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("套用所有規則到所有交易明細")
            .assertIsNotEnabled()
    }

    @Test
    fun resetReclassificationDialogRendersZeroAndNonzeroProgress() {
        var resetUiState by mutableStateOf<ClassificationResetUiState>(
            ClassificationResetUiState.Reclassifying(processedTransferCount = 0, totalTransferCount = 0),
        )
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                AutoRuleListContent(
                    rules = emptyList(), categories = emptyList(), tags = emptyList(), accounts = emptyList(),
                    onNavigateUp = {}, onSave = {}, onDelete = {},
                    isApplyingAllRules = false, onApplyAllRules = {},
                    classificationResetUiState = resetUiState,
                    onShowResetClassificationConfirmation = {}, onCancelClassificationReset = {},
                    onStartClassificationReset = {}, onRetryClassificationReset = {}, onDismissClassificationReset = {},
                    snackbarHostState = SnackbarHostState(),
                )
            }
        }

        composeRule.onNodeWithText("正在重新分類交易").assertExists()
        composeRule.onNodeWithText("已處理 0 / 0 筆交易").assertExists()
        composeRule.onNodeWithContentDescription("重新分類進度").assertExists()
        composeRule.runOnIdle {
            resetUiState = ClassificationResetUiState.Reclassifying(processedTransferCount = 25, totalTransferCount = 100)
        }
        composeRule.onNodeWithText("已處理 25 / 100 筆交易").assertExists()
    }

    @Test
    fun resetTerminalDialogsSupportFinishRetryAndClose() {
        var finishedCount = 0
        var retryCount = 0
        var resetUiState by mutableStateOf<ClassificationResetUiState>(
            ClassificationResetUiState.Success(processedTransferCount = 12, matchedTransferCount = 9),
        )
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                AutoRuleListContent(
                    rules = emptyList(), categories = emptyList(), tags = emptyList(), accounts = emptyList(),
                    onNavigateUp = {}, onSave = {}, onDelete = {},
                    isApplyingAllRules = false, onApplyAllRules = {},
                    classificationResetUiState = resetUiState,
                    onShowResetClassificationConfirmation = {}, onCancelClassificationReset = {}, onStartClassificationReset = {},
                    onRetryClassificationReset = {
                        retryCount += 1
                        resetUiState = ClassificationResetUiState.ResettingCatalog
                    },
                    onDismissClassificationReset = {
                        finishedCount += 1
                        resetUiState = ClassificationResetUiState.Idle
                    },
                    snackbarHostState = SnackbarHostState(),
                )
            }
        }

        composeRule.onNodeWithText("已重新分類 12 筆交易，符合 9 筆規則。").assertExists()
        composeRule.onNodeWithText("完成").performClick()
        composeRule.runOnIdle { assertEquals(1, finishedCount) }

        composeRule.runOnIdle {
            resetUiState = ClassificationResetUiState.Error(
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
            resetUiState = ClassificationResetUiState.Error("重設分類系統失敗，請稍後再試。")
        }
        composeRule.onNodeWithText("關閉").performClick()
        composeRule.runOnIdle { assertEquals(2, finishedCount) }
    }
}
