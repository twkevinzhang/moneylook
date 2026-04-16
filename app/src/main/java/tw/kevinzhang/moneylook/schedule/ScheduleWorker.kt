package tw.kevinzhang.moneylook.schedule

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
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
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.session.SessionStore
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

@HiltWorker
class ScheduleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val extensionRunner: ExtensionRunner,
    private val installedExtensionDao: InstalledExtensionDao,
    private val accountDao: AccountDao,
    private val sessionStore: SessionStore,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val extensionId = inputData.getString(KEY_EXTENSION_ID)
            ?: return Result.failure()

        val extension = installedExtensionDao.getById(extensionId)
            ?: return Result.failure()   // extension was uninstalled

        val cronExpr = extension.scheduleCron
            ?: return Result.failure()   // no cron configured

        if (!sessionStore.hasSession(extensionId)) {
            // No session — silently skip and re-enqueue for next tick
            enqueueNext(applicationContext, extensionId, cronExpr)
            return Result.success()
        }

        val result = extensionRunner.runSchedule(extension)

        if (result is SyncResult.Success) {
            val now = System.currentTimeMillis()
            val accounts = result.accounts.map { data ->
                Account(
                    id = "${extensionId}_${data.name}",
                    extensionId = extensionId,
                    extensionName = extension.name,
                    accountName = data.name,
                    balance = data.balance,
                    currency = data.currency,
                    lastSyncAt = now,
                )
            }
            accountDao.upsertAll(accounts)
        }

        // Re-enqueue for the next cron tick
        enqueueNext(applicationContext, extensionId, cronExpr)

        return Result.success()
    }

    companion object {
        const val KEY_EXTENSION_ID = "extensionId"
        const val TAG_PREFIX = "schedule:"

        private val cronParser = CronParser(
            CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING)
        )

        fun tag(extensionId: String) = "$TAG_PREFIX$extensionId"

        fun enqueueNext(context: Context, extensionId: String, cronExpr: String) {
            val now = ZonedDateTime.now()
            val next = ExecutionTime.forCron(cronParser.parse(cronExpr))
                .nextExecution(now)
                .orElse(null) ?: return

            val delayMs = Duration.between(now, next).toMillis().coerceAtLeast(0L)

            val request = OneTimeWorkRequestBuilder<ScheduleWorker>()
                .setInputData(workDataOf(KEY_EXTENSION_ID to extensionId))
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(tag(extensionId))
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
