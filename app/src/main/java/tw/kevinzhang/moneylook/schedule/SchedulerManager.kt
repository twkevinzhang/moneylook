package tw.kevinzhang.moneylook.schedule

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tw.kevinzhang.core.data.model.CredentialProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SchedulerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val userEnqueueMutex = Mutex()

    fun scheduleProfile(profile: CredentialProfile) {
        cancelSchedule(profile.extensionId)
        ScheduleWorker.enqueueNext(context, profile)
    }

    /**
     * Stops future cron wake-ups only. It intentionally leaves an already-started user sync
     * alone, and queued sync work will self-skip if its credential profile is deleted.
     */
    fun cancelSchedule(extensionId: String) {
        WorkManager.getInstance(context).run {
            cancelUniqueWork(ScheduleWorker.uniqueName(extensionId))
            // Previous app versions stored the cron timer under sync:<id>. Clear it during the
            // first reschedule so an upgrade cannot leave both the legacy and current timer alive.
            cancelUniqueWork(ScheduleWorker.legacyUniqueName(extensionId))
            cancelAllWorkByTag(ScheduleWorker.tag(extensionId))
        }
    }

    /** Backwards-compatible name used by credential disable/delete callers. */
    fun cancelExtension(extensionId: String) {
        cancelSchedule(extensionId)
    }

    fun rescheduleAll(profiles: List<CredentialProfile>) {
        profiles.forEach(::scheduleProfile)
    }

    /**
     * Enqueues one explicit sync unless the extension is already queued, blocked, or running.
     * The return value reports whether this call added new WorkManager work.
     */
    suspend fun enqueueUserSync(extensionId: String): Boolean = userEnqueueMutex.withLock {
        withContext(Dispatchers.IO) {
            val workManager = WorkManager.getInstance(context)
            if (hasPendingSync(workManager, extensionId)) return@withContext false
            BankSyncWorker.enqueue(
                context,
                listOf(BankSyncWorker.request(extensionId, BankSyncWorker.SyncTrigger.USER_SYNC)),
            )
            true
        }
    }

    /**
     * Appends missing explicit syncs as one chain in the supplied order. Existing work stays in
     * the queue and is therefore still awaited before the first newly-added extension.
     */
    suspend fun enqueueUserSyncsSequentially(extensionIds: List<String>): List<String> =
        userEnqueueMutex.withLock {
            withContext(Dispatchers.IO) {
                val workManager = WorkManager.getInstance(context)
                val addedIds = extensionIds.distinct().filterNot { extensionId ->
                    hasPendingSync(workManager, extensionId)
                }
                BankSyncWorker.enqueue(
                    context,
                    addedIds.map { extensionId ->
                        BankSyncWorker.request(extensionId, BankSyncWorker.SyncTrigger.USER_SYNC)
                    },
                )
                addedIds
            }
        }

    private fun hasPendingSync(workManager: WorkManager, extensionId: String): Boolean = try {
        workManager.getWorkInfosByTag(BankSyncWorker.tag(extensionId)).get().any(BankSyncWorker::isPending)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Do not assume an unknown WorkManager state is safe to duplicate. The next UI refresh
        // can retry this operation after WorkManager becomes available.
        true
    }
}
