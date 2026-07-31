package tw.kevinzhang.moneylook.ui.home

/**
 * Decides the next user-visible step before a background sync can be enqueued.
 *
 * A foreground data-sync worker is only useful here when its progress is visible in the
 * notification shade, so a disabled notification channel is treated the same as a denied
 * runtime permission.
 */
internal enum class BackgroundSyncNotificationAccess {
    ALLOWED,
    REQUEST_RUNTIME_PERMISSION,
    OPEN_NOTIFICATION_SETTINGS,
}

internal fun backgroundSyncNotificationAccess(
    sdkInt: Int,
    runtimePermissionGranted: Boolean,
    appNotificationsEnabled: Boolean,
): BackgroundSyncNotificationAccess = when {
    sdkInt >= 33 && !runtimePermissionGranted -> {
        BackgroundSyncNotificationAccess.REQUEST_RUNTIME_PERMISSION
    }
    !appNotificationsEnabled -> BackgroundSyncNotificationAccess.OPEN_NOTIFICATION_SETTINGS
    else -> BackgroundSyncNotificationAccess.ALLOWED
}
