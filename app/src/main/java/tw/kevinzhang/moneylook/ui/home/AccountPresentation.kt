package tw.kevinzhang.moneylook.ui.home

import tw.kevinzhang.core.data.model.AssetKind
import java.util.Locale
import kotlin.math.abs

/**
 * UI-ready values for an account row. The account number is deliberately not
 * represented here, so it cannot accidentally be rendered on the Home screen.
 */
data class AccountRowPresentation(
    val primaryAmount: String,
    val supportingText: String?,
    val isLiability: Boolean,
)

fun accountRowPresentation(
    kind: AssetKind,
    balance: Double,
    currency: String,
    availableCredit: Double?,
): AccountRowPresentation {
    val isLiability = kind == AssetKind.CREDIT_CARD || kind == AssetKind.LOAN
    val primaryAmount = if (isLiability && balance > 0) {
        "-${formatCurrencyAmount(abs(balance), currency)}"
    } else {
        formatCurrencyAmount(balance, currency)
    }

    val supportingText = availableCredit
        ?.takeIf { kind == AssetKind.CREDIT_CARD }
        ?.let { "可用額度 ${formatCurrencyAmount(it, currency)}" }

    return AccountRowPresentation(
        primaryAmount = primaryAmount,
        supportingText = supportingText,
        isLiability = isLiability,
    )
}

fun formatCurrencyAmount(amount: Double, currency: String): String {
    val normalizedCurrency = currency.trim().uppercase(Locale.ROOT)
    return if (normalizedCurrency == "TWD") {
        "$ ${String.format(Locale.US, "%,.0f", amount)}"
    } else {
        "$normalizedCurrency ${String.format(Locale.US, "%.2f", amount)}"
    }
}
