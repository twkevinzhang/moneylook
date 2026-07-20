package tw.kevinzhang.moneylook.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.TransferDao
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.TransferData

class SyncResultPersisterTest {
    @Test
    fun `persists typed fields and keeps same named accounts distinct by kind number and currency`() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = RecordingAccountDao(events)
        val transfers = RecordingTransferDao(events)
        val persister = SyncResultPersister(accounts, transfers)
        val extension = InstalledExtension(
            id = "bank::repo",
            manifestId = "bank",
            name = "Bank",
            version = 1,
            repoUrl = "https://github.com/example/repo",
            syncTriggerCachePath = "/tmp/sync.js",
            iconUrl = null,
        )
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

        assertEquals(2, accounts.persisted.size)
        assertNotEquals(accounts.persisted[0].id, accounts.persisted[1].id)
        accounts.persisted[1].also { card ->
            assertEquals(AssetKind.CREDIT_CARD, card.kind)
            assertEquals(800.0, card.availableCredit!!, 0.0)
            assertEquals(1000.0, card.creditLimit!!, 0.0)
        }
        assertEquals(accounts.persisted[0].id, transfers.persisted.single().accountId)
        assertEquals(
            listOf("delete-transfers", "delete-accounts", "upsert-accounts", "upsert-transfers"),
            events,
        )
    }

    @Test
    fun `clears stale transfers when the new account snapshot has no transfers`() = runBlocking {
        val events = mutableListOf<String>()
        val accounts = RecordingAccountDao(events)
        val transfers = RecordingTransferDao(events)
        val extension = InstalledExtension(
            id = "bank::repo",
            manifestId = "bank",
            name = "Bank",
            version = 1,
            repoUrl = "https://github.com/example/repo",
            syncTriggerCachePath = "/tmp/sync.js",
            iconUrl = null,
        )

        SyncResultPersister(accounts, transfers).persist(
            extension,
            SyncResult.Success(listOf(AccountData("活期", 1.0, "TWD"))),
        )

        assertEquals(listOf("delete-transfers", "delete-accounts", "upsert-accounts"), events)
        assertTrue(transfers.persisted.isEmpty())
    }

    @Test
    fun `stable account id uses account number then falls back to name`() {
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
    }

    private class RecordingAccountDao(private val events: MutableList<String>) : AccountDao {
        var persisted: List<Account> = emptyList()

        override fun observeAll(): Flow<List<Account>> = emptyFlow()

        override suspend fun upsertAll(accounts: List<Account>) {
            events += "upsert-accounts"
            persisted = accounts
        }

        override suspend fun deleteByExtensionId(extensionId: String) {
            events += "delete-accounts"
        }
    }

    private class RecordingTransferDao(private val events: MutableList<String>) : TransferDao {
        var persisted: List<Transfer> = emptyList()

        override fun observeByAccount(accountId: String): Flow<List<Transfer>> = emptyFlow()

        override suspend fun upsertAll(transfers: List<Transfer>) {
            events += "upsert-transfers"
            persisted = transfers
        }

        override suspend fun deleteByExtensionId(extensionId: String) {
            events += "delete-transfers"
        }
    }
}
