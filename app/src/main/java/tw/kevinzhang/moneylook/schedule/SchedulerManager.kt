package tw.kevinzhang.moneylook.schedule

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tw.kevinzhang.core.data.db.PendingSyncRequestDao
import tw.kevinzhang.core.data.model.CredentialProfile
import tw.kevinzhang.core.data.model.PendingSyncRequest
import tw.kevinzhang.core.data.model.SyncRequestTrigger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SchedulerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pendingSyncRequestDao: PendingSyncRequestDao,
) {
    fun scheduleProfile(profile: CredentialProfile) {
        cancelSchedule(profile.extensionId)
        ScheduleWorker.enqueueNext(context, profile)
    }

    /**
     * Stops future cron wake-ups only. It intentionally leaves an already-started user sync
     * alone, and queued sync work will self-skip if its credential profile is deleted.
     */
    fun cancelSchedule(extensionId: String) {
        androidx.work.WorkManager.getInstance(context).run {
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

    /** A visible foreground notification is a product requirement, not a best-effort extra. */
    fun isBackgroundSyncAllowed(): Boolean = SyncNotification.isAllowed(context)

    /**
     * Adds one explicit sync only when notification access is currently available. The database
     * primary key, rather than transient WorkManager state, makes duplicate taps safe.
     */
    suspend fun enqueueUserSync(extensionId: String): Boolean = enqueue(extensionId, SyncRequestTrigger.USER)

    /**
     * Adds missing explicit syncs as independent durable requests. The global worker starts
     * different extensions concurrently; its primary-key queue still prevents a bank overlap.
     */
    suspend fun enqueueUserSyncs(extensionIds: List<String>): List<String> {
        if (!isBackgroundSyncAllowed()) return emptyList()
        return try {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val addedIds = extensionIds.distinct().filter { extensionId ->
                    val inserted = pendingSyncRequestDao.insertIgnore(
                        PendingSyncRequest(
                            extensionId = extensionId,
                            trigger = SyncRequestTrigger.USER,
                            requestedAt = now,
                        ),
                    ) != -1L
                    if (!inserted) pendingSyncRequestDao.promoteQueuedToUser(extensionId, now)
                    inserted
                }
                // Waking an existing global worker is harmless and closes the drain/finish race.
                if (extensionIds.isNotEmpty()) BankSyncWorker.wake(context)
                addedIds
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Temporary source compatibility for callers upgraded independently of the UI. */
    suspend fun enqueueUserSyncsSequentially(extensionIds: List<String>): List<String> =
        enqueueUserSyncs(extensionIds)

    internal suspend fun enqueueScheduledSync(extensionId: String): Boolean =
        enqueue(extensionId, SyncRequestTrigger.SCHEDULED)

    private suspend fun enqueue(extensionId: String, trigger: SyncRequestTrigger): Boolean {
        if (!isBackgroundSyncAllowed()) return false
        return try {
            withContext(Dispatchers.IO) {
                val inserted = pendingSyncRequestDao.insertIgnore(
                    PendingSyncRequest(
                        extensionId = extensionId,
                        trigger = trigger,
                        requestedAt = System.currentTimeMillis(),
                    ),
                ) != -1L
                if (!inserted && trigger == SyncRequestTrigger.USER) {
                    pendingSyncRequestDao.promoteQueuedToUser(extensionId, System.currentTimeMillis())
                }
                // A successor WorkRequest closes the case where the current drainer checked for
                // emptiness just before this insert.
                BankSyncWorker.wake(context)
                inserted
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }
}
