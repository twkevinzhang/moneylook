package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme

class TransactionDetailStatusInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun postedCreditCardDetailShowsStatusChipAndPostingDate() {
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                TransactionDetailContent(
                    state = TransactionDetailUiState(
                        title = "虛構商店",
                        amountText = "TWD -100.00",
                        amount = -100.0,
                        accountName = "虛構信用卡",
                        transactionDate = "2026-07-20",
                        postingDate = "2026-07-22",
                        description = "虛構商店",
                        bankMemo = null,
                        selectedCategoryId = null,
                        selectedTagIds = emptySet(),
                        userNote = "",
                        categories = emptyList(),
                        tags = emptyList(),
                        accountKind = AssetKind.CREDIT_CARD,
                        status = "posted",
                    ),
                    onNavigateUp = {},
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithText("已出帳").assertExists()
        composeRule.onNodeWithText("入帳日").assertExists()
        composeRule.onNodeWithText("2026-07-22").assertExists()
    }

    @Test
    fun pendingCreditCardDetailShowsStatusChipWithoutPostingDate() {
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                TransactionDetailContent(
                    state = TransactionDetailUiState(
                        title = "虛構商店",
                        amountText = "TWD -100.00",
                        amount = -100.0,
                        accountName = "虛構信用卡",
                        transactionDate = "2026-07-20",
                        postingDate = null,
                        description = "虛構商店",
                        bankMemo = null,
                        selectedCategoryId = null,
                        selectedTagIds = emptySet(),
                        userNote = "",
                        categories = emptyList(),
                        tags = emptyList(),
                        accountKind = AssetKind.CREDIT_CARD,
                        status = "pending",
                    ),
                    onNavigateUp = {},
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithText("未出帳").assertExists()
        composeRule.onNodeWithText("入帳日").assertDoesNotExist()
    }
}
