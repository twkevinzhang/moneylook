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
                    isResettingClassification = false,
                    onResetClassificationSystem = {},
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
                    isResettingClassification = false,
                    onResetClassificationSystem = {},
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
                    isResettingClassification = false,
                    onResetClassificationSystem = { resetCount += 1 },
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
    fun resetConfirmationInvokesOnceAndShowsBusyState() {
        var resetCount = 0
        var isResetting by mutableStateOf(false)
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
                    isResettingClassification = isResetting,
                    onResetClassificationSystem = {
                        resetCount += 1
                        isResetting = true
                    },
                    snackbarHostState = SnackbarHostState(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("更多選項").performClick()
        composeRule.onNodeWithText("清除並回到預設規則").performClick()
        composeRule.onNodeWithText("清除並恢復").performClick()
        composeRule.runOnIdle { assertEquals(1, resetCount) }
        composeRule.onNodeWithContentDescription("正在重設分類系統").assertExists()
        composeRule.onNodeWithContentDescription("套用所有規則到所有交易明細")
            .assertIsNotEnabled()
    }
}
