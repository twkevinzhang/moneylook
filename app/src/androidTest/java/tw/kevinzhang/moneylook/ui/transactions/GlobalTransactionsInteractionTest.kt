package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme
import java.time.LocalDate

class GlobalTransactionsInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun topBarActionsAndSummaryDirectionKeepTheActiveTab() {
        val today = LocalDate.now()
        val anchor = GlobalDateRange(
            startInclusive = today.minusDays(8),
            endInclusive = today.minusDays(6),
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
                    onResetToThisMonth = {
                        val thisMonth = GlobalDateRange.thisMonth(today)
                        state.value = state.value.copy(dateRange = thisMonth, datePagerAnchor = thisMonth)
                    },
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

        val pager = globalDateRangePager(anchor, today)
        val nextRange = pager.rangeAt(pager.selectedPage + 1)
        composeRule.onNodeWithTag("date-period-pager").performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(anchor, state.value.dateRange) }
        composeRule.onNodeWithTag("date-period-${nextRange.startKey}").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { state.value.dateRange == nextRange }
        composeRule.onNodeWithTag("date-period-${nextRange.startKey}").assertIsSelected()

        composeRule.onNodeWithContentDescription("回到本月").assertExists().performClick()
        val thisMonth = GlobalDateRange.thisMonth(today)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            state.value.dateRange == thisMonth && state.value.datePagerAnchor == thisMonth
        }
        composeRule.onNodeWithContentDescription("回到本月").assertDoesNotExist()

        composeRule.onNodeWithTag("date-period-pager").performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回到本月").assertExists().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("回到本月").assertDoesNotExist()
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
                    onResetToThisMonth = {},
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

    @Test
    fun creditCardDetailsShowPostedAndPendingChipsBesideTheirDescriptions() {
        val range = GlobalDateRange.thisMonth(LocalDate.now())
        val state = GlobalTransactionsUiState(
            dateRange = range,
            filter = GlobalTransactionsFilter(direction = GlobalTransactionDirection.EXPENSE),
            activeTab = GlobalTransactionsTab.DETAILS,
            items = listOf(
                item("posted", -100.0, "TWD").copy(
                    accountKind = AssetKind.CREDIT_CARD,
                    status = "posted",
                ),
                item("pending", -50.0, "TWD").copy(
                    accountKind = AssetKind.CREDIT_CARD,
                    status = "pending",
                ),
            ),
            exchangeRatesLoading = false,
        )

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                GlobalTransactionsContent(
                    state = state,
                    onNavigateToTransaction = {},
                    onSelectDateRange = {},
                    onResetToThisMonth = {},
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

        composeRule.onNodeWithText("已出帳").assertExists()
        composeRule
            .onNode(hasScrollAction() and hasAnyDescendant(hasText("已出帳")))
            .performScrollToNode(hasText("未出帳"))
        composeRule.onNodeWithText("未出帳").assertExists()
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
