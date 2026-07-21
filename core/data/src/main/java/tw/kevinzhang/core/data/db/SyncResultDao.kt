package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.Transfer

/**
 * Atomically replaces the account snapshot while preserving transaction history outside ranges
 * explicitly marked complete by an extension.
 */
@Dao
abstract class SyncResultDao : TransferSyncStore {
    @Transaction
    override suspend fun replaceSnapshot(
        extensionId: String,
        accounts: List<Account>,
        transfers: List<Transfer>,
        refreshes: List<AccountTransferRefresh>,
    ) {
        if (accounts.isEmpty()) {
            deleteTransfersByExtensionId(extensionId)
            deleteAccountsByExtensionId(extensionId)
            return
        }

        val accountIds = accounts.map(Account::id)
        deleteTransfersForRemovedAccounts(extensionId, accountIds)
        deleteAccountsByExtensionId(extensionId)
        upsertAccounts(accounts)

        refreshes.forEach { refresh ->
            val completedRanges = refresh.completedRanges
            if (completedRanges == null) {
                deleteTransfersByAccountId(refresh.accountId)
            } else {
                completedRanges.forEach { range ->
                    deleteTransfersInRange(refresh.accountId, range.start, range.end)
                }
            }
            refresh.retainFrom?.let { deleteTransfersBefore(refresh.accountId, it) }
        }
        if (transfers.isNotEmpty()) upsertTransfers(transfers)
    }

    @Upsert
    protected abstract suspend fun upsertAccounts(accounts: List<Account>)

    @Upsert
    protected abstract suspend fun upsertTransfers(transfers: List<Transfer>)

    @Query("DELETE FROM accounts WHERE extensionId = :extensionId")
    protected abstract suspend fun deleteAccountsByExtensionId(extensionId: String)

    @Query("DELETE FROM transfers WHERE extensionId = :extensionId")
    protected abstract suspend fun deleteTransfersByExtensionId(extensionId: String)

    @Query("DELETE FROM transfers WHERE extensionId = :extensionId AND accountId NOT IN (:accountIds)")
    protected abstract suspend fun deleteTransfersForRemovedAccounts(
        extensionId: String,
        accountIds: List<String>,
    )

    @Query("DELETE FROM transfers WHERE accountId = :accountId")
    protected abstract suspend fun deleteTransfersByAccountId(accountId: String)

    @Query(
        """
        DELETE FROM transfers
        WHERE accountId = :accountId
          AND substr(txnDateTime, 1, 10) >= :start
          AND substr(txnDateTime, 1, 10) <= :end
        """,
    )
    protected abstract suspend fun deleteTransfersInRange(accountId: String, start: String, end: String)

    @Query("DELETE FROM transfers WHERE accountId = :accountId AND substr(txnDateTime, 1, 10) < :retainFrom")
    protected abstract suspend fun deleteTransfersBefore(accountId: String, retainFrom: String)
}
