package tw.kevinzhang.moneylook.schedule

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import tw.kevinzhang.core.data.db.CredentialProfileDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.moneylook.sync.BankSyncCoordinator
import tw.kevinzhang.moneylook.sync.SyncResultPersister
import tw.kevinzhang.moneylook.sync.appLastRunStatus
import tw.kevinzhang.moneylook.sync.hasPartialSyncFailure
import java.util.concurrent.TimeUnit

/**
 * The durable execution boundary for one extension sync.
 *
 * All requests enter the same unique WorkManager chain.  This deliberately serializes bank
 * sessions: extensions use WebView and may invoke OCR, so running several at once provides no
 * useful guarantee and made the former "sync all" action contradict its UI copy.
 */
@HiltWorker
class BankSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncCoordinator: BankSyncCoordinator,
    private val syncResultPersister: SyncResultPersister,
    private val installedExtensionDao: InstalledExtensionDao,
    private val credentialProfileDao: CredentialProfileDao,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val extensionId = inputData.getString(KEY_EXTENSION_ID)
            ?: return Result.failure(resultData(RESULT_ERROR))
        val trigger = SyncTrigger.fromWireValue(inputData.getString(KEY_TRIGGER))
            ?: return Result.failure(resultData(RESULT_ERROR))
        val extension = try {
            installedExtensionDao.getById(extensionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return retryOrFinishPreExtensionFailure(extensionId)
        } ?: return Result.success(resultData(RESULT_SKIPPED))
        val profile = try {
            credentialProfileDao.getByExtensionId(extensionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return retryOrFinishPreExtensionFailure(extensionId)
        } ?: return Result.success(resultData(RESULT_SKIPPED))

        // A scheduled run must honour a newly-disabled schedule. A user request intentionally
        // does not, because changing the schedule is unrelated to an explicit sync action.
        if (profile.credential.isBlank() || (trigger == SyncTrigger.SCHEDULED_SYNC && !profile.scheduleEnabled)) {
            return Result.success(resultData(RESULT_SKIPPED))
        }

        setProgress(workDataOf(KEY_PROGRESS_STATE to PROGRESS_RUNNING))
        val syncResult = try {
            syncCoordinator.sync(extension, profile)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val failure = SyncResult.Error(
                message = "sync runtime failed",
                origin = "RUNTIME",
                cause = error,
                rawMessage = error.message ?: error.toString(),
                rawStack = error.stackTraceToString(),
            )
            recordFailureSafely(extension, trigger.ingestionTrigger, failure = failure)
            return finishTerminalFailure(extensionId)
        }

        return when (syncResult) {
            is SyncResult.Success -> persistSuccess(extensionId, extension, trigger, syncResult)
            is SyncResult.Error -> {
                recordFailureSafely(
                    extension = extension,
                    trigger = trigger.ingestionTrigger,
                    sourceDocuments = syncResult.sourceDocuments,
                    sourceRunId = syncResult.runId,
                    sourceRunStartedAt = syncResult.runStartedAt,
                    failure = syncResult,
                )
                finishTerminalFailure(extensionId)
            }
        }
    }

    private suspend fun persistSuccess(
        extensionId: String,
        extension: tw.kevinzhang.core.data.model.InstalledExtension,
        trigger: SyncTrigger,
        result: SyncResult.Success,
    ): Result = try {
        syncResultPersister.persist(extension, result, trigger.ingestionTrigger)
        credentialProfileDao.updateLastRun(
            extensionId = extensionId,
            lastRunAt = System.currentTimeMillis(),
            lastRunStatus = result.appLastRunStatus,
        )
        Result.success(resultData(if (result.hasPartialSyncFailure) RESULT_PARTIAL else RESULT_SUCCESS))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // persist() records its own persistence failure when possible. Keep the profile state
        // aligned even if that diagnostic recording itself failed. Never retry the whole Worker
        // here: the bank session already completed, so WorkManager retry would submit the login
        // and scrape again merely to recover a local persistence failure.
        updateLastRunErrorSafely(extensionId)
        Result.success(resultData(RESULT_ERROR))
    }

    private suspend fun retryOrFinishPreExtensionFailure(extensionId: String): Result {
        updateLastRunErrorSafely(extensionId)
        return if (shouldRetry(FailureBoundary.PRE_EXTENSION, runAttemptCount)) {
            Result.retry()
        } else {
            Result.success(resultData(RESULT_ERROR))
        }
    }

    private suspend fun finishTerminalFailure(extensionId: String): Result {
        updateLastRunErrorSafely(extensionId)
        return Result.success(resultData(RESULT_ERROR))
    }

    private suspend fun recordFailureSafely(
        extension: tw.kevinzhang.core.data.model.InstalledExtension,
        trigger: IngestionTrigger,
        sourceDocuments: List<tw.kevinzhang.extension_runtime.data.CapturedSourceDocument> = emptyList(),
        sourceRunId: String? = null,
        sourceRunStartedAt: Long? = null,
        failure: SyncResult.Error,
    ) {
        try {
            syncResultPersister.recordFailure(
                extension = extension,
                trigger = trigger,
                sourceDocuments = sourceDocuments,
                sourceRunId = sourceRunId,
                sourceRunStartedAt = sourceRunStartedAt,
                failure = failure,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The work retry below is the recovery path when persistence is temporarily down.
        }
    }

    private suspend fun updateLastRunErrorSafely(extensionId: String) {
        try {
            credentialProfileDao.updateLastRun(extensionId, System.currentTimeMillis(), "error")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Preserve the WorkManager retry decision even if Room is temporarily unavailable.
        }
    }

    enum class SyncTrigger(val wireValue: String, val ingestionTrigger: IngestionTrigger) {
        USER_SYNC("USER_SYNC", IngestionTrigger.USER_SYNC),
        SCHEDULED_SYNC("SCHEDULED_SYNC", IngestionTrigger.SCHEDULED_SYNC),
        ;

        companion object {
            fun fromWireValue(value: String?): SyncTrigger? = entries.firstOrNull { it.wireValue == value }
        }
    }

    companion object {
        const val KEY_EXTENSION_ID = "extensionId"
        const val KEY_TRIGGER = "trigger"
        const val KEY_PROGRESS_STATE = "syncProgressState"
        const val KEY_RESULT_STATUS = "syncResultStatus"

        const val RESULT_SUCCESS = "success"
        const val RESULT_PARTIAL = "partial"
        const val RESULT_ERROR = "error"
        const val RESULT_SKIPPED = "skipped"
        const val PROGRESS_RUNNING = "running"

        private const val UNIQUE_QUEUE_NAME = "bank-sync:queue"
        private const val TAG_PREFIX = "bank-sync:"
        private const val ALL_TAG = "bank-sync:all"
        private const val MAX_PRE_EXTENSION_RETRIES = 2

        fun uniqueQueueName(): String = UNIQUE_QUEUE_NAME
        fun tag(extensionId: String): String = "$TAG_PREFIX$extensionId"
        fun allTag(): String = ALL_TAG

        fun request(extensionId: String, trigger: SyncTrigger): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<BankSyncWorker>()
                .setInputData(requestInputData(extensionId, trigger))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(tag(extensionId))
                .addTag(ALL_TAG)
                .build()

        internal fun requestInputData(extensionId: String, trigger: SyncTrigger): Data = workDataOf(
            KEY_EXTENSION_ID to extensionId,
            KEY_TRIGGER to trigger.wireValue,
        )

        fun isPending(workInfo: WorkInfo): Boolean =
            workInfo.state == WorkInfo.State.ENQUEUED ||
                workInfo.state == WorkInfo.State.RUNNING ||
                workInfo.state == WorkInfo.State.BLOCKED

        internal fun shouldRetry(boundary: FailureBoundary, runAttemptCount: Int): Boolean =
            boundary == FailureBoundary.PRE_EXTENSION &&
                runAttemptCount < MAX_PRE_EXTENSION_RETRIES

        fun enqueue(context: Context, requests: List<OneTimeWorkRequest>) {
            if (requests.isEmpty()) return
            val continuation = WorkManager.getInstance(context).beginUniqueWork(
                UNIQUE_QUEUE_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                requests.first(),
            )
            requests.drop(1).fold(continuation) { chain, request -> chain.then(request) }.enqueue()
        }

        private fun resultData(status: String): Data = workDataOf(KEY_RESULT_STATUS to status)
    }

    internal enum class FailureBoundary {
        PRE_EXTENSION,
        EXTENSION_OR_PERSISTENCE,
    }
}
