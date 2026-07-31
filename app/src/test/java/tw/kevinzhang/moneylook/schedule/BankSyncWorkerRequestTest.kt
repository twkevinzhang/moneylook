package tw.kevinzhang.moneylook.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BankSyncWorkerRequestTest {

    @Test
    fun `global sync worker name cannot collide with cron timers`() {
        assertEquals("bank-sync:orchestrator", BankSyncWorker.uniqueQueueName())
        assertEquals("schedule:bank-a", ScheduleWorker.uniqueName("bank-a"))
        assertEquals("sync:bank-a", ScheduleWorker.legacyUniqueName("bank-a"))
        assertFalse(BankSyncWorker.uniqueQueueName() == ScheduleWorker.uniqueName("bank-a"))
        assertFalse(ScheduleWorker.uniqueName("bank-a") == ScheduleWorker.legacyUniqueName("bank-a"))
    }
}
