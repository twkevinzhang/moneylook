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
    val authorizationDateTime: String? = null,
    val valueDateTime: String? = null,
    val referenceNumber: String? = null,
    val authorizationCode: String? = null,
    val channel: String? = null,
    val direction: String? = null,
    val transactionCode: String? = null,
    val originalAmount: Double? = null,
    val originalCurrency: String? = null,
    val settlementAmount: Double? = null,
    val settlementCurrency: String? = null,
    val exchangeRate: Double? = null,
    val feeAmount: Double? = null,
    val feeCurrency: String? = null,
    val taxAmount: Double? = null,
    val taxCurrency: String? = null,
    val merchantLocation: String? = null,
    val counterpartyAccount: String? = null,
    val counterpartyBank: String? = null,
    val installmentNumber: Int? = null,
    val installmentTotal: Int? = null,
    val isRefund: Boolean? = null,
    val isReversal: Boolean? = null,
    val originalTransactionSourceId: String? = null,
    /** Extension-provided raw source record, per-field locators, and unmapped source facts. */
    val sourceRecord: Map<String, Any?>? = null,
    val sourceFields: Map<String, Any?>? = null,
    val sourceFacts: Map<String, Any?>? = null,
    val parserVersion: String? = null,
)
