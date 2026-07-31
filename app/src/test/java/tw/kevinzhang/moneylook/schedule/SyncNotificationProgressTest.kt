package tw.kevinzhang.moneylook.schedule

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncNotificationProgressTest {
    @Test
    fun `notification statuses remain bank-level and reveal no account data`() {
        val entries = listOf(
            SyncNotificationEntry("a", "銀行甲", SyncNotificationStatus.RUNNING),
            SyncNotificationEntry("b", "銀行乙", SyncNotificationStatus.QUEUED),
            SyncNotificationEntry("c", "銀行丙", SyncNotificationStatus.PARTIAL),
        )

        assertEquals(listOf("銀行甲", "銀行乙", "銀行丙"), entries.map { it.extensionName })
        assertEquals(listOf("同步中", "等待同步", "部分完成"), entries.map { it.status.label })
    }

    @Test
    fun `aggregate notification is ongoing opens the app and exposes only bank progress`() {
        val notification = SyncNotification.create(
            RuntimeEnvironment.getApplication(),
            listOf(
                SyncNotificationEntry("a", "銀行甲", SyncNotificationStatus.RUNNING),
                SyncNotificationEntry("b", "銀行乙", SyncNotificationStatus.SUCCESS),
            ),
        )

        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(notification.contentIntent)
        assertEquals(0, notification.actions?.size ?: 0)
        val lines = notification.extras
            .getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            .orEmpty()
            .map(CharSequence::toString)
        assertEquals(setOf("銀行甲：同步中", "銀行乙：同步完成"), lines.toSet())
        assertTrue(lines.none { it.contains("credential") || it.contains("balance") })
    }
}
