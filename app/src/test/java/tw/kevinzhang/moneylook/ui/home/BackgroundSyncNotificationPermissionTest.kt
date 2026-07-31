package tw.kevinzhang.moneylook.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundSyncNotificationPermissionTest {
    @Test
    fun `Android 13 requests runtime permission before background sync`() {
        assertEquals(
            BackgroundSyncNotificationAccess.REQUEST_RUNTIME_PERMISSION,
            backgroundSyncNotificationAccess(
                sdkInt = 33,
                runtimePermissionGranted = false,
                appNotificationsEnabled = true,
            ),
        )
    }

    @Test
    fun `disabled App notifications always require settings instead of enqueueing`() {
        assertEquals(
            BackgroundSyncNotificationAccess.OPEN_NOTIFICATION_SETTINGS,
            backgroundSyncNotificationAccess(
                sdkInt = 32,
                runtimePermissionGranted = true,
                appNotificationsEnabled = false,
            ),
        )
        assertEquals(
            BackgroundSyncNotificationAccess.OPEN_NOTIFICATION_SETTINGS,
            backgroundSyncNotificationAccess(
                sdkInt = 33,
                runtimePermissionGranted = true,
                appNotificationsEnabled = false,
            ),
        )
    }

    @Test
    fun `only a granted permission and enabled notifications allow enqueueing`() {
        assertEquals(
            BackgroundSyncNotificationAccess.ALLOWED,
            backgroundSyncNotificationAccess(
                sdkInt = 33,
                runtimePermissionGranted = true,
                appNotificationsEnabled = true,
            ),
        )
    }
}
