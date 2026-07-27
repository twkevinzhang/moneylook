package tw.kevinzhang.moneylook.data.transfer

import androidx.room.withTransaction
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.google.gson.Gson
import java.io.File
import java.io.Writer
import java.security.MessageDigest
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.AutoCategoryRuleCsvCodec
import tw.kevinzhang.core.data.db.AutoCategoryRuleCsvDecodeResult
import tw.kevinzhang.core.data.db.AutoCategoryRuleCsvImport
import tw.kevinzhang.core.data.db.AutoCategoryRuleDao
import tw.kevinzhang.core.data.db.CategoryDao
import tw.kevinzhang.core.data.db.CredentialProfileCsvCodec
import tw.kevinzhang.core.data.db.CredentialProfileCsvDecodeResult
import tw.kevinzhang.core.data.db.CredentialProfileCsvExtension
import tw.kevinzhang.core.data.db.CredentialProfileCsvFieldDefinition
import tw.kevinzhang.core.data.db.CredentialProfileDao
import tw.kevinzhang.core.data.db.IngestionProvenanceDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.db.TagDao
import tw.kevinzhang.core.data.db.TransferAnnotationDao
import tw.kevinzhang.core.data.db.TransferCsvAccountMetadata
import tw.kevinzhang.core.data.db.TransferCsvCodec
import tw.kevinzhang.core.data.db.TransferCsvDecodeResult
import tw.kevinzhang.core.data.db.TransferCsvRecord
import tw.kevinzhang.core.data.db.TransferCsvTagAssignment
import tw.kevinzhang.core.data.db.TransferDao
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.IngestionClassificationStatus
import tw.kevinzhang.core.data.model.IngestionRun
import tw.kevinzhang.core.data.model.IngestionStatus
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.core.data.model.TransferIngestionEvent
import tw.kevinzhang.core.data.model.TransferObservation
import tw.kevinzhang.core.data.model.TransferTagCrossRef
import tw.kevinzhang.moneylook.security.SourceFingerprintProtector
import tw.kevinzhang.moneylook.ui.home.CredentialJsonCodec

data class DataImportPreview(
    val newCount: Int,
    val overwriteCount: Int,
    val skippedCount: Int = 0,
    val errors: List<String> = emptyList(),
    val warningCount: Int = 0,
    val warnings: List<String> = emptyList(),
) {
    val canCommit: Boolean get() = errors.isEmpty()
}

sealed interface PreparedDataImport {
    val preview: DataImportPreview

    data class AutoRules(
        val value: AutoCategoryRuleCsvImport,
        override val preview: DataImportPreview,
    ) : PreparedDataImport

    data class Credentials(
        val value: List<CredentialProfileCsvExtension>,
        override val preview: DataImportPreview,
    ) : PreparedDataImport

    data class Transactions(
        val cachedFile: File,
        val sha256: String,
        override val preview: DataImportPreview,
    ) : PreparedDataImport
}

sealed interface PrepareDataImportResult {
    data class Success(val value: PreparedDataImport) : PrepareDataImportResult
    data class Failure(val reason: String) : PrepareDataImportResult
}

/**
 * Owns the database boundary for CSV transfer. Parsing and reference validation complete before
 * Room is mutated, and each confirmed import is committed in one database transaction.
 */
class DataTransferRepository @Inject constructor(
    private val database: MoneylookDatabase,
    private val autoCategoryRuleDao: AutoCategoryRuleDao,
    private val categoryDao: CategoryDao,
    private val tagDao: TagDao,
    private val accountDao: AccountDao,
    private val credentialProfileDao: CredentialProfileDao,
    private val installedExtensionDao: InstalledExtensionDao,
    private val transferDao: TransferDao,
    private val transferAnnotationDao: TransferAnnotationDao,
    private val ingestionProvenanceDao: IngestionProvenanceDao,
    private val sourceFingerprintProtector: SourceFingerprintProtector,
    private val gson: Gson,
) {
    private val credentialJsonCodec = CredentialJsonCodec(gson)
    private val cronParser = CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX),
    )

    suspend fun exportAutoRules(): String {
        val rules = autoCategoryRuleDao.observeAll()
            .first()
            .filter { row ->
                !row.rule.isDefault &&
                    row.rule.origin != AutoCategoryRuleOrigin.PUBLIC_DEFAULT
            }
        return AutoCategoryRuleCsvCodec.encode(
            AutoCategoryRuleCsvImport(
                rules = rules.map { it.rule },
                conditionsByRuleId = rules.associate { row ->
                    row.rule.id to row.conditions
                },
                tagsByRuleId = rules.associate { row ->
                    row.rule.id to row.tags.map { it.id }
                },
            ),
        )
    }

    suspend fun prepareAutoRules(csv: String): PrepareDataImportResult =
        when (val decoded = AutoCategoryRuleCsvCodec.decode(csv)) {
            is AutoCategoryRuleCsvDecodeResult.Failure ->
                PrepareDataImportResult.Failure(decoded.reason)
            is AutoCategoryRuleCsvDecodeResult.Success -> {
                val errors = validateAutoRules(decoded.value)
                val existingIds = autoCategoryRuleDao.observeAll()
                    .first()
                    .mapTo(mutableSetOf()) { it.rule.id }
                PrepareDataImportResult.Success(
                    PreparedDataImport.AutoRules(
                        value = decoded.value,
                        preview = DataImportPreview(
                            newCount = decoded.value.rules.count { it.id !in existingIds },
                            overwriteCount = decoded.value.rules.count { it.id in existingIds },
                            errors = errors,
                        ),
                    ),
                )
            }
        }

    suspend fun commitAutoRules(import: PreparedDataImport.AutoRules) {
        database.withTransaction {
            val errors = validateAutoRules(import.value)
            require(errors.isEmpty()) { errors.joinToString("；") }
            import.value.rules.forEach { rule ->
                autoCategoryRuleDao.upsertWithDetails(
                    rule = rule,
                    conditions = import.value.conditionsByRuleId[rule.id].orEmpty(),
                    tagIds = import.value.tagsByRuleId[rule.id].orEmpty().toSet(),
                )
            }
        }
    }

    suspend fun exportCredentials(): String {
        val extensions = installedExtensionDao.getAll().associateBy { it.id }
        val rows = credentialProfileDao.getAll().map { profile ->
            val extension = requireNotNull(extensions[profile.extensionId]) {
                "登入資料對應的擴充不存在"
            }
            CredentialProfileCsvExtension(
                extensionId = extension.id,
                extensionName = extension.name,
                profile = profile,
                fields = credentialJsonCodec.parseFields(extension.credentialFieldsJson).map { field ->
                    CredentialProfileCsvFieldDefinition(
                        key = field.key,
                        label = field.label,
                        type = field.type,
                        required = field.required,
                    )
                },
            )
        }
        require(rows.isNotEmpty()) { "沒有可匯出的帳號密碼" }
        return CredentialProfileCsvCodec.encode(rows)
    }

    suspend fun prepareCredentials(csv: String): PrepareDataImportResult =
        when (val decoded = CredentialProfileCsvCodec.decode(csv)) {
            is CredentialProfileCsvDecodeResult.Failure ->
                PrepareDataImportResult.Failure(decoded.reason)
            is CredentialProfileCsvDecodeResult.Success -> {
                val normalized = normalizeAndValidateCredentials(decoded.value)
                val existingIds = credentialProfileDao.getAll()
                    .mapTo(mutableSetOf(), CredentialProfile::extensionId)
                PrepareDataImportResult.Success(
                    PreparedDataImport.Credentials(
                        // Keep every decoded row in the prepared payload. Invalid rows must still
                        // be present when commit re-validates inside the Room transaction; dropping
                        // them here could let a caller bypass the preview error and partially import.
                        value = decoded.value,
                        preview = DataImportPreview(
                            newCount = decoded.value.count { it.extensionId !in existingIds },
                            overwriteCount = decoded.value.count { it.extensionId in existingIds },
                            errors = normalized.errors,
                        ),
                    ),
                )
            }
        }

    /**
     * Returns the committed profiles so callers can update WorkManager after Room succeeds.
     * This method never starts a synchronization.
     */
    suspend fun commitCredentials(import: PreparedDataImport.Credentials): List<CredentialProfile> {
        val committed = mutableListOf<CredentialProfile>()
        database.withTransaction {
            val normalized = normalizeAndValidateCredentials(import.value)
            require(normalized.errors.isEmpty()) { normalized.errors.joinToString("；") }
            val existing = credentialProfileDao.getAll().associateBy { it.extensionId }
            normalized.value.forEach { row ->
                val previous = existing[row.extensionId]
                val profile = row.profile.copy(
                    lastRunAt = previous?.lastRunAt,
                    lastRunStatus = previous?.lastRunStatus,
                )
                credentialProfileDao.upsert(profile)
                committed += profile
            }
        }
        return committed
    }

    /** Writes all transactions in stable, bounded Room pages directly to the selected SAF stream. */
    suspend fun exportTransactions(writer: Writer) {
        val accounts = accountDao.observeAll().first().associateBy(Account::id)
        val extensions = installedExtensionDao.getAll().associateBy(InstalledExtension::id)
        val encoder = TransferCsvCodec.encoder(writer)
        var offset = 0
        while (true) {
            val page = transferAnnotationDao.getAllDetailsPage(TRANSACTION_PAGE_SIZE, offset)
            if (page.isEmpty()) break
            val transferIds = page.map { it.transfer.id }
            val sourcesByTransferAndTag = transferAnnotationDao
                .getTagCrossRefsForTransferIds(transferIds)
                .associateBy { it.transferId to it.tagId }
            page.forEach { detail ->
                val account = requireNotNull(accounts[detail.transfer.accountId]) {
                    "交易對應的帳戶不存在"
                }
                val sourceAccountKey = requireNotNull(account.sourceAccountKey?.takeIf(String::isNotBlank)) {
                    "帳戶「${account.accountName}」缺少可攜式來源識別，無法匯出"
                }
                val extension = requireNotNull(extensions[account.extensionId]) {
                    "交易對應的擴充不存在"
                }
                encoder.write(
                    TransferCsvRecord(
                        account = TransferCsvAccountMetadata(
                            exportedAccountId = account.id,
                            extensionId = account.extensionId,
                            sourceAccountKey = sourceAccountKey,
                            kind = account.kind,
                            currency = account.currency,
                            accountName = account.accountName,
                            extensionName = extension.name,
                        ),
                        transfer = detail.transfer.copy(
                            sourceRecordJson = null,
                            sourceFieldsJson = null,
                            sourceFactsJson = null,
                        ),
                        annotation = detail.annotation,
                        category = detail.category,
                        tags = detail.tags.map { tag ->
                            TransferCsvTagAssignment(
                                tag = tag,
                                source = requireNotNull(
                                    sourcesByTransferAndTag[detail.transfer.id to tag.id],
                                ) { "交易標籤來源不存在" }.source,
                            )
                        },
                    ),
                )
            }
            offset += page.size
        }
        encoder.finish()
    }

    suspend fun prepareTransactions(
        cachedFile: File,
        sha256: String,
    ): PrepareDataImportResult {
        if (!cachedFile.isFile || fileSha256(cachedFile) != sha256) {
            return PrepareDataImportResult.Failure("暫存 CSV 已變更")
        }
        val resolver = newTransactionResolver()
        val counters = TransactionImportCounters()
        val decoded = cachedFile.bufferedReader(Charsets.UTF_8).use { reader ->
            TransferCsvCodec.decode(reader) { record ->
                when (val resolution = resolver.resolve(record)) {
                    is TransactionResolution.Accepted -> counters.accept(
                        resolution.record.transfer.id in resolver.existingTransferIds,
                    )
                    is TransactionResolution.Skipped -> counters.skip(resolution.reason)
                }
            }
        }
        return when (decoded) {
            is TransferCsvDecodeResult.Failure -> PrepareDataImportResult.Failure(decoded.reason)
            is TransferCsvDecodeResult.Success -> PrepareDataImportResult.Success(
                PreparedDataImport.Transactions(
                    cachedFile = cachedFile,
                    sha256 = sha256,
                    preview = counters.preview(),
                ),
            )
        }
    }

    /**
     * Re-opens the immutable cache and writes the accepted subset in one Room transaction.
     * A fatal error discovered after earlier callbacks still throws before commit, rolling back all
     * transfers, definitions, annotations, tag links, and MANUAL_FILE audit rows.
     */
    suspend fun commitTransactions(import: PreparedDataImport.Transactions) {
        require(import.cachedFile.isFile && fileSha256(import.cachedFile) == import.sha256) {
            "暫存 CSV 已變更"
        }
        database.withTransaction {
            val resolver = newTransactionResolver()
            val counters = TransactionImportCounters()
            val insertedCategoryIds = mutableSetOf<String>()
            val insertedTagIds = mutableSetOf<String>()
            val auditByExtension = linkedMapOf<String, TransactionAuditAccumulator>()
            val pendingEvents = mutableListOf<TransferIngestionEvent>()
            val startedAt = System.currentTimeMillis()

            suspend fun flushEvents() {
                if (pendingEvents.isNotEmpty()) {
                    ingestionProvenanceDao.insertTransferEvents(pendingEvents.toList())
                    pendingEvents.clear()
                }
            }

            val decoded = import.cachedFile.bufferedReader(Charsets.UTF_8).use { reader ->
                TransferCsvCodec.decodeSuspending(reader) { record ->
                    when (val resolution = resolver.resolve(record)) {
                        is TransactionResolution.Skipped -> counters.skip(resolution.reason)
                        is TransactionResolution.Accepted -> {
                            val accepted = resolution.record
                            val existed = accepted.transfer.id in resolver.existingTransferIds
                            counters.accept(existed)
                            accepted.category?.let { category ->
                                if (category.id !in resolver.originalCategoryIds &&
                                    insertedCategoryIds.add(category.id)
                                ) {
                                    categoryDao.upsert(category)
                                }
                            }
                            accepted.tags.forEach { assignment ->
                                if (assignment.tag.id !in resolver.originalTagIds &&
                                    insertedTagIds.add(assignment.tag.id)
                                ) {
                                    tagDao.upsert(assignment.tag)
                                }
                            }
                            transferDao.upsertAll(listOf(accepted.transfer))
                            transferAnnotationDao.replaceImportedMetadata(
                                transferId = accepted.transfer.id,
                                annotation = accepted.annotation,
                                tagCrossRefs = accepted.tags.map { assignment ->
                                    TransferTagCrossRef(
                                        transferId = accepted.transfer.id,
                                        tagId = assignment.tag.id,
                                        source = assignment.source,
                                    )
                                },
                            )

                            val audit = auditByExtension.getOrPut(accepted.transfer.extensionId) {
                                TransactionAuditAccumulator(
                                    runId = UUID.randomUUID().toString(),
                                    extension = resolver.extensions.getValue(
                                        accepted.transfer.extensionId,
                                    ),
                                )
                            }
                            audit.accountIds += accepted.transfer.accountId
                            audit.transferCount++
                            val sourceFingerprint = sourceFingerprintProtector.fingerprint(
                                "transaction-csv-source",
                                import.sha256,
                                accepted.transfer.id,
                            )
                            val payloadFingerprint = sourceFingerprintProtector.fingerprint(
                                "transaction-csv-payload",
                                gson.toJson(accepted.transfer),
                            )
                            require(
                                sourceFingerprint.keyVersion == payloadFingerprint.keyVersion,
                            ) { "稽核指紋版本不一致" }
                            pendingEvents += TransferIngestionEvent(
                                id = UUID.randomUUID().toString(),
                                runId = audit.runId,
                                occurredAt = System.currentTimeMillis(),
                                transferId = accepted.transfer.id,
                                extensionId = accepted.transfer.extensionId,
                                observation = if (existed) {
                                    TransferObservation.UPDATED
                                } else {
                                    TransferObservation.INSERTED
                                },
                                sourceFingerprint = sourceFingerprint.value,
                                payloadFingerprint = payloadFingerprint.value,
                                fingerprintKeyVersion = sourceFingerprint.keyVersion,
                                hasDescription = accepted.transfer.description.isNotBlank(),
                                hasMemo = accepted.transfer.memo.isNotBlank(),
                                hasType = !accepted.transfer.type.isNullOrBlank(),
                                hasMerchantName = !accepted.transfer.merchantName.isNullOrBlank(),
                                hasMerchantCategoryCode =
                                    !accepted.transfer.merchantCategoryCode.isNullOrBlank(),
                                hasCounterpartyName =
                                    !accepted.transfer.counterpartyName.isNullOrBlank(),
                                hasPurpose = !accepted.transfer.purpose.isNullOrBlank(),
                            )
                            if (pendingEvents.size >= AUDIT_EVENT_BATCH_SIZE) flushEvents()
                        }
                    }
                }
            }
            require(decoded is TransferCsvDecodeResult.Success) {
                (decoded as TransferCsvDecodeResult.Failure).reason
            }
            require(counters.preview() == import.preview) {
                "匯入期間資料狀態已變更，請重新預覽"
            }
            flushEvents()
            val completedAt = System.currentTimeMillis()
            auditByExtension.values.forEach { audit ->
                val runFingerprint = sourceFingerprintProtector.fingerprint(
                    "transaction-csv-run",
                    import.sha256,
                    audit.extension.id,
                )
                ingestionProvenanceDao.insertRun(
                    IngestionRun(
                        id = audit.runId,
                        startedAt = startedAt,
                        completedAt = completedAt,
                        extensionId = audit.extension.id,
                        extensionVersion = audit.extension.version,
                        artifactRevision = audit.extension.artifactRevision,
                        artifactSha256 = audit.extension.artifactSha256,
                        trigger = IngestionTrigger.MANUAL_FILE,
                        status = if (resolver.skippedExtensionIds.contains(audit.extension.id)) {
                            IngestionStatus.PARTIAL
                        } else {
                            IngestionStatus.SUCCESS
                        },
                        classificationStatus = IngestionClassificationStatus.COMPLETE,
                        classificationCompletedAt = completedAt,
                        accountCount = audit.accountIds.size,
                        transferCount = audit.transferCount,
                        sourceFingerprint = runFingerprint.value,
                        fingerprintKeyVersion = runFingerprint.keyVersion,
                    ),
                )
            }
        }
    }

    private suspend fun newTransactionResolver(): TransactionResolver = TransactionResolver(
        extensions = installedExtensionDao.getAll().associateBy(InstalledExtension::id),
        accounts = accountDao.observeAll().first(),
        categories = categoryDao.observeAll().first(),
        tags = tagDao.observeAll().first(),
        existingTransferIds = transferDao.getAllIds().toSet(),
    )

    private class TransactionResolver(
        val extensions: Map<String, InstalledExtension>,
        accounts: List<Account>,
        categories: List<Category>,
        tags: List<Tag>,
        val existingTransferIds: Set<String>,
    ) {
        val originalCategoryIds = categories.mapTo(mutableSetOf(), Category::id)
        val originalTagIds = tags.mapTo(mutableSetOf(), Tag::id)
        val skippedExtensionIds = mutableSetOf<String>()
        private val accountsByKey = accounts.groupBy { account ->
            AccountMatchKey(
                extensionId = account.extensionId,
                sourceAccountKey = account.sourceAccountKey.orEmpty(),
                kind = account.kind.name,
                currency = account.currency,
            )
        }
        private val categoriesById = categories.associateByTo(linkedMapOf(), Category::id)
        private val categoriesByName = categories.associateByTo(linkedMapOf()) {
            it.name.lowercase(Locale.ROOT)
        }
        private val tagsById = tags.associateByTo(linkedMapOf(), Tag::id)
        private val tagsByName = tags.associateByTo(linkedMapOf()) {
            it.name.lowercase(Locale.ROOT)
        }
        private val resolvedTransferIds = mutableSetOf<String>()

        fun resolve(record: TransferCsvRecord): TransactionResolution {
            val account = record.account
            if (account.extensionId !in extensions) {
                skippedExtensionIds += account.extensionId
                return TransactionResolution.Skipped(
                    "未安裝擴充「${account.extensionName}」，略過其交易",
                )
            }
            val candidates = accountsByKey[
                AccountMatchKey(
                    extensionId = account.extensionId,
                    sourceAccountKey = account.sourceAccountKey.orEmpty(),
                    kind = account.kind.name,
                    currency = account.currency,
                ),
            ].orEmpty()
            if (candidates.size != 1) {
                skippedExtensionIds += account.extensionId
                return TransactionResolution.Skipped(
                    if (candidates.isEmpty()) {
                        "找不到相符帳戶「${account.accountName}」，略過其交易"
                    } else {
                        "帳戶「${account.accountName}」匹配到多個候選，略過其交易"
                    },
                )
            }
            val targetAccount = candidates.single()
            val targetTransferId = remapTransferId(
                exportedId = record.transfer.id,
                exportedAccountId = account.exportedAccountId,
                targetAccountId = targetAccount.id,
            ) ?: run {
                skippedExtensionIds += account.extensionId
                return TransactionResolution.Skipped(
                    "帳戶「${account.accountName}」的交易識別無法安全映射",
                )
            }
            require(resolvedTransferIds.add(targetTransferId)) {
                "multiple imported transactions resolve to the same target id"
            }

            val mappedCategory = when (val mapped = mapCategory(record.category)) {
                is DefinitionResolution.Success -> mapped.value
                is DefinitionResolution.Conflict -> {
                    skippedExtensionIds += account.extensionId
                    return TransactionResolution.Skipped(mapped.reason)
                }
            }
            val mappedTags = mutableListOf<TransferCsvTagAssignment>()
            record.tags.forEach { assignment ->
                when (val mapped = mapTag(assignment.tag)) {
                    is DefinitionResolution.Success -> mappedTags += assignment.copy(
                        tag = mapped.value,
                    )
                    is DefinitionResolution.Conflict -> {
                        skippedExtensionIds += account.extensionId
                        return TransactionResolution.Skipped(mapped.reason)
                    }
                }
            }
            val mappedCardInstrumentId = record.transfer.cardInstrumentId?.let { cardId ->
                when {
                    targetAccount.id == account.exportedAccountId -> cardId
                    cardId.startsWith("${account.exportedAccountId}::card::") ->
                        targetAccount.id + cardId.removePrefix(account.exportedAccountId)
                    else -> null
                }
            }
            val transfer = record.transfer.copy(
                id = targetTransferId,
                accountId = targetAccount.id,
                extensionId = targetAccount.extensionId,
                cardInstrumentId = mappedCardInstrumentId,
                sourceRecordJson = null,
                sourceFieldsJson = null,
                sourceFactsJson = null,
            )
            val annotation = record.annotation?.copy(
                transferId = targetTransferId,
                extensionId = targetAccount.extensionId,
                categoryId = mappedCategory?.id,
            )
            return TransactionResolution.Accepted(
                record.copy(
                    account = account.copy(
                        exportedAccountId = targetAccount.id,
                        extensionId = targetAccount.extensionId,
                        sourceAccountKey = targetAccount.sourceAccountKey,
                        kind = targetAccount.kind,
                        currency = targetAccount.currency,
                        accountName = targetAccount.accountName,
                        extensionName = targetAccount.extensionName,
                    ),
                    transfer = transfer,
                    annotation = annotation,
                    category = mappedCategory,
                    tags = mappedTags,
                ),
            )
        }

        private fun mapCategory(imported: Category?): DefinitionResolution<Category?> {
            if (imported == null) return DefinitionResolution.Success(null)
            categoriesById[imported.id]?.let { return DefinitionResolution.Success(it) }
            val nameKey = imported.name.lowercase(Locale.ROOT)
            categoriesByName[nameKey]?.let { sameName ->
                return if (sameName.reportingGroup == imported.reportingGroup) {
                    DefinitionResolution.Success(sameName)
                } else {
                    DefinitionResolution.Conflict(
                        "分類「${imported.name}」名稱相同但種類不同，略過受影響交易",
                    )
                }
            }
            categoriesById[imported.id] = imported
            categoriesByName[nameKey] = imported
            return DefinitionResolution.Success(imported)
        }

        private fun mapTag(imported: Tag): DefinitionResolution<Tag> {
            tagsById[imported.id]?.let { return DefinitionResolution.Success(it) }
            val nameKey = imported.name.lowercase(Locale.ROOT)
            tagsByName[nameKey]?.let { return DefinitionResolution.Success(it) }
            tagsById[imported.id] = imported
            tagsByName[nameKey] = imported
            return DefinitionResolution.Success(imported)
        }

        private fun remapTransferId(
            exportedId: String,
            exportedAccountId: String,
            targetAccountId: String,
        ): String? = when {
            targetAccountId == exportedAccountId -> exportedId
            exportedId.startsWith("$exportedAccountId::txn::") ->
                targetAccountId + exportedId.removePrefix(exportedAccountId)
            else -> null
        }
    }

    private class TransactionImportCounters {
        private var newCount = 0
        private var overwriteCount = 0
        private var skippedCount = 0
        private val warnings = linkedSetOf<String>()

        fun accept(overwrite: Boolean) {
            if (overwrite) overwriteCount++ else newCount++
        }

        fun skip(reason: String) {
            skippedCount++
            if (warnings.size < MAX_PREVIEW_WARNINGS) warnings += reason
        }

        fun preview() = DataImportPreview(
            newCount = newCount,
            overwriteCount = overwriteCount,
            skippedCount = skippedCount,
            warningCount = skippedCount,
            warnings = warnings.toList(),
        )
    }

    private sealed interface TransactionResolution {
        data class Accepted(val record: TransferCsvRecord) : TransactionResolution
        data class Skipped(val reason: String) : TransactionResolution
    }

    private sealed interface DefinitionResolution<out T> {
        data class Success<T>(val value: T) : DefinitionResolution<T>
        data class Conflict(val reason: String) : DefinitionResolution<Nothing>
    }

    private data class AccountMatchKey(
        val extensionId: String,
        val sourceAccountKey: String,
        val kind: String,
        val currency: String,
    )

    private data class TransactionAuditAccumulator(
        val runId: String,
        val extension: InstalledExtension,
        val accountIds: MutableSet<String> = mutableSetOf(),
        var transferCount: Int = 0,
    )

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private suspend fun validateAutoRules(import: AutoCategoryRuleCsvImport): List<String> {
        val categories = categoryDao.observeAll().first().mapTo(mutableSetOf()) { it.id }
        val tags = tagDao.observeAll().first().mapTo(mutableSetOf()) { it.id }
        val accounts = accountDao.observeAll().first().mapTo(mutableSetOf()) { it.id }
        val extensions = installedExtensionDao.getAll().mapTo(mutableSetOf()) { it.id }
        return buildList {
            import.rules.forEach { rule ->
                if (rule.isDefault || rule.origin == AutoCategoryRuleOrigin.PUBLIC_DEFAULT) {
                    add("規則「${rule.name}」是系統預設規則，不能由 CSV 覆蓋")
                }
                rule.categoryId?.takeIf { it !in categories }?.let {
                    add("規則「${rule.name}」找不到分類 $it")
                }
                rule.accountId?.takeIf { it !in accounts }?.let {
                    add("規則「${rule.name}」找不到帳戶 $it")
                }
                rule.extensionId?.takeIf { it !in extensions }?.let {
                    add("規則「${rule.name}」找不到擴充 $it")
                }
                import.tagsByRuleId[rule.id].orEmpty()
                    .filterNot(tags::contains)
                    .forEach { add("規則「${rule.name}」找不到標籤 $it") }
            }
        }.distinct()
    }

    private suspend fun normalizeAndValidateCredentials(
        rows: List<CredentialProfileCsvExtension>,
    ): CredentialValidation {
        val installed = installedExtensionDao.getAll().associateBy { it.id }
        val normalized = mutableListOf<CredentialProfileCsvExtension>()
        val errors = mutableListOf<String>()
        rows.forEach { row ->
            val extension = installed[row.extensionId]
            if (extension == null) {
                errors += "找不到擴充 ${row.extensionName}（${row.extensionId}）"
                return@forEach
            }
            val expectedFields = credentialJsonCodec.parseFields(extension.credentialFieldsJson)
            val importedFields = row.fields.associateBy { it.key }
            val schemaMatches = expectedFields.size == importedFields.size &&
                expectedFields.all { expected ->
                    importedFields[expected.key]?.let { imported ->
                        imported.label == expected.label &&
                            imported.type == expected.type &&
                            imported.required == expected.required
                    } == true
                }
            if (!schemaMatches) {
                errors += "擴充「${extension.name}」的登入欄位已變更"
                return@forEach
            }
            val values = credentialJsonCodec.parseCredential(row.profile.credential)
            if (values == null || values.keys != importedFields.keys) {
                errors += "擴充「${extension.name}」的登入資料欄位不完整"
                return@forEach
            }
            val resolution = credentialJsonCodec.resolveForSave(
                fields = expectedFields,
                submittedValues = values,
                existingValues = emptyMap(),
            )
            if (resolution.missingRequiredField != null) {
                errors += "擴充「${extension.name}」缺少${resolution.missingRequiredField.label}"
                return@forEach
            }
            if (row.profile.scheduleEnabled &&
                !isValidSchedule(row.profile.scheduleCron, row.profile.timezoneId)
            ) {
                errors += "擴充「${extension.name}」的排程或時區格式不正確"
                return@forEach
            }
            normalized += row.copy(
                extensionName = extension.name,
                profile = row.profile.copy(
                    credential = credentialJsonCodec.encodeCredential(resolution.values),
                    scheduleCron = row.profile.scheduleCron.trim(),
                    timezoneId = row.profile.timezoneId.trim(),
                    lastRunAt = null,
                    lastRunStatus = null,
                ),
            )
        }
        return CredentialValidation(normalized, errors.distinct())
    }

    private fun isValidSchedule(cron: String, timezoneId: String): Boolean = runCatching {
        ZoneId.of(timezoneId)
        cronParser.parse(cron).validate()
    }.isSuccess

    private data class CredentialValidation(
        val value: List<CredentialProfileCsvExtension>,
        val errors: List<String>,
    )

    private companion object {
        const val TRANSACTION_PAGE_SIZE = 250
        const val AUDIT_EVENT_BATCH_SIZE = 250
        const val MAX_PREVIEW_WARNINGS = 20
    }
}
