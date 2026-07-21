package tw.kevinzhang.extension_runtime.data

data class TransferData(
    val txnDateTime: String,
    val description: String,
    val amount: Double,   // positive = income, negative = expend
    val balance: Double?,
    val memo: String,
    /** Bank-provided immutable transaction identifier, when the source exposes one. */
    val id: String? = null,
)
