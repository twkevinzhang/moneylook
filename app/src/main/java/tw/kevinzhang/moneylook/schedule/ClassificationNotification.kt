package tw.kevinzhang.moneylook.schedule

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import tw.kevinzhang.core.data.model.ClassificationJob
import tw.kevinzhang.moneylook.MainActivity
import tw.kevinzhang.moneylook.R
import javax.inject.Inject

internal object ClassificationNotification {
    const val CHANNEL = "background_classification"
    const val ID = 31002
    fun allowed(context: Context): Boolean = SyncNotification.isAllowed(context) &&
        (Build.VERSION.SDK_INT < 26 || context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE)
    fun create(context: Context, job: ClassificationJob): Notification {
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "背景自動分類", NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (job.status == "RESTARTING") "規則已變更，正在重新分類" else "Moneylook 正在背景分類")
            .setContentText(if (job.status == "QUEUED") "已排程，等待網路與系統執行" else "已處理 ${job.processed}／${job.total} 筆交易")
            .setProgress(job.total.coerceAtLeast(0), job.processed.coerceAtLeast(0), job.total <= 0)
            .setContentIntent(PendingIntent.getActivity(context, 31002, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "停止並保存進度", PendingIntent.getBroadcast(context, 31002,
                Intent(context, ClassificationStopReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(job.active).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS).build()
    }
    fun publish(context: Context, job: ClassificationJob) {
        if (allowed(context)) NotificationManagerCompat.from(context).notify(ID, create(context, job))
    }
    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}

@AndroidEntryPoint
class ClassificationStopReceiver : BroadcastReceiver() {
    @Inject lateinit var scheduler: BackgroundClassificationScheduler
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { withTimeout(8_000) { scheduler.stop() } }
            catch (_: Exception) { /* The scheduler preserves a paused state even if WorkManager times out. */ }
            finally { pending.finish() }
        }
    }
}
