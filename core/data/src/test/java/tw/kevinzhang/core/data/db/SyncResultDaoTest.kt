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

    private fun account(id: String) = Account(
        id = id,
        extensionId = "extension",
        extensionName = "Bank",
        accountName = "活期",
        balance = 1.0,
        currency = "TWD",
        lastSyncAt = 1,
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
