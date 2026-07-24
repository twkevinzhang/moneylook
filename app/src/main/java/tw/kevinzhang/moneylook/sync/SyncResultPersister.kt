package tw.kevinzhang.moneylook.sync

import tw.kevinzhang.core.data.db.AccountTransferRefresh
import tw.kevinzhang.core.data.db.LegacyAccountIdentity
import tw.kevinzhang.core.data.db.TransferDateRange
import tw.kevinzhang.core.data.db.TransferSyncStore
import tw.kevinzhang.core.data.db.IngestionContext
import tw.kevinzhang.core.data.db.TransferFingerprintEvidence
import tw.kevinzhang.core.data.model.IngestionStatus
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.CreditCardInstrument
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.moneylook.security.CardPanProtector
import tw.kevinzhang.moneylook.security.ProtectedCardPan
import tw.kevinzhang.moneylook.security.SourceFingerprintProtector
import tw.kevinzhang.moneylook.security.ProtectedSourceFingerprint
import tw.kevinzhang.extension_runtime.data.KindSyncStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import kotlinx.coroutines.CancellationException

@Singleton
class SyncResultPersister @Inject constructor(
    private val transferSyncStore: TransferSyncStore,
    private val autoCategorizer: TransferAutoCategorizer = TransferAutoCategorizer { },
    private val cardPanProtector: CardPanProtector = MissingCardPanProtector,
    private val sourceFingerprintProtector: SourceFingerprintProtector = MissingSourceFingerprintProtector,
) {
    suspend fun persist(
        extension: InstalledExtension,
        result: SyncResult.Success,
        trigger: IngestionTrigger = IngestionTrigger.USER_SYNC,
    ) {
        val startedAt = System.currentTimeMillis()
        val runId = UUID.randomUUID().toString()
        var ingestionContext: IngestionContext? = null
        var snapshotCommitted = false
        try {
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
                lastSyncAt = startedAt,
                accountNo = data.no,
                sourceAccountKey = data.sourceAccountKey,
                kind = data.kind,
                branchName = data.branchName,
                availableCredit = data.availableCredit,
                creditLimit = data.creditLimit,
                transferSyncComplete = data.transferSync?.complete,
            )
        }
        val cardInstrumentIdsByAccountAndRef = mutableMapOf<Pair<String, String>, String>()
        val replaceCardAccountIds = result.accounts
            .filter { it.cardsComplete == true }
            .map(::accountId)
            .toSet()
        val cardInstruments = result.accounts.flatMap { data ->
            val accountId = accountId(data)
            data.cards.map { card ->
                // The sole persistence boundary for plaintext PAN. Encryption happens before
                // constructing the Room entity or invoking the transactional store.
                val protectedPan = card.pan?.let(cardPanProtector::protect)
                val instrumentId = stableCardInstrumentId(
                    accountId = accountId,
                    sourceCardKey = card.sourceCardKey,
                    panFingerprint = protectedPan?.fingerprint,
                    resultLocalRef = card.ref,
                )
                cardInstrumentIdsByAccountAndRef[accountId to card.ref] = instrumentId
                CreditCardInstrument(
                    id = instrumentId,
                    accountId = accountId,
                    extensionId = extension.id,
                    sourceCardKey = card.sourceCardKey,
                    panCiphertext = protectedPan?.ciphertext,
                    panIv = protectedPan?.iv,
                    panFingerprint = protectedPan?.fingerprint,
                    maskedPan = card.maskedPan,
                    lastFour = card.lastFour ?: card.pan?.takeLast(4),
                    displayName = card.displayName,
                    network = card.network,
                    productType = card.productType,
                    holderRole = card.holderRole,
                    holderName = card.holderName,
                    status = card.status,
                    expiryMonth = card.expiryMonth,
                    expiryYear = card.expiryYear,
                    creditLimit = card.creditLimit,
                    availableCredit = card.availableCredit,
                )
            }
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
                    postingDateTime = transfer.postingDateTime,
                    merchantName = transfer.merchantName,
                    merchantCategoryCode = transfer.merchantCategoryCode,
                    counterpartyName = transfer.counterpartyName,
                    purpose = transfer.purpose,
                    cardInstrumentId = transfer.cardRef?.let { ref ->
                        cardInstrumentIdsByAccountAndRef[accountId to ref]
                    },
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
        val completedAt = System.currentTimeMillis()
        val runFingerprint = sourceFingerprintProtector.fingerprint(
            "ingestion-run",
            extension.id,
            extension.version.toString(),
            extension.artifactRevision.orEmpty(),
            extension.artifactSha256.orEmpty(),
        )
        val transferFingerprints = transfers.associate { transfer ->
            val sourceFingerprint = sourceFingerprintProtector.fingerprint(
                "transfer-source",
                extension.id,
                transfer.id,
            )
            val payloadFingerprint = sourceFingerprintProtector.fingerprint(
                "transfer-payload",
                transfer.id,
                transfer.txnDateTime,
                transfer.postingDateTime.orEmpty(),
                transfer.description,
                transfer.amount.toString(),
                transfer.balance?.toString().orEmpty(),
                transfer.memo,
                transfer.type.orEmpty(),
                transfer.status.orEmpty(),
                transfer.cardInstrumentId.orEmpty(),
                transfer.merchantName.orEmpty(),
                transfer.merchantCategoryCode.orEmpty(),
                transfer.counterpartyName.orEmpty(),
                transfer.purpose.orEmpty(),
            )
            require(sourceFingerprint.keyVersion == payloadFingerprint.keyVersion) {
                "source fingerprint key version changed during one import"
            }
            transfer.id to TransferFingerprintEvidence(
                sourceFingerprint = sourceFingerprint.value,
                payloadFingerprint = payloadFingerprint.value,
            )
        }
        val preparedContext = IngestionContext(
            runId = runId,
            startedAt = startedAt,
            completedAt = completedAt,
            extensionVersion = extension.version,
            artifactRevision = extension.artifactRevision,
            artifactSha256 = extension.artifactSha256,
            trigger = trigger,
            status = if (result.hasPartialSyncFailure) IngestionStatus.PARTIAL else IngestionStatus.SUCCESS,
            sourceFingerprint = runFingerprint.value,
            fingerprintKeyVersion = runFingerprint.keyVersion,
            transferFingerprints = transferFingerprints,
        )
        ingestionContext = preparedContext
        transferSyncStore.replaceSnapshot(
                extensionId = extension.id,
                accounts = accounts,
                transfers = transfers,
                refreshes = refreshes,
                cardInstruments = cardInstruments,
                replaceCardAccountIds = replaceCardAccountIds,
                legacyIdentityByAccountId = legacyIdentityByProposedId
                    .filterValues { it in uniqueLegacyIdentities },
                replaceKinds = result.kindSync
                    ?.filter { it.status == KindSyncStatus.COMPLETE }
                    ?.mapTo(mutableSetOf()) { it.kind },
                ingestionContext = preparedContext,
            )
        snapshotCommitted = true
        try {
            autoCategorizer.categorizeTransferIds(transfers.map(Transfer::id), runId)
            transferSyncStore.updateClassificationStatus(
                runId,
                tw.kevinzhang.core.data.model.IngestionClassificationStatus.COMPLETE,
                System.currentTimeMillis(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            markClassificationFailedOrSuppress(runId, error)
            throw error
        }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!snapshotCommitted) {
                recordFailedRunOrSuppress(
                    extensionId = extension.id,
                    context = ingestionContext ?: failedPreprocessingContext(
                        extension = extension,
                        trigger = trigger,
                        startedAt = startedAt,
                        runId = runId,
                    ),
                    accountCount = result.accounts.size,
                    transferCount = result.accounts.sumOf { it.transfers.size },
                    original = error,
                )
            }
            throw error
        }
    }

    private fun failedPreprocessingContext(
        extension: InstalledExtension,
        trigger: IngestionTrigger,
        startedAt: Long,
        runId: String,
    ) = IngestionContext(
        runId = runId,
        startedAt = startedAt,
        completedAt = System.currentTimeMillis(),
        extensionVersion = extension.version,
        artifactRevision = extension.artifactRevision,
        artifactSha256 = extension.artifactSha256,
        trigger = trigger,
        status = IngestionStatus.FAILED,
        classificationStatus =
            tw.kevinzhang.core.data.model.IngestionClassificationStatus.FAILED,
        sourceFingerprint = "unavailable",
        fingerprintKeyVersion = 0,
        transferFingerprints = emptyMap(),
    )

    /** Records an extension/runtime failure without retaining its message or any private payload. */
    suspend fun recordFailure(
        extension: InstalledExtension,
        trigger: IngestionTrigger = IngestionTrigger.USER_SYNC,
    ) {
        val now = System.currentTimeMillis()
        val fingerprint = sourceFingerprintProtector.fingerprint(
            "ingestion-run",
            extension.id,
            extension.version.toString(),
            extension.artifactRevision.orEmpty(),
            extension.artifactSha256.orEmpty(),
        )
        transferSyncStore.recordFailedIngestion(
            extensionId = extension.id,
            ingestionContext = IngestionContext(
                runId = UUID.randomUUID().toString(),
                startedAt = now,
                completedAt = now,
                extensionVersion = extension.version,
                artifactRevision = extension.artifactRevision,
                artifactSha256 = extension.artifactSha256,
                trigger = trigger,
                status = IngestionStatus.FAILED,
                classificationStatus =
                    tw.kevinzhang.core.data.model.IngestionClassificationStatus.FAILED,
                sourceFingerprint = fingerprint.value,
                fingerprintKeyVersion = fingerprint.keyVersion,
                transferFingerprints = emptyMap(),
            ),
            accountCount = 0,
            transferCount = 0,
        )
    }

    private suspend fun recordFailedRunOrSuppress(
        extensionId: String,
        context: IngestionContext,
        accountCount: Int,
        transferCount: Int,
        original: Exception,
    ) {
        try {
            transferSyncStore.recordFailedIngestion(
                extensionId,
                context,
                accountCount,
                transferCount,
            )
        } catch (auditError: CancellationException) {
            throw auditError
        } catch (auditError: Exception) {
            original.addSuppressed(auditError)
        }
    }

    private suspend fun markClassificationFailedOrSuppress(runId: String, original: Exception) {
        try {
            transferSyncStore.updateClassificationStatus(
                runId,
                tw.kevinzhang.core.data.model.IngestionClassificationStatus.FAILED,
                System.currentTimeMillis(),
            )
        } catch (auditError: CancellationException) {
            throw auditError
        } catch (auditError: Exception) {
            original.addSuppressed(auditError)
        }
    }
}

/** Unit-test fallback. Production always receives the Android Keystore implementation from Hilt. */
private object MissingSourceFingerprintProtector : SourceFingerprintProtector {
    override fun fingerprint(vararg components: String): ProtectedSourceFingerprint =
        ProtectedSourceFingerprint("test-only", 0)
}

/** Prevents accidental plaintext persistence in unit tests that did not opt into a cipher fake. */
private object MissingCardPanProtector : CardPanProtector {
    override fun protect(pan: String): ProtectedCardPan =
        throw IllegalStateException("card PAN protector is unavailable")

    override fun reveal(ciphertext: ByteArray, iv: ByteArray): String =
        throw IllegalStateException("card PAN protector is unavailable")
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

/** The result-local card ref is a fallback only; stable bank keys or keyed PAN fingerprints win. */
internal fun stableCardInstrumentId(
    accountId: String,
    sourceCardKey: String?,
    panFingerprint: String?,
    resultLocalRef: String,
): String {
    val identity = sourceCardKey ?: panFingerprint ?: resultLocalRef
    val canonicalIdentity = listOf(accountId, identity)
        .joinToString(separator = "\u001F") { value -> "${value.length}:$value" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(canonicalIdentity.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "$accountId::card::$digest"
}

private fun transferFallbackFingerprint(
    txnDateTime: String,
    description: String,
    amount: Double,
    balance: Double?,
    memo: String,
): String = listOf(txnDateTime, description, amount.toString(), balance?.toString().orEmpty(), memo)
    .joinToString(separator = "\u001F") { value -> "${value.length}:$value" }
