package tw.kevinzhang.extension_runtime.data

data class TransferData(
    val txnDateTime: String,
    val description: String,
    val amount: Double,   // positive = income, negative = expend
    val balance: Double?,
    val memo: String,
    val type: String? = null,
    val status: String? = null,
    /** Bank-provided immutable transaction identifier, when the source exposes one. */
    val id: String? = null,
    /** Bank-provided posting / settlement date-time, when distinct from the transaction date. */
    val postingDateTime: String? = null,
    /** Result-local [CardData.ref], never a PAN or a persisted identifier. */
    val cardRef: String? = null,
    /** Merchant display name supplied by the source, when distinct from the bank description. */
    val merchantName: String? = null,
    /** ISO 18245 four-digit merchant category code, when supplied by the source. */
    val merchantCategoryCode: String? = null,
    /** Structured counterparty name supplied by the source. */
    val counterpartyName: String? = null,
    /** Structured transaction purpose supplied by the source. */
    val purpose: String? = null,
)
