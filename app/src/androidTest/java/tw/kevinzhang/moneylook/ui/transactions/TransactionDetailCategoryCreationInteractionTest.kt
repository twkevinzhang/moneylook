package tw.kevinzhang.moneylook.ui.transactions

import androidx.room.Room
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
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
        var navigateUpCount = 0
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                TransactionDetailContent(
                    state = state,
                    onNavigateUp = { navigateUpCount += 1 },
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

        composeRule.onNodeWithText("建立全域分類？").assertExists()
        composeRule.onNodeWithText("這個分類建立後會顯示在所有交易中。即使取消目前交易的變更，分類仍會保留。").assertExists()
        composeRule.runOnIdle {
            assertEquals(null, submittedName)
            assertEquals(null, runBlocking { database.categoryDao().getById("commute") })
        }
        composeRule.onNodeWithText("建立分類").performClick()

        composeRule.runOnIdle {
            assertEquals("通勤", submittedName)
            assertEquals(CategoryReportingGroup.EXCLUDED, submittedGroup)
        }
        composeRule.onNodeWithText("更改分類").assertDoesNotExist()
        composeRule.onNodeWithText("通勤").assertExists()
        assertEquals("通勤", runBlocking { database.categoryDao().getById("commute")?.name })

        composeRule.onNodeWithText("取消").performClick()
        composeRule.onNodeWithText("放棄變更").performClick()
        composeRule.runOnIdle { assertEquals(1, navigateUpCount) }
        assertEquals("通勤", runBlocking { database.categoryDao().getById("commute")?.name })
    }

    @Test
    fun returningFromGlobalCategoryConfirmationKeepsEditorAndDoesNotCreateCategory() {
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                TransactionDetailContent(
                    state = detailState(),
                    onNavigateUp = {},
                    onSave = {},
                    onCreateCategory = { name, color, group, onResult ->
                        onResult(
                            runBlocking {
                                persistNewCategory(
                                    categoryDao = database.categoryDao(),
                                    name = name,
                                    color = color,
                                    reportingGroup = group,
                                    idFactory = { "not-created" },
                                )
                            },
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("更改分類").performClick()
        composeRule.onNodeWithText("新增分類").performClick()
        composeRule.onNodeWithText("名稱").performTextInput("暫不建立")
        composeRule.onNodeWithTag("category-editor-save").performClick()
        composeRule.onNodeWithText("建立全域分類？").assertExists()
        composeRule.onNodeWithText("返回編輯").performClick()

        composeRule.onNodeWithTag("category-editor-save").assertExists()
        composeRule.onNodeWithText("暫不建立").assertExists()
        assertEquals(null, runBlocking { database.categoryDao().getById("not-created") })
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
        composeRule.onNodeWithText("建立分類").performClick()

        composeRule.onNodeWithTag("category-editor-save").assertExists()
        composeRule.onNodeWithText("分類名稱已存在，請使用其他名稱。").assertExists()
    }

    @Test
    fun selectingExistingOrUncategorizedClosesCategorySheet() {
        val food = CategoryOption(
            id = "food",
            name = "餐飲",
            color = 0xFF607D8B,
            reportingGroup = CategoryReportingGroup.EXPENSE,
        )
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                TransactionDetailContent(
                    state = detailState(categories = listOf(food)),
                    onNavigateUp = {},
                    onSave = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("更改分類").performClick()
        composeRule.onNodeWithTag("category-tile-food").performClick()
        composeRule.onNodeWithText("更改分類").assertDoesNotExist()
        composeRule.onNodeWithText("餐飲").assertExists()

        composeRule.onNodeWithContentDescription("更改分類").performClick()
        composeRule.onNodeWithTag("category-tile-uncategorized").performClick()
        composeRule.onNodeWithText("更改分類").assertDoesNotExist()
        composeRule.onNodeWithText("尚未分類").assertExists()
    }

    @Test
    fun applyingToPastAndFutureRequiresConfirmationBeforeSaving() {
        val food = CategoryOption(
            id = "food",
            name = "餐飲",
            color = 0xFF607D8B,
            reportingGroup = CategoryReportingGroup.EXPENSE,
        )
        var savedDraft: TransactionDetailDraft? = null
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                TransactionDetailContent(
                    state = detailState(categories = listOf(food)),
                    onNavigateUp = {},
                    onSave = { savedDraft = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("更改分類").performClick()
        composeRule.onNodeWithText("套用過去及未來的相同明細").performClick()
        composeRule.onNodeWithTag("category-tile-food").performClick()
        composeRule.onNodeWithText("儲存").performClick()

        composeRule.onNodeWithText("套用到過去及未來的交易？").assertExists()
        composeRule.runOnIdle { assertEquals(null, savedDraft) }
        composeRule.onNodeWithText("返回編輯").performClick()
        composeRule.runOnIdle { assertEquals(null, savedDraft) }

        composeRule.onNodeWithText("儲存").performClick()
        composeRule.onNodeWithText("確認儲存").performClick()
        composeRule.runOnIdle {
            assertEquals("food", savedDraft?.categoryId)
            assertEquals("food", savedDraft?.matchingRule?.categoryId)
        }
    }

    @Test
    fun currentOnlySelectionSavesWithoutRuleConfirmation() {
        val food = CategoryOption(
            id = "food",
            name = "餐飲",
            color = 0xFF607D8B,
            reportingGroup = CategoryReportingGroup.EXPENSE,
        )
        var savedDraft: TransactionDetailDraft? = null
        composeRule.setContent {
            MoneylookTheme(darkTheme = false, dynamicColor = false) {
                TransactionDetailContent(
                    state = detailState(categories = listOf(food)),
                    onNavigateUp = {},
                    onSave = { savedDraft = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("更改分類").performClick()
        composeRule.onNodeWithTag("category-tile-food").performClick()
        composeRule.onNodeWithText("儲存").performClick()

        composeRule.onNodeWithText("套用到過去及未來的交易？").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals("food", savedDraft?.categoryId)
            assertEquals(null, savedDraft?.matchingRule)
        }
    }

    private fun detailState(
        categories: List<CategoryOption> = emptyList(),
    ) = TransactionDetailUiState(
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
        categories = categories,
        tags = emptyList(),
        accountKind = AssetKind.DEPOSIT,
    )
}
