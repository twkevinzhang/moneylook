package tw.kevinzhang.moneylook.schedule

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import tw.kevinzhang.core.data.db.CredentialProfileDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.IngestionTrigger
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.moneylook.sync.BankSyncCoordinator
import tw.kevinzhang.moneylook.sync.SyncResultPersister
import tw.kevinzhang.moneylook.sync.appLastRunStatus
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

@HiltWorker
class ScheduleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncCoordinator: BankSyncCoordinator,
    private val syncResultPersister: SyncResultPersister,
    private val installedExtensionDao: InstalledExtensionDao,
    private val credentialProfileDao: CredentialProfileDao,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val extensionId = inputData.getString(KEY_EXTENSION_ID) ?: return Result.failure()
        val extension = installedExtensionDao.getById(extensionId) ?: return Result.failure()
        val profile = credentialProfileDao.getByExtensionId(extensionId) ?: return Result.failure()
        if (!profile.scheduleEnabled || profile.credential.isBlank()) {
            return Result.success()
        }

        val result = try {
            syncCoordinator.sync(extension, profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            syncResultPersister.recordFailure(extension, IngestionTrigger.SCHEDULED_SYNC)
            return handleFailure(profile)
        }
        return when (result) {
                is SyncResult.Success -> {
                    // persist() owns the run lifecycle, including a safe FAILED state on error.
                    try {
                        syncResultPersister.persist(extension, result, IngestionTrigger.SCHEDULED_SYNC)
                        credentialProfileDao.updateLastRun(
                            extensionId,
                            System.currentTimeMillis(),
                            result.appLastRunStatus,
                        )
                        enqueueNext(applicationContext, profile)
                        Result.success()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handleFailure(profile)
                    }
                }
                is SyncResult.Error -> {
                    syncResultPersister.recordFailure(extension, IngestionTrigger.SCHEDULED_SYNC)
                    handleFailure(profile)
                }
            }
    }

    private suspend fun handleFailure(profile: CredentialProfile): Result {
        credentialProfileDao.updateLastRun(
            profile.extensionId,
            System.currentTimeMillis(),
            "error",
        )
        return if (runAttemptCount < MAX_IMMEDIATE_RETRIES) {
            Result.retry()
        } else {
            enqueueNext(applicationContext, profile)
            Result.success()
        }
    }

    companion object {
        const val KEY_EXTENSION_ID = "extensionId"
        const val TAG_PREFIX = "schedule:"
        private const val MAX_IMMEDIATE_RETRIES = 2

        private val cronParser = CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX),
        )

        fun tag(extensionId: String) = "$TAG_PREFIX$extensionId"
        fun uniqueName(extensionId: String) = "sync:$extensionId"

        fun enqueueNext(context: Context, profile: CredentialProfile) {
            if (!profile.scheduleEnabled || profile.scheduleCron.isBlank()) return
            val zoneId = runCatching { ZoneId.of(profile.timezoneId) }.getOrNull() ?: return
            val now = ZonedDateTime.now(zoneId)
            val next = runCatching {
                ExecutionTime.forCron(cronParser.parse(profile.scheduleCron))
                    .nextExecution(now)
                    .orElse(null)
            }.getOrNull() ?: return
            val delayMs = Duration.between(now, next).toMillis().coerceAtLeast(0L)

            val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
                .setInputData(workDataOf(KEY_EXTENSION_ID to profile.extensionId))
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(tag(profile.extensionId))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueName(profile.extensionId),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
