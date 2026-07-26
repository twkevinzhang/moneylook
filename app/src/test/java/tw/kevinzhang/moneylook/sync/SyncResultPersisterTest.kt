package tw.kevinzhang.moneylook.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.db.AccountTransferRefresh
import tw.kevinzhang.core.data.db.LegacyAccountIdentity
import tw.kevinzhang.core.data.db.TransferDateRange
import tw.kevinzhang.core.data.db.TransferSyncStore
import tw.kevinzhang.core.data.db.IngestionContext
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.CreditCardInstrument
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.IngestionClassificationStatus
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.CardData
import tw.kevinzhang.extension_runtime.data.KindSyncResult
import tw.kevinzhang.extension_runtime.data.KindSyncStatus
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.TransferData
import tw.kevinzhang.extension_runtime.data.TransferSyncData
import tw.kevinzhang.extension_runtime.data.TransferSyncRangeData
import tw.kevinzhang.extension_runtime.data.CapturedSourceDocument
import tw.kevinzhang.moneylook.security.CardPanProtector
import tw.kevinzhang.moneylook.security.ProtectedCardPan
import tw.kevinzhang.moneylook.security.SourceFingerprintProtector
import tw.kevinzhang.moneylook.security.ProtectedSourceFingerprint
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class SyncResultPersisterTest {
    @Test
    fun `failed run retains authenticated response archive and original start time`() = runBlocking {
        val store = RecordingStore()
        SyncResultPersister(store).recordFailure(
            extension = extension(),
            sourceDocuments = listOf(
                CapturedSourceDocument(
                    id = "failed-doc",
                    capturedAt = 201,
                    stage = "authenticated-error",
                    transport = "browser_xhr",
                    method = "GET",
                    url = "https://bank.invalid/error",
                    statusCode = 500,
                    responseHeadersJson = "{}",
                    mediaKind = "text/html",
                    bodyEncoding = "text",
                    representation = "exact_bytes",
                    bodyBytes = "<html>failure</html>".toByteArray(),
                ),
            ),
            sourceRunId = "failed-run",
            sourceRunStartedAt = 200,
            failure = SyncResult.Error(
                message = "extension script failed",
                origin = "SCRIPT",
                code = "BANK_REJECTED",
                scriptFrame = "line 71, column 9",
                rawMessage = "complete authenticated rejection",
                rawStack = "Error: complete authenticated rejection\n at bank.js:71:9",
                rawDiagnosticJson = """{"thrown":{"credential":"test-secret"}}""",
            ),
        )

        requireNotNull(store.failedContext).also {
            assertEquals("failed-run", it.runId)
            assertEquals(200, it.startedAt)
            assertEquals("failed-doc", it.sourceDocuments.single().id)
            assertEquals("SCRIPT", it.failureOrigin)
            assertEquals("BANK_REJECTED", it.failureCode)
            assertEquals("complete authenticated rejection", it.failureMessage)
            assertTrue(requireNotNull(it.failureStack).contains("bank.js:71:9"))
            assertEquals("""{"thrown":{"credential":"test-secret"}}""", it.failureDiagnosticJson)
            assertEquals("line 71, column 9", it.failureScriptFrame)
        }
        Unit
    }

    @Test
    fun `persists exact response archive and field lineage under runner lifecycle`() = runBlocking {
        val store = RecordingStore()
        val exactBody = byteArrayOf(0, 1, 2, 3, 0xff.toByte())
        val result = SyncResult.Success(
            accounts = listOf(
                AccountData(
                    name = "Fictional account",
                    balance = 10.0,
                    currency = "TWD",
                    transfers = listOf(
                        TransferData(
                            txnDateTime = "2026-07-26",
                            description = "Fictional merchant",
                            amount = -10.0,
                            balance = 0.0,
                            memo = "",
                            referenceNumber = "REF-1",
                            sourceRecord = mapOf("sourceDocumentId" to "doc-1", "row" to 0),
                            sourceFields = mapOf(
                                "referenceNumber" to mapOf("locator" to "${'$'}.rows[0].reference"),
                            ),
                            sourceFacts = mapOf(
                                "bankSpecificCode" to mapOf(
                                    "value" to "X1",
                                    "locator" to "${'$'}.rows[0].bankCode",
                                    "rawKey" to "BANK_CODE",
                                    "confidence" to 0.9,
                                ),
                            ),
                            parserVersion = "fictional-v1",
                        ),
                    ),
                ),
            ),
            sourceDocuments = listOf(
                CapturedSourceDocument(
                    id = "doc-1",
                    capturedAt = 101,
                    stage = "history",
                    transport = "native_http",
                    method = "GET",
                    url = "https://bank.invalid/history",
                    statusCode = 200,
                    responseHeadersJson = """{"Content-Type":["application/octet-stream"]}""",
                    mediaKind = "application/octet-stream",
                    bodyEncoding = "base64",
                    representation = "exact_bytes",
                    bodyBytes = exactBody,
                ),
            ),
            runId = "runner-run",
            runStartedAt = 100,
        )

        SyncResultPersister(store).persist(extension(), result)

        val context = requireNotNull(store.ingestionContext)
        assertEquals("runner-run", context.runId)
        assertEquals(100, context.startedAt)
        context.sourceDocuments.single().also { document ->
            assertEquals(exactBody.size.toLong(), document.bodyByteCount)
            assertEquals(
                exactBody.toList(),
                GZIPInputStream(ByteArrayInputStream(document.bodyGzip)).use { it.readBytes() }.toList(),
            )
            assertEquals(64, document.bodySha256.length)
        }
        val reference = context.fieldObservations.first { it.fieldName == "referenceNumber" }
        assertEquals("doc-1", reference.sourceDocumentId)
        assertEquals("${'$'}.rows[0].reference", reference.sourcePath)
        context.fieldObservations.single { it.fieldName == "sourceFact.bankSpecificCode" }.also {
            assertEquals("\"X1\"", it.valueJson)
            assertEquals("${'$'}.rows[0].bankCode", it.sourcePath)
            assertTrue(requireNotNull(it.sourceFieldJson).contains("\"rawKey\":\"BANK_CODE\""))
            assertTrue(it.sourceFieldJson!!.contains("\"confidence\":0.9"))
        }
        assertTrue(context.fieldObservations.any {
            it.assetType == "ACCOUNT" && it.parserVersion == "manifest:1"
        })
    }

    @Test
    fun `dangling source document reference fails preprocessing but retains captured archive`() = runBlocking {
        val store = RecordingStore()
        val document = CapturedSourceDocument(
            id = "captured-doc",
            capturedAt = 1,
            stage = "history",
            transport = "native_http",
            method = "GET",
            url = "https://bank.invalid/history",
            statusCode = 200,
            responseHeadersJson = "{}",
            mediaKind = "application/json",
            bodyEncoding = "text",
            representation = "exact_bytes",
            bodyBytes = "{}".toByteArray(),
        )
        val result = SyncResult.Success(
            accounts = listOf(
                AccountData(
                    "Account",
                    1.0,
                    "TWD",
                    sourceRecord = mapOf("sourceDocumentId" to "missing-doc"),
                ),
            ),
            sourceDocuments = listOf(document),
        )

        val error = runCatching { SyncResultPersister(store).persist(extension(), result) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(listOf("captured-doc"), store.failedContext?.sourceDocuments?.map { it.id })
        assertTrue(store.accounts.isEmpty())
    }

    @Test
    fun `payload fingerprint changes when a rich or provenance field changes`() = runBlocking {
        val protector = object : SourceFingerprintProtector {
            override fun fingerprint(vararg components: String) =
                ProtectedSourceFingerprint(components.joinToString("\u001f"), 1)
        }
        suspend fun fingerprint(transfer: TransferData): String {
            val store = RecordingStore()
            SyncResultPersister(store, sourceFingerprintProtector = protector).persist(
                extension(),
                SyncResult.Success(
                    listOf(AccountData("Account", 1.0, "TWD", transfers = listOf(transfer))),
                ),
            )
            return requireNotNull(store.ingestionContext).transferFingerprints.values.single().payloadFingerprint
        }
        val base = TransferData("2026-07-26", "Fictional", -10.0, null, "")

        assertNotEquals(fingerprint(base), fingerprint(base.copy(feeAmount = 1.0)))
        assertNotEquals(fingerprint(base), fingerprint(base.copy(parserVersion = "v2")))
        assertNotEquals(
            fingerprint(base),
            fingerprint(base.copy(sourceFacts = mapOf("raw" to mapOf("value" to "changed")))),
        )
    }
    @Test
    fun `encrypts PAN before persistence and maps transactions by card ref`() = runBlocking {
        val store = RecordingStore()
        val fictionalPan = "4242424242424242"
        val result = SyncResult.Success(
            listOf(
                AccountData(
                    name = "Credit statement",
                    balance = 1.0,
                    currency = "TWD",
                    kind = AssetKind.CREDIT_CARD,
                    cardsComplete = true,
                    cards = listOf(
                        CardData(
                            ref = "main",
                            sourceCardKey = "c".repeat(64),
                            pan = fictionalPan,
                            displayName = "Main card",
                        ),
                        CardData(ref = "supplementary", lastFour = "0002"),
                    ),
                    transfers = listOf(
                        TransferData("2026-07-21", "purchase", -1.0, null, "", cardRef = "main"),
                        TransferData("2026-07-22", "unknown", -2.0, null, ""),
                    ),
                ),
            ),
        )

        SyncResultPersister(store, cardPanProtector = FakeCardPanProtector).persist(extension(), result)

        assertEquals(2, store.cardInstruments.size)
        assertEquals(setOf(store.accounts.single().id), store.replaceCardAccountIds)
        val persisted = store.cardInstruments.first { it.lastFour == "4242" }
        assertFalse(persisted.panCiphertext!!.toString(Charsets.UTF_8).contains(fictionalPan))
        assertEquals("iv".toByteArray().toList(), persisted.panIv!!.toList())
        assertEquals(persisted.id, store.transfers.first().cardInstrumentId)
        assertNull(store.transfers.last().cardInstrumentId)
        assertFalse(stableCardInstrumentId(store.accounts.single().id, "c".repeat(64), null, "other").contains("4242"))
    }

    @Test
    fun `persists typed fields and keeps same named accounts distinct by kind number and currency`() = runBlocking {
        val store = RecordingStore()
        val persister = SyncResultPersister(store)
        val extension = extension()
        val result = SyncResult.Success(
            listOf(
                AccountData(
                    name = "主帳戶",
                    balance = 100.0,
                    currency = "TWD",
                    no = "001",
                    sourceAccountKey = "deposit-opaque-1",
                    kind = AssetKind.DEPOSIT,
                    branchName = "總行",
                    transfers = listOf(
                        TransferData(
                            "2026-01-01", "", 1.0, 101.0, "",
                            type = "transfer",
                            status = "posted",
                            postingDateTime = "2026-01-02T09:00:00",
                            merchantName = "Fictional Grocery",
                            merchantCategoryCode = "5411",
                            counterpartyName = "Example Counterparty",
                            purpose = "Household supplies",
                        ),
                    ),
                ),
                AccountData(
                    name = "主帳戶",
                    balance = 200.0,
                    currency = "TWD",
                    no = "002",
                    kind = AssetKind.CREDIT_CARD,
                    availableCredit = 800.0,
                    creditLimit = 1000.0,
                ),
            ),
        )

        persister.persist(extension, result)

        assertEquals(2, store.accounts.size)
        assertNotEquals(store.accounts[0].id, store.accounts[1].id)
        store.accounts[1].also { card ->
            assertEquals(AssetKind.CREDIT_CARD, card.kind)
            assertEquals(800.0, card.availableCredit!!, 0.0)
            assertEquals(1000.0, card.creditLimit!!, 0.0)
        }
        assertEquals(store.accounts[0].id, store.transfers.single().accountId)
        assertEquals("deposit-opaque-1", store.accounts[0].sourceAccountKey)
        assertEquals("transfer", store.transfers.single().type)
        assertEquals("posted", store.transfers.single().status)
        assertEquals("2026-01-02T09:00:00", store.transfers.single().postingDateTime)
        assertEquals("Fictional Grocery", store.transfers.single().merchantName)
        assertEquals("5411", store.transfers.single().merchantCategoryCode)
        assertEquals("Example Counterparty", store.transfers.single().counterpartyName)
        assertEquals("Household supplies", store.transfers.single().purpose)
        assertNull(store.refreshes[0].completedRanges)
    }

    @Test
    fun `legacy accounts retain snapshot semantics`() = runBlocking {
        val store = RecordingStore()

        SyncResultPersister(store).persist(
            extension(),
            SyncResult.Success(listOf(AccountData("活期", 1.0, "TWD"))),
        )

        assertTrue(store.transfers.isEmpty())
        assertEquals(1, store.refreshes.size)
        assertNull(store.refreshes.single().completedRanges)
        assertNull(store.accounts.single().transferSyncComplete)
    }

    @Test
    fun `history metadata preserves only completed ranges and stores partial marker`() = runBlocking {
        val store = RecordingStore()
        val sync = TransferSyncData(
            requestedStart = "2025-07-21",
            requestedEnd = "2026-07-21",
            completedRanges = listOf(TransferSyncRangeData("2026-06-01", "2026-07-21")),
            complete = false,
        )
        val account = AccountData(
            name = "活期",
            balance = 1.0,
            currency = "TWD",
            no = "001",
            transfers = listOf(
                TransferData("2026-07-01", "同秒交易 A", 1.0, null, "", id = "source-a"),
                TransferData("2026-07-01", "同秒交易 B", -1.0, null, "", id = "source-b"),
            ),
            transferSync = sync,
        )

        SyncResultPersister(store).persist(extension(), SyncResult.Success(listOf(account)))

        assertEquals(false, store.accounts.single().transferSyncComplete)
        assertEquals(
            listOf(TransferDateRange("2026-06-01", "2026-07-21")),
            store.refreshes.single().completedRanges,
        )
        assertNotEquals(store.transfers[0].id, store.transfers[1].id)
        assertNull(store.transfers[0].balance)
    }

    @Test
    fun `source key upgrade forwards only a unique legacy identity to the transactional store`() = runBlocking {
        val legacy = AccountData(name = "活期", balance = 1.0, currency = "TWD", no = "0012345678")
        val store = RecordingStore()
        val upgraded = legacy.copy(
            sourceAccountKey = "a".repeat(64),
            transfers = listOf(TransferData("2026-07-21", "既有交易", 1.0, null, "", id = "txn-1")),
        )

        SyncResultPersister(store).persist(extension(), SyncResult.Success(listOf(upgraded)))

        assertEquals(
            mapOf(
                stableAccountId(extension().id, upgraded) to
                    LegacyAccountIdentity("0012345678", AssetKind.DEPOSIT, "TWD"),
            ),
            store.legacyIdentityByAccountId,
        )
    }

    @Test
    fun `ambiguous returned legacy identity never reuses one stored account id`() = runBlocking {
        val store = RecordingStore()
        val first = AccountData(
            name = "活期 A", balance = 1.0, currency = "TWD", no = "0012345678",
            sourceAccountKey = "a".repeat(64),
        )
        val second = first.copy(name = "活期 B", sourceAccountKey = "b".repeat(64))

        SyncResultPersister(store).persist(extension(), SyncResult.Success(listOf(first, second)))

        assertTrue(store.legacyIdentityByAccountId.isEmpty())
        assertNotEquals(store.accounts[0].id, store.accounts[1].id)
    }

    @Test
    fun `partial result forwards only complete kinds to the transactional store`() = runBlocking {
        val store = RecordingStore()
        val result = SyncResult.Success(
            accounts = listOf(AccountData("活期", 1.0, "TWD", kind = AssetKind.DEPOSIT)),
            kindSync = listOf(
                KindSyncResult(AssetKind.DEPOSIT, KindSyncStatus.COMPLETE, null),
                KindSyncResult(
                    AssetKind.TIME_DEPOSIT,
                    KindSyncStatus.FAILED,
                    "TERM_QUERY_REJECTED",
                    rawMessage = "complete term response rejection",
                    rawStack = "Error: complete term response rejection\n at term.js:3:2",
                    rawDiagnosticJson = """{"authenticated":"secret"}""",
                ),
            ),
        )

        SyncResultPersister(store).persist(extension(), result)

        assertEquals(setOf(AssetKind.DEPOSIT), store.replaceKinds)
        assertTrue(result.hasPartialSyncFailure)
        assertEquals("partial", result.appLastRunStatus)
        assertEquals("PARTIAL_KIND", store.ingestionContext?.failureOrigin)
        assertEquals("TERM_QUERY_REJECTED", store.ingestionContext?.failureCode)
        assertEquals("complete term response rejection", store.ingestionContext?.failureMessage)
        assertTrue(requireNotNull(store.ingestionContext?.failureStack).contains("term.js:3:2"))
        assertTrue(requireNotNull(store.ingestionContext?.failureDiagnosticJson).contains("authenticated"))
    }

    @Test
    fun `incomplete account history marks a successful result partial without changing completed ranges`() = runBlocking {
        val store = RecordingStore()
        val result = SyncResult.Success(
            accounts = listOf(
                AccountData(
                    name = "活期",
                    balance = 1.0,
                    currency = "TWD",
                    transferSync = TransferSyncData(
                        requestedStart = "2026-01-01",
                        requestedEnd = "2026-07-22",
                        completedRanges = listOf(TransferSyncRangeData("2026-07-01", "2026-07-22")),
                        complete = false,
                    ),
                ),
            ),
            kindSync = listOf(KindSyncResult(AssetKind.DEPOSIT, KindSyncStatus.COMPLETE, null)),
        )

        SyncResultPersister(store).persist(extension(), result)

        assertEquals(false, store.accounts.single().transferSyncComplete)
        assertEquals(
            listOf(TransferDateRange("2026-07-01", "2026-07-22")),
            store.refreshes.single().completedRanges,
        )
        assertTrue(result.hasPartialSyncFailure)
        assertEquals("partial", result.appLastRunStatus)
    }

    @Test
    fun `complete or legacy result keeps its snapshot replacement semantics`() = runBlocking {
        val store = RecordingStore()
        val result = SyncResult.Success(listOf(AccountData("活期", 1.0, "TWD")))

        SyncResultPersister(store).persist(extension(), result)

        assertNull(store.replaceKinds)
        assertFalse(result.hasPartialSyncFailure)
        assertEquals("success", result.appLastRunStatus)
    }

    @Test
    fun `classification failure marks committed ingestion failed before rethrowing`() = runBlocking {
        val store = RecordingStore()
        val persister = SyncResultPersister(
            transferSyncStore = store,
            autoCategorizer = TransferAutoCategorizer { throw IllegalStateException("fictional failure") },
        )

        val error = runCatching {
            persister.persist(extension(), SyncResult.Success(listOf(AccountData("Account", 1.0, "TWD"))))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(IngestionClassificationStatus.FAILED, store.classificationStatus)
        assertEquals("CLASSIFIER", store.classificationFailureOrigin)
        assertEquals("fictional failure", store.classificationFailureMessage)
        assertTrue(store.classificationFailureStack?.contains("IllegalStateException") == true)
        assertTrue(store.failedContext == null)
    }

    @Test
    fun `snapshot failure records a safe failed ingestion run`() = runBlocking {
        val store = RecordingStore().apply { failSnapshot = true }
        val error = runCatching {
            SyncResultPersister(store).persist(
                extension(),
                SyncResult.Success(listOf(AccountData("Account", 1.0, "TWD"))),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("bank::repo", store.failedExtensionId)
        assertEquals(0, store.failedTransferCount)
        assertEquals(IngestionClassificationStatus.PENDING, store.failedContext?.classificationStatus)
    }

    @Test
    fun `preprocessing failure records exactly one safe failed run`() = runBlocking {
        val store = RecordingStore()
        val result = SyncResult.Success(
            listOf(
                AccountData(
                    name = "Card",
                    balance = 1.0,
                    currency = "TWD",
                    kind = AssetKind.CREDIT_CARD,
                    cards = listOf(CardData(ref = "card", pan = "4242424242424242")),
                ),
            ),
        )

        val error = runCatching {
            SyncResultPersister(store).persist(extension(), result)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(1, store.failedRunCount)
        assertEquals("unavailable", store.failedContext?.sourceFingerprint)
        assertEquals(0, store.failedContext?.fingerprintKeyVersion)
        assertTrue(store.accounts.isEmpty())
    }

    @Test
    fun `explicit runtime failure entry records no payload counts`() = runBlocking {
        val store = RecordingStore()

        SyncResultPersister(store).recordFailure(extension())

        assertEquals("bank::repo", store.failedExtensionId)
        assertEquals(0, store.failedAccountCount)
        assertEquals(0, store.failedTransferCount)
        assertEquals(IngestionClassificationStatus.FAILED, store.failedContext?.classificationStatus)
    }

    @Test
    fun `runtime failure uses documents carried by the error when caller omits duplicate argument`() =
        runBlocking {
            val store = RecordingStore()
            val document = CapturedSourceDocument(
                id = "error-doc",
                capturedAt = 101,
                stage = "extension.error",
                transport = "extension_runtime",
                method = "RETURN",
                url = "extension-runtime://script/error",
                statusCode = null,
                responseHeadersJson = "{}",
                mediaKind = "application/json",
                bodyEncoding = "utf-8",
                representation = "decoded_text",
                bodyBytes = """{"secret":"complete"}""".toByteArray(),
            )

            SyncResultPersister(store).recordFailure(
                extension = extension(),
                failure = SyncResult.Error(
                    message = "failed",
                    sourceDocuments = listOf(document),
                ),
            )

            assertEquals(listOf("error-doc"), store.failedContext?.sourceDocuments?.map { it.id })
        }

    @Test
    fun `stable account and transfer ids are opaque and deterministic`() {
        val numbered = AccountData("活期", 1.0, "TWD", no = "001")
        val sourceKeyed = numbered.copy(sourceAccountKey = "deposit-opaque-1")
        val named = AccountData("活期", 1.0, "TWD")
        val foreignCurrency = numbered.copy(currency = "USD")
        val card = numbered.copy(kind = AssetKind.CREDIT_CARD)

        assertNotEquals(stableAccountId("bank", numbered), stableAccountId("bank", named))
        assertNotEquals(stableAccountId("bank", numbered), stableAccountId("bank", sourceKeyed))
        assertNotEquals(stableAccountId("bank", numbered), stableAccountId("bank", foreignCurrency))
        assertNotEquals(stableAccountId("bank", numbered), stableAccountId("bank", card))
        assertEquals(stableAccountId("bank", named), stableAccountId("bank", named))
        assertFalse(stableAccountId("bank", numbered).contains("001"))
        assertFalse(stableAccountId("bank", named).contains("活期"))
        assertTrue(stableAccountId("bank", numbered).matches(Regex("bank::[0-9a-f]{64}")))

        val accountId = stableAccountId("bank", numbered)
        val sameSource = stableTransferId(accountId, "bank-id", "2026-01-01", "x", 1.0, null, "")
        assertEquals(
            sameSource,
            stableTransferId(
                accountId,
                "bank-id",
                "different-date",
                "different-description",
                2.0,
                null,
                "different-memo",
            ),
        )
        assertFalse(sameSource.contains("bank-id"))
        assertNotEquals(
            stableTransferId(accountId, null, "2026-01-01", "x", 1.0, null, "", fallbackOccurrence = 0),
            stableTransferId(accountId, null, "2026-01-01", "x", 1.0, null, "", fallbackOccurrence = 1),
        )
    }

    private fun extension() = InstalledExtension(
        id = "bank::repo",
        manifestId = "bank",
        name = "Bank",
        version = 1,
        repoUrl = "https://github.com/example/repo",
        syncTriggerCachePath = "/tmp/sync.js",
        iconUrl = null,
    )

    private class RecordingStore : TransferSyncStore {
        var extensionId = ""
        var accounts: List<Account> = emptyList()
        var transfers: List<Transfer> = emptyList()
        var cardInstruments: List<CreditCardInstrument> = emptyList()
        var replaceCardAccountIds: Set<String> = emptySet()
        var refreshes: List<AccountTransferRefresh> = emptyList()
        var legacyIdentityByAccountId: Map<String, LegacyAccountIdentity> = emptyMap()
        var replaceKinds: Set<AssetKind>? = null
        var failSnapshot = false
        var failedExtensionId: String? = null
        var failedContext: IngestionContext? = null
        var failedAccountCount = -1
        var failedTransferCount = -1
        var classificationStatus: IngestionClassificationStatus? = null
        var classificationFailureOrigin: String? = null
        var classificationFailureMessage: String? = null
        var classificationFailureStack: String? = null
        var failedRunCount = 0
        var ingestionContext: IngestionContext? = null

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
            if (failSnapshot) throw IllegalStateException("fictional snapshot failure")
            this.extensionId = extensionId
            this.accounts = accounts
            this.transfers = transfers
            this.refreshes = refreshes
            this.cardInstruments = cardInstruments
            this.replaceCardAccountIds = replaceCardAccountIds
            this.legacyIdentityByAccountId = legacyIdentityByAccountId
            this.replaceKinds = replaceKinds
        }

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
            this.ingestionContext = ingestionContext
            replaceSnapshot(
                extensionId,
                accounts,
                transfers,
                refreshes,
                cardInstruments,
                replaceCardAccountIds,
                legacyIdentityByAccountId,
                replaceKinds,
            )
        }

        override suspend fun recordFailedIngestion(
            extensionId: String,
            ingestionContext: IngestionContext,
            accountCount: Int,
            transferCount: Int,
        ) {
            failedRunCount += 1
            failedExtensionId = extensionId
            failedContext = ingestionContext
            failedAccountCount = accountCount
            failedTransferCount = transferCount
        }

        override suspend fun updateClassificationStatus(
            runId: String,
            status: IngestionClassificationStatus,
            completedAt: Long?,
        ) {
            classificationStatus = status
        }

        override suspend fun updateClassificationFailure(
            runId: String,
            completedAt: Long,
            origin: String,
            message: String,
            stack: String,
        ) {
            classificationStatus = IngestionClassificationStatus.FAILED
            classificationFailureOrigin = origin
            classificationFailureMessage = message
            classificationFailureStack = stack
        }
    }

    private object FakeCardPanProtector : CardPanProtector {
        override fun protect(pan: String): ProtectedCardPan = ProtectedCardPan(
            ciphertext = "ciphertext".toByteArray(),
            iv = "iv".toByteArray(),
            fingerprint = "f".repeat(64),
        )

        override fun reveal(ciphertext: ByteArray, iv: ByteArray): String = "4242424242424242"
    }
}
