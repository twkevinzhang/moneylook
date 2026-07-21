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
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.Transfer

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
                ),
            ),
            cursors,
        )
        assertFalse(cursors.single().toString().contains("deposit-opaque-1"))
        assertFalse(cursors.single().toString().contains("2026-07-21"))
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

    private fun account(
        id: String,
        accountNo: String? = null,
        sourceAccountKey: String? = null,
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
}
