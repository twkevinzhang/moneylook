package tw.kevinzhang.moneylook.ui.transactions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import java.time.LocalDate
import java.time.YearMonth

class GlobalTransactionsPresentationTest {
    private val today = LocalDate.of(2026, 7, 22)

    @Test
    fun `month controls keep range inside current month`() {
        val june = GlobalDateRange.month(YearMonth.of(2026, 6))
        assertEquals(YearMonth.of(2026, 7), YearMonth.from(moveGlobalMonth(june, 1, today).startInclusive))
        assertEquals(YearMonth.of(2026, 7), YearMonth.from(moveGlobalMonth(GlobalDateRange.thisMonth(today), 1, today).startInclusive))
        val first = GlobalDateRange.month(YearMonth.of(1970, 1))
        assertEquals(first, moveGlobalMonth(first, -1, today))
    }

    @Test
    fun `custom range is inclusive and rejects invalid future end`() {
        val range = customGlobalDateRange(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 2), today)
        assertEquals("2026-06-30", range!!.startKey)
        assertEquals("2026-07-03", range.endExclusiveKey)
        assertNull(customGlobalDateRange(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 2), today))
        assertNull(customGlobalDateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 23), today))
        assertNull(customGlobalDateRange(LocalDate.of(1969, 12, 31), LocalDate.of(1970, 1, 1), today))
    }

    @Test
    fun `date pager labels show full calendar months and unambiguous custom years`() {
        val february = GlobalDateRange.month(YearMonth.of(2024, 2))
        assertEquals("2024", february.tabYearLabel())
        assertEquals("2/1–2/29", february.tabDateRangeLabel())

        val crossYear = GlobalDateRange(
            startInclusive = LocalDate.of(2025, 12, 30),
            endInclusive = LocalDate.of(2026, 1, 2),
            isCustom = true,
        )
        assertEquals("2025–2026", crossYear.tabYearLabel())
        assertEquals("12/30–1/2", crossYear.tabDateRangeLabel())

        val sameYear = GlobalDateRange(
            startInclusive = LocalDate.of(2026, 6, 30),
            endInclusive = LocalDate.of(2026, 7, 2),
            isCustom = true,
        )
        assertEquals("2026", sameYear.tabYearLabel())
        assertEquals("6/30–7/2", sameYear.tabDateRangeLabel())
    }

    @Test
    fun `all currency details and advanced filters work together`() {
        val items = listOf(
            item(id = "food", amount = -120.0, categoryId = "food", categoryName = "餐飲", tags = listOf(GlobalTag("daily", "日常"))),
            item(id = "salary", amount = 50000.0, currency = "USD", description = "Salary", categoryReportingGroup = CategoryReportingGroup.INCOME),
            item(id = "note", amount = -50.0, userNote = "週末咖啡"),
        )
        assertEquals(listOf("food"), filterGlobalTransactions(items, GlobalTransactionsFilter(query = "日常")).map { it.transferId })
        assertEquals(listOf("note"), filterGlobalTransactions(items, GlobalTransactionsFilter(query = "咖啡")).map { it.transferId })
        assertEquals(listOf("food"), filterGlobalTransactions(items, GlobalTransactionsFilter(extensionId = "extension-food")).map { it.transferId })
        assertEquals(listOf("food"), filterGlobalTransactions(items, GlobalTransactionsFilter(categoryId = "food", maximumAmount = "200")).map { it.transferId })
        assertEquals(listOf("salary"), filterGlobalTransactions(items, GlobalTransactionsFilter(currency = "TWD", minimumAmount = "500")).map { it.transferId })
    }

    @Test
    fun `account choices require a source and are limited to its accounts in the current period`() {
        val sourceAFirst = item("a-first", -100.0).copy(
            extensionId = "source-a",
            extensionName = "銀行 A",
            accountId = "a-first",
            accountName = "A 活存",
        )
        val sourceASecond = item("a-second", -200.0).copy(
            extensionId = "source-a",
            extensionName = "銀行 A",
            accountId = "a-second",
            accountName = "A 信用卡",
        )
        val sourceB = item("b-first", -300.0).copy(
            extensionId = "source-b",
            extensionName = "銀行 B",
            accountId = "b-first",
            accountName = "B 活存",
        )
        val state = GlobalTransactionsUiState(
            dateRange = GlobalDateRange.thisMonth(today),
            allItems = listOf(sourceAFirst, sourceASecond, sourceB),
        )

        assertTrue(state.accountsForExtension(null).isEmpty())
        assertEquals(
            setOf("a-first", "a-second"),
            state.accountsForExtension("source-a").map(GlobalChoice::id).toSet(),
        )
        assertEquals(listOf("b-first"), state.accountsForExtension("source-b").map(GlobalChoice::id))
    }

    @Test
    fun `changing or clearing source atomically clears the selected account`() {
        val selected = GlobalTransactionsFilter(extensionId = "source-a", accountId = "a-first")

        assertEquals(
            GlobalTransactionsFilter(extensionId = "source-b"),
            selectGlobalTransactionExtension(selected, "source-b"),
        )
        assertEquals(
            GlobalTransactionsFilter(),
            selectGlobalTransactionExtension(selected, null),
        )
    }

    @Test
    fun `source and account filters apply together after account selection`() {
        val sourceAFirst = item("a-first", -100.0).copy(extensionId = "source-a", accountId = "a-first")
        val sourceASecond = item("a-second", -200.0).copy(extensionId = "source-a", accountId = "a-second")
        val sourceBFirst = item("b-first", -300.0).copy(extensionId = "source-b", accountId = "b-first")

        assertEquals(
            listOf("a-second"),
            filterGlobalTransactions(
                listOf(sourceAFirst, sourceASecond, sourceBFirst),
                GlobalTransactionsFilter(extensionId = "source-a", accountId = "a-second"),
            ).map(GlobalTransactionItem::transferId),
        )
    }

    @Test
    fun `details default and search include every transaction direction across categories`() {
        val items = listOf(
            item("expense", -120.0, description = "咖啡", categoryId = "food", categoryName = "餐飲"),
            item("income", 120.0, description = "咖啡退款", categoryId = "refund", categoryName = "退款", categoryReportingGroup = CategoryReportingGroup.INCOME),
            item("transfer", -120.0, description = "咖啡儲值", categoryId = "move", categoryName = "帳戶移轉", categoryReportingGroup = CategoryReportingGroup.EXCLUDED),
        )

        assertNull(GlobalTransactionsFilter().direction)
        assertEquals(
            setOf("expense", "income", "transfer"),
            filterGlobalTransactions(items, GlobalTransactionsFilter()).map { it.transferId }.toSet(),
        )
        assertEquals(
            setOf("expense", "income", "transfer"),
            filterGlobalTransactions(items, GlobalTransactionsFilter(query = "咖啡")).map { it.transferId }.toSet(),
        )
    }

    @Test
    fun `category details add the selected category while retaining explicit filters including uncategorized`() {
        val items = listOf(
            item("food-expense", -120.0, description = "咖啡", categoryId = "food", categoryName = "餐飲"),
            item("food-income", 120.0, description = "咖啡退款", categoryId = "food", categoryName = "餐飲", categoryReportingGroup = CategoryReportingGroup.INCOME),
            item("travel-expense", -80.0, description = "咖啡", categoryId = "travel", categoryName = "旅遊"),
            item("uncategorized", -60.0, description = "咖啡"),
        )
        val explicitFilter = GlobalTransactionsFilter(
            query = "咖啡",
            direction = GlobalTransactionDirection.EXPENSE,
        )

        assertEquals(
            listOf("food-expense"),
            filterCategoryTransactions(items, explicitFilter, "food").map { it.transferId },
        )
        assertEquals(
            listOf("uncategorized"),
            filterCategoryTransactions(items, explicitFilter, null).map { it.transferId },
        )
        assertEquals(
            listOf("travel-expense"),
            filterCategoryTransactions(
                items,
                explicitFilter.copy(categoryId = "travel"),
                "travel",
            ).map { it.transferId },
        )
    }

    @Test
    fun `direction filters use assigned category reporting group before amount sign`() {
        val items = listOf(
            item("income", 100.0, categoryReportingGroup = CategoryReportingGroup.EXPENSE),
            item("expense", -100.0, categoryReportingGroup = CategoryReportingGroup.INCOME),
            item("excluded", -10.0, categoryReportingGroup = CategoryReportingGroup.EXCLUDED),
            item("uncategorized-positive", 9.0),
            item("uncategorized-negative", -9.0),
            item("zero", 0.0),
        )

        assertEquals(listOf("expense", "uncategorized-positive"), filterGlobalTransactions(items, GlobalTransactionsFilter(direction = GlobalTransactionDirection.INCOME)).map { it.transferId })
        assertEquals(listOf("income", "uncategorized-negative"), filterGlobalTransactions(items, GlobalTransactionsFilter(direction = GlobalTransactionDirection.EXPENSE)).map { it.transferId })
        assertEquals(listOf("excluded"), filterGlobalTransactions(items, GlobalTransactionsFilter(direction = GlobalTransactionDirection.EXCLUDED)).map { it.transferId })
    }

    @Test
    fun `calendar pager spans every month from 1970 through current month`() {
        val pager = globalDateRangePager(GlobalDateRange.month(YearMonth.of(2024, 2)), today)

        assertEquals(679, pager.pageCount)
        assertEquals(649, pager.selectedPage)
        assertEquals(GlobalDateRange.month(YearMonth.of(1970, 1)), pager.rangeAt(0))
        assertEquals(GlobalDateRange.month(YearMonth.of(2026, 7)), pager.rangeAt(pager.pageCount - 1))

        val currentMonthPager = globalDateRangePager(GlobalDateRange.thisMonth(today), today)
        assertEquals(currentMonthPager.pageCount - 1, currentMonthPager.selectedPage)
    }

    @Test
    fun `custom pager anchors inclusive ranges and neighbours by their day count`() {
        val selected = GlobalDateRange(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3), isCustom = true)
        val pager = globalDateRangePager(selected, LocalDate.of(2026, 3, 8))

        assertEquals(selected, pager.rangeAt(pager.selectedPage))
        assertEquals(range("2026-02-26", "2026-02-28"), pager.rangeAt(pager.selectedPage - 1))
        assertEquals(range("2026-03-04", "2026-03-06"), pager.rangeAt(pager.selectedPage + 1))
        assertEquals(range("2026-03-07", "2026-03-08"), pager.rangeAt(pager.pageCount - 1))
    }

    @Test
    fun `custom pager clips only the 1970 and today boundary pages including leap days`() {
        val selected = GlobalDateRange(LocalDate.of(1970, 1, 2), LocalDate.of(1970, 1, 4), isCustom = true)
        val pager = globalDateRangePager(selected, LocalDate.of(1970, 1, 10))

        assertEquals(4, pager.pageCount)
        assertEquals(1, pager.selectedPage)
        assertEquals(range("1970-01-01", "1970-01-01"), pager.rangeAt(0))
        assertEquals(range("1970-01-08", "1970-01-10"), pager.rangeAt(pager.pageCount - 1))

        val leapSelected = GlobalDateRange(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 2, 29), isCustom = true)
        val leapPager = globalDateRangePager(leapSelected, LocalDate.of(2024, 3, 3))
        assertEquals(range("2024-03-01", "2024-03-02"), leapPager.rangeAt(leapPager.selectedPage + 1))
    }

    @Test
    fun `excluded category and zero values are excluded from reports`() {
        val items = listOf(
            item("expense", -150.0, categoryId = "food", categoryName = "餐飲"),
            item("income", 400.0, categoryId = "salary", categoryName = "薪資", categoryReportingGroup = CategoryReportingGroup.INCOME),
            item("transfer", -999.0, categoryId = "move", categoryName = "帳戶移轉", categoryReportingGroup = CategoryReportingGroup.EXCLUDED),
            item("zero", 0.0),
        )
        assertEquals(GlobalTransactionDirection.EXCLUDED, globalTransactionDirection(items[2]))
        assertNull(globalTransactionDirection(items[3]))
        assertEquals(GlobalTransactionsSummary(400.0, 150.0), globalTransactionsSummary(items))
    }

    @Test
    fun `reports use converted TWD amounts and identify currencies without rates`() {
        val items = listOf(
            item("usd-income", 3.0, currency = "USD").copy(amountTwd = 100.0),
            item("jpy-expense", -500.0, currency = "JPY").copy(amountTwd = -110.0),
            item("eur-missing", 20.0, currency = "eur"),
            item("twd", -30.0),
        )

        assertEquals(GlobalTransactionsSummary(income = 100.0, expense = 140.0), globalTransactionsSummary(items))
        assertEquals(listOf("EUR"), missingExchangeCurrencies(items))
    }

    @Test
    fun `only exact credit card posted and pending statuses receive a display status`() {
        assertEquals(
            GlobalCreditCardTransactionStatus.POSTED,
            globalCreditCardTransactionStatus(item("posted", -100.0).copy(
                accountKind = AssetKind.CREDIT_CARD,
                status = "posted",
            )),
        )
        assertEquals(
            GlobalCreditCardTransactionStatus.PENDING,
            globalCreditCardTransactionStatus(item("pending", -100.0).copy(
                accountKind = AssetKind.CREDIT_CARD,
                status = "pending",
            )),
        )
        assertNull(globalCreditCardTransactionStatus(item("unknown", -100.0).copy(
            accountKind = AssetKind.CREDIT_CARD,
            status = "POSTED",
        )))
        assertNull(globalCreditCardTransactionStatus(item("deposit", -100.0).copy(
            accountKind = AssetKind.DEPOSIT,
            status = "pending",
        )))
    }

    @Test
    fun `pending credit card items remain in details but are excluded from every report source`() {
        val posted = item("posted", -100.0, categoryId = "food", categoryName = "餐飲").copy(
            accountKind = AssetKind.CREDIT_CARD,
            status = "posted",
        )
        val pending = item("pending", -50.0, currency = "EUR", categoryId = "food", categoryName = "餐飲").copy(
            accountKind = AssetKind.CREDIT_CARD,
            status = "pending",
        )

        assertEquals(listOf("posted", "pending"), filterGlobalTransactions(listOf(posted, pending), GlobalTransactionsFilter()).map { it.transferId })
        assertEquals(listOf("posted"), globalReportableTransactions(listOf(posted, pending)).map { it.transferId })
        assertEquals(GlobalTransactionsSummary(expense = 100.0), globalTransactionsSummary(listOf(posted, pending)))
        assertEquals(1, globalCategorySummaries(listOf(posted, pending), GlobalTransactionDirection.EXPENSE).single().transactionCount)
        assertTrue(missingExchangeCurrencies(listOf(posted, pending)).isEmpty())
    }

    @Test
    fun `amount tone is muted only for non-reporting rows`() {
        assertEquals(
            GlobalTransactionAmountTone.MUTED,
            globalTransactionAmountTone(item("transfer", -100.0, categoryReportingGroup = CategoryReportingGroup.EXCLUDED)),
        )
        assertEquals(GlobalTransactionAmountTone.MUTED, globalTransactionAmountTone(item("zero", 0.0)))
        assertEquals(GlobalTransactionAmountTone.MUTED, globalTransactionAmountTone(item("missing-rate", 10.0, currency = "USD")))
        assertEquals(
            GlobalTransactionAmountTone.POSITIVE,
            globalTransactionAmountTone(item("converted-income", 3.0, currency = "USD").copy(amountTwd = 100.0)),
        )
        assertEquals(
            GlobalTransactionAmountTone.NEGATIVE,
            globalTransactionAmountTone(item("converted-expense", -500.0, currency = "JPY").copy(amountTwd = -110.0)),
        )
    }

    @Test
    fun `category share uses report direction and unclassified fallback`() {
        val items = listOf(
            item("food-a", -70.0, categoryId = "food", categoryName = "餐飲"),
            item("food-b", -30.0, categoryId = "food", categoryName = "餐飲"),
            item("none", -100.0),
            item("move", -100.0, categoryId = "move", categoryReportingGroup = CategoryReportingGroup.EXCLUDED),
        )
        val categories = globalCategorySummaries(items, GlobalTransactionDirection.EXPENSE)
        assertEquals(2, categories.size)
        assertEquals("餐飲", categories.first().name)
        assertEquals(100.0, categories.first().amount, 0.001)
        assertEquals(0.5f, categories.first().percentage, 0.001f)
        assertTrue(globalCategorySummaries(items, GlobalTransactionDirection.EXCLUDED).isEmpty())
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
        categoryReportingGroup: CategoryReportingGroup? = if (categoryId != null) CategoryReportingGroup.EXPENSE else null,
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
        categoryReportingGroup = categoryReportingGroup,
        categoryEmoji = null,
        categoryColor = null,
        tags = tags,
        accountId = "account-$id",
        accountName = "測試帳戶",
        extensionId = "extension-$id",
        extensionName = "測試銀行",
        currency = currency,
    )

    private fun range(start: String, end: String) = GlobalDateRange(
        startInclusive = LocalDate.parse(start),
        endInclusive = LocalDate.parse(end),
        isCustom = true,
    )
}
