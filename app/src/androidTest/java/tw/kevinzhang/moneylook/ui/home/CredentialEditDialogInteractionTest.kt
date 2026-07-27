package tw.kevinzhang.moneylook.ui.home

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme

@RunWith(AndroidJUnit4::class)
class CredentialEditDialogInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deleteRequiresConfirmationAndKeepsActionsInExpectedOrder() {
        var deleteCount = 0

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                CredentialEditDialog(
                    extension = fictionalExtension(),
                    summary = fictionalCredentialSummary(),
                    onDismiss = {},
                    onSave = { _, _, _, _ -> },
                    onDelete = { deleteCount += 1 },
                )
            }
        }

        val deleteLeft = composeRule.onNodeWithTag("credential-delete-action")
            .getUnclippedBoundsInRoot().left
        val cancelLeft = composeRule.onNodeWithTag("credential-cancel-action")
            .getUnclippedBoundsInRoot().left
        val saveLeft = composeRule.onNodeWithTag("credential-save-action")
            .getUnclippedBoundsInRoot().left
        assertTrue(deleteLeft < cancelLeft)
        assertTrue(cancelLeft < saveLeft)

        composeRule.onNodeWithTag("credential-delete-action").performClick()
        composeRule.onNodeWithText("刪除登入資料？").assertExists()
        composeRule.runOnIdle { assertEquals(0, deleteCount) }

        composeRule.onNodeWithTag("credential-delete-confirm-cancel").performClick()
        composeRule.runOnIdle { assertEquals(0, deleteCount) }

        composeRule.onNodeWithTag("credential-delete-action").performClick()
        composeRule.onNodeWithTag("credential-delete-confirm").performClick()
        composeRule.runOnIdle { assertEquals(1, deleteCount) }
    }

    private fun fictionalExtension() = InstalledExtension(
        id = "fictional-bank",
        manifestId = "fictional.bank",
        name = "虛構銀行",
        version = 1,
        repoUrl = "https://github.com/fictional/bank",
        syncTriggerCachePath = "/fictional/sync-trigger.js",
        iconUrl = null,
    )

    private fun fictionalCredentialSummary() = CredentialSummary(
        extensionId = "fictional-bank",
        fields = listOf(
            CredentialFieldDefinition(
                key = "username",
                label = "虛構帳號",
                type = CredentialFieldDefinition.TYPE_TEXT,
                required = true,
                summary = true,
            ),
        ),
        visibleValues = mapOf("username" to "FICTIONAL"),
        storedPasswordKeys = emptySet(),
        summaryText = "FICTIONAL",
        isConfigured = true,
        scheduleEnabled = true,
        scheduleCron = "0 8 * * *",
        timezoneId = "Asia/Taipei",
        lastRunAt = null,
        lastRunStatus = null,
    )
}
