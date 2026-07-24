package tw.kevinzhang.moneylook.ui.home

import tw.kevinzhang.core.data.db.CreditCardInstrumentMetadata

/**
 * Redaction-safe card metadata crossing the data-to-Compose boundary.
 *
 * A full PAN deliberately has no representation here. The separate, short-lived reveal flow
 * owns that sensitive value and never stores it in a ViewModel or this presentation model.
 */
data class CreditCardDisplay(
    val id: String,
    val displayName: String?,
    val maskedPan: String?,
    val lastFour: String?,
    val network: String?,
    val productType: String?,
    val holderRole: String?,
    val holderName: String?,
    val status: String?,
    val expiryMonth: Int?,
    val expiryYear: Int?,
    val creditLimit: Double?,
    val availableCredit: Double?,
    val canRevealPan: Boolean = false,
)

internal fun CreditCardDisplay.title(): String =
    displayName?.trim()?.takeIf(String::isNotBlank) ?: "信用卡"

/** Never derive a display number from a PAN. Only bank-provided masking or last four are usable. */
internal fun CreditCardDisplay.numberLabel(): String? =
    maskedPan?.trim()?.takeIf(String::isNotBlank)
        ?: lastFour?.trim()?.takeIf { it.matches(Regex("\\d{4}")) }?.let { "•••• $it" }

internal fun CreditCardDisplay.metadataLabel(): String? = listOfNotNull(
    network?.trim()?.takeIf(String::isNotBlank),
    productType?.trim()?.takeIf(String::isNotBlank),
    holderRole?.trim()?.takeIf(String::isNotBlank)?.let(::localizedHolderRole),
    status?.trim()?.takeIf(String::isNotBlank),
).joinToString(" · ").takeIf(String::isNotBlank)

internal fun CreditCardDisplay.expiryLabel(): String? =
    expiryMonth?.takeIf { it in 1..12 }?.let { month ->
        expiryYear?.takeIf { it in 2000..9999 }?.let { year -> "有效期限 ${year}/${month.toString().padStart(2, '0')}" }
    }

internal fun localizedHolderRole(value: String): String = when (value.lowercase()) {
    "primary" -> "正卡"
    "supplementary" -> "附卡"
    else -> value
}

/** Home intentionally contains only a quantity, not any card identifier or metadata. */
internal fun creditCardCountLabel(count: Int): String? =
    count.takeIf { it > 0 }?.let { "${it} 張卡" }

internal fun CreditCardInstrumentMetadata.toDisplay() = CreditCardDisplay(
    id = id,
    displayName = displayName,
    maskedPan = maskedPan,
    lastFour = lastFour,
    network = network,
    productType = productType,
    holderRole = holderRole,
    holderName = holderName,
    status = status,
    expiryMonth = expiryMonth,
    expiryYear = expiryYear,
    creditLimit = creditLimit,
    availableCredit = availableCredit,
    canRevealPan = canRevealPan,
)
