package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CreditCardInstrument
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.core.data.model.IngestionStatus
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.core.data.model.TransferFieldObservation

@RunWith(RobolectricTestRunner::class)
class SyncResultDaoTest {
    private lateinit var database: MoneylookDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            MoneylookDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `failed ingestion atomically retains all raw failure fields on its run`() = runBlocking {
        database.syncResultDao().recordFailedIngestion(
            extensionId = "extension",
            ingestionContext = IngestionContext(
                runId = "failed-run",
                startedAt = 10,
                completedAt = 20,
                extensionVersion = 9,
                artifactRevision = "revision",
                artifactSha256 = "sha",
                trigger = IngestionTrigger.USER_SYNC,
                status = IngestionStatus.FAILED,
                sourceFingerprint = "fingerprint",
                fingerprintKeyVersion = 1,
                transferFingerprints = emptyMap(),
                failureOrigin = "SCRIPT",
                failureCode = "BANK_REJECTED",
                failureMessage = "authenticated response was rejected",
                failureStack = "Error: authenticated response was rejected\n at bank.js:71:9",
                failureDiagnosticJson = """{"thrown":{"credential":"test-secret"}}""",
                failureScriptFrame = "line 71, column 9",
            ),
            accountCount = 0,
            transferCount = 0,
        )

        val run = database.ingestionProvenanceDao().getRecentRuns("extension").single()
        assertEquals("SCRIPT", run.failureOrigin)
        assertEquals("BANK_REJECTED", run.failureCode)
        assertEquals("authenticated response was rejected", run.failureMessage)
        assertTrue(requireNotNull(run.failureStack).contains("bank.js:71:9"))
        assertEquals("""{"thrown":{"credential":"test-secret"}}""", run.failureDiagnosticJson)
        assertEquals("line 71, column 9", run.failureScriptFrame)
    }

    @Test
    fun `completed ranges replace only their history and preserve older data in one transaction`() = runBlocking {
        val store = database.syncResultDao()
        val account = account("account")
        val legacyAccount = account("legacy-account")
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account, legacyAccount),
            transfers = listOf(
                transfer("old-outside", "2026-05-10", 1.0),
                transfer("old-in-range", "2026-06-10", 2.0),
                transfer("expired", "2024-01-01", 3.0),
                transfer("legacy-old", "2024-01-01", 6.0, accountId = "legacy-account"),
            ),
            refreshes = listOf(
                AccountTransferRefresh("account", null),
                AccountTransferRefresh("legacy-account", null),
            ),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account.copy(transferSyncComplete = false), legacyAccount),
            transfers = listOf(
                transfer("replacement", "2026-06-10", 4.0),
                transfer("partial-page", "2026-05-11", 5.0),
                transfer("legacy-old", "2024-01-01", 6.0, accountId = "legacy-account"),
            ),
            refreshes = listOf(
                AccountTransferRefresh(
                    accountId = "account",
                    completedRanges = listOf(TransferDateRange("2026-06-01", "2026-06-30")),
                ),
                AccountTransferRefresh("legacy-account", null),
            ),
        )

        val transfers = database.transferDao().observeByAccount("account").first()
        assertEquals(
            setOf("replacement", "partial-page", "old-outside", "expired"),
            transfers.map(Transfer::id).toSet(),
        )
        assertFalse(transfers.any { it.id == "old-in-range" })
        assertEquals(
            listOf("legacy-old"),
            database.transferDao().observeByAccount("legacy-account").first().map(Transfer::id),
        )
        assertEquals(
            false,
            database.accountDao().observeAll().first().single { it.id == "account" }.transferSyncComplete,
        )
    }

    @Test
    fun `pending to posted upsert with a stable transfer id preserves manual category tags and note`() = runBlocking {
        val store = database.syncResultDao()
        val annotationDao = database.transferAnnotationDao()
        val account = account("account")
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account),
            transfers = listOf(
                transfer("stable", "2026-07-21", -100.0).copy(status = "pending"),
            ),
            refreshes = listOf(AccountTransferRefresh("account", null)),
        )
        database.categoryDao().upsert(Category("food", "餐飲", "#2E7D32"))
        database.tagDao().upsert(Tag("work", "公司", "#1565C0"))
        annotationDao.saveManualAnnotation(
            TransferAnnotation(
                transferId = "stable",
                extensionId = "extension",
                categoryId = "food",
                note = "保留這個備註",
                categoryAssignment = AssignmentSource.MANUAL,
            ),
            setOf("work"),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account),
            transfers = listOf(
                transfer("stable", "2026-07-21", -100.0).copy(
                    description = "銀行更新後的描述",
                    status = "posted",
                    postingDateTime = "2026-07-22T09:00:00",
                ),
            ),
            refreshes = listOf(
                AccountTransferRefresh("account", listOf(TransferDateRange("2026-07-21", "2026-07-21"))),
            ),
        )

        annotationDao.observeDetail("stable").first()!!.also { detail ->
            assertEquals("銀行更新後的描述", detail.transfer.description)
            assertEquals("food", detail.annotation?.categoryId)
            assertEquals("保留這個備註", detail.annotation?.note)
            assertEquals(AssignmentSource.MANUAL, detail.annotation?.categoryAssignment)
            assertEquals(listOf("work"), detail.tags.map(Tag::id))
            assertEquals("posted", detail.transfer.status)
            assertEquals("2026-07-22T09:00:00", detail.transfer.postingDateTime)
        }
        Unit
    }

    @Test
    fun `partial card snapshots preserve prior cards while complete snapshots remove stale cards safely`() = runBlocking {
        val store = database.syncResultDao()
        val cardAccount = account("card-account", kind = AssetKind.CREDIT_CARD)
        val first = cardInstrument("card-1")
        val stale = cardInstrument("card-2")
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(cardAccount),
            transfers = listOf(
                transfer("purchase", "2026-07-21", -1.0, "card-account")
                    .copy(cardInstrumentId = stale.id),
            ),
            refreshes = listOf(AccountTransferRefresh("card-account", null)),
            cardInstruments = listOf(first, stale),
            replaceCardAccountIds = setOf("card-account"),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(cardAccount),
            transfers = emptyList(),
            refreshes = listOf(AccountTransferRefresh("card-account", emptyList())),
            cardInstruments = listOf(first.copy(displayName = "更新卡名")),
            replaceCardAccountIds = emptySet(),
        )
        assertEquals(
            setOf("card-1", "card-2"),
            database.creditCardInstrumentDao().observeByAccount("card-account").first().map { it.id }.toSet(),
        )
        assertEquals(
            "card-2",
            database.transferDao().observeByAccount("card-account").first().single().cardInstrumentId,
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(cardAccount),
            transfers = emptyList(),
            refreshes = listOf(AccountTransferRefresh("card-account", emptyList())),
            cardInstruments = listOf(first),
            replaceCardAccountIds = setOf("card-account"),
        )
        assertEquals(
            listOf("card-1"),
            database.creditCardInstrumentDao().observeByAccount("card-account").first().map { it.id },
        )
        assertEquals(
            null,
            database.transferDao().observeByAccount("card-account").first().single().cardInstrumentId,
        )
    }

    @Test
    fun `next day incremental replacement retains the initial six month history`() = runBlocking {
        val store = database.syncResultDao()
        val account = account("account")
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account),
            transfers = listOf(
                transfer("jan", "2026-01-15", 1.0),
                transfer("apr", "2026-04-15", 2.0),
                transfer("jul", "2026-07-21", 3.0),
            ),
            refreshes = listOf(AccountTransferRefresh("account", null)),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account.copy(transferSyncComplete = true)),
            transfers = listOf(transfer("next-day", "2026-07-22", 4.0)),
            refreshes = listOf(
                AccountTransferRefresh("account", listOf(TransferDateRange("2026-07-22", "2026-07-22"))),
            ),
        )

        assertEquals(
            setOf("jan", "apr", "jul", "next-day"),
            database.transferDao().observeByAccount("account").first().map(Transfer::id).toSet(),
        )
    }

    @Test
    fun `partial and empty completed ranges retain transactions outside successful ranges`() = runBlocking {
        val store = database.syncResultDao()
        val account = account("account")
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account),
            transfers = listOf(
                transfer("june", "2026-06-30", 1.0),
                transfer("july-old", "2026-07-21", 2.0),
            ),
            refreshes = listOf(AccountTransferRefresh("account", null)),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account.copy(transferSyncComplete = false)),
            transfers = emptyList(),
            refreshes = listOf(AccountTransferRefresh("account", emptyList())),
        )
        assertEquals(
            setOf("june", "july-old"),
            database.transferDao().observeByAccount("account").first().map(Transfer::id).toSet(),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account.copy(transferSyncComplete = false)),
            transfers = listOf(transfer("july-replacement", "2026-07-21", 3.0)),
            refreshes = listOf(
                AccountTransferRefresh("account", listOf(TransferDateRange("2026-07-21", "2026-07-21"))),
            ),
        )
        assertEquals(
            setOf("june", "july-replacement"),
            database.transferDao().observeByAccount("account").first().map(Transfer::id).toSet(),
        )
    }

    @Test
    fun `partial history with no completed ranges upserts successful statements and retains prior history`() = runBlocking {
        val store = database.syncResultDao()
        val account = account("account")
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account),
            transfers = listOf(
                transfer("prior-march", "2026-03-01", 1.0),
                transfer("prior-april", "2026-04-01", 2.0),
            ),
            refreshes = listOf(AccountTransferRefresh("account", null)),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account.copy(transferSyncComplete = false)),
            transfers = listOf(
                transfer("statement-april-success", "2026-03-15", 3.0),
            ),
            // A failed month (for example HTTP 502) means no date range is safe to replace.
            refreshes = listOf(AccountTransferRefresh("account", emptyList())),
        )

        assertEquals(
            setOf("prior-march", "prior-april", "statement-april-success"),
            database.transferDao().observeByAccount("account").first().map(Transfer::id).toSet(),
        )
        assertFalse(
            database.accountDao().observeAll().first().single { it.id == "account" }.transferSyncComplete!!,
        )
    }

    @Test
    fun `latest transfer cursors are grouped by opaque source identity and redact their values`() = runBlocking {
        val store = database.syncResultDao()
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(
                account("account", sourceAccountKey = "deposit-opaque-1"),
                account("without-number"),
                account("legacy-account", accountNo = "0012345678"),
            ),
            transfers = listOf(
                transfer("older", "2026-07-20T09:00:00+08:00", 1.0),
                transfer("latest", "2026-07-21T18:00:00+08:00", 2.0),
                transfer("ignored", "2026-07-22", 3.0, accountId = "without-number"),
                transfer("legacy", "2026-07-22T10:00:00+08:00", 4.0, accountId = "legacy-account"),
            ),
            refreshes = listOf(
                AccountTransferRefresh("account", null),
                AccountTransferRefresh("without-number", null),
                AccountTransferRefresh("legacy-account", null),
            ),
        )

        val cursors = database.transferDao().latestByExtension("extension")

        assertEquals(
            listOf(
                TransferSyncCursor(
                    sourceAccountKey = "deposit-opaque-1",
                    kind = tw.kevinzhang.core.data.model.AssetKind.DEPOSIT,
                    currency = "TWD",
                    latestTxnDateTime = "2026-07-21T18:00:00+08:00",
                    earliestTxnDateTime = "2026-07-20T09:00:00+08:00",
                ),
            ),
            cursors,
        )
        assertFalse(cursors.single().toString().contains("deposit-opaque-1"))
        assertFalse(cursors.single().toString().contains("2026-07-21"))
        assertFalse(cursors.single().toString().contains("2026-07-20"))
    }

    @Test
    fun `transaction atomically retains one exact legacy account id for source key upgrades`() = runBlocking {
        val store = database.syncResultDao()
        val identity = LegacyAccountIdentity("0012345678", AssetKind.DEPOSIT, "TWD")
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(
                account("legacy", accountNo = "0012345678"),
            ),
            transfers = listOf(transfer("legacy-transfer", "2026-07-20", 1.0, accountId = "legacy")),
            refreshes = listOf(
                AccountTransferRefresh("legacy", null),
            ),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(
                account("source-proposed", accountNo = "0012345678", sourceAccountKey = "a".repeat(64)),
            ),
            transfers = listOf(transfer("refreshed", "2026-07-21", 2.0, accountId = "source-proposed")),
            refreshes = listOf(
                AccountTransferRefresh("source-proposed", listOf(TransferDateRange("2026-07-21", "2026-07-21"))),
            ),
            legacyIdentityByAccountId = mapOf("source-proposed" to identity),
        )

        assertEquals("legacy", database.accountDao().observeAll().first().single().id)
        assertEquals("a".repeat(64), database.accountDao().observeAll().first().single().sourceAccountKey)
        assertEquals(
            setOf("legacy-transfer", "refreshed"),
            database.transferDao().observeByAccount("legacy").first().map(Transfer::id).toSet(),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(
                account("source-proposed", accountNo = "0012345678", sourceAccountKey = "a".repeat(64)),
            ),
            transfers = listOf(transfer("next", "2026-07-22", 3.0, accountId = "source-proposed")),
            refreshes = listOf(
                AccountTransferRefresh("source-proposed", listOf(TransferDateRange("2026-07-22", "2026-07-22"))),
            ),
        )

        assertEquals("legacy", database.accountDao().observeAll().first().single().id)
        assertEquals("legacy", database.transferDao().observeByAccount("legacy").first().single { it.id == "next" }.accountId)
    }

    @Test
    fun `account field observations use the retained account id after source key upgrade`() = runBlocking {
        val store = database.syncResultDao()
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(account("legacy", accountNo = "0012345678")),
            transfers = emptyList(),
            refreshes = emptyList(),
        )
        val identity = LegacyAccountIdentity("0012345678", AssetKind.DEPOSIT, "TWD")
        val observation = TransferFieldObservation(
            id = "observation",
            runId = "run-rewrite",
            transferId = null,
            extensionId = "extension",
            observedAt = 2,
            fieldName = "balance",
            valueJson = "1.0",
            sourceDocumentId = null,
            sourcePath = "$.balance",
            sourceRecordJson = "{}",
            sourceFieldJson = """{"locator":"$.balance"}""",
            parserVersion = "v1",
            assetType = "ACCOUNT",
            assetId = "source-proposed",
        )
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(
                account(
                    "source-proposed",
                    accountNo = "0012345678",
                    sourceAccountKey = "a".repeat(64),
                ),
            ),
            transfers = emptyList(),
            refreshes = emptyList(),
            legacyIdentityByAccountId = mapOf("source-proposed" to identity),
            ingestionContext = IngestionContext(
                runId = "run-rewrite",
                startedAt = 1,
                completedAt = 2,
                extensionVersion = 1,
                artifactRevision = null,
                artifactSha256 = null,
                trigger = IngestionTrigger.USER_SYNC,
                status = IngestionStatus.SUCCESS,
                sourceFingerprint = "source",
                fingerprintKeyVersion = 1,
                transferFingerprints = emptyMap(),
                fieldObservations = listOf(observation),
            ),
        )

        assertEquals(
            "legacy",
            database.ingestionProvenanceDao()
                .getFieldObservationsForAsset("ACCOUNT", "legacy")
                .single()
                .assetId,
        )
        assertTrue(
            database.ingestionProvenanceDao()
                .getFieldObservationsForAsset("ACCOUNT", "source-proposed")
                .isEmpty(),
        )
    }

    @Test
    fun `scoped replacement updates complete kinds and preserves failed or omitted kinds`() = runBlocking {
        val store = database.syncResultDao()
        val deposit = account("deposit", kind = AssetKind.DEPOSIT)
        val card = account("card", kind = AssetKind.CREDIT_CARD)
        val loan = account("loan", kind = AssetKind.LOAN)
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(deposit, card, loan),
            transfers = listOf(
                transfer("deposit-old", "2026-07-20", 1.0, accountId = "deposit"),
                transfer("card-old", "2026-07-20", 2.0, accountId = "card"),
                transfer("loan-old", "2026-07-20", 3.0, accountId = "loan"),
            ),
            refreshes = listOf(
                AccountTransferRefresh("deposit", null),
                AccountTransferRefresh("card", null),
                AccountTransferRefresh("loan", null),
            ),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(deposit.copy(balance = 2.0)),
            transfers = listOf(transfer("deposit-new", "2026-07-21", 4.0, accountId = "deposit")),
            refreshes = listOf(AccountTransferRefresh("deposit", null)),
            replaceKinds = setOf(AssetKind.DEPOSIT),
        )

        val accounts = database.accountDao().observeAll().first().associateBy(Account::id)
        assertEquals(setOf("deposit", "card", "loan"), accounts.keys)
        assertEquals(2.0, accounts.getValue("deposit").balance, 0.0)
        assertEquals(listOf("deposit-new"), transferIds("deposit"))
        assertEquals(listOf("card-old"), transferIds("card"))
        assertEquals(listOf("loan-old"), transferIds("loan"))
    }

    @Test
    fun `empty complete kind deletes only that kind while an empty scoped set is a no-op`() = runBlocking {
        val store = database.syncResultDao()
        val deposit = account("deposit", kind = AssetKind.DEPOSIT)
        val card = account("card", kind = AssetKind.CREDIT_CARD)
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(deposit, card),
            transfers = listOf(
                transfer("deposit-old", "2026-07-20", 1.0, accountId = "deposit"),
                transfer("card-old", "2026-07-20", 2.0, accountId = "card"),
            ),
            refreshes = listOf(AccountTransferRefresh("deposit", null), AccountTransferRefresh("card", null)),
        )

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = emptyList(),
            transfers = emptyList(),
            refreshes = emptyList(),
            replaceKinds = emptySet(),
        )
        assertEquals(setOf("deposit", "card"), database.accountDao().observeAll().first().map(Account::id).toSet())

        store.replaceSnapshot(
            extensionId = "extension",
            accounts = emptyList(),
            transfers = emptyList(),
            refreshes = emptyList(),
            replaceKinds = setOf(AssetKind.DEPOSIT),
        )

        assertEquals(listOf("card"), database.accountDao().observeAll().first().map(Account::id))
        assertEquals(listOf("card-old"), transferIds("card"))
        assertTrue(transferIds("deposit").isEmpty())
    }

    private suspend fun transferIds(accountId: String): List<String> =
        database.transferDao().observeByAccount(accountId).first().map(Transfer::id)

    private fun account(
        id: String,
        accountNo: String? = null,
        sourceAccountKey: String? = null,
        kind: AssetKind = AssetKind.DEPOSIT,
    ) = Account(
        id = id,
        extensionId = "extension",
        extensionName = "Bank",
        accountName = "活期",
        balance = 1.0,
        currency = "TWD",
        lastSyncAt = 1,
        accountNo = accountNo,
        sourceAccountKey = sourceAccountKey,
        kind = kind,
    )

    private fun transfer(
        id: String,
        date: String,
        amount: Double,
        accountId: String = "account",
    ) = Transfer(
        id = id,
        accountId = accountId,
        extensionId = "extension",
        txnDateTime = date,
        description = id,
        amount = amount,
        balance = null,
        memo = "",
    )

    private fun cardInstrument(id: String) = CreditCardInstrument(
        id = id,
        accountId = "card-account",
        extensionId = "extension",
        panCiphertext = byteArrayOf(1, 2, 3),
        panIv = byteArrayOf(4, 5, 6),
        panFingerprint = id.padEnd(64, '0'),
        maskedPan = "•••• 4242",
        lastFour = "4242",
    )
}
