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
        legacyIdentityByAccountId: Map<String, LegacyAccountIdentity>,
    ) {
        if (accounts.isEmpty()) {
            deleteTransfersByExtensionId(extensionId)
            deleteAccountsByExtensionId(extensionId)
            return
        }

        val idRewrites = accounts.associate { account ->
            val reusedId = account.sourceAccountKey?.let { sourceKey ->
                findSourceAccountIds(extensionId, sourceKey, account.kind, account.currency).singleOrNull()
            } ?: legacyIdentityByAccountId[account.id]?.let { identity ->
                findLegacyAccountIds(extensionId, identity.accountNo, identity.kind, identity.currency).singleOrNull()
            }
            account.id to (reusedId ?: account.id)
        }
        val resolvedAccounts = accounts.map { account -> account.copy(id = idRewrites.getValue(account.id)) }
        val resolvedTransfers = transfers.map { transfer ->
            transfer.copy(accountId = idRewrites[transfer.accountId] ?: transfer.accountId)
        }
        val resolvedRefreshes = refreshes.map { refresh ->
            refresh.copy(accountId = idRewrites[refresh.accountId] ?: refresh.accountId)
        }
        val accountIds = resolvedAccounts.map(Account::id)
        deleteTransfersForRemovedAccounts(extensionId, accountIds)
        deleteAccountsByExtensionId(extensionId)
        upsertAccounts(resolvedAccounts)

        resolvedRefreshes.forEach { refresh ->
            val completedRanges = refresh.completedRanges
            if (completedRanges == null) {
                deleteTransfersByAccountId(refresh.accountId)
            } else {
                completedRanges.forEach { range ->
                    deleteTransfersInRange(refresh.accountId, range.start, range.end)
                }
            }
        }
        if (resolvedTransfers.isNotEmpty()) upsertTransfers(resolvedTransfers)
    }

    @Upsert
    protected abstract suspend fun upsertAccounts(accounts: List<Account>)

    @Upsert
    protected abstract suspend fun upsertTransfers(transfers: List<Transfer>)

    @Query("DELETE FROM accounts WHERE extensionId = :extensionId")
    protected abstract suspend fun deleteAccountsByExtensionId(extensionId: String)

    @Query(
        """
        SELECT id FROM accounts
        WHERE extensionId = :extensionId
          AND accountNo = :accountNo
          AND kind = :kind
          AND currency = :currency
          AND sourceAccountKey IS NULL
        """,
    )
    protected abstract suspend fun findLegacyAccountIds(
        extensionId: String,
        accountNo: String,
        kind: tw.kevinzhang.core.data.model.AssetKind,
        currency: String,
    ): List<String>

    @Query(
        """
        SELECT id FROM accounts
        WHERE extensionId = :extensionId
          AND sourceAccountKey = :sourceAccountKey
          AND kind = :kind
          AND currency = :currency
        """,
    )
    protected abstract suspend fun findSourceAccountIds(
        extensionId: String,
        sourceAccountKey: String,
        kind: tw.kevinzhang.core.data.model.AssetKind,
        currency: String,
    ): List<String>

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

}
