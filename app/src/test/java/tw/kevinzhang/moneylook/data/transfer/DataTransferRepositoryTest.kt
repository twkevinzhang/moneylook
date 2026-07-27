package tw.kevinzhang.moneylook.data.transfer

import androidx.room.Room
import com.google.gson.Gson
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.db.AutoCategoryRuleCsvCodec
import tw.kevinzhang.core.data.db.AutoCategoryRuleCsvImport
import tw.kevinzhang.core.data.db.CredentialProfileCsvCodec
import tw.kevinzhang.core.data.db.CredentialProfileCsvExtension
import tw.kevinzhang.core.data.db.CredentialProfileCsvFieldDefinition
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.db.TransferCsvAccountMetadata
import tw.kevinzhang.core.data.db.TransferCsvCodec
import tw.kevinzhang.core.data.db.TransferCsvRecord
import tw.kevinzhang.core.data.db.TransferCsvTagAssignment
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AutoCategoryRule
import tw.kevinzhang.core.data.model.AutoCategoryRuleCondition
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionField
import tw.kevinzhang.core.data.model.AutoCategoryRuleConditionMatchMode
import tw.kevinzhang.core.data.model.AutoCategoryRuleOrigin
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.IngestionStatus
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.moneylook.security.ProtectedSourceFingerprint
import tw.kevinzhang.moneylook.security.SourceFingerprintProtector

@RunWith(RobolectricTestRunner::class)
class DataTransferRepositoryTest {
    private lateinit var database: MoneylookDatabase
    private lateinit var repository: DataTransferRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = DataTransferRepository(
            database = database,
            autoCategoryRuleDao = database.autoCategoryRuleDao(),
            categoryDao = database.categoryDao(),
            tagDao = database.tagDao(),
            accountDao = database.accountDao(),
            credentialProfileDao = database.credentialProfileDao(),
            installedExtensionDao = database.installedExtensionDao(),
            transferDao = database.transferDao(),
            transferAnnotationDao = database.transferAnnotationDao(),
            ingestionProvenanceDao = database.ingestionProvenanceDao(),
            sourceFingerprintProtector = FakeSourceFingerprintProtector,
            gson = Gson(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `rule export and import preserve details while excluding system defaults`() = runBlocking {
        val category = Category("food", "餐飲", "#123456")
        val tag = Tag("daily", "日常", "#654321")
        database.categoryDao().upsert(category)
        database.tagDao().upsert(tag)
        val detailedRule = AutoCategoryRule(
            id = "coffee",
            name = "咖啡",
            categoryId = category.id,
            priority = 7,
            origin = AutoCategoryRuleOrigin.USER_CONFIRMED,
        )
        val condition = AutoCategoryRuleCondition(
            ruleId = detailedRule.id,
            position = 0,
            field = AutoCategoryRuleConditionField.MERCHANT_NAME,
            matchMode = AutoCategoryRuleConditionMatchMode.CONTAINS,
            pattern = "咖啡",
        )
        database.autoCategoryRuleDao().upsertWithDetails(
            detailedRule,
            listOf(condition),
            setOf(tag.id),
        )
        val zeroConditionRule = AutoCategoryRule(
            id = "manual-only",
            name = "無條件規則",
            origin = AutoCategoryRuleOrigin.PRIVATE_LEARNED,
        )
        database.autoCategoryRuleDao().upsertWithDetails(
            zeroConditionRule,
            emptyList(),
            emptySet(),
        )
        database.autoCategoryRuleDao().upsert(
            AutoCategoryRule(
                id = "system-default",
                name = "系統預設",
                isDefault = true,
                origin = AutoCategoryRuleOrigin.PUBLIC_DEFAULT,
            ),
        )

        val csv = repository.exportAutoRules()
        assertTrue(csv.contains("coffee"))
        assertTrue(csv.contains("manual-only"))
        assertFalse(csv.contains("system-default"))

        database.autoCategoryRuleDao().upsertWithDetails(
            detailedRule.copy(name = "待覆寫"),
            emptyList(),
            emptySet(),
        )
        val prepared = repository.prepareAutoRules(csv)
            as PrepareDataImportResult.Success
        val rules = prepared.value as PreparedDataImport.AutoRules
        assertEquals(2, rules.preview.overwriteCount)
        assertTrue(rules.preview.canCommit)

        repository.commitAutoRules(rules)

        val imported = database.autoCategoryRuleDao().observeAll().first()
            .associateBy { it.rule.id }
        assertEquals(detailedRule, imported.getValue(detailedRule.id).rule)
        assertEquals(listOf(condition), imported.getValue(detailedRule.id).conditions)
        assertEquals(listOf(tag), imported.getValue(detailedRule.id).tags)
        assertTrue(imported.getValue(zeroConditionRule.id).conditions.isEmpty())
        assertNotNull(imported["system-default"])
    }

    @Test
    fun `invalid rule reference prevents every write`() = runBlocking {
        val valid = AutoCategoryRule(
            id = "valid",
            name = "有效規則",
            origin = AutoCategoryRuleOrigin.IMPORTED,
        )
        val invalid = AutoCategoryRule(
            id = "invalid",
            name = "無效規則",
            categoryId = "missing-category",
            origin = AutoCategoryRuleOrigin.IMPORTED,
        )
        val csv = AutoCategoryRuleCsvCodec.encode(
            AutoCategoryRuleCsvImport(
                rules = listOf(valid, invalid),
                conditionsByRuleId = emptyMap(),
            ),
        )
        val prepared = repository.prepareAutoRules(csv)
            as PrepareDataImportResult.Success
        val rules = prepared.value as PreparedDataImport.AutoRules
        assertFalse(rules.preview.canCommit)

        assertTrue(runCatching { repository.commitAutoRules(rules) }.isFailure)
        val ids = database.autoCategoryRuleDao().observeAll().first().map { it.rule.id }
        assertFalse("valid rule must roll back with invalid row", valid.id in ids)
        assertFalse(invalid.id in ids)
    }

    @Test
    fun `credential round trip keeps plaintext and preserves last run status`() = runBlocking {
        val extension = extension("bank")
        database.installedExtensionDao().insert(extension)
        val original = profile(
            extensionId = extension.id,
            username = "alice",
            password = "plain-secret",
            enabled = true,
            cron = "15 6 * * *",
        ).copy(lastRunAt = 100L, lastRunStatus = "success")
        database.credentialProfileDao().upsert(original)

        val csv = repository.exportCredentials()
        assertTrue("password must be exported as plaintext", csv.contains("plain-secret"))

        database.credentialProfileDao().upsert(
            profile(
                extensionId = extension.id,
                username = "changed",
                password = "changed-secret",
                enabled = false,
                cron = "0 8 * * *",
            ).copy(lastRunAt = 999L, lastRunStatus = "newer-status"),
        )
        val prepared = repository.prepareCredentials(csv)
            as PrepareDataImportResult.Success
        val credentials = prepared.value as PreparedDataImport.Credentials
        assertEquals(1, credentials.preview.overwriteCount)
        assertTrue(credentials.preview.canCommit)

        repository.commitCredentials(credentials)

        val imported = database.credentialProfileDao().getByExtensionId(extension.id)!!
        assertTrue(imported.credential.contains("plain-secret"))
        assertEquals(original.scheduleEnabled, imported.scheduleEnabled)
        assertEquals(original.scheduleCron, imported.scheduleCron)
        assertEquals(999L, imported.lastRunAt)
        assertEquals("newer-status", imported.lastRunStatus)
    }

    @Test
    fun `invalid credential row cannot be dropped for a partial commit`() = runBlocking {
        val installed = extension("installed")
        database.installedExtensionDao().insert(installed)
        val existing = profile(
            extensionId = installed.id,
            username = "before",
            password = "before-secret",
            enabled = false,
            cron = "0 7 * * *",
        )
        database.credentialProfileDao().upsert(existing)
        val fields = fields()
        val csv = CredentialProfileCsvCodec.encode(
            listOf(
                CredentialProfileCsvExtension(
                    extensionId = installed.id,
                    extensionName = installed.name,
                    profile = profile(
                        extensionId = installed.id,
                        username = "after",
                        password = "after-secret",
                        enabled = true,
                        cron = "0 9 * * *",
                    ),
                    fields = fields,
                ),
                CredentialProfileCsvExtension(
                    extensionId = "missing::repo",
                    extensionName = "未安裝銀行",
                    profile = profile(
                        extensionId = "missing::repo",
                        username = "missing",
                        password = "missing-secret",
                        enabled = true,
                        cron = "0 10 * * *",
                    ),
                    fields = fields,
                ),
            ),
        )
        val prepared = repository.prepareCredentials(csv)
            as PrepareDataImportResult.Success
        val credentials = prepared.value as PreparedDataImport.Credentials
        assertFalse(credentials.preview.canCommit)
        assertEquals(2, credentials.value.size)

        assertTrue(runCatching { repository.commitCredentials(credentials) }.isFailure)
        assertEquals(existing, database.credentialProfileDao().getByExtensionId(installed.id))
    }

    @Test
    fun `transaction import maps definitions skips incompatible rows and records manual file audit`() =
        runBlocking {
            val installed = extension("transactions")
            database.installedExtensionDao().insert(installed)
            val account = account(installed, "account-a", "source-a")
            database.accountDao().upsertAll(listOf(account))
            val localCategory = Category(
                id = "local-food",
                name = "CSV 測試餐飲",
                color = "#111111",
                reportingGroup = CategoryReportingGroup.EXPENSE,
            )
            val localTag = Tag("local-daily", "CSV 測試日常", "#222222")
            database.categoryDao().upsert(localCategory)
            database.tagDao().upsert(localTag)

            val overwriteId = "${account.id}::txn::overwrite"
            val unrelatedId = "${account.id}::txn::unrelated"
            database.transferDao().upsertAll(
                listOf(
                    transfer(overwriteId, account, amount = -1.0, description = "before"),
                    transfer(unrelatedId, account, amount = 99.0, description = "unrelated"),
                ),
            )
            val importedNewCategory = Category(
                id = "portable-new-category",
                name = "CSV 測試新分類",
                color = "#333333",
                reportingGroup = CategoryReportingGroup.EXPENSE,
            )
            val importedNewTag = Tag("portable-new-tag", "CSV 測試新標籤", "#444444")
            val records = listOf(
                transactionRecord(
                    account = account,
                    extension = installed,
                    transfer = transfer(
                        overwriteId,
                        account,
                        amount = -120.0,
                        description = "after",
                    ),
                    category = localCategory.copy(id = "portable-food", color = "#999999"),
                    tags = listOf(localTag.copy(id = "portable-daily", color = "#999999")),
                ),
                transactionRecord(
                    account = account,
                    extension = installed,
                    transfer = transfer(
                        "${account.id}::txn::new",
                        account,
                        amount = -88.0,
                        description = "new",
                    ),
                    category = importedNewCategory,
                    tags = listOf(importedNewTag),
                ),
                transactionRecord(
                    account = account,
                    extension = installed,
                    transfer = transfer(
                        "${account.id}::txn::conflict",
                        account,
                        amount = 5.0,
                        description = "conflict",
                    ),
                    category = localCategory.copy(
                        id = "portable-conflict",
                        reportingGroup = CategoryReportingGroup.INCOME,
                    ),
                ),
                transactionRecord(
                    account = account.copy(
                        id = "missing-account",
                        accountName = "找不到的帳戶",
                        sourceAccountKey = "missing-source",
                    ),
                    extension = installed,
                    transfer = transfer(
                        "missing-account::txn::missing",
                        account.copy(id = "missing-account"),
                        amount = 1.0,
                        description = "missing",
                    ),
                ),
            )
            val csvFile = writeTransactionCsv(records)

            val preparedResult = repository.prepareTransactions(csvFile, sha256(csvFile))
                as PrepareDataImportResult.Success
            val prepared = preparedResult.value as PreparedDataImport.Transactions
            assertEquals(1, prepared.preview.newCount)
            assertEquals(1, prepared.preview.overwriteCount)
            assertEquals(2, prepared.preview.skippedCount)
            assertEquals(2, prepared.preview.warningCount)
            assertTrue(prepared.preview.canCommit)

            repository.commitTransactions(prepared)

            val transfers = database.transferDao().getByIds(
                listOf(overwriteId, unrelatedId, "${account.id}::txn::new"),
            ).associateBy(Transfer::id)
            assertEquals(-120.0, transfers.getValue(overwriteId).amount, 0.0)
            assertEquals("after", transfers.getValue(overwriteId).description)
            assertEquals("unrelated", transfers.getValue(unrelatedId).description)
            assertNotNull(transfers["${account.id}::txn::new"])
            val overwriteAnnotation = database.transferAnnotationDao()
                .getByTransferIds(listOf(overwriteId))
                .single()
            assertEquals(localCategory.id, overwriteAnnotation.categoryId)
            assertEquals(
                listOf(localTag.id),
                database.transferAnnotationDao().getTagCrossRefs(overwriteId).map { it.tagId },
            )
            assertNotNull(database.categoryDao().getById(importedNewCategory.id))
            assertNotNull(database.tagDao().getById(importedNewTag.id))
            assertTrue(
                database.transferDao().getByIds(
                    listOf("${account.id}::txn::conflict", "missing-account::txn::missing"),
                ).isEmpty(),
            )

            val run = database.ingestionProvenanceDao().getRecentRuns(installed.id).single()
            assertEquals(IngestionTrigger.MANUAL_FILE, run.trigger)
            assertEquals(IngestionStatus.PARTIAL, run.status)
            assertEquals(2, run.transferCount)
            assertEquals(2, database.ingestionProvenanceDao()
                .getTransferIngestionEvents(overwriteId)
                .plus(
                    database.ingestionProvenanceDao()
                        .getTransferIngestionEvents("${account.id}::txn::new"),
                ).size)
            assertTrue(database.ingestionProvenanceDao().getSourceDocumentsForRun(run.id).isEmpty())
            csvFile.delete()
            Unit
        }

    @Test
    fun `fatal duplicate transaction id prevents every write`() = runBlocking {
        val installed = extension("duplicate")
        database.installedExtensionDao().insert(installed)
        val account = account(installed, "account-duplicate", "source-duplicate")
        database.accountDao().upsertAll(listOf(account))
        val first = transactionRecord(
            account,
            installed,
            transfer("${account.id}::txn::first", account, -1.0, "first"),
        )
        val second = transactionRecord(
            account,
            installed,
            transfer("${account.id}::txn::second", account, -2.0, "second"),
        )
        val validFile = writeTransactionCsv(listOf(first, second))
        val invalidFile = File.createTempFile("transactions-duplicate-", ".csv").apply {
            writeText(
                validFile.readText().replace(
                    "${account.id}::txn::second",
                    "${account.id}::txn::first",
                ),
            )
        }

        val result = repository.prepareTransactions(invalidFile, sha256(invalidFile))

        assertTrue(result is PrepareDataImportResult.Failure)
        val commitResult = runCatching {
            repository.commitTransactions(
                PreparedDataImport.Transactions(
                    cachedFile = invalidFile,
                    sha256 = sha256(invalidFile),
                    preview = DataImportPreview(newCount = 2, overwriteCount = 0),
                ),
            )
        }
        assertTrue(commitResult.isFailure)
        assertTrue(database.transferDao().getAll().isEmpty())
        assertTrue(database.ingestionProvenanceDao().getRecentRuns(installed.id).isEmpty())
        validFile.delete()
        invalidFile.delete()
        Unit
    }

    @Test
    fun `transaction export streams portable fields and excludes source evidence`() = runBlocking {
        val installed = extension("export")
        database.installedExtensionDao().insert(installed)
        val account = account(installed, "account-export", "source-export")
        database.accountDao().upsertAll(listOf(account))
        val exportedTransfer = transfer(
            "${account.id}::txn::export",
            account,
            -42.0,
            "=portable formula",
        ).copy(
            sourceRecordJson = "raw-record-secret",
            sourceFieldsJson = "raw-fields-secret",
            sourceFactsJson = "raw-facts-secret",
        )
        database.transferDao().upsertAll(listOf(exportedTransfer))
        val writer = StringWriter()

        repository.exportTransactions(writer)

        val csv = writer.toString()
        assertFalse(csv.contains("raw-record-secret"))
        assertFalse(csv.contains("raw-fields-secret"))
        assertFalse(csv.contains("raw-facts-secret"))
        assertTrue(csv.contains("'=portable formula"))
        val decoded = mutableListOf<TransferCsvRecord>()
        assertEquals(
            tw.kevinzhang.core.data.db.TransferCsvDecodeResult.Success(1),
            TransferCsvCodec.decode(StringReader(csv), decoded::add),
        )
        assertEquals("=portable formula", decoded.single().transfer.description)
    }

    private fun extension(id: String): InstalledExtension = InstalledExtension(
        id = "$id::repo",
        manifestId = id,
        name = "測試銀行 $id",
        version = 1,
        repoUrl = "https://github.com/example/$id",
        syncTriggerCachePath = "/tmp/$id.js",
        iconUrl = null,
        credentialFieldsJson =
            """[{"key":"username","label":"帳號","type":"text","required":true,"summary":true},""" +
                """{"key":"password","label":"密碼","type":"password","required":true,"summary":false}]""",
    )

    private fun fields(): List<CredentialProfileCsvFieldDefinition> = listOf(
        CredentialProfileCsvFieldDefinition(
            key = "username",
            label = "帳號",
            type = "text",
            required = true,
        ),
        CredentialProfileCsvFieldDefinition(
            key = "password",
            label = "密碼",
            type = "password",
            required = true,
        ),
    )

    private fun profile(
        extensionId: String,
        username: String,
        password: String,
        enabled: Boolean,
        cron: String,
    ): CredentialProfile = CredentialProfile(
        extensionId = extensionId,
        credential = Gson().toJson(
            linkedMapOf(
                "username" to username,
                "password" to password,
            ),
        ),
        scheduleEnabled = enabled,
        scheduleCron = cron,
        timezoneId = "Asia/Taipei",
    )

    private fun account(
        extension: InstalledExtension,
        id: String,
        sourceAccountKey: String,
    ) = Account(
        id = id,
        extensionId = extension.id,
        extensionName = extension.name,
        accountName = "測試帳戶 $id",
        balance = 1000.0,
        currency = "TWD",
        lastSyncAt = 1L,
        sourceAccountKey = sourceAccountKey,
        kind = AssetKind.DEPOSIT,
    )

    private fun transfer(
        id: String,
        account: Account,
        amount: Double,
        description: String,
    ) = Transfer(
        id = id,
        accountId = account.id,
        extensionId = account.extensionId,
        txnDateTime = "2026-07-27T12:00:00+08:00",
        description = description,
        amount = amount,
        balance = 100.0,
        memo = "memo",
    )

    private fun transactionRecord(
        account: Account,
        extension: InstalledExtension,
        transfer: Transfer,
        category: Category? = null,
        tags: List<Tag> = emptyList(),
    ): TransferCsvRecord {
        val annotation = if (category == null && tags.isEmpty()) {
            null
        } else {
            TransferAnnotation(
                transferId = transfer.id,
                extensionId = extension.id,
                categoryId = category?.id,
                note = "CSV note",
                categoryAssignment = AssignmentSource.MANUAL,
            )
        }
        return TransferCsvRecord(
            account = TransferCsvAccountMetadata(
                exportedAccountId = account.id,
                extensionId = extension.id,
                sourceAccountKey = account.sourceAccountKey,
                kind = account.kind,
                currency = account.currency,
                accountName = account.accountName,
                extensionName = extension.name,
            ),
            transfer = transfer,
            annotation = annotation,
            category = category,
            tags = tags.map { tag ->
                TransferCsvTagAssignment(tag, AssignmentSource.MANUAL)
            },
        )
    }

    private fun writeTransactionCsv(records: List<TransferCsvRecord>): File {
        val writer = StringWriter()
        TransferCsvCodec.write(writer, records)
        return File.createTempFile("transactions-", ".csv").apply {
            writeText(writer.toString())
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private object FakeSourceFingerprintProtector : SourceFingerprintProtector {
        override fun fingerprint(vararg components: String) = ProtectedSourceFingerprint(
            value = components.joinToString("|").hashCode().toString(),
            keyVersion = 1,
        )
    }
}
