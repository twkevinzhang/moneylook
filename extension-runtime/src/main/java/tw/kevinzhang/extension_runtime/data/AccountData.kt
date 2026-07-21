package tw.kevinzhang.extension_runtime.data

import tw.kevinzhang.core.data.model.AssetKind

data class AccountData(
    val name: String,
    val balance: Double,
    val currency: String,
    val no: String? = null,
    /** Lowercase 64-character SHA-256 hex identity for cursor matching; legacy extensions may omit it. */
    val sourceAccountKey: String? = null,
    val kind: AssetKind = AssetKind.DEPOSIT,
    val branchName: String? = null,
    val availableCredit: Double? = null,
    val creditLimit: Double? = null,
    val transfers: List<TransferData> = emptyList(),
    /** Null keeps the legacy snapshot contract for extensions that do not download history. */
    val transferSync: TransferSyncData? = null,
)
