package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme
import java.time.LocalDate

class GlobalTransactionsInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun topBarActionsAndSummaryDirectionKeepTheActiveTab() {
        val anchor = GlobalDateRange(
            startInclusive = LocalDate.now().minusDays(8),
            endInclusive = LocalDate.now().minusDays(6),
            isCustom = true,
        )
        val income = item("income", 12.0, "USD")
        val expense = item("expense", -30.0, "JPY")
        val state = mutableStateOf(
            GlobalTransactionsUiState(
                dateRange = anchor,
                datePagerAnchor = anchor,
                filter = GlobalTransactionsFilter(direction = GlobalTransactionDirection.EXPENSE),
                activeTab = GlobalTransactionsTab.CATEGORY,
                categoryDirection = GlobalTransactionDirection.EXPENSE,
                allItems = listOf(income, expense),
                items = listOf(expense),
                summary = GlobalTransactionsSummary(income = 100.0, expense = 200.0),
                missingExchangeCurrencies = listOf("JPY"),
                exchangeRatesLoading = false,
            ),
        )

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                GlobalTransactionsContent(
                    state = state.value,
                    onNavigateToTransaction = {},
                    onSelectDateRange = { range -> state.value = state.value.copy(dateRange = range) },
                    onSetDateRange = { _, _ -> true },
                    onSetQuery = { query -> state.value = state.value.copy(filter = state.value.filter.copy(query = query)) },
                    onUpdateFilter = {},
                    onClearFilters = {},
                    onSelectTab = { tab -> state.value = state.value.copy(activeTab = tab) },
                    onSelectReportDirection = { direction ->
                        state.value = state.value.copy(
                            filter = state.value.filter.copy(direction = direction),
                            categoryDirection = direction,
                            items = filterGlobalTransactions(
                                state.value.allItems,
                                state.value.filter.copy(direction = direction),
                            ),
                        )
                    },
                    onCategoryClick = {},
                )
            }
        }

        composeRule.onNodeWithText("本月").assertDoesNotExist()
        composeRule.onNodeWithText("部分幣別未計入", substring = true).assertExists()
        composeRule.onNodeWithTag("summary-expense").assertIsSelected()

        composeRule.onNodeWithTag("summary-income").performClick().assertIsSelected()
        composeRule.onNodeWithText("分類").assertIsSelected()
        composeRule.onNodeWithTag("summary-income").performClick().assertIsSelected()

        composeRule.onNodeWithContentDescription("搜尋").performClick()
        composeRule.onNodeWithTag("transaction-search-field").assertExists().assertIsFocused()
        composeRule.onNodeWithText("搜尋交易").assertExists()
        composeRule.onNodeWithContentDescription("關閉搜尋").assertExists()
        composeRule.onNodeWithContentDescription("搜尋").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("進階篩選").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("選擇日期區間").assertDoesNotExist()

        composeRule.onNodeWithTag("transaction-search-field").performTextInput("coffee")
        composeRule.waitUntil(timeoutMillis = 5_000) { state.value.filter.query == "coffee" }
        composeRule.onNodeWithContentDescription("清除搜尋").assertExists().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { state.value.filter.query.isEmpty() }
        composeRule.onNodeWithTag("transaction-search-field").assertExists()
        composeRule.onNodeWithContentDescription("清除搜尋").assertDoesNotExist()

        composeRule.onNodeWithTag("transaction-search-field").performTextInput("momo")
        composeRule.waitUntil(timeoutMillis = 5_000) { state.value.filter.query == "momo" }
        composeRule.onNodeWithContentDescription("關閉搜尋").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { state.value.filter.query.isEmpty() }
        composeRule.onNodeWithTag("transaction-search-field").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("搜尋").assertExists()
        composeRule.onNodeWithContentDescription("進階篩選").assertExists()
        composeRule.onNodeWithContentDescription("選擇日期區間").assertExists()

        composeRule.onNodeWithContentDescription("選擇日期區間").performClick()
        composeRule.onNodeWithText("選擇日期區間").assertExists()
        composeRule.onNodeWithText("取消").performClick()

        composeRule.onNodeWithTag("date-period-pager").performTouchInput { swipeLeft() }
        composeRule.waitUntil(timeoutMillis = 5_000) { state.value.dateRange != anchor }
        composeRule.runOnIdle { assertNotEquals(anchor, state.value.dateRange) }
    }

    @Test
    fun detailsKeepEachOriginalCurrency() {
        val range = GlobalDateRange.thisMonth(LocalDate.now())
        val state = GlobalTransactionsUiState(
            dateRange = range,
            filter = GlobalTransactionsFilter(direction = GlobalTransactionDirection.EXPENSE),
            activeTab = GlobalTransactionsTab.DETAILS,
            items = listOf(item("usd", -10.0, "USD"), item("jpy", -500.0, "JPY")),
            exchangeRatesLoading = false,
        )

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                GlobalTransactionsContent(
                    state = state,
                    onNavigateToTransaction = {},
                    onSelectDateRange = {},
                    onSetDateRange = { _, _ -> true },
                    onSetQuery = {},
                    onUpdateFilter = {},
                    onClearFilters = {},
                    onSelectTab = {},
                    onSelectReportDirection = {},
                    onCategoryClick = {},
                )
            }
        }

        composeRule.onNodeWithText("USD -10.00").assertExists()
        composeRule.onNodeWithText("JPY -500.00").assertExists()
    }

    private fun item(id: String, amount: Double, currency: String) = GlobalTransactionItem(
        transferId = id,
        transactionDateTime = "2026-07-21",
        description = "虛構交易 $id",
        memo = "",
        amount = amount,
        userNote = "",
        categoryId = null,
        categoryName = null,
        categoryKind = null,
        categoryEmoji = null,
        categoryColor = null,
        tags = emptyList(),
        accountId = "fictional-$id",
        accountName = "虛構帳戶",
        extensionId = "fictional",
        extensionName = "虛構銀行",
        currency = currency,
    )
}
