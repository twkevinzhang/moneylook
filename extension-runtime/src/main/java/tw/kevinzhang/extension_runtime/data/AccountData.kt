package tw.kevinzhang.extension_runtime.data

data class AccountData(
    val name: String,
    val balance: Double,
    val currency: String,
    val no: String? = null,
    val transfers: List<TransferData> = emptyList(),
)
