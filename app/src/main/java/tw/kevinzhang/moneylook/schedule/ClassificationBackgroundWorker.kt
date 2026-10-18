package tw.kevinzhang.moneylook.schedule

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import tw.kevinzhang.core.data.db.ClassificationJobStore
import tw.kevinzhang.moneylook.sync.BackgroundClassificationRunner

@HiltWorker
class ClassificationBackgroundWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted params: WorkerParameters,
    private val store: ClassificationJobStore,
    private val runner: BackgroundClassificationRunner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val attempt = inputData.getString("attemptId") ?: return Result.success()
        val current = store.current()
        if (!current.active || current.attemptId != attempt) return Result.success()
        if (!store.claimAttempt(attempt)) {
            store.pause(attempt, "背景工作曾被系統中止，已保存進度，請點擊繼續分類")
            ClassificationNotification.cancel(applicationContext)
            return Result.success()
        }
        if (!ClassificationNotification.allowed(applicationContext)) {
            store.pause(attempt, "背景分類通知未開啟，請允許通知後再接續"); return Result.success()
        }
        return try {
            val notification = ClassificationNotification.create(applicationContext, current)
            setForeground(if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ClassificationNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ForegroundInfo(ClassificationNotification.ID, notification))
            coroutineScope {
                val updates = launch { store.observe().collect { if (it.attemptId == attempt) ClassificationNotification.publish(applicationContext, it) } }
                try { runner.run(attempt) } finally { updates.cancelAndJoin() }
            }
            Result.success()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { store.pause(attempt, "背景分類未能啟動，已保存進度，可再次接續"); Result.success() }
        finally { ClassificationNotification.cancel(applicationContext) }
    }
}
