package tw.kevinzhang.moneylook.schedule

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BankSyncWorkerRequestTest {

    @Test
    fun `user request exposes extension tag and user trigger without schedule dependency`() {
        val request = BankSyncWorker.request("bank-a", BankSyncWorker.SyncTrigger.USER_SYNC)
        val input = BankSyncWorker.requestInputData("bank-a", BankSyncWorker.SyncTrigger.USER_SYNC)

        assertTrue(BankSyncWorker.tag("bank-a") in request.tags)
        assertTrue(BankSyncWorker.allTag() in request.tags)
        assertEquals("bank-a", input.getString(BankSyncWorker.KEY_EXTENSION_ID))
        assertEquals(
            BankSyncWorker.SyncTrigger.USER_SYNC.wireValue,
            input.getString(BankSyncWorker.KEY_TRIGGER),
        )
    }

    @Test
    fun `scheduled request keeps its distinct trigger for runtime schedule gate`() {
        val input = BankSyncWorker.requestInputData("bank-a", BankSyncWorker.SyncTrigger.SCHEDULED_SYNC)

        assertEquals(
            BankSyncWorker.SyncTrigger.SCHEDULED_SYNC.wireValue,
            input.getString(BankSyncWorker.KEY_TRIGGER),
        )
    }

    @Test
    fun `sync queue and cron unique names cannot collide`() {
        assertEquals("bank-sync:queue", BankSyncWorker.uniqueQueueName())
        assertEquals("schedule:bank-a", ScheduleWorker.uniqueName("bank-a"))
        assertEquals("sync:bank-a", ScheduleWorker.legacyUniqueName("bank-a"))
        assertFalse(BankSyncWorker.uniqueQueueName() == ScheduleWorker.uniqueName("bank-a"))
        assertFalse(ScheduleWorker.uniqueName("bank-a") == ScheduleWorker.legacyUniqueName("bank-a"))
    }

    @Test
    fun `blocked work is treated as pending so sequential tail cannot be duplicated`() {
        assertTrue(BankSyncWorker.isPending(workInfo(WorkInfo.State.BLOCKED)))
        assertTrue(BankSyncWorker.isPending(workInfo(WorkInfo.State.ENQUEUED)))
        assertTrue(BankSyncWorker.isPending(workInfo(WorkInfo.State.RUNNING)))
        assertFalse(BankSyncWorker.isPending(workInfo(WorkInfo.State.SUCCEEDED)))
    }

    @Test
    fun `pre-extension failure is the only boundary eligible for bounded retry`() {
        assertTrue(BankSyncWorker.shouldRetry(BankSyncWorker.FailureBoundary.PRE_EXTENSION, 0))
        assertTrue(BankSyncWorker.shouldRetry(BankSyncWorker.FailureBoundary.PRE_EXTENSION, 1))
        assertFalse(BankSyncWorker.shouldRetry(BankSyncWorker.FailureBoundary.PRE_EXTENSION, 2))
    }

    @Test
    fun `extension and persistence failures never resubmit the bank session`() {
        for (attempt in 0..3) {
            assertFalse(
                BankSyncWorker.shouldRetry(
                    BankSyncWorker.FailureBoundary.EXTENSION_OR_PERSISTENCE,
                    attempt,
                ),
            )
        }
    }

    private fun workInfo(state: WorkInfo.State): WorkInfo = WorkInfo(
        id = java.util.UUID.randomUUID(),
        state = state,
        tags = emptySet(),
    )
}
