package tw.kevinzhang.core.data.db

import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import tw.kevinzhang.core.data.model.Account
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
    fun `completed ranges replace only their history and old data is pruned in one transaction`() = runBlocking {
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
                AccountTransferRefresh("account", null, null),
                AccountTransferRefresh("legacy-account", null, null),
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
                    retainFrom = "2025-07-21",
                ),
                AccountTransferRefresh("legacy-account", null, null),
            ),
        )

        val transfers = database.transferDao().observeByAccount("account").first()
        assertEquals(
            setOf("replacement", "partial-page", "old-outside"),
            transfers.map(Transfer::id).toSet(),
        )
        assertFalse(transfers.any { it.id == "old-in-range" || it.id == "expired" })
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
    fun `latest transfer cursors are grouped by extension account and redact their values`() = runBlocking {
        val store = database.syncResultDao()
        store.replaceSnapshot(
            extensionId = "extension",
            accounts = listOf(
                account("account", accountNo = "000000000001"),
                account("without-number"),
            ),
            transfers = listOf(
                transfer("older", "2026-07-20T09:00:00+08:00", 1.0),
                transfer("latest", "2026-07-21T18:00:00+08:00", 2.0),
                transfer("ignored", "2026-07-22", 3.0, accountId = "without-number"),
            ),
            refreshes = listOf(
                AccountTransferRefresh("account", null, null),
                AccountTransferRefresh("without-number", null, null),
            ),
        )

        val cursors = database.transferDao().latestByExtension("extension")

        assertEquals(
            listOf(
                TransferSyncCursor(
                    accountNo = "000000000001",
                    currency = "TWD",
                    latestTxnDateTime = "2026-07-21T18:00:00+08:00",
                ),
            ),
            cursors,
        )
        assertFalse(cursors.single().toString().contains("000000000001"))
        assertFalse(cursors.single().toString().contains("2026-07-21"))
    }

    private fun account(id: String, accountNo: String? = null) = Account(
        id = id,
        extensionId = "extension",
        extensionName = "Bank",
        accountName = "活期",
        balance = 1.0,
        currency = "TWD",
        lastSyncAt = 1,
        accountNo = accountNo,
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
