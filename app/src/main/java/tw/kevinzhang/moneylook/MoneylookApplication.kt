package tw.kevinzhang.moneylook

import android.app.Application
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import tw.kevinzhang.moneylook.schedule.SchedulerManager
import javax.inject.Inject

@HiltAndroidApp
class MoneylookApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
