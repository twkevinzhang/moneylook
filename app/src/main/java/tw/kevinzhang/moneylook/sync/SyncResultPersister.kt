package tw.kevinzhang.moneylook.sync

import tw.kevinzhang.core.data.db.AccountTransferRefresh
import tw.kevinzhang.core.data.db.LegacyAccountIdentity
import tw.kevinzhang.core.data.db.TransferDateRange
import tw.kevinzhang.core.data.db.TransferSyncStore
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.KindSyncStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncResultPersister @Inject constructor(
    private val transferSyncStore: TransferSyncStore,
    private val autoCategorizer: TransferAutoCategorizer = TransferAutoCategorizer { },
) {
    suspend fun persist(extension: InstalledExtension, result: SyncResult.Success) {
        val now = System.currentTimeMillis()
        val legacyIdentityByProposedId = result.accounts.associate { data ->
            stableAccountId(extension.id, data) to legacyIdentityForSourceKey(data)
        }.filterValues { it != null }.mapValues { (_, identity) -> requireNotNull(identity) }
        val uniqueLegacyIdentities = legacyIdentityByProposedId.values
            .groupingBy { it }
            .eachCount()
            .filterValues { count -> count == 1 }
            .keys
        fun accountId(data: AccountData): String = stableAccountId(extension.id, data)
        val accounts = result.accounts.map { data ->
            Account(
                id = accountId(data),
                extensionId = extension.id,
                extensionName = extension.name,
                accountName = data.name,
                balance = data.balance,
                currency = data.currency,
                lastSyncAt = now,
                accountNo = data.no,
                sourceAccountKey = data.sourceAccountKey,
                kind = data.kind,
                branchName = data.branchName,
                availableCredit = data.availableCredit,
                creditLimit = data.creditLimit,
                transferSyncComplete = data.transferSync?.complete,
            )
        }
        val transfers = result.accounts.flatMap { data ->
            val accountId = accountId(data)
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
                    type = transfer.type,
                    status = transfer.status,
                )
            }
        }
        val refreshes = result.accounts.map { data ->
            AccountTransferRefresh(
                accountId = accountId(data),
                completedRanges = data.transferSync?.completedRanges?.map { range ->
                    TransferDateRange(start = range.start, end = range.end)
                },
            )
        }
        transferSyncStore.replaceSnapshot(
            extensionId = extension.id,
            accounts = accounts,
            transfers = transfers,
            refreshes = refreshes,
            legacyIdentityByAccountId = legacyIdentityByProposedId
                .filterValues { it in uniqueLegacyIdentities },
            replaceKinds = result.kindSync
                ?.filter { it.status == KindSyncStatus.COMPLETE }
                ?.mapTo(mutableSetOf()) { it.kind },
        )
        autoCategorizer.categorizeTransferIds(transfers.map(Transfer::id))
    }
}

/**
 * A successful extension run may still leave a product or part of an account's history
 * unsynchronized. Treat either case as partial so callers do not present it as a complete
 * snapshot.
 */
internal val SyncResult.Success.hasPartialSyncFailure: Boolean
    get() = kindSync?.any { it.status == KindSyncStatus.FAILED } == true ||
        accounts.any { it.transferSync?.complete == false }

internal val SyncResult.Success.appLastRunStatus: String
    get() = if (hasPartialSyncFailure) "partial" else "success"

/** Only source-key upgrades with a full legacy account number are eligible for ID preservation. */
private fun legacyIdentityForSourceKey(data: AccountData): LegacyAccountIdentity? {
    if (data.sourceAccountKey.isNullOrBlank()) return null
    val accountNo = data.no?.takeIf { it.isNotBlank() } ?: return null
    return LegacyAccountIdentity(accountNo, data.kind, data.currency)
}

/**
 * Account names are not unique: banks commonly expose multiple accounts with the same product
 * label. An extension-provided opaque source key is therefore preferred. Account numbers and
 * names remain legacy compatibility fallbacks for extensions that do not return one.
 */
internal fun stableAccountId(extensionId: String, data: AccountData): String {
    val identity = data.sourceAccountKey?.takeIf { it.isNotBlank() }
        ?: data.no?.takeIf { it.isNotBlank() }
        ?: data.name
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
