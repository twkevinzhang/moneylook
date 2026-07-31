package tw.kevinzhang.moneylook.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import tw.kevinzhang.moneylook.MainActivity
import tw.kevinzhang.moneylook.R

internal enum class SyncNotificationStatus(val label: String) {
    QUEUED("等待同步"),
    RUNNING("同步中"),
    SUCCESS("同步完成"),
    PARTIAL("部分完成"),
    ERROR("同步失敗"),
    SKIPPED("略過同步"),
}

internal data class SyncNotificationEntry(
    val extensionId: String,
    val extensionName: String,
    val status: SyncNotificationStatus,
)

/** Owns the one app-wide, privacy-safe foreground notification for a batch of bank syncs. */
internal object SyncNotification {
    const val CHANNEL_ID = "background_sync"
    const val NOTIFICATION_ID = 31001

    fun isAllowed(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED)

    fun create(context: Context, entries: Collection<SyncNotificationEntry>) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Moneylook 正在同步")
            .setContentText(summary(entries))
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    entries.sortedBy { it.extensionName }.forEach { entry ->
                        style.addLine("${entry.extensionName}：${entry.status.label}")
                    }
                },
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "背景同步",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "顯示 Moneylook 背景同步進度"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun summary(entries: Collection<SyncNotificationEntry>): String = when {
        entries.isEmpty() -> "正在準備同步"
        entries.any { it.status == SyncNotificationStatus.RUNNING } -> "正在同步 ${entries.size} 家銀行"
        else -> "等待同步 ${entries.size} 家銀行"
    }
}
