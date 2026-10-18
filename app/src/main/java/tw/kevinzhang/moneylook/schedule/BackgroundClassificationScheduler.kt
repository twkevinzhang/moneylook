package tw.kevinzhang.moneylook.schedule

import android.content.Context
import androidx.work.*
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tw.kevinzhang.core.data.db.ClassificationJobStore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface BackgroundClassificationScheduler {
    suspend fun start(): Boolean
    suspend fun stop()
    suspend fun reconcile()
}

@Singleton
class WorkManagerBackgroundClassificationScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ClassificationJobStore,
) : BackgroundClassificationScheduler {
    private val mutex = Mutex()
    private val manager get() = WorkManager.getInstance(context)
    override suspend fun start(): Boolean = mutex.withLock {
        if (!ClassificationNotification.allowed(context)) throw IllegalStateException("請先允許背景分類通知，再開始分類")
        val job = store.enqueue() ?: return@withLock false
        val request = OneTimeWorkRequestBuilder<ClassificationBackgroundWorker>()
            .setInputData(workDataOf("attemptId" to job.attemptId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        try {
            ClassificationNotification.publish(context, job)
            withContext(Dispatchers.IO) {
                manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get(10, TimeUnit.SECONDS)
            }
            true
        } catch (error: Exception) {
            store.pause(job.attemptId!!, "無法排程背景分類，請稍後重試")
            ClassificationNotification.cancel(context)
            throw error
        }
    }
    override suspend fun stop() = mutex.withLock {
        val job = store.current()
        val attempt = job.attemptId ?: return@withLock
        if (!store.stopping(attempt)) return@withLock
        try { withContext(Dispatchers.IO) { manager.cancelUniqueWork(WORK_NAME).result.get(5, TimeUnit.SECONDS) } }
        finally { store.pause(attempt, "已停止並保存進度，可再次接續"); ClassificationNotification.cancel(context) }
    }
    override suspend fun reconcile() = mutex.withLock {
        val job = store.current()
        if (!job.active || job.attemptId == null) return@withLock
        val work = withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork(WORK_NAME).get(5, TimeUnit.SECONDS) }
        if (work.none { !it.state.isFinished }) {
            store.pause(job.attemptId!!, "背景工作已中止，已保存進度，可再次接續")
            ClassificationNotification.cancel(context)
        }
    }
    companion object { const val WORK_NAME = "background_ai_classification" }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackgroundClassificationModule {
    @Binds abstract fun scheduler(impl: WorkManagerBackgroundClassificationScheduler): BackgroundClassificationScheduler
}
