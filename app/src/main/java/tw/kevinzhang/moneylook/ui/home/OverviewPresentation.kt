package tw.kevinzhang.moneylook.ui.home

import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import java.util.Locale

/**
 * A per-currency overview deliberately avoids converting currencies.  The app
 * has no exchange-rate source, so combining TWD and foreign balances would be
 * misleading.
 */
data class CurrencyOverviewPresentation(
    val currency: String,
    val assets: Double,
    val liabilities: Double,
) {
    val netWorth: Double get() = assets - liabilities
}

data class HomeOverviewPresentation(
    val currencies: List<CurrencyOverviewPresentation>,
    val hasPartialData: Boolean,
) {
    val hasAccounts: Boolean get() = currencies.isNotEmpty()
}

enum class OverviewSection { ASSETS, LIABILITIES }

fun homeOverviewPresentation(
    accounts: List<Account>,
    hasPartialData: Boolean,
): HomeOverviewPresentation {
    val currencyGroups = accounts
        .asSequence()
        .filter { account -> account.balance.isFinite() }
        .groupBy { account -> account.currency.trim().uppercase(Locale.ROOT) }
        .filterKeys(String::isNotBlank)

    val currencies = currencyGroups
        .map { (currency, currencyAccounts) ->
            CurrencyOverviewPresentation(
                currency = currency,
                assets = currencyAccounts
                    .filterNot { it.kind.isLiability() }
                    .sumOf(Account::balance),
                liabilities = currencyAccounts
                    .filter { it.kind.isLiability() }
                    .sumOf { account -> account.balance.coerceAtLeast(0.0) },
            )
        }
        .sortedWith(compareBy<CurrencyOverviewPresentation> { it.currency != "TWD" }.thenBy { it.currency })

    return HomeOverviewPresentation(
        currencies = currencies,
        hasPartialData = hasPartialData,
    )
}

fun formatVisibleCurrencyAmount(
    amount: Double,
    currency: String,
    isAmountVisible: Boolean,
): String = if (isAmountVisible) {
    formatCurrencyAmount(amount, currency)
} else {
    formatHiddenCurrencyAmount(currency)
}

fun formatHiddenCurrencyAmount(currency: String): String {
    val normalizedCurrency = currency.trim().uppercase(Locale.ROOT)
    return if (normalizedCurrency == "TWD") "••••" else "$normalizedCurrency ••••"
}

private fun AssetKind.isLiability(): Boolean = this == AssetKind.CREDIT_CARD || this == AssetKind.LOAN
