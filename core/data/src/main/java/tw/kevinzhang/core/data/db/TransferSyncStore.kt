package tw.kevinzhang.core.data.db

import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.Transfer

/** A successful inclusive date range whose transactions may safely replace local history. */
data class TransferDateRange(
    val start: String,
    val end: String,
)

/**
 * A null range list retains the pre-history-extension snapshot behaviour for this account.
 * A non-null list replaces only ranges that the extension confirmed as successfully fetched.
 */
data class AccountTransferRefresh(
    val accountId: String,
    val completedRanges: List<TransferDateRange>?,
    /** Oldest date retained for this account; null preserves legacy snapshot semantics. */
    val retainFrom: String?,
)

interface TransferSyncStore {
    suspend fun replaceSnapshot(
        extensionId: String,
        accounts: List<Account>,
        transfers: List<Transfer>,
        refreshes: List<AccountTransferRefresh>,
    )
}
