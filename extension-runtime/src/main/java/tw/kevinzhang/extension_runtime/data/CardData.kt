package tw.kevinzhang.extension_runtime.data

/**
 * Per-physical-card metadata returned beneath an aggregated credit-card account.
 * [ref] is result-local only; it must be used by a transfer's [TransferData.cardRef].
 */
data class CardData(
    val ref: String,
    val sourceCardKey: String? = null,
    val pan: String? = null,
    val maskedPan: String? = null,
    val lastFour: String? = null,
    val displayName: String? = null,
    val network: String? = null,
    val productType: String? = null,
    val holderRole: String? = null,
    val holderName: String? = null,
    val status: String? = null,
    val expiryMonth: Int? = null,
    val expiryYear: Int? = null,
    val creditLimit: Double? = null,
    val availableCredit: Double? = null,
) {
    override fun toString(): String = "CardData([REDACTED])"
}
