package tw.kevinzhang.extension_runtime.data

data class TransferData(
    val txnDateTime: String,
    val description: String,
    val amount: Double,   // positive = income, negative = expend
    val balance: Double,
    val memo: String,
)
