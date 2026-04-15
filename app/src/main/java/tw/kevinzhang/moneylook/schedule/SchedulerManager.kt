package tw.kevinzhang.moneylook.schedule

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import tw.kevinzhang.core.data.model.InstalledExtension
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SchedulerManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Enqueues the first schedule job for an extension.
     * Call this after installing an extension that has a scheduleCron.
     */
    fun scheduleExtension(extension: InstalledExtension) {
        val cron = extension.scheduleCron ?: return
        ScheduleWorker.enqueueNext(context, extension.id, cron)
    }

    /**
     * Cancels all pending schedule jobs for an extension.
     * Call this when uninstalling an extension.
     */
    fun cancelExtension(extensionId: String) {
        WorkManager.getInstance(context)
            .cancelAllWorkByTag(ScheduleWorker.tag(extensionId))
    }

    /**
     * Re-syncs schedules for all installed extensions on app start.
     * Cancels existing work first to avoid duplicate jobs.
     */
    fun rescheduleAll(extensions: List<InstalledExtension>) {
        extensions.forEach { ext ->
            cancelExtension(ext.id)
            scheduleExtension(ext)
        }
    }
}
