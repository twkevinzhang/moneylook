package tw.kevinzhang.moneylook.schedule

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.Binds
import dagger.Module
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import tw.kevinzhang.core.data.db.ClassificationTraceMaintenanceStore
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TRACE_MAINTENANCE_CONTINUATION_KEY = "continuation"
private const val TRACE_MAINTENANCE_CONTINUATION_DELAY_SECONDS = 10L
private const val TRACE_MAINTENANCE_RETRY_BACKOFF_SECONDS = 30L
private const val TRACE_MAINTENANCE_LOG_TAG = "TraceMaintenance"

/**
 * Deletes obsolete classification trace rows in bounded windows.
 *
 * A successor carries a finite continuation counter, so process death is resumable without
 * turning one app start into an unbounded WorkManager chain. A later app start or terminal sync
 * starts a fresh bounded chain if the previous chain reached its cap.
 */
@HiltWorker
class ClassificationTraceMaintenanceWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val runner: ClassificationTraceMaintenanceRunner,
    private val scheduler: ClassificationTraceMaintenanceScheduler,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = try {
        Log.i(
            TRACE_MAINTENANCE_LOG_TAG,
            "classification trace cleanup window start: " +
                "continuation=${inputData.getInt(TRACE_MAINTENANCE_CONTINUATION_KEY, 0)}, " +
                "attempt=$runAttemptCount",
        )
        val outcome = runner.runWindow()
        outcome.stats?.let { stats ->
            Log.i(
                TRACE_MAINTENANCE_LOG_TAG,
                "classification trace cleanup complete: rowsDeleted=${outcome.rowsDeleted}, " +
                    "pageCount=${stats.pageCount}, freelistCount=${stats.freelistCount}, " +
                    "pageSize=${stats.pageSize}, databaseBytes=${stats.databaseBytes}, " +
                    "autoVacuum=${stats.autoVacuum}, " +
                    "walBusy=${stats.walBusy}, walLogFrames=${stats.walLogFrames}, " +
                    "walCheckpointedFrames=${stats.walCheckpointedFrames}",
            )
        }

        if (outcome.hasMore) {
            val continuation = inputData.getInt(TRACE_MAINTENANCE_CONTINUATION_KEY, 0)
            if (continuation < MAX_CONTINUATIONS_PER_CHAIN) {
                scheduler.continueAfter(continuation + 1)
            } else {
                Log.w(
                    TRACE_MAINTENANCE_LOG_TAG,
                    "classification trace cleanup paused at bounded continuation limit; " +
                        "the next app start or terminal sync will resume it",
                )
            }
        }
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (runAttemptCount < MAX_RETRY_ATTEMPTS - 1) {
            Result.retry()
        } else {
            // Maintenance must never turn an already-terminal bank sync into a business failure.
            // The next app start or terminal sync will create a fresh bounded attempt.
            Log.e(TRACE_MAINTENANCE_LOG_TAG, "classification trace cleanup paused after bounded retries", error)
            Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "classification-trace:maintenance"
        private const val ALL_TAG = "classification-trace:maintenance"
        private const val MAX_CONTINUATIONS_PER_CHAIN = 256
        private const val MAX_RETRY_ATTEMPTS = 5

        internal fun uniqueWorkName(): String = UNIQUE_WORK_NAME
        internal fun allTag(): String = ALL_TAG
        internal fun maxContinuationsPerChain(): Int = MAX_CONTINUATIONS_PER_CHAIN
        internal fun maxRetryAttempts(): Int = MAX_RETRY_ATTEMPTS
    }
}

@Singleton
class ClassificationTraceMaintenanceRunner @Inject constructor(
    private val store: ClassificationTraceMaintenanceStore,
) {
    suspend fun runWindow(
        nowMs: () -> Long = SystemClock::elapsedRealtime,
    ): ClassificationTraceMaintenanceWindowResult {
        val startedAt = nowMs()
        var batches = 0
        var rowsDeleted = 0L
        var hasMore: Boolean
        do {
            logTraceMaintenanceDebug("classification trace cleanup batch start: batch=${batches + 1}")
            val batch = store.pruneBatch(
                limit = ROWS_PER_BATCH,
                retainTerminalExecutions = RETAIN_TERMINAL_EXECUTIONS,
            )
            logTraceMaintenanceDebug(
                "classification trace cleanup batch end: batch=${batches + 1}, " +
                    "phase=${batch.phase}, rowsDeleted=${batch.rowsDeleted}, hasMore=${batch.hasMore}",
            )
            batches += 1
            rowsDeleted += batch.rowsDeleted.toLong()
            hasMore = batch.hasMore
        } while (
            hasMore &&
            batches < MAX_BATCHES_PER_WINDOW &&
            nowMs() - startedAt < MAX_WINDOW_MILLIS
        )

        val stats = if (hasMore) null else store.checkpointAndStats()
        return ClassificationTraceMaintenanceWindowResult(
            rowsDeleted = rowsDeleted,
            batches = batches,
            hasMore = hasMore,
            stats = stats,
        )
    }

    companion object {
        internal const val ROWS_PER_BATCH = 5_000
        internal const val RETAIN_TERMINAL_EXECUTIONS = 5
        internal const val MAX_BATCHES_PER_WINDOW = 20
        internal const val MAX_WINDOW_MILLIS = 20_000L
    }
}

data class ClassificationTraceMaintenanceWindowResult(
    val rowsDeleted: Long,
    val batches: Int,
    val hasMore: Boolean,
    val stats: tw.kevinzhang.core.data.db.ClassificationTraceDatabaseStats?,
)

/** Local JVM unit tests do not provide an Android Log implementation. */
private fun logTraceMaintenanceDebug(message: String) {
    runCatching { Log.d(TRACE_MAINTENANCE_LOG_TAG, message) }
}

interface ClassificationTraceMaintenanceScheduler {
    /** Best-effort external wake used at app start and after terminal sync state is durable. */
    fun wake()

    /** Appends one finite successor to the currently-running unique chain. */
    fun continueAfter(continuation: Int)
}

@Singleton
internal class WorkManagerClassificationTraceMaintenanceScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ClassificationTraceMaintenanceScheduler {
    override fun wake() {
        enqueueBestEffort {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ClassificationTraceMaintenanceWorker.uniqueWorkName(),
                ExistingWorkPolicy.KEEP,
                request(continuation = 0, delaySeconds = 0),
            )
        }
    }

    override fun continueAfter(continuation: Int) {
        require(continuation in 1..ClassificationTraceMaintenanceWorker.maxContinuationsPerChain())
        enqueueBestEffort {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ClassificationTraceMaintenanceWorker.uniqueWorkName(),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request(continuation, TRACE_MAINTENANCE_CONTINUATION_DELAY_SECONDS),
            )
        }
    }

    private fun request(continuation: Int, delaySeconds: Long) =
        OneTimeWorkRequestBuilder<ClassificationTraceMaintenanceWorker>()
            .setInputData(workDataOf(TRACE_MAINTENANCE_CONTINUATION_KEY to continuation))
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                TRACE_MAINTENANCE_RETRY_BACKOFF_SECONDS,
                TimeUnit.SECONDS,
            )
            .addTag(ClassificationTraceMaintenanceWorker.allTag())
            .build()
}

/** Scheduling is diagnostic maintenance; failure is deliberately isolated from sync state. */
internal inline fun enqueueBestEffort(enqueue: () -> Unit): Boolean = runCatching(enqueue).isSuccess

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ClassificationTraceMaintenanceModule {
    @Binds
    abstract fun bindClassificationTraceMaintenanceScheduler(
        implementation: WorkManagerClassificationTraceMaintenanceScheduler,
    ): ClassificationTraceMaintenanceScheduler
}
