package tw.kevinzhang.moneylook.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.moneylook.ui.home.HomeScaffoldLayout
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme
import tw.kevinzhang.moneylook.ui.transactions.CategoryManagementContent
import tw.kevinzhang.moneylook.ui.transactions.CategoryOption
import tw.kevinzhang.moneylook.ui.transactions.TagManagementContent
import tw.kevinzhang.moneylook.ui.transactions.TagOption

@RunWith(AndroidJUnit4::class)
class FabClearanceInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun categoryManagementLastItemCanScrollAboveFab() {
        val categories = (1..20).map { index ->
            CategoryOption(
                id = "category-$index",
                name = "分類 $index",
                color = 0xFF607D8B,
                reportingGroup = CategoryReportingGroup.EXPENSE,
            )
        }
        composeRule.setMoneylookContent {
            CategoryManagementContent(
                categories = categories,
                onNavigateUp = {},
                onSave = { _, _, _, _ -> },
                onDelete = {},
            )
        }

        composeRule.onNodeWithTag("category-management-list")
            .performScrollToIndex(categories.lastIndex)

        assertClearOfFab(
            item = composeRule.onNodeWithTag("category-management-item-${categories.last().id}"),
            fab = composeRule.onNodeWithTag("category-management-fab"),
            message = "分類管理最後一列應可捲動到 FAB 上方",
        )
    }

    @Test
    fun tagManagementLastItemCanScrollAboveFab() {
        val tags = (1..20).map { index ->
            TagOption(id = "tag-$index", name = "標籤 $index", color = 0xFF607D8B)
        }
        composeRule.setMoneylookContent {
            TagManagementContent(
                tags = tags,
                onNavigateUp = {},
                onSave = { _, _, _ -> },
                onDelete = {},
            )
        }

        composeRule.onNodeWithTag("classification-management-list")
            .performScrollToIndex(tags.lastIndex)

        assertClearOfFab(
            item = composeRule.onNodeWithTag("classification-management-item-${tags.last().id}"),
            fab = composeRule.onNodeWithTag("classification-management-fab"),
            message = "標籤管理最後一列應可捲動到 FAB 上方",
        )
    }

    @Test
    fun homeLastCardCanScrollAboveSyncFab() {
        val itemCount = 12
        composeRule.setMoneylookContent {
            HomeScaffoldLayout(
                bottomBar = {
                    Box(modifier = Modifier.fillMaxWidth().height(80.dp))
                },
                onNavigateToMarketplace = {},
                onShowSyncDialog = {},
            ) {
                repeat(itemCount) { index ->
                    item(key = "home-fixture-$index") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp)
                                .testTag("home-fixture-$index"),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("home-content-list")
            .performScrollToIndex(itemCount - 1)

        assertClearOfFab(
            item = composeRule.onNodeWithTag("home-fixture-${itemCount - 1}"),
            fab = composeRule.onNodeWithTag("home-sync-fab"),
            message = "首頁最後一張卡片應可捲動到同步 FAB 上方",
        )
    }

    private fun assertClearOfFab(
        item: SemanticsNodeInteraction,
        fab: SemanticsNodeInteraction,
        message: String,
    ) {
        val itemBottom = item.fetchSemanticsNode().boundsInRoot.bottom
        val fabTop = fab.fetchSemanticsNode().boundsInRoot.top
        assertTrue(message, itemBottom <= fabTop)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.setMoneylookContent(
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false, content = content)
        }
    }
}
