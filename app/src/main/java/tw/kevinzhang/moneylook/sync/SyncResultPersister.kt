package tw.kevinzhang.moneylook.sync

import tw.kevinzhang.core.data.db.AccountTransferRefresh
import tw.kevinzhang.core.data.db.TransferDateRange
import tw.kevinzhang.core.data.db.TransferSyncStore
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
    private val transferSyncStore: TransferSyncStore,
) {
    suspend fun persist(extension: InstalledExtension, result: SyncResult.Success) {
        val now = System.currentTimeMillis()
        val accounts = result.accounts.map { data ->
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
                transferSyncComplete = data.transferSync?.complete,
            )
        }
        val transfers = result.accounts.flatMap { data ->
            val accountId = stableAccountId(extension.id, data)
            val legacyOccurrences = mutableMapOf<String, Int>()
            data.transfers.map { transfer ->
                val occurrence = if (transfer.id == null) {
                    val fingerprint = transferFallbackFingerprint(
                        transfer.txnDateTime,
                        transfer.description,
                        transfer.amount,
                        transfer.balance,
                        transfer.memo,
                    )
                    legacyOccurrences.getOrDefault(fingerprint, 0).also { index ->
                        legacyOccurrences[fingerprint] = index + 1
                    }
                } else {
                    null
                }
                Transfer(
                    id = stableTransferId(
                        accountId,
                        transfer.id,
                        transfer.txnDateTime,
                        transfer.description,
                        transfer.amount,
                        transfer.balance,
                        transfer.memo,
                        occurrence,
                    ),
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
        val refreshes = result.accounts.map { data ->
            AccountTransferRefresh(
                accountId = stableAccountId(extension.id, data),
                completedRanges = data.transferSync?.completedRanges?.map { range ->
                    TransferDateRange(start = range.start, end = range.end)
                },
                retainFrom = data.transferSync?.requestedStart,
            )
        }
        transferSyncStore.replaceSnapshot(extension.id, accounts, transfers, refreshes)
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

/**
 * Keeps a bank transaction identifier opaque in storage. Legacy extensions without one receive
 * a deterministic composite key so independently posted transactions at the same second survive.
 */
internal fun stableTransferId(
    accountId: String,
    sourceId: String?,
    txnDateTime: String,
    description: String,
    amount: Double,
    balance: Double?,
    memo: String,
    fallbackOccurrence: Int? = null,
): String {
    val identity = sourceId?.takeIf { it.isNotBlank() }
        ?: "${transferFallbackFingerprint(txnDateTime, description, amount, balance, memo)}\u001F${fallbackOccurrence ?: 0}"
    val canonicalIdentity = "$accountId\u001F${identity.length}:$identity"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonicalIdentity.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "$accountId::txn::$digest"
}

private fun transferFallbackFingerprint(
    txnDateTime: String,
    description: String,
    amount: Double,
    balance: Double?,
    memo: String,
): String = listOf(txnDateTime, description, amount.toString(), balance?.toString().orEmpty(), memo)
    .joinToString(separator = "\u001F") { value -> "${value.length}:$value" }
