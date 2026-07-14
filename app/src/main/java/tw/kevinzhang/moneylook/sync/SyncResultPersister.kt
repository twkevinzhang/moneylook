package tw.kevinzhang.moneylook.sync

import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.TransferDao
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.extension_runtime.data.SyncResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncResultPersister @Inject constructor(
    private val accountDao: AccountDao,
    private val transferDao: TransferDao,
) {
    suspend fun persist(extension: InstalledExtension, result: SyncResult.Success) {
        val now = System.currentTimeMillis()
        accountDao.upsertAll(
            result.accounts.map { data ->
                Account(
                    id = "${extension.id}_${data.name}",
                    extensionId = extension.id,
                    extensionName = extension.name,
                    accountName = data.name,
                    balance = data.balance,
                    currency = data.currency,
                    lastSyncAt = now,
                    accountNo = data.no,
                )
            },
        )

        val transfers = result.accounts.flatMap { data ->
            val accountId = "${extension.id}_${data.name}"
            data.transfers.map { transfer ->
                Transfer(
                    id = "${accountId}_${transfer.txnDateTime}",
                    accountId = accountId,
                    extensionId = extension.id,
                    txnDateTime = transfer.txnDateTime,
                    description = transfer.description,
                    amount = transfer.amount,
                    balance = transfer.balance,
                    memo = transfer.memo,
                )
            }
        }
        if (transfers.isNotEmpty()) transferDao.upsertAll(transfers)
    }
}
