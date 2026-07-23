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
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.KindSyncResult
import tw.kevinzhang.extension_runtime.data.KindSyncStatus
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.TransferData
import tw.kevinzhang.extension_runtime.data.TransferSyncData
import tw.kevinzhang.extension_runtime.data.TransferSyncRangeData

class SyncResultPersisterTest {
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
                KindSyncResult(AssetKind.TIME_DEPOSIT, KindSyncStatus.FAILED, "TERM_QUERY_REJECTED"),
            ),
        )

        SyncResultPersister(store).persist(extension(), result)

        assertEquals(setOf(AssetKind.DEPOSIT), store.replaceKinds)
        assertTrue(result.hasPartialSyncFailure)
        assertEquals("partial", result.appLastRunStatus)
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
        var refreshes: List<AccountTransferRefresh> = emptyList()
        var legacyIdentityByAccountId: Map<String, LegacyAccountIdentity> = emptyMap()
        var replaceKinds: Set<AssetKind>? = null

        override suspend fun replaceSnapshot(
            extensionId: String,
            accounts: List<Account>,
            transfers: List<Transfer>,
            refreshes: List<AccountTransferRefresh>,
            legacyIdentityByAccountId: Map<String, LegacyAccountIdentity>,
            replaceKinds: Set<AssetKind>?,
        ) {
            this.extensionId = extensionId
            this.accounts = accounts
            this.transfers = transfers
            this.refreshes = refreshes
            this.legacyIdentityByAccountId = legacyIdentityByAccountId
            this.replaceKinds = replaceKinds
        }
    }
}
