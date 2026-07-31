package tw.kevinzhang.moneylook.ui.analysis

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AnalysisContentInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun incomeDirectionShowsOnlyIncomeSummaryAndTrendCopy() {
        composeRule.setContent {
            MaterialTheme {
                AnalysisContent(
                    presentation = presentation(),
                    selectedDirection = AnalysisDirection.INCOME,
                )
            }
        }

        composeRule.onNodeWithText("2026年7月總收入").assertExists()
        composeRule.onNodeWithText("收入總額").assertExists()
        composeRule.onNodeWithText("支出總額").assertDoesNotExist()
        composeRule.onNodeWithText("結餘").assertDoesNotExist()
        composeRule.onNodeWithText("收入的月度變化").assertExists()
        composeRule.onNodeWithText("收入與支出的月度變化").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("近半年收入趨勢：", substring = true).assertExists()
    }

    @Test
    fun expenseDirectionShowsOnlyExpenseSummaryAndTrendCopy() {
        composeRule.setContent {
            MaterialTheme {
                AnalysisContent(
                    presentation = presentation(),
                    selectedDirection = AnalysisDirection.EXPENSE,
                )
            }
        }

        composeRule.onNodeWithText("2026年7月總支出").assertExists()
        composeRule.onNodeWithText("支出總額").assertExists()
        composeRule.onNodeWithText("收入總額").assertDoesNotExist()
        composeRule.onNodeWithText("結餘").assertDoesNotExist()
        composeRule.onNodeWithText("支出的月度變化").assertExists()
        composeRule.onNodeWithContentDescription("近半年支出趨勢：", substring = true).assertExists()
    }

    private fun presentation() = AnalysisPresentation(
        currency = "TWD",
        month = AnalysisMonth(2026, 7),
        periodLabel = "2026年7月",
        summary = AnalysisSummary(income = 120.0, expense = 40.0),
        incomeCategorySlices = listOf(AnalysisCategorySlice("薪資", 120.0, null)),
        expenseCategorySlices = listOf(AnalysisCategorySlice("餐飲", 40.0, null)),
        trend = (-5..0).map { offset ->
            AnalysisTrendPoint(
                month = AnalysisMonth(2026, 7).plus(offset),
                income = (offset + 6) * 20.0,
                expense = (offset + 6) * 5.0,
            )
        },
    )
}
