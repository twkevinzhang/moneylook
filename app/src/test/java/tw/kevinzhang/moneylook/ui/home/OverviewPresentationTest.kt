package tw.kevinzhang.moneylook.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind

class OverviewPresentationTest {
    @Test
    fun groupsAssetsAndLiabilitiesByCurrencyWithoutConversion() {
        val overview = homeOverviewPresentation(
            accounts = listOf(
                account("deposit-twd", 20_000.0, "twd", AssetKind.DEPOSIT),
                account("term-twd", 5_000.0, "TWD", AssetKind.TIME_DEPOSIT),
                account("card-twd", 2_000.0, "TWD", AssetKind.CREDIT_CARD),
                account("deposit-usd", 100.0, "USD", AssetKind.DEPOSIT),
                account("loan-usd", 40.0, "usd", AssetKind.LOAN),
            ),
            hasPartialData = false,
        )

        assertEquals(listOf("TWD", "USD"), overview.currencies.map { it.currency })
        assertEquals(25_000.0, overview.currencies[0].assets, 0.0)
        assertEquals(2_000.0, overview.currencies[0].liabilities, 0.0)
        assertEquals(23_000.0, overview.currencies[0].netWorth, 0.0)
        assertEquals(100.0, overview.currencies[1].assets, 0.0)
        assertEquals(40.0, overview.currencies[1].liabilities, 0.0)
        assertEquals(60.0, overview.currencies[1].netWorth, 0.0)
    }

    @Test
    fun onlyPositiveCardAndLoanBalancesBecomeLiabilities() {
        val overview = homeOverviewPresentation(
            accounts = listOf(
                account("card-credit", -500.0, "TWD", AssetKind.CREDIT_CARD),
                account("loan", 1_500.0, "TWD", AssetKind.LOAN),
            ),
            hasPartialData = true,
        )

        assertEquals(0.0, overview.currencies.single().assets, 0.0)
        assertEquals(1_500.0, overview.currencies.single().liabilities, 0.0)
        assertTrue(overview.hasPartialData)
    }

    @Test
    fun negativeDepositReducesAssetsWithoutBecomingALiability() {
        val overview = homeOverviewPresentation(
            accounts = listOf(
                account("deposit", 1_000.0, "TWD", AssetKind.DEPOSIT),
                account("overdrawn", -200.0, "TWD", AssetKind.DEPOSIT),
                account("card", 100.0, "TWD", AssetKind.CREDIT_CARD),
            ),
            hasPartialData = false,
        )

        val twd = overview.currencies.single()
        assertEquals(800.0, twd.assets, 0.0)
        assertEquals(100.0, twd.liabilities, 0.0)
        assertEquals(700.0, twd.netWorth, 0.0)
    }

    @Test
    fun emptyOrInvalidAccountsShowNoOverviewBalance() {
        val overview = homeOverviewPresentation(
            accounts = listOf(
                account("nan", Double.NaN, "TWD", AssetKind.DEPOSIT),
                account("infinite", Double.POSITIVE_INFINITY, "TWD", AssetKind.DEPOSIT),
                account("blank-currency", 1_000.0, " ", AssetKind.DEPOSIT),
            ),
            hasPartialData = false,
        )

        assertFalse(overview.hasAccounts)
        assertTrue(overview.currencies.isEmpty())
    }

    @Test
    fun formatsAmountsWithoutMasking() {
        assertEquals("$ 10,000", formatCurrencyAmount(10_000.0, "TWD"))
        assertEquals("USD 10.00", formatCurrencyAmount(10.0, " usd "))
    }

    private fun account(id: String, balance: Double, currency: String, kind: AssetKind) = Account(
        id = id,
        extensionId = "extension",
        extensionName = "銀行",
        accountName = id,
        balance = balance,
        currency = currency,
        lastSyncAt = 0L,
        kind = kind,
    )
}
