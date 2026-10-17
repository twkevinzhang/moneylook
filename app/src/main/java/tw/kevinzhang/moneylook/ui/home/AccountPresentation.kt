package tw.kevinzhang.moneylook.ui.home

import tw.kevinzhang.core.data.model.AssetKind
import java.util.Locale
import kotlin.math.abs

/** UI-ready account values; only deposit accounts expose their supplied account number. */
data class AccountRowPresentation(
    val primaryAmount: String,
    val supportingText: String?,
    val isLiability: Boolean,
    val accountNumberText: String?,
)

fun accountRowPresentation(
    kind: AssetKind,
    balance: Double,
    currency: String,
    availableCredit: Double?,
    accountNo: String? = null,
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
        accountNumberText = if (kind == AssetKind.DEPOSIT || kind == AssetKind.TIME_DEPOSIT) {
            accountNo?.trim()?.takeIf(String::isNotEmpty)?.let { "帳號 $it" } ?: "尚未取得帳號"
        } else null,
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
