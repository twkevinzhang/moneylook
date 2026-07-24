package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.CreditCardInstrument
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.IngestionRun
import tw.kevinzhang.core.data.model.TransferObservation
import tw.kevinzhang.core.data.model.TransferIngestionEvent
import tw.kevinzhang.core.data.model.IngestionClassificationStatus
import tw.kevinzhang.core.data.model.IngestionStatus
import java.util.UUID

/**
 * Atomically replaces the account snapshot while preserving transaction history outside ranges
 * explicitly marked complete by an extension.
 */
@Dao
abstract class SyncResultDao : TransferSyncStore {
    /** Snapshot mutation and its append-only import evidence share the Room transaction. */
    @Transaction
    override suspend fun replaceSnapshot(
        extensionId: String,
        accounts: List<Account>,
        transfers: List<Transfer>,
        refreshes: List<AccountTransferRefresh>,
        cardInstruments: List<CreditCardInstrument>,
        replaceCardAccountIds: Set<String>,
        legacyIdentityByAccountId: Map<String, LegacyAccountIdentity>,
        replaceKinds: Set<AssetKind>?,
        ingestionContext: IngestionContext,
    ) {
        require(ingestionContext.transferFingerprints.keys.containsAll(transfers.map(Transfer::id))) {
            "every transfer requires keyed provenance fingerprints"
        }
        val previousTransfers = if (transfers.isEmpty()) {
            emptyMap()
        } else {
            findTransfersByIds(transfers.map(Transfer::id)).associateBy(Transfer::id)
        }
        replaceSnapshot(
            extensionId = extensionId,
            accounts = accounts,
            transfers = transfers,
            refreshes = refreshes,
            cardInstruments = cardInstruments,
            replaceCardAccountIds = replaceCardAccountIds,
            legacyIdentityByAccountId = legacyIdentityByAccountId,
            replaceKinds = replaceKinds,
        )
        insertIngestionRun(
            IngestionRun(
                id = ingestionContext.runId,
                startedAt = ingestionContext.startedAt,
                completedAt = ingestionContext.completedAt,
                extensionId = extensionId,
                extensionVersion = ingestionContext.extensionVersion,
                artifactRevision = ingestionContext.artifactRevision,
                artifactSha256 = ingestionContext.artifactSha256,
                trigger = ingestionContext.trigger,
                status = ingestionContext.status,
                classificationStatus = ingestionContext.classificationStatus,
                classificationCompletedAt = null,
                accountCount = accounts.size,
                transferCount = transfers.size,
                sourceFingerprint = ingestionContext.sourceFingerprint,
                fingerprintKeyVersion = ingestionContext.fingerprintKeyVersion,
            ),
        )
        if (transfers.isNotEmpty()) {
            insertIngestionEvents(
                transfers.map { transfer ->
                    val fingerprints = ingestionContext.transferFingerprints.getValue(transfer.id)
                    val previous = previousTransfers[transfer.id]
                    TransferIngestionEvent(
                        id = UUID.randomUUID().toString(),
                        runId = ingestionContext.runId,
                        occurredAt = ingestionContext.completedAt,
                        transferId = transfer.id,
                        extensionId = transfer.extensionId,
                        observation = when {
                            previous == null -> TransferObservation.INSERTED
                            previous.copy(accountId = transfer.accountId) == transfer ->
                                TransferObservation.UNCHANGED
                            else -> TransferObservation.UPDATED
                        },
                        sourceFingerprint = fingerprints.sourceFingerprint,
                        payloadFingerprint = fingerprints.payloadFingerprint,
                        fingerprintKeyVersion = ingestionContext.fingerprintKeyVersion,
                        hasDescription = transfer.description.isNotBlank(),
                        hasMemo = transfer.memo.isNotBlank(),
                        hasType = !transfer.type.isNullOrBlank(),
                        hasMerchantName = !transfer.merchantName.isNullOrBlank(),
                        hasMerchantCategoryCode = !transfer.merchantCategoryCode.isNullOrBlank(),
                        hasCounterpartyName = !transfer.counterpartyName.isNullOrBlank(),
                        hasPurpose = !transfer.purpose.isNullOrBlank(),
                    )
                },
            )
        }
    }

    @Transaction
    override suspend fun recordFailedIngestion(
        extensionId: String,
        ingestionContext: IngestionContext,
        accountCount: Int,
        transferCount: Int,
    ) {
        insertIngestionRun(
            IngestionRun(
                id = ingestionContext.runId,
                startedAt = ingestionContext.startedAt,
                completedAt = System.currentTimeMillis(),
                extensionId = extensionId,
                extensionVersion = ingestionContext.extensionVersion,
                artifactRevision = ingestionContext.artifactRevision,
                artifactSha256 = ingestionContext.artifactSha256,
                trigger = ingestionContext.trigger,
                status = IngestionStatus.FAILED,
                classificationStatus = IngestionClassificationStatus.FAILED,
                classificationCompletedAt = System.currentTimeMillis(),
                accountCount = accountCount,
                transferCount = transferCount,
                sourceFingerprint = ingestionContext.sourceFingerprint,
                fingerprintKeyVersion = ingestionContext.fingerprintKeyVersion,
            ),
        )
    }

    @Query(
        """
        UPDATE ingestion_runs
        SET classificationStatus = :status, classificationCompletedAt = :completedAt
        WHERE id = :runId
        """,
    )
    override abstract suspend fun updateClassificationStatus(
        runId: String,
        status: IngestionClassificationStatus,
        completedAt: Long?,
    )

    @Transaction
    override suspend fun replaceSnapshot(
        extensionId: String,
        accounts: List<Account>,
        transfers: List<Transfer>,
        refreshes: List<AccountTransferRefresh>,
        cardInstruments: List<CreditCardInstrument>,
        replaceCardAccountIds: Set<String>,
        legacyIdentityByAccountId: Map<String, LegacyAccountIdentity>,
        replaceKinds: Set<AssetKind>?,
    ) {
        if (replaceKinds == null && accounts.isEmpty()) {
            deleteTransfersByExtensionId(extensionId)
            deleteCardInstrumentsByExtensionId(extensionId)
            deleteAccountsByExtensionId(extensionId)
            return
        }
        require(replaceKinds == null || accounts.all { it.kind in replaceKinds }) {
            "scoped snapshot contains an account outside its authoritative kinds"
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
        val resolvedCardInstruments = cardInstruments.map { instrument ->
            instrument.copy(accountId = idRewrites[instrument.accountId] ?: instrument.accountId)
        }
        val resolvedReplaceCardAccountIds =
            replaceCardAccountIds.mapTo(mutableSetOf()) { idRewrites[it] ?: it }
        if (replaceKinds == null) {
            val accountIds = resolvedAccounts.map(Account::id)
            deleteTransfersForRemovedAccounts(extensionId, accountIds)
            deleteCardInstrumentsForRemovedAccounts(extensionId, accountIds)
            deleteAccountsByExtensionId(extensionId)
        } else {
            replaceKinds.forEach { kind ->
                val accountIds = resolvedAccounts.filter { it.kind == kind }.map(Account::id)
                if (accountIds.isEmpty()) {
                    deleteTransfersByExtensionIdAndKind(extensionId, kind)
                    if (kind == AssetKind.CREDIT_CARD) {
                        deleteCardInstrumentsByExtensionIdAndKind(extensionId, kind)
                    }
                } else {
                    deleteTransfersForRemovedAccountsByKind(extensionId, kind, accountIds)
                    if (kind == AssetKind.CREDIT_CARD) {
                        deleteCardInstrumentsForRemovedAccountsByKind(extensionId, kind, accountIds)
                    }
                }
                deleteAccountsByExtensionIdAndKind(extensionId, kind)
            }
        }
        if (resolvedAccounts.isNotEmpty()) upsertAccounts(resolvedAccounts)

        if (resolvedCardInstruments.isNotEmpty()) upsertCardInstruments(resolvedCardInstruments)
        resolvedReplaceCardAccountIds.forEach { accountId ->
            val retainedIds = resolvedCardInstruments
                .filter { it.accountId == accountId }
                .map(CreditCardInstrument::id)
            if (retainedIds.isEmpty()) {
                clearTransferCardLinksByAccountId(accountId)
                deleteCardInstrumentsByAccountId(accountId)
            } else {
                clearStaleTransferCardLinks(accountId, retainedIds)
                deleteStaleCardInstruments(accountId, retainedIds)
            }
        }

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

    @Upsert
    protected abstract suspend fun upsertCardInstruments(instruments: List<CreditCardInstrument>)

    @Query("SELECT * FROM transfers WHERE id IN (:ids)")
    protected abstract suspend fun findTransfersByIds(ids: List<String>): List<Transfer>

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    protected abstract suspend fun insertIngestionRun(run: IngestionRun)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.ABORT)
    protected abstract suspend fun insertIngestionEvents(events: List<TransferIngestionEvent>)

    @Query("DELETE FROM accounts WHERE extensionId = :extensionId")
    protected abstract suspend fun deleteAccountsByExtensionId(extensionId: String)

    @Query("DELETE FROM accounts WHERE extensionId = :extensionId AND kind = :kind")
    protected abstract suspend fun deleteAccountsByExtensionIdAndKind(extensionId: String, kind: AssetKind)

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

    @Query("DELETE FROM credit_card_instruments WHERE extensionId = :extensionId")
    protected abstract suspend fun deleteCardInstrumentsByExtensionId(extensionId: String)

    @Query(
        """
        DELETE FROM transfers
        WHERE accountId IN (
            SELECT id FROM accounts WHERE extensionId = :extensionId AND kind = :kind
        )
        """,
    )
    protected abstract suspend fun deleteTransfersByExtensionIdAndKind(
        extensionId: String,
        kind: AssetKind,
    )

    @Query("DELETE FROM transfers WHERE extensionId = :extensionId AND accountId NOT IN (:accountIds)")
    protected abstract suspend fun deleteTransfersForRemovedAccounts(
        extensionId: String,
        accountIds: List<String>,
    )

    @Query("DELETE FROM credit_card_instruments WHERE extensionId = :extensionId AND accountId NOT IN (:accountIds)")
    protected abstract suspend fun deleteCardInstrumentsForRemovedAccounts(
        extensionId: String,
        accountIds: List<String>,
    )

    @Query(
        """
        DELETE FROM transfers
        WHERE accountId IN (
            SELECT id FROM accounts
            WHERE extensionId = :extensionId
              AND kind = :kind
              AND id NOT IN (:accountIds)
        )
        """,
    )
    protected abstract suspend fun deleteTransfersForRemovedAccountsByKind(
        extensionId: String,
        kind: AssetKind,
        accountIds: List<String>,
    )

    @Query(
        """
        DELETE FROM credit_card_instruments
        WHERE accountId IN (
            SELECT id FROM accounts
            WHERE extensionId = :extensionId AND kind = :kind AND id NOT IN (:accountIds)
        )
        """,
    )
    protected abstract suspend fun deleteCardInstrumentsForRemovedAccountsByKind(
        extensionId: String,
        kind: AssetKind,
        accountIds: List<String>,
    )

    @Query(
        """
        DELETE FROM credit_card_instruments
        WHERE accountId IN (
            SELECT id FROM accounts WHERE extensionId = :extensionId AND kind = :kind
        )
        """,
    )
    protected abstract suspend fun deleteCardInstrumentsByExtensionIdAndKind(
        extensionId: String,
        kind: AssetKind,
    )

    @Query("DELETE FROM transfers WHERE accountId = :accountId")
    protected abstract suspend fun deleteTransfersByAccountId(accountId: String)

    @Query("DELETE FROM credit_card_instruments WHERE accountId = :accountId")
    protected abstract suspend fun deleteCardInstrumentsByAccountId(accountId: String)

    @Query("UPDATE transfers SET cardInstrumentId = NULL WHERE accountId = :accountId")
    protected abstract suspend fun clearTransferCardLinksByAccountId(accountId: String)

    @Query(
        """
        UPDATE transfers
        SET cardInstrumentId = NULL
        WHERE accountId = :accountId
          AND cardInstrumentId IS NOT NULL
          AND cardInstrumentId NOT IN (:retainedIds)
        """,
    )
    protected abstract suspend fun clearStaleTransferCardLinks(
        accountId: String,
        retainedIds: List<String>,
    )

    @Query(
        """
        DELETE FROM credit_card_instruments
        WHERE accountId = :accountId
          AND id NOT IN (:retainedIds)
        """,
    )
    protected abstract suspend fun deleteStaleCardInstruments(
        accountId: String,
        retainedIds: List<String>,
    )

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
