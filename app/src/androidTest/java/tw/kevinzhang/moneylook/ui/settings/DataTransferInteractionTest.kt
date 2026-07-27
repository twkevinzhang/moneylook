package tw.kevinzhang.moneylook.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme

@RunWith(AndroidJUnit4::class)
class DataTransferInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsDataTransferRowNavigates() {
        var navigationCount = 0
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                SettingsScreen(onNavigateToDataTransfer = { navigationCount += 1 })
            }
        }

        composeRule.onNodeWithContentDescription("前往資料匯入與匯出").performClick()
        composeRule.runOnIdle { assertEquals(1, navigationCount) }
    }

    @Test
    fun transferActionsUseDedicatedCallbacksAndShowPlaintextWarning() {
        var rulesImportCount = 0
        var rulesExportCount = 0
        var credentialsImportCount = 0
        var credentialsExportCount = 0
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                DataTransferContent(
                    state = DataTransferUiState(),
                    onImportAutoRules = { rulesImportCount += 1 },
                    onExportAutoRules = { rulesExportCount += 1 },
                    onImportCredentials = { credentialsImportCount += 1 },
                    onExportCredentials = { credentialsExportCount += 1 },
                    onConfirmImport = {},
                    onDismissImportPreview = {},
                    onDismissStatus = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("明碼密碼安全警告").assertExists()
        composeRule.onNodeWithContentDescription("匯入自動化分類規則 CSV").performClick()
        composeRule.onNodeWithContentDescription("匯出自動化分類規則 CSV").performClick()
        composeRule.onNodeWithContentDescription("匯入帳號密碼 CSV").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("匯出帳號密碼 CSV").performScrollTo().performClick()

        composeRule.runOnIdle {
            assertEquals(1, rulesImportCount)
            assertEquals(1, rulesExportCount)
            assertEquals(1, credentialsImportCount)
            assertEquals(1, credentialsExportCount)
        }
    }

    @Test
    fun importPreviewBlocksConfirmationWhenValidationHasErrors() {
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                DataTransferContent(
                    state = DataTransferUiState(
                        importPreview = CsvImportPreviewUiState(
                            target = CsvTransferTarget.CREDENTIALS,
                            fileName = "帳號密碼.csv",
                            newCount = 2,
                            overwriteCount = 1,
                            skippedCount = 0,
                            errorCount = 1,
                            errorSummary = "有一個欄位不符合已安裝擴充的設定。",
                        ),
                    ),
                    onImportAutoRules = {},
                    onExportAutoRules = {},
                    onImportCredentials = {},
                    onExportCredentials = {},
                    onConfirmImport = {},
                    onDismissImportPreview = {},
                    onDismissStatus = {},
                )
            }
        }

        composeRule.onNodeWithText("匯入預覽：帳號密碼").assertExists()
        composeRule.onNodeWithContentDescription("確認匯入帳號密碼").assertIsNotEnabled()
        composeRule.onNodeWithText("不會立即登入或同步銀行", substring = true).assertExists()
    }

    @Test
    fun validPreviewCanBeConfirmed() {
        var confirmCount = 0
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                DataTransferContent(
                    state = DataTransferUiState(
                        importPreview = CsvImportPreviewUiState(
                            target = CsvTransferTarget.AUTO_RULES,
                            fileName = "自動化分類規則.csv",
                            newCount = 5,
                            overwriteCount = 2,
                            skippedCount = 1,
                            errorCount = 0,
                        ),
                    ),
                    onImportAutoRules = {},
                    onExportAutoRules = {},
                    onImportCredentials = {},
                    onExportCredentials = {},
                    onConfirmImport = { confirmCount += 1 },
                    onDismissImportPreview = {},
                    onDismissStatus = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("確認匯入自動化分類規則")
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, confirmCount) }
    }
}
