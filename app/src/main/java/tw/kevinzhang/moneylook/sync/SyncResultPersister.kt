package tw.kevinzhang.moneylook.sync

import com.google.gson.Gson
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
import tw.kevinzhang.core.data.model.SourceDocument
import tw.kevinzhang.core.data.model.TransferFieldObservation
import tw.kevinzhang.extension_runtime.data.CapturedSourceDocument
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
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

@Singleton
class SyncResultPersister @Inject constructor(
    private val transferSyncStore: TransferSyncStore,
    private val autoCategorizer: TransferAutoCategorizer = TransferAutoCategorizer { },
    private val cardPanProtector: CardPanProtector = MissingCardPanProtector,
    private val sourceFingerprintProtector: SourceFingerprintProtector = MissingSourceFingerprintProtector,
    private val gson: Gson = Gson(),
) {
    suspend fun persist(
        extension: InstalledExtension,
        result: SyncResult.Success,
        trigger: IngestionTrigger = IngestionTrigger.USER_SYNC,
    ) {
        val startedAt = result.runStartedAt ?: System.currentTimeMillis()
        val runId = result.runId ?: UUID.randomUUID().toString()
        val capturedDocuments = mutableListOf<SourceDocument>()
        var ingestionContext: IngestionContext? = null
        var snapshotCommitted = false
        try {
        result.sourceDocuments.forEach {
            capturedDocuments += it.toEntity(runId = runId, extensionId = extension.id)
        }
        validateResultEvidence(result)
        validateRichTransferValues(result)
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
                sourceRecordJson = data.sourceRecord?.let(gson::toJson),
                sourceFieldsJson = data.sourceFields?.let(gson::toJson),
                sourceFactsJson = data.sourceFacts?.let(gson::toJson),
                parserVersion = data.parserVersion ?: "manifest:${extension.version}",
            )
        }
        val sourceAccountByAccountId = result.accounts.associateBy(::accountId)
        val cardInstrumentIdsByAccountAndRef = mutableMapOf<Pair<String, String>, String>()
        val sourceCardByInstrumentId =
            mutableMapOf<String, tw.kevinzhang.extension_runtime.data.CardData>()
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
                sourceCardByInstrumentId[instrumentId] = card
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
                    sourceRecordJson = card.sourceRecord?.let(gson::toJson),
                    sourceFieldsJson = card.sourceFields?.let(gson::toJson),
                    sourceFactsJson = card.sourceFacts?.let(gson::toJson),
                    parserVersion = card.parserVersion ?: "manifest:${extension.version}",
                )
            }
        }
        val sourceTransferByTransferId = mutableMapOf<String, tw.kevinzhang.extension_runtime.data.TransferData>()
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
                val transferId = stableTransferId(
                        accountId,
                        transfer.id,
                        transfer.txnDateTime,
                        transfer.description,
                        transfer.amount,
                        transfer.balance,
                        transfer.memo,
                        occurrence,
                    )
                val persisted = Transfer(
                    id = transferId,
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
                    authorizationDateTime = transfer.authorizationDateTime,
                    valueDateTime = transfer.valueDateTime,
                    referenceNumber = transfer.referenceNumber,
                    authorizationCode = transfer.authorizationCode,
                    channel = transfer.channel,
                    direction = transfer.direction,
                    transactionCode = transfer.transactionCode,
                    originalAmount = transfer.originalAmount,
                    originalCurrency = transfer.originalCurrency,
                    settlementAmount = transfer.settlementAmount,
                    settlementCurrency = transfer.settlementCurrency,
                    exchangeRate = transfer.exchangeRate,
                    feeAmount = transfer.feeAmount,
                    feeCurrency = transfer.feeCurrency,
                    taxAmount = transfer.taxAmount,
                    taxCurrency = transfer.taxCurrency,
                    merchantLocation = transfer.merchantLocation,
                    counterpartyAccount = transfer.counterpartyAccount,
                    counterpartyBank = transfer.counterpartyBank,
                    installmentNumber = transfer.installmentNumber,
                    installmentTotal = transfer.installmentTotal,
                    isRefund = transfer.isRefund,
                    isReversal = transfer.isReversal,
                    originalTransactionSourceId = transfer.originalTransactionSourceId,
                    sourceRecordJson = transfer.sourceRecord?.let(gson::toJson),
                    sourceFieldsJson = transfer.sourceFields?.let(gson::toJson),
                    sourceFactsJson = transfer.sourceFacts?.let(gson::toJson),
                    parserVersion = transfer.parserVersion ?: "manifest:${extension.version}",
                    cardInstrumentId = transfer.cardRef?.let { ref ->
                        cardInstrumentIdsByAccountAndRef[accountId to ref]
                    },
                )
                sourceTransferByTransferId[transferId] = transfer
                persisted
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
            // The complete persisted payload (including every rich/provenance/parser field) is
            // canonicalized by Gson's stable data-class property order.
            val payloadFingerprint = sourceFingerprintProtector.fingerprint(
                "transfer-payload",
                gson.toJson(transfer),
            )
            require(sourceFingerprint.keyVersion == payloadFingerprint.keyVersion) {
                "source fingerprint key version changed during one import"
            }
            transfer.id to TransferFingerprintEvidence(
                sourceFingerprint = sourceFingerprint.value,
                payloadFingerprint = payloadFingerprint.value,
            )
        }
        val failedKinds = result.kindSync.orEmpty().filter { it.status == KindSyncStatus.FAILED }
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
            sourceDocuments = capturedDocuments,
            fieldObservations = buildList {
                addAll(transfers.flatMap { transfer ->
                    sourceTransferByTransferId.getValue(transfer.id).toFieldObservations(
                    transfer = transfer,
                    runId = runId,
                    observedAt = completedAt,
                    extensionId = extension.id,
                    gson = gson,
                    parserVersionFallback = "manifest:${extension.version}",
                    )
                })
                addAll(accounts.flatMap { account ->
                    sourceAccountByAccountId.getValue(account.id).toFieldObservations(
                        account = account,
                        runId = runId,
                        observedAt = completedAt,
                        extensionId = extension.id,
                        gson = gson,
                        parserVersionFallback = "manifest:${extension.version}",
                    )
                })
                addAll(cardInstruments.flatMap { card ->
                    sourceCardByInstrumentId.getValue(card.id).toFieldObservations(
                        card = card,
                        runId = runId,
                        observedAt = completedAt,
                        extensionId = extension.id,
                        gson = gson,
                        parserVersionFallback = "manifest:${extension.version}",
                    )
                })
            },
            failureOrigin = "PARTIAL_KIND".takeIf { failedKinds.isNotEmpty() },
            failureCode = when (failedKinds.size) {
                0 -> null
                1 -> failedKinds.single().code
                else -> "MULTIPLE_KIND_FAILURES"
            },
            failureMessage = failedKinds.mapNotNull { it.rawMessage }
                .joinToString("\n\n")
                .takeIf(String::isNotEmpty),
            failureStack = failedKinds.mapNotNull { it.rawStack }
                .joinToString("\n\n")
                .takeIf(String::isNotEmpty),
            failureDiagnosticJson = failedKinds.takeIf { it.isNotEmpty() }?.let(gson::toJson),
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
                    context = (ingestionContext ?: failedPreprocessingContext(
                        extension = extension,
                        trigger = trigger,
                        startedAt = startedAt,
                        runId = runId,
                        sourceDocuments = capturedDocuments,
                    )).copy(
                        failureOrigin = "PERSISTENCE",
                        failureMessage = error.message ?: error.toString(),
                        failureStack = error.stackTraceToString(),
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
        sourceDocuments: List<SourceDocument>,
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
        sourceDocuments = sourceDocuments,
    )

    /** Records an extension/runtime failure with its complete raw diagnostic evidence. */
    suspend fun recordFailure(
        extension: InstalledExtension,
        trigger: IngestionTrigger = IngestionTrigger.USER_SYNC,
        sourceDocuments: List<CapturedSourceDocument> = emptyList(),
        sourceRunId: String? = null,
        sourceRunStartedAt: Long? = null,
        failure: SyncResult.Error? = null,
    ) {
        val now = System.currentTimeMillis()
        val runId = sourceRunId ?: UUID.randomUUID().toString()
        val startedAt = sourceRunStartedAt ?: now
        val completeSourceDocuments = if (sourceDocuments.isNotEmpty()) {
            sourceDocuments
        } else {
            failure?.sourceDocuments.orEmpty()
        }
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
                runId = runId,
                startedAt = startedAt,
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
                sourceDocuments = completeSourceDocuments.map {
                    it.toEntity(runId = runId, extensionId = extension.id)
                },
                failureOrigin = failure?.origin,
                failureCode = failure?.code,
                failureMessage = failure?.rawMessage ?: failure?.message,
                failureStack = failure?.rawStack ?: failure?.cause?.stackTraceToString(),
                failureDiagnosticJson = failure?.rawDiagnosticJson,
                failureScriptFrame = failure?.scriptFrame,
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
            transferSyncStore.updateClassificationFailure(
                runId = runId,
                completedAt = System.currentTimeMillis(),
                origin = "CLASSIFIER",
                message = original.message ?: original.toString(),
                stack = original.stackTraceToString(),
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

private fun CapturedSourceDocument.toEntity(runId: String, extensionId: String): SourceDocument {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(bodyBytes)
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    val compressed = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bodyBytes) }
        output.toByteArray()
    }
    return SourceDocument(
        id = id,
        runId = runId,
        extensionId = extensionId,
        capturedAt = capturedAt,
        stage = stage,
        transport = transport,
        method = method,
        url = url,
        statusCode = statusCode,
        responseHeadersJson = responseHeadersJson,
        mediaKind = mediaKind,
        bodyEncoding = bodyEncoding,
        representation = representation,
        bodyByteCount = bodyBytes.size.toLong(),
        bodySha256 = digest,
        bodyGzip = compressed,
    )
}

private fun tw.kevinzhang.extension_runtime.data.TransferData.toFieldObservations(
    transfer: Transfer,
    runId: String,
    observedAt: Long,
    extensionId: String,
    gson: Gson,
    parserVersionFallback: String,
): List<TransferFieldObservation> {
    val values: Map<String, Any?> = buildMap {
        putAll(linkedMapOf(
        "id" to id,
        "txnDateTime" to txnDateTime,
        "description" to description,
        "amount" to amount,
        "balance" to balance,
        "memo" to memo,
        "type" to type,
        "status" to status,
        "postingDateTime" to postingDateTime,
        "cardRef" to cardRef,
        "merchantName" to merchantName,
        "merchantCategoryCode" to merchantCategoryCode,
        "counterpartyName" to counterpartyName,
        "purpose" to purpose,
        "authorizationDateTime" to authorizationDateTime,
        "valueDateTime" to valueDateTime,
        "referenceNumber" to referenceNumber,
        "authorizationCode" to authorizationCode,
        "channel" to channel,
        "direction" to direction,
        "transactionCode" to transactionCode,
        "originalAmount" to originalAmount,
        "originalCurrency" to originalCurrency,
        "settlementAmount" to settlementAmount,
        "settlementCurrency" to settlementCurrency,
        "exchangeRate" to exchangeRate,
        "feeAmount" to feeAmount,
        "feeCurrency" to feeCurrency,
        "taxAmount" to taxAmount,
        "taxCurrency" to taxCurrency,
        "merchantLocation" to merchantLocation,
        "counterpartyAccount" to counterpartyAccount,
        "counterpartyBank" to counterpartyBank,
        "installmentNumber" to installmentNumber,
        "installmentTotal" to installmentTotal,
        "isRefund" to isRefund,
        "isReversal" to isReversal,
        "originalTransactionSourceId" to originalTransactionSourceId,
        ))
        sourceFacts.orEmpty().forEach { (key, descriptor) ->
            put("sourceFact.$key", sourceValue(descriptor))
        }
    }
    val sourceRecordJson = sourceRecord?.let(gson::toJson)
    val recordDocumentId = sourceRecord?.get("sourceDocumentId") as? String
    return values.map { (fieldName, value) ->
        val descriptor = if (fieldName.startsWith("sourceFact.")) {
            sourceFacts?.get(fieldName.removePrefix("sourceFact."))
        } else {
            sourceFields?.get(fieldName)
        }
        val locator = descriptor as? Map<*, *>
        TransferFieldObservation(
            id = UUID.randomUUID().toString(),
            runId = runId,
            transferId = transfer.id,
            extensionId = extensionId,
            observedAt = observedAt,
            fieldName = fieldName,
            valueJson = gson.toJson(value),
            sourceDocumentId = ((locator?.get("sourceDocumentId") ?: locator?.get("documentId")) as? String)
                ?: recordDocumentId,
            sourcePath = (
                locator?.get("locator")
                    ?: locator?.get("sourcePath")
                    ?: locator?.get("path")
                ) as? String,
            sourceRecordJson = sourceRecordJson,
            sourceFieldJson = descriptor?.let(gson::toJson),
            parserVersion = parserVersion ?: parserVersionFallback,
        )
    }
}

private fun AccountData.toFieldObservations(
    account: Account,
    runId: String,
    observedAt: Long,
    extensionId: String,
    gson: Gson,
    parserVersionFallback: String,
): List<TransferFieldObservation> = assetFieldObservations(
    assetType = "ACCOUNT",
    assetId = account.id,
    runId = runId,
    observedAt = observedAt,
    extensionId = extensionId,
    values = linkedMapOf(
        "name" to name,
        "balance" to balance,
        "currency" to currency,
        "no" to no,
        "kind" to kind.name,
        "branchName" to branchName,
        "availableCredit" to availableCredit,
        "creditLimit" to creditLimit,
        "sourceAccountKey" to sourceAccountKey,
        "transferSync" to transferSync,
    ),
    sourceRecord = sourceRecord,
    sourceFields = sourceFields,
    sourceFacts = sourceFacts,
    parserVersion = parserVersion,
    parserVersionFallback = parserVersionFallback,
    gson = gson,
)

private fun tw.kevinzhang.extension_runtime.data.CardData.toFieldObservations(
    card: CreditCardInstrument,
    runId: String,
    observedAt: Long,
    extensionId: String,
    gson: Gson,
    parserVersionFallback: String,
): List<TransferFieldObservation> = assetFieldObservations(
    assetType = "CARD",
    assetId = card.id,
    runId = runId,
    observedAt = observedAt,
    extensionId = extensionId,
    values = linkedMapOf(
        "maskedPan" to maskedPan,
        "ref" to ref,
        "sourceCardKey" to sourceCardKey,
        "pan" to pan,
        "lastFour" to lastFour,
        "displayName" to displayName,
        "network" to network,
        "productType" to productType,
        "holderRole" to holderRole,
        "holderName" to holderName,
        "status" to status,
        "expiryMonth" to expiryMonth,
        "expiryYear" to expiryYear,
        "creditLimit" to creditLimit,
        "availableCredit" to availableCredit,
    ),
    sourceRecord = sourceRecord,
    sourceFields = sourceFields,
    sourceFacts = sourceFacts,
    parserVersion = parserVersion,
    parserVersionFallback = parserVersionFallback,
    gson = gson,
)

private fun assetFieldObservations(
    assetType: String,
    assetId: String,
    runId: String,
    observedAt: Long,
    extensionId: String,
    values: Map<String, Any?>,
    sourceRecord: Map<String, Any?>?,
    sourceFields: Map<String, Any?>?,
    sourceFacts: Map<String, Any?>?,
    parserVersion: String?,
    parserVersionFallback: String,
    gson: Gson,
): List<TransferFieldObservation> {
    val completeValues = buildMap {
        putAll(values)
        sourceFacts.orEmpty().forEach { (key, descriptor) ->
            put("sourceFact.$key", sourceValue(descriptor))
        }
    }
    val recordJson = sourceRecord?.let(gson::toJson)
    val recordDocumentId = sourceRecord?.get("sourceDocumentId") as? String
    return completeValues.map { (fieldName, value) ->
        val descriptor = if (fieldName.startsWith("sourceFact.")) {
            sourceFacts?.get(fieldName.removePrefix("sourceFact."))
        } else {
            sourceFields?.get(fieldName)
        }
        val locator = descriptor as? Map<*, *>
        TransferFieldObservation(
            id = UUID.randomUUID().toString(),
            runId = runId,
            transferId = null,
            extensionId = extensionId,
            observedAt = observedAt,
            fieldName = fieldName,
            valueJson = gson.toJson(value),
            sourceDocumentId = ((locator?.get("sourceDocumentId") ?: locator?.get("documentId")) as? String)
                ?: recordDocumentId,
            sourcePath = (
                locator?.get("locator")
                    ?: locator?.get("sourcePath")
                    ?: locator?.get("path")
                ) as? String,
            sourceRecordJson = recordJson,
            sourceFieldJson = descriptor?.let(gson::toJson),
            parserVersion = parserVersion ?: parserVersionFallback,
            assetType = assetType,
            assetId = assetId,
        )
    }
}

private fun sourceValue(descriptor: Any?): Any? =
    (descriptor as? Map<*, *>)?.takeIf { "value" in it }?.get("value") ?: descriptor

private fun validateResultEvidence(result: SyncResult.Success) {
    val capturedIds = result.sourceDocuments.map { it.id }
    require(capturedIds.size == capturedIds.toSet().size) { "captured source document ids must be unique" }
    val known = capturedIds.toSet()
    result.accounts.forEach { account ->
        validateDocumentReferences(account.sourceRecord, known)
        validateDocumentReferences(account.sourceFields, known)
        validateDocumentReferences(account.sourceFacts, known)
        account.cards.forEach { card ->
            validateDocumentReferences(card.sourceRecord, known)
            validateDocumentReferences(card.sourceFields, known)
            validateDocumentReferences(card.sourceFacts, known)
        }
        account.transfers.forEach { transfer ->
            validateDocumentReferences(transfer.sourceRecord, known)
            validateDocumentReferences(transfer.sourceFields, known)
            validateDocumentReferences(transfer.sourceFacts, known)
        }
    }
}

private fun validateDocumentReferences(value: Any?, knownDocumentIds: Set<String>) {
    when (value) {
        is Map<*, *> -> value.forEach { (key, child) ->
            if (key == "sourceDocumentId" || key == "documentId") {
                require(child is String && child in knownDocumentIds) {
                    "provenance references a source document outside this ingestion run"
                }
            } else {
                validateDocumentReferences(child, knownDocumentIds)
            }
        }
        is Iterable<*> -> value.forEach { validateDocumentReferences(it, knownDocumentIds) }
        is Array<*> -> value.forEach { validateDocumentReferences(it, knownDocumentIds) }
    }
}

private fun validateRichTransferValues(result: SyncResult.Success) {
    result.accounts.flatMap(AccountData::transfers).forEach { transfer ->
        require(transfer.amount.isFinite()) { "transfer amount must be finite" }
        listOf(
            "originalAmount" to transfer.originalAmount,
            "settlementAmount" to transfer.settlementAmount,
            "feeAmount" to transfer.feeAmount,
            "taxAmount" to transfer.taxAmount,
        ).forEach { (name, value) ->
            require(value == null || value.isFinite() && value >= 0.0) {
                "$name must be finite and non-negative"
            }
        }
        val exchangeRate = transfer.exchangeRate
        require(exchangeRate == null || exchangeRate.isFinite() && exchangeRate > 0.0) {
            "exchangeRate must be finite and positive"
        }
        require(transfer.direction == null || transfer.direction in setOf("debit", "credit")) {
            "direction must be debit or credit"
        }
    }
}
