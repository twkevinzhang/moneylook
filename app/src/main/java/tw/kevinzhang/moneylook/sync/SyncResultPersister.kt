package tw.kevinzhang.moneylook.sync

import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.TransferDao
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.AccountData
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncResultPersister @Inject constructor(
    private val accountDao: AccountDao,
    private val transferDao: TransferDao,
) {
    suspend fun persist(extension: InstalledExtension, result: SyncResult.Success) {
        val now = System.currentTimeMillis()
        // Replace the extension snapshot so removed accounts and old v7 IDs cannot remain visible.
        // Transfers must be removed first because they reference account IDs.
        transferDao.deleteByExtensionId(extension.id)
        accountDao.deleteByExtensionId(extension.id)
        accountDao.upsertAll(
            result.accounts.map { data ->
                Account(
                    id = stableAccountId(extension.id, data),
                    extensionId = extension.id,
                    extensionName = extension.name,
                    accountName = data.name,
                    balance = data.balance,
                    currency = data.currency,
                    lastSyncAt = now,
                    accountNo = data.no,
                    kind = data.kind,
                    branchName = data.branchName,
                    availableCredit = data.availableCredit,
                    creditLimit = data.creditLimit,
                )
            },
        )

        val transfers = result.accounts.flatMap { data ->
            val accountId = stableAccountId(extension.id, data)
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

/**
 * Account names are not unique: banks commonly expose multiple accounts with the same product
 * label. The bank account number is therefore preferred, while a name remains the compatibility
 * fallback for extensions that do not return one.
 */
internal fun stableAccountId(extensionId: String, data: AccountData): String {
    val identity = data.no?.takeIf { it.isNotBlank() } ?: data.name
    val canonicalIdentity = listOf(extensionId, data.kind.name, identity, data.currency)
        .joinToString(separator = "\u001F") { value -> "${value.length}:$value" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonicalIdentity.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "$extensionId::$digest"
}
