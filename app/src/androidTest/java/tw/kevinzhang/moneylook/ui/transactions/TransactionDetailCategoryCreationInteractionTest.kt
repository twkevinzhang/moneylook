package tw.kevinzhang.moneylook.ui.transactions

import androidx.room.Room
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme

class TransactionDetailCategoryCreationInteractionTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var database: MoneylookDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun newCategoryUsesAllowedGroupAndBecomesSelected() {
        var state by mutableStateOf(detailState())
        var submittedName: String? = null
        var submittedGroup: CategoryReportingGroup? = null
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                TransactionDetailContent(
                    state = state,
                    onNavigateUp = {},
                    onSave = {},
                    onCreateCategory = { name, color, group, onResult ->
                        submittedName = name
                        submittedGroup = group
                        val result = runBlocking {
                            persistNewCategory(
                                categoryDao = database.categoryDao(),
                                name = name,
                                color = color,
                                reportingGroup = group,
                                idFactory = { "commute" },
                            )
                        }
                        if (result is CategoryCreationResult.Created) {
                            state = state.copy(categories = listOf(result.category))
                        }
                        onResult(result)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("更改分類").performClick()
        composeRule.onNodeWithText("不統計").performClick()
        composeRule.onNodeWithText("新增分類").performClick()
        composeRule.onNodeWithTag("category-editor-group-EXPENSE").assertExists()
        composeRule.onNodeWithTag("category-editor-group-EXCLUDED").assertExists()
        composeRule.onNodeWithTag("category-editor-group-INCOME").assertDoesNotExist()
        composeRule.onNodeWithTag("category-editor-save").assertIsNotEnabled()
        composeRule.onNodeWithText("名稱").performTextInput("通勤")
        composeRule.onNodeWithTag("category-editor-save").performClick()

        composeRule.runOnIdle {
            assertEquals("通勤", submittedName)
            assertEquals(CategoryReportingGroup.EXCLUDED, submittedGroup)
        }
        composeRule.onNodeWithText("更改分類").assertExists()
        composeRule.onNodeWithTag("category-tile-commute").assertIsSelected()
        assertEquals("通勤", runBlocking { database.categoryDao().getById("commute")?.name })
    }

    @Test
    fun duplicateNameKeepsEditorOpenAndShowsError() {
        runBlocking {
            database.categoryDao().upsert(
                Category(
                    id = "existing",
                    name = "餐飲",
                    color = "#607D8B",
                    reportingGroup = CategoryReportingGroup.EXPENSE,
                ),
            )
        }
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                TransactionDetailContent(
                    state = detailState(),
                    onNavigateUp = {},
                    onSave = {},
                    onCreateCategory = { name, color, group, onResult ->
                        onResult(
                            runBlocking {
                                persistNewCategory(database.categoryDao(), name, color, group)
                            },
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("更改分類").performClick()
        composeRule.onNodeWithText("新增分類").performClick()
        composeRule.onNodeWithText("名稱").performTextInput("餐飲")
        composeRule.onNodeWithTag("category-editor-save").performClick()

        composeRule.onNodeWithTag("category-editor-save").assertExists()
        composeRule.onNodeWithText("分類名稱已存在，請使用其他名稱。").assertExists()
    }

    private fun detailState() = TransactionDetailUiState(
        title = "虛構商店",
        amountText = "TWD -100.00",
        amount = -100.0,
        accountName = "測試帳戶",
        transactionDate = "2026-08-01",
        postingDate = null,
        description = "虛構商店",
        bankMemo = null,
        selectedCategoryId = null,
        selectedTagIds = emptySet(),
        userNote = "",
        categories = emptyList(),
        tags = emptyList(),
        accountKind = AssetKind.DEPOSIT,
    )
}
