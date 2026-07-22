package tw.kevinzhang.moneylook.ui.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.CategoryKind
import java.time.LocalDate
import java.time.YearMonth

class GlobalTransactionsPresentationTest {
    private val today = LocalDate.of(2026, 7, 22)

    @Test
    fun `month controls keep range inside current month`() {
        val june = GlobalDateRange.month(YearMonth.of(2026, 6))
        assertEquals(YearMonth.of(2026, 7), YearMonth.from(moveGlobalMonth(june, 1, today).startInclusive))
        assertEquals(YearMonth.of(2026, 7), YearMonth.from(moveGlobalMonth(GlobalDateRange.thisMonth(today), 1, today).startInclusive))
    }

    @Test
    fun `custom range is inclusive and rejects invalid future end`() {
        val range = customGlobalDateRange(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 2), today)
        assertEquals("2026-06-30", range!!.startKey)
        assertEquals("2026-07-03", range.endExclusiveKey)
        assertNull(customGlobalDateRange(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 2), today))
        assertNull(customGlobalDateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 23), today))
    }

    @Test
    fun `currency search and advanced filters work together`() {
        val items = listOf(
            item(id = "food", amount = -120.0, categoryId = "food", categoryName = "餐飲", tags = listOf(GlobalTag("daily", "日常"))),
            item(id = "salary", amount = 50000.0, currency = "USD", description = "Salary", categoryKind = CategoryKind.INCOME),
            item(id = "note", amount = -50.0, userNote = "週末咖啡"),
        )
        assertEquals(listOf("food"), filterGlobalTransactions(items, GlobalTransactionsFilter(query = "日常")).map { it.transferId })
        assertEquals(listOf("note"), filterGlobalTransactions(items, GlobalTransactionsFilter(query = "咖啡")).map { it.transferId })
        assertEquals(listOf("food"), filterGlobalTransactions(items, GlobalTransactionsFilter(extensionId = "extension-food")).map { it.transferId })
        assertEquals(listOf("food"), filterGlobalTransactions(items, GlobalTransactionsFilter(categoryId = "food", maximumAmount = "200")).map { it.transferId })
        assertTrue(filterGlobalTransactions(items, GlobalTransactionsFilter(currency = "TWD", minimumAmount = "500")).isEmpty())
    }

    @Test
    fun `transfer category and zero values are excluded from reports`() {
        val items = listOf(
            item("expense", -150.0, categoryId = "food", categoryName = "餐飲"),
            item("income", 400.0, categoryId = "salary", categoryName = "薪資", categoryKind = CategoryKind.INCOME),
            item("transfer", -999.0, categoryId = "move", categoryName = "帳戶移轉", categoryKind = CategoryKind.TRANSFER),
            item("zero", 0.0),
        )
        assertEquals(GlobalTransactionDirection.TRANSFER, globalTransactionDirection(items[2]))
        assertNull(globalTransactionDirection(items[3]))
        assertEquals(GlobalTransactionsSummary(400.0, 150.0), globalTransactionsSummary(items))
    }

    @Test
    fun `category share uses report direction and unclassified fallback`() {
        val items = listOf(
            item("food-a", -70.0, categoryId = "food", categoryName = "餐飲"),
            item("food-b", -30.0, categoryId = "food", categoryName = "餐飲"),
            item("none", -100.0),
            item("move", -100.0, categoryId = "move", categoryKind = CategoryKind.TRANSFER),
        )
        val categories = globalCategorySummaries(items, GlobalTransactionDirection.EXPENSE)
        assertEquals(2, categories.size)
        assertEquals("餐飲", categories.first().name)
        assertEquals(100.0, categories.first().amount, 0.001)
        assertEquals(0.5f, categories.first().percentage, 0.001f)
        assertTrue(globalCategorySummaries(items, GlobalTransactionDirection.TRANSFER).isEmpty())
    }

    @Test
    fun `empty sources remain empty`() {
        assertTrue(filterGlobalTransactions(emptyList(), GlobalTransactionsFilter()).isEmpty())
        assertTrue(globalCategorySummaries(emptyList(), GlobalTransactionDirection.EXPENSE).isEmpty())
        assertEquals(GlobalTransactionsSummary(), globalTransactionsSummary(emptyList()))
    }

    private fun item(
        id: String,
        amount: Double,
        currency: String = "TWD",
        description: String = "交易",
        userNote: String = "",
        categoryId: String? = null,
        categoryName: String? = null,
        categoryKind: CategoryKind? = if (categoryId != null) CategoryKind.EXPENSE else null,
        tags: List<GlobalTag> = emptyList(),
    ) = GlobalTransactionItem(
        transferId = id,
        transactionDateTime = "2026-07-21",
        description = description,
        memo = "",
        amount = amount,
        userNote = userNote,
        categoryId = categoryId,
        categoryName = categoryName,
        categoryKind = categoryKind,
        categoryEmoji = null,
        categoryColor = null,
        tags = tags,
        accountId = "account-$id",
        accountName = "測試帳戶",
        extensionId = "extension-$id",
        extensionName = "測試銀行",
        currency = currency,
    )
}
