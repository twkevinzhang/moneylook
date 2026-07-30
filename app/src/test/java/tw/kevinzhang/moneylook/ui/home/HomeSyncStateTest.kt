package tw.kevinzhang.moneylook.ui.home

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.runBlocking
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.KindSyncResult
import tw.kevinzhang.extension_runtime.data.KindSyncStatus
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.data.TransferSyncData
import tw.kevinzhang.extension_runtime.data.TransferSyncRangeData

class HomeSyncStateTest {
    @Test
    fun `partial success uses a fixed warning without exposing the extension code`() {
        val result = SyncResult.Success(
            accounts = listOf(AccountData("活期", 1.0, "TWD")),
            kindSync = listOf(
                KindSyncResult(AssetKind.DEPOSIT, KindSyncStatus.COMPLETE, null),
                KindSyncResult(AssetKind.TIME_DEPOSIT, KindSyncStatus.FAILED, "PRIVATE_BANK_CODE"),
            ),
        )

        assertEquals(SyncState.PARTIAL, result.homeSyncState())
        assertEquals(PARTIAL_SYNC_MESSAGE, result.homeSyncMessage())
        assertEquals(false, requireNotNull(result.homeSyncMessage()).contains("PRIVATE_BANK_CODE"))
    }

    @Test
    fun `complete or legacy success keeps the normal success state`() {
        val result = SyncResult.Success(listOf(AccountData("活期", 1.0, "TWD")))

        assertEquals(SyncState.SUCCESS, result.homeSyncState())
        assertNull(result.homeSyncMessage())
    }

    @Test
    fun `incomplete account history uses the fixed partial warning without bank details`() {
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
        )

        assertEquals(SyncState.PARTIAL, result.homeSyncState())
        assertEquals(PARTIAL_SYNC_MESSAGE, result.homeSyncMessage())
        assertEquals(false, requireNotNull(result.homeSyncMessage()).contains("活期"))
    }

    @Test
    fun `persisted partial state survives a Home reload without exposing a code`() {
        assertEquals(SyncState.PARTIAL, persistedSyncState("partial"))
        assertEquals(PARTIAL_SYNC_MESSAGE, persistedSyncMessage("partial"))
        assertEquals(SyncState.ERROR, persistedSyncState("error"))
        assertEquals(SYNC_FAILURE_MESSAGE, persistedSyncMessage("error"))
        assertEquals(SyncState.IDLE, persistedSyncState("success"))
        assertNull(persistedSyncMessage("success"))
    }

    @Test
    fun `active background work takes running over queued and ignores terminal history`() {
        assertEquals(
            SyncState.SYNCING,
            activeWorkSyncState(
                listOf(
                    WorkInfo.State.SUCCEEDED,
                    WorkInfo.State.ENQUEUED,
                    WorkInfo.State.RUNNING,
                ),
            ),
        )
        assertEquals(
            SyncState.QUEUED,
            activeWorkSyncState(listOf(WorkInfo.State.BLOCKED, WorkInfo.State.SUCCEEDED)),
        )
        assertNull(activeWorkSyncState(listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED)))
    }

    @Test
    fun `successful sync persistence failure stores fixed error state without exception text`() =
        runBlocking {
            val result = SyncResult.Success(listOf(AccountData("Account", 1.0, "TWD")))
            var lastRunStatus: String? = null
            var uiState: SyncState? = null
            var uiMessage: String? = null

            handleSuccessfulSyncPersistence(
                result = result,
                persist = { throw IllegalStateException("PRIVATE_PERSISTENCE_DETAIL") },
                updateLastRun = { lastRunStatus = it },
                updateUi = { state, message ->
                    uiState = state
                    uiMessage = message
                },
            )

            assertEquals("error", lastRunStatus)
            assertEquals(SyncState.ERROR, uiState)
            assertEquals(PERSISTENCE_FAILURE_MESSAGE, uiMessage)
            assertEquals(false, requireNotNull(uiMessage).contains("PRIVATE_PERSISTENCE_DETAIL"))
        }
}
