package tw.kevinzhang.moneylook.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.KindSyncResult
import tw.kevinzhang.extension_runtime.data.KindSyncStatus
import tw.kevinzhang.extension_runtime.data.SyncResult

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
    fun `persisted partial state survives a Home reload without exposing a code`() {
        assertEquals(SyncState.PARTIAL, persistedSyncState("partial"))
        assertEquals(PARTIAL_SYNC_MESSAGE, persistedSyncMessage("partial"))
        assertEquals(SyncState.IDLE, persistedSyncState("success"))
        assertNull(persistedSyncMessage("success"))
    }
}
