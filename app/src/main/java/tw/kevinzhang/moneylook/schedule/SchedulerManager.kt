package tw.kevinzhang.moneylook.schedule

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import tw.kevinzhang.core.data.model.CredentialProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SchedulerManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun scheduleProfile(profile: CredentialProfile) {
        cancelExtension(profile.extensionId)
        ScheduleWorker.enqueueNext(context, profile)
    }

    fun cancelExtension(extensionId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(ScheduleWorker.uniqueName(extensionId))
        WorkManager.getInstance(context).cancelAllWorkByTag(ScheduleWorker.tag(extensionId))
    }

    fun rescheduleAll(profiles: List<CredentialProfile>) {
        profiles.forEach(::scheduleProfile)
    }
}
