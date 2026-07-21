package tw.kevinzhang.core.data.db

import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
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
)

/**
 * App-private legacy identity used exactly once to retain an existing account ID when an
 * extension upgrades to opaque source keys. Its account number must never be logged.
 */
data class LegacyAccountIdentity(
    val accountNo: String,
    val kind: AssetKind,
    val currency: String,
) {
    override fun toString(): String = "LegacyAccountIdentity([REDACTED])"
}

interface TransferSyncStore {
    suspend fun replaceSnapshot(
        extensionId: String,
        accounts: List<Account>,
        transfers: List<Transfer>,
        refreshes: List<AccountTransferRefresh>,
        /** Proposed account ID to its unique, app-private legacy identity for one-time migration. */
        legacyIdentityByAccountId: Map<String, LegacyAccountIdentity> = emptyMap(),
    )
}
