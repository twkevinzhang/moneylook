package tw.kevinzhang.moneylook.schedule

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tw.kevinzhang.core.data.db.ClassificationJobStore
import tw.kevinzhang.core.data.db.MoneylookDatabase
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.AiClassificationState
import tw.kevinzhang.core.data.model.Transfer
import java.util.concurrent.TimeUnit

/** Real WorkManager persistence and scheduler, with network constraints deliberately unmet. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BackgroundClassificationSchedulerTest {
    private lateinit var context: Context
    private lateinit var db: MoneylookDatabase
    private lateinit var store: ClassificationJobStore
    private lateinit var manager: WorkManager
    private lateinit var scheduler: WorkManagerBackgroundClassificationScheduler
    private var workerCreations = 0

    @Before fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                workerCreations++
                error("Network-constrained work must never run or reach Hilt/AI in this test")
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        manager = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, MoneylookDatabase::class.java).allowMainThreadQueries().build()
        store = ClassificationJobStore(db)
        scheduler = WorkManagerBackgroundClassificationScheduler(context, store)
        db.accountDao().upsertAll(listOf(Account("synthetic", "test", "Bank", "Account", 0.0, "TWD", 0)))
        db.aiClassificationDao().putState(AiClassificationState(started = true, complete = true))
        db.transferDao().upsertAll(listOf(Transfer("pending", "synthetic", "test", "2026-10-18", "合成交易", -60.0, null, "")))
    }

    @After fun tearDown() {
        manager.cancelAllWork().result.get(5, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        db.close()
        assertEquals("No actual Worker, network request, or AI execution is allowed", 0, workerCreations)
    }

    private fun work(): List<WorkInfo> = manager.getWorkInfosForUniqueWork(
        WorkManagerBackgroundClassificationScheduler.WORK_NAME).get(5, TimeUnit.SECONDS)

    @Test fun doubleStartKeepsOneNetworkConstrainedUniqueWorkAndPublishesStopAction() = runBlocking {
        assertTrue(scheduler.start())
        val attempt = store.current().attemptId
        assertFalse(scheduler.start())
        val queued = work().single()
        assertEquals(WorkInfo.State.ENQUEUED, queued.state)
        assertEquals(NetworkType.CONNECTED, queued.constraints.requiredNetworkType)
        assertEquals(attempt, store.current().attemptId)
        assertEquals("QUEUED", store.current().status)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertTrue(notification.actions.any { it.title.toString() == "停止並保存進度" })
        scheduler.reconcile()
        assertEquals("QUEUED", store.current().status)
        assertEquals(1, work().size)
    }

    @Test fun stopCancelsWorkSavesPendingScopeAndResumeCreatesOnlyOneNewActiveWork() = runBlocking {
        scheduler.start()
        val oldAttempt = store.current().attemptId
        scheduler.stop()
        assertEquals(WorkInfo.State.CANCELLED, work().single().state)
        assertEquals("PAUSED", store.current().status)
        assertEquals(oldAttempt, store.current().attemptId)
        assertEquals(1, store.preview().pendingTransferCount)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
        assertTrue(scheduler.start())
        assertNotEquals(oldAttempt, store.current().attemptId)
        assertEquals(1, work().count { !it.state.isFinished })
        assertFalse(scheduler.start())
        assertEquals(1, work().count { !it.state.isFinished })
    }

    @Test fun reconcilePausesOrphanedDurableJobWithoutStartingClassification() = runBlocking {
        val queued = store.enqueue()!!
        assertTrue(work().isEmpty())
        scheduler.reconcile()
        val recovered = store.current()
        assertEquals("PAUSED", recovered.status)
        assertEquals(queued.attemptId, recovered.attemptId)
        assertEquals(1, store.preview().pendingTransferCount)
        assertTrue(work().isEmpty())
    }
}
