package tw.kevinzhang.moneylook.ui.transactions

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
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
import java.time.YearMonth

class GlobalLedgerInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun datePagerRestoresAnOlderActiveMonthAtTheViewportCenter() {
        val today = LocalDate.now()
        val anchor = GlobalDateRange.thisMonth(today)
        val activeRange = GlobalDateRange.month(YearMonth.from(today).minusMonths(2))

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                GlobalLedgerContent(
                    state = GlobalLedgerUiState(
                        dateRange = activeRange,
                        datePagerAnchor = anchor,
                    ),
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

        composeRule.waitForIdle()
        val activeNode = composeRule.onNodeWithTag("date-period-${activeRange.startKey}")
        activeNode.assertIsSelected()
        val pagerBounds = composeRule.onNodeWithTag("date-period-pager").getBoundsInRoot()
        val activeBounds = activeNode.getBoundsInRoot()
        assertEquals(
            (pagerBounds.left.value + pagerBounds.right.value) / 2f,
            (activeBounds.left.value + activeBounds.right.value) / 2f,
            1f,
        )
    }

    @Test
    fun topBarActionsAndSummaryDirectionKeepTheActiveTab() {
        var excludedSummaryClickCount = 0
        val today = LocalDate.now()
        val anchor = GlobalDateRange(
            startInclusive = today.minusDays(8),
            endInclusive = today.minusDays(6),
            isCustom = true,
        )
        val income = item("income", 12.0, "USD")
        val expense = item("expense", -30.0, "JPY")
        val state = mutableStateOf(
            GlobalLedgerUiState(
                dateRange = anchor,
                datePagerAnchor = anchor,
                filter = GlobalLedgerFilter(),
                activeTab = GlobalLedgerTab.CATEGORY,
                categoryDirection = GlobalLedgerDirection.EXPENSE,
                allItems = listOf(income, expense),
                items = listOf(income, expense),
                summary = GlobalLedgerSummary(income = 100.0, expense = 200.0, excludedCount = 2),
                missingExchangeCurrencies = listOf("JPY"),
                exchangeRatesLoading = false,
            ),
        )

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                GlobalLedgerContent(
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
                        state.value = state.value.copy(categoryDirection = direction)
                    },
                    onCategoryClick = {},
                    onExcludedSummaryClick = { excludedSummaryClickCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("本月").assertDoesNotExist()
        composeRule.onNodeWithText("部分幣別未計入", substring = true).assertExists()
        composeRule.onNodeWithTag("summary-expense").assertIsSelected()
        composeRule.onNodeWithTag("summary-excluded-count")
            .assert(hasClickAction())
            .performClick()
        composeRule.onNodeWithContentDescription("查看不統計 2 筆明細").assertExists()
        composeRule.runOnIdle { assertEquals(1, excludedSummaryClickCount) }

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
        val state = GlobalLedgerUiState(
            dateRange = range,
            filter = GlobalLedgerFilter(direction = GlobalLedgerDirection.EXPENSE),
            activeTab = GlobalLedgerTab.DETAILS,
            items = listOf(item("usd", -10.0, "USD"), item("jpy", -500.0, "JPY")),
            exchangeRatesLoading = false,
        )

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                GlobalLedgerContent(
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
        composeRule.onNodeWithText("不統計 0 筆").assertExists()
    }

    @Test
    fun creditCardDetailsShowPostedAndPendingChipsBesideTheirDescriptions() {
        val range = GlobalDateRange.thisMonth(LocalDate.now())
        val state = GlobalLedgerUiState(
            dateRange = range,
            filter = GlobalLedgerFilter(direction = GlobalLedgerDirection.EXPENSE),
            activeTab = GlobalLedgerTab.DETAILS,
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
                GlobalLedgerContent(
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

    @Test
    fun categoryClickOnlyRequestsIndependentCategoryNavigation() {
        val range = GlobalDateRange.thisMonth(LocalDate.now())
        val selectedCategory = GlobalCategorySummary(
            id = "food",
            name = "餐飲",
            emoji = "🍜",
            amount = 360.0,
            percentage = 1f,
            transactionCount = 1,
        )
        val state = mutableStateOf(
            GlobalLedgerUiState(
                dateRange = range,
                filter = GlobalLedgerFilter(direction = GlobalLedgerDirection.EXPENSE),
                activeTab = GlobalLedgerTab.CATEGORY,
                categories = listOf(selectedCategory),
            ),
        )
        var requestedCategoryId: String? = "not-clicked"

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                GlobalLedgerContent(
                    state = state.value,
                    onNavigateToTransaction = {},
                    onSelectDateRange = {},
                    onResetToThisMonth = {},
                    onSetDateRange = { _, _ -> true },
                    onSetQuery = {},
                    onUpdateFilter = {},
                    onClearFilters = {},
                    onSelectTab = {},
                    onSelectReportDirection = {},
                    onCategoryClick = { requestedCategoryId = it },
                )
            }
        }

        composeRule.onNodeWithText("餐飲").performClick()
        composeRule.runOnIdle {
            assertEquals("food", requestedCategoryId)
            assertEquals(GlobalLedgerTab.CATEGORY, state.value.activeTab)
            assertEquals(null, state.value.filter.categoryId)
        }
    }

    @Test
    fun detailsTabShowsAllCurrentResultsAfterCategoryNavigationRequest() {
        val range = GlobalDateRange.thisMonth(LocalDate.now())
        val income = item("salary", 20_000.0, "TWD").copy(
            description = "七月薪資",
            categoryId = "salary",
            categoryName = "薪資",
        )
        val food = item("food", -360.0, "TWD").copy(
            description = "午餐",
            categoryId = "food",
            categoryName = "餐飲",
        )
        val uncategorized = item("uncategorized", -80.0, "TWD").copy(
            description = "未分類消費",
        )
        val allCurrentResults = listOf(income, food, uncategorized)
        val state = mutableStateOf(
            GlobalLedgerUiState(
                dateRange = range,
                filter = GlobalLedgerFilter(),
                activeTab = GlobalLedgerTab.CATEGORY,
                allItems = allCurrentResults,
                items = allCurrentResults,
                categories = listOf(
                    GlobalCategorySummary(
                        id = "food",
                        name = "餐飲",
                        emoji = "🍜",
                        amount = 360.0,
                        percentage = 1f,
                        transactionCount = 1,
                    ),
                ),
            ),
        )
        var requestedCategoryId: String? = null

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                GlobalLedgerContent(
                    state = state.value,
                    onNavigateToTransaction = {},
                    onSelectDateRange = {},
                    onResetToThisMonth = {},
                    onSetDateRange = { _, _ -> true },
                    onSetQuery = {},
                    onUpdateFilter = {},
                    onClearFilters = {},
                    onSelectTab = { tab -> state.value = state.value.copy(activeTab = tab) },
                    onSelectReportDirection = {},
                    onCategoryClick = { requestedCategoryId = it },
                )
            }
        }

        composeRule.onNodeWithText("餐飲").performClick()
        composeRule.runOnIdle {
            assertEquals("food", requestedCategoryId)
            assertEquals(null, state.value.filter.categoryId)
        }

        composeRule.onNode(hasText("明細") and hasClickAction()).performClick()
        composeRule.onNodeWithText("七月薪資").assertExists()
        composeRule.onNodeWithText("午餐").assertExists()
        composeRule.onNodeWithText("未分類消費").assertExists()
    }

    @Test
    fun categoryTransactionsContentShowsCategoryRowsAndDispatchesBackAndTransactionCallbacks() {
        var backCount = 0
        var selectedTransferId: String? = null
        val categoryItem = item("food-1", -360.0, "TWD").copy(
            description = "午餐",
            categoryId = "food",
            categoryName = "餐飲",
        )

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                CategoryTransactionsContent(
                    categoryName = "餐飲",
                    items = listOf(categoryItem),
                    onNavigateUp = { backCount += 1 },
                    onNavigateToTransaction = { selectedTransferId = it },
                )
            }
        }

        composeRule.onNodeWithText("餐飲 明細").assertExists()
        composeRule.onNodeWithText("午餐").performClick()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.runOnIdle {
            assertEquals("food-1", selectedTransferId)
            assertEquals(1, backCount)
        }
    }

    @Test
    fun categoryTransactionsContentSupportsUncategorizedAndEmptyStates() {
        val uncategorized = item("uncategorized-1", -80.0, "TWD").copy(
            description = "未分類消費",
        )
        val state = mutableStateOf(listOf(uncategorized))

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                CategoryTransactionsContent(
                    categoryName = "尚未分類",
                    items = state.value,
                    onNavigateUp = {},
                    onNavigateToTransaction = {},
                )
            }
        }

        composeRule.onNodeWithText("尚未分類 明細").assertExists()
        composeRule.onNodeWithText("未分類消費").assertExists()

        composeRule.runOnUiThread { state.value = emptyList() }
        composeRule.onNodeWithText("未分類消費").assertDoesNotExist()
        composeRule.onNodeWithText("這個條件下沒有尚未分類明細").assertExists()
    }

    @Test
    fun zeroExcludedSummaryIsNonInteractiveAndHasNoNavigationAffordance() {
        val range = GlobalDateRange.thisMonth(LocalDate.now())
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                GlobalLedgerContent(
                    state = GlobalLedgerUiState(
                        dateRange = range,
                        summary = GlobalLedgerSummary(excludedCount = 0),
                    ),
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

        composeRule.onNodeWithTag("summary-excluded-count").assertHasNoClickAction()
        composeRule.onNodeWithText("不統計 0 筆").assertExists()
        composeRule.onNodeWithText("不統計 0 筆 ›").assertDoesNotExist()
    }

    @Test
    fun excludedTransactionsContentDispatchesBackAndTransactionNavigation() {
        var backCount = 0
        var selectedTransferId: String? = null
        val excludedItem = item("excluded-1", -360.0, "TWD").copy(
            description = "帳戶移轉",
        )
        val state = mutableStateOf(listOf(excludedItem))

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                ExcludedTransactionsContent(
                    items = state.value,
                    onNavigateUp = { backCount += 1 },
                    onNavigateToTransaction = { selectedTransferId = it },
                )
            }
        }

        composeRule.onNodeWithText("不統計明細").assertExists()
        composeRule.onNodeWithText("帳戶移轉").performClick()
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.runOnIdle {
            assertEquals("excluded-1", selectedTransferId)
            assertEquals(1, backCount)
        }

        composeRule.runOnUiThread { state.value = emptyList() }
        composeRule.onNodeWithText("帳戶移轉").assertDoesNotExist()
        composeRule.onNodeWithText("這個條件下沒有不統計明細").assertExists()
    }

    private fun item(id: String, amount: Double, currency: String) = GlobalLedgerItem(
        transferId = id,
        transactionDateTime = "2026-07-21",
        description = "虛構交易 $id",
        memo = "",
        amount = amount,
        userNote = "",
        categoryId = null,
        categoryName = null,
        categoryReportingGroup = null,
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
