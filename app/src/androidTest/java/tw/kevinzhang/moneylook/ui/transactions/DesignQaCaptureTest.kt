package tw.kevinzhang.moneylook.ui.transactions

import android.graphics.Bitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.moneylook.ui.analysis.GlobalLedgerAnalysisContent
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme
import java.io.File
import java.time.LocalDate

/** Captures the real Compose implementation with fictional data; no private database is read. */
@RunWith(AndroidJUnit4::class)
class DesignQaCaptureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun captureAnalysisReferenceStates() {
        val currentItems = listOf(
            item("salary", "2026-07-05", 68_000.0, "薪資", CategoryReportingGroup.INCOME, "#43B96D"),
            item("food", "2026-07-08", -18_420.0, "飲食", CategoryReportingGroup.EXPENSE, "#FB8C00"),
            item("shopping", "2026-07-12", -7_860.0, "購物", CategoryReportingGroup.EXPENSE, "#42A5F5"),
            item("cash", "2026-07-18", -3_240.0, "現金消費", CategoryReportingGroup.EXPENSE, "#66BB6A"),
            item("uncategorized", "2026-07-20", -1_180.0, null, null, null),
            item("transfer", "2026-07-21", -10_000.0, "帳戶移轉", CategoryReportingGroup.EXCLUDED, "#9E9E9E"),
        )
        val trendItems = listOf(
            item("feb", "2026-02-03", 52_000.0, "薪資", CategoryReportingGroup.INCOME, "#43B96D"),
            item("feb-expense", "2026-02-18", -48_000.0, "生活", CategoryReportingGroup.EXPENSE, "#FB8C00"),
            item("mar", "2026-03-03", 59_000.0, "薪資", CategoryReportingGroup.INCOME, "#43B96D"),
            item("mar-expense", "2026-03-18", -31_000.0, "生活", CategoryReportingGroup.EXPENSE, "#FB8C00"),
            item("apr", "2026-04-03", 61_000.0, "薪資", CategoryReportingGroup.INCOME, "#43B96D"),
            item("apr-expense", "2026-04-18", -35_000.0, "生活", CategoryReportingGroup.EXPENSE, "#FB8C00"),
            item("may", "2026-05-03", 63_000.0, "薪資", CategoryReportingGroup.INCOME, "#43B96D"),
            item("may-expense", "2026-05-18", -29_000.0, "生活", CategoryReportingGroup.EXPENSE, "#FB8C00"),
            item("jun", "2026-06-03", 66_000.0, "薪資", CategoryReportingGroup.INCOME, "#43B96D"),
            item("jun-expense", "2026-06-18", -33_000.0, "生活", CategoryReportingGroup.EXPENSE, "#FB8C00"),
        ) + currentItems
        val state = GlobalTransactionsUiState(
            dateRange = GlobalDateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 22)),
            activeTab = GlobalTransactionsTab.ANALYSIS,
            allItems = currentItems,
            items = currentItems,
            trendItems = trendItems,
            summary = globalTransactionsSummary(currentItems),
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
                    analysisContent = { GlobalLedgerAnalysisContent(it) },
                    bottomBar = { QaBottomBar() },
                )
            }
        }

        composeRule.waitForIdle()
        capture("design-qa-analysis-summary.png")
        composeRule.onNodeWithTag("analysis-trend-chart").performScrollTo()
        composeRule.waitForIdle()
        capture("design-qa-analysis-trend.png")
    }

    @Test
    fun captureCategoryAndDetailReferenceStates() {
        val items = listOf(
            item("salary", "2026-07-05", 68_000.0, "薪資", CategoryReportingGroup.INCOME, "#43B96D"),
            item("food", "2026-07-08", -18_420.0, "飲食", CategoryReportingGroup.EXPENSE, "#FB8C00"),
            item("shopping", "2026-07-12", -7_860.0, "購物", CategoryReportingGroup.EXPENSE, "#42A5F5"),
            item("cash", "2026-07-18", -3_240.0, "現金消費", CategoryReportingGroup.EXPENSE, "#66BB6A"),
            item("uncategorized", "2026-07-20", -1_180.0, null, null, null),
        )
        val base = GlobalTransactionsUiState(
            dateRange = GlobalDateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 22)),
            activeTab = GlobalTransactionsTab.CATEGORY,
            categoryDirection = GlobalTransactionDirection.EXPENSE,
            allItems = items,
            items = items,
            summary = globalTransactionsSummary(items),
            categories = globalCategorySummaries(items, GlobalTransactionDirection.EXPENSE),
        )
        val state = mutableStateOf(base)

        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                GlobalTransactionsContent(
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
                    onCategoryClick = {},
                    bottomBar = { QaBottomBar() },
                )
            }
        }

        composeRule.waitForIdle()
        capture("design-qa-category.png")
        composeRule.runOnUiThread { state.value = base.copy(activeTab = GlobalTransactionsTab.DETAILS) }
        composeRule.waitForIdle()
        capture("design-qa-details.png")
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), name)
        output.outputStream().use { stream ->
            composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    private fun item(
        id: String,
        date: String,
        amount: Double,
        categoryName: String?,
        categoryReportingGroup: CategoryReportingGroup?,
        categoryColor: String?,
    ) = GlobalTransactionItem(
        transferId = id,
        transactionDateTime = "${date}T12:00:00+08:00",
        description = categoryName ?: "一般交易",
        memo = "",
        amount = amount,
        userNote = "",
        categoryId = categoryName,
        categoryName = categoryName,
        categoryReportingGroup = categoryReportingGroup,
        categoryEmoji = null,
        categoryColor = categoryColor,
        tags = emptyList(),
        accountId = "qa-account",
        accountName = "日常帳戶",
        extensionId = "qa-bank",
        extensionName = "示範銀行",
        currency = "TWD",
    )
}

@androidx.compose.runtime.Composable
private fun QaBottomBar() {
    NavigationBar {
        NavigationBarItem(false, {}, { Icon(Icons.Default.Home, "首頁") }, label = { Text("首頁") })
        NavigationBarItem(true, {}, { Icon(Icons.AutoMirrored.Filled.List, "明細") }, label = { Text("明細") })
        NavigationBarItem(false, {}, { Icon(Icons.Default.Settings, "設定") }, label = { Text("設定") })
    }
}
