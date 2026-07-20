package tw.kevinzhang.extension_runtime.data

import tw.kevinzhang.core.data.model.AssetKind

data class AccountData(
    val name: String,
    val balance: Double,
    val currency: String,
    val no: String? = null,
    val kind: AssetKind = AssetKind.DEPOSIT,
    val branchName: String? = null,
    val availableCredit: Double? = null,
    val creditLimit: Double? = null,
    val transfers: List<TransferData> = emptyList(),
)
