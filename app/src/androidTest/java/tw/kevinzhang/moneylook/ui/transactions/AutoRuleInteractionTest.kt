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
                    snackbarHostState = SnackbarHostState(),
                )
            }
        }

        composeRule.onNodeWithContentDescription("套用所有規則到所有交易明細")
            .assertIsNotEnabled()
    }
}
