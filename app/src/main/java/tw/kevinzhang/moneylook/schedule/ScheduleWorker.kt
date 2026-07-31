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
import tw.kevinzhang.core.data.db.CredentialProfileDao
import tw.kevinzhang.core.data.model.CredentialProfile
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** Schedules the next cron wake-up; [BankSyncWorker] owns the actual bank session. */
@HiltWorker
class ScheduleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val credentialProfileDao: CredentialProfileDao,
    private val schedulerManager: SchedulerManager,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val extensionId = inputData.getString(KEY_EXTENSION_ID) ?: return Result.failure()
        val profile = credentialProfileDao.getByExtensionId(extensionId) ?: return Result.success()
        if (!profile.scheduleEnabled || profile.scheduleCron.isBlank() || profile.credential.isBlank()) {
            return Result.success()
        }

        // Re-arm before appending the actual sync. A bank failure/retry must never erase the
        // next cron occurrence, and the schedule queue is intentionally distinct from sync work.
        enqueueNext(applicationContext, profile)
        // A denied or globally-disabled notification skips this occurrence. The timer above has
        // already re-armed the next cron occurrence, so permission recovery needs no migration.
        schedulerManager.enqueueScheduledSync(extensionId)
        return Result.success()
    }

    companion object {
        const val KEY_EXTENSION_ID = "extensionId"
        private const val TAG_PREFIX = "schedule:"
        private const val UNIQUE_PREFIX = "schedule:"
        private const val LEGACY_UNIQUE_PREFIX = "sync:"

        private val cronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))

        fun tag(extensionId: String): String = "$TAG_PREFIX$extensionId"
        fun uniqueName(extensionId: String): String = "$UNIQUE_PREFIX$extensionId"
        fun legacyUniqueName(extensionId: String): String = "$LEGACY_UNIQUE_PREFIX$extensionId"

        fun enqueueNext(context: Context, profile: CredentialProfile) {
            if (!profile.scheduleEnabled || profile.scheduleCron.isBlank()) return
            val zoneId = runCatching { ZoneId.of(profile.timezoneId) }.getOrNull() ?: return
            val now = ZonedDateTime.now(zoneId)
            val next = runCatching {
                ExecutionTime.forCron(cronParser.parse(profile.scheduleCron)).nextExecution(now).orElse(null)
            }.getOrNull() ?: return
            val delayMs = Duration.between(now, next).toMillis().coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
                .setInputData(workDataOf(KEY_EXTENSION_ID to profile.extensionId))
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
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
