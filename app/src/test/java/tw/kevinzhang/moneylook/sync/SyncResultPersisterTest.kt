package tw.kevinzhang.moneylook.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.db.AccountTransferRefresh
import tw.kevinzhang.core.data.db.TransferDateRange
import tw.kevinzhang.core.data.db.TransferSyncStore
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.extension_runtime.data.AccountData
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
                    kind = AssetKind.DEPOSIT,
                    branchName = "總行",
                    transfers = listOf(TransferData("2026-01-01", "", 1.0, 101.0, "")),
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
        assertNull(store.refreshes[0].completedRanges)
        assertNull(store.refreshes[0].retainFrom)
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
        assertEquals("2025-07-21", store.refreshes.single().retainFrom)
    }

    @Test
    fun `stable account and transfer ids are opaque and deterministic`() {
        val numbered = AccountData("活期", 1.0, "TWD", no = "001")
        val named = AccountData("活期", 1.0, "TWD")
        val foreignCurrency = numbered.copy(currency = "USD")
        val card = numbered.copy(kind = AssetKind.CREDIT_CARD)

        assertNotEquals(stableAccountId("bank", numbered), stableAccountId("bank", named))
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

        override suspend fun replaceSnapshot(
            extensionId: String,
            accounts: List<Account>,
            transfers: List<Transfer>,
            refreshes: List<AccountTransferRefresh>,
        ) {
            this.extensionId = extensionId
            this.accounts = accounts
            this.transfers = transfers
            this.refreshes = refreshes
        }
    }
}
