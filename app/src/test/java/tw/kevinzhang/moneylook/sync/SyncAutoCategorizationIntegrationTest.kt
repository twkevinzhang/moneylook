package tw.kevinzhang.moneylook.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tw.kevinzhang.core.data.db.AccountTransferRefresh
import tw.kevinzhang.core.data.db.LegacyAccountIdentity
import tw.kevinzhang.core.data.db.TransferSyncStore
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.TransferData

class SyncAutoCategorizationIntegrationTest {
    @Test
    fun `persisted transfer ids are forwarded to automatic categorization`() = runBlocking {
        val store = RecordingStore()
        var receivedIds = emptyList<String>()
        val persister = SyncResultPersister(
            transferSyncStore = store,
            autoCategorizer = TransferAutoCategorizer { receivedIds = it },
        )

        persister.persist(
            extension = InstalledExtension(
                id = "extension",
                manifestId = "bank",
                name = "Bank",
                version = 1,
                repoUrl = "https://github.com/example/extensions",
                syncTriggerCachePath = "/tmp/sync.js",
                iconUrl = null,
            ),
            result = SyncResult.Success(
                listOf(
                    AccountData(
                        name = "帳戶",
                        balance = 100.0,
                        currency = "TWD",
                        sourceAccountKey = "source-account",
                        transfers = listOf(
                            TransferData(
                                txnDateTime = "2026-07-22",
                                description = "午餐",
                                amount = -120.0,
                                balance = null,
                                memo = "",
                                id = "source-transfer",
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(store.transfers.map(Transfer::id), receivedIds)
    }

    private class RecordingStore : TransferSyncStore {
        var transfers = emptyList<Transfer>()

        override suspend fun replaceSnapshot(
            extensionId: String,
            accounts: List<Account>,
            transfers: List<Transfer>,
            refreshes: List<AccountTransferRefresh>,
            legacyIdentityByAccountId: Map<String, LegacyAccountIdentity>,
            replaceKinds: Set<AssetKind>?,
        ) {
            this.transfers = transfers
        }
    }
}
