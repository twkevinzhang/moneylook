package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A physical card belonging to an aggregated credit-card account. */
@Entity(
    tableName = "credit_card_instruments",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["extensionId", "sourceCardKey"]),
        Index(value = ["extensionId", "panFingerprint"]),
    ],
)
data class CreditCardInstrument(
    @PrimaryKey val id: String,
    val accountId: String,
    val extensionId: String,
    /** Opaque SHA-256 source identity; never a card number. */
    val sourceCardKey: String? = null,
    /** AES/GCM ciphertext. Plaintext PANs must never enter Room. */
    val panCiphertext: ByteArray? = null,
    /** AES/GCM IV paired with [panCiphertext]. */
    val panIv: ByteArray? = null,
    /** Stable keyed fingerprint used only for local identity matching. */
    val panFingerprint: String? = null,
    val maskedPan: String? = null,
    val lastFour: String? = null,
    val displayName: String? = null,
    val network: String? = null,
    val productType: String? = null,
    /** `primary` or `supplementary` when provided by the bank. */
    val holderRole: String? = null,
    val holderName: String? = null,
    val status: String? = null,
    val expiryMonth: Int? = null,
    val expiryYear: Int? = null,
    val creditLimit: Double? = null,
    val availableCredit: Double? = null,
    val sourceRecordJson: String? = null,
    val sourceFieldsJson: String? = null,
    val sourceFactsJson: String? = null,
    val parserVersion: String? = null,
) {
    override fun toString(): String = "CreditCardInstrument(id=$id, sensitiveFields=[REDACTED])"
}
