package tw.kevinzhang.moneylook

import android.app.Application
import android.webkit.CookieManager
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import tw.kevinzhang.moneylook.schedule.SchedulerManager
import javax.inject.Inject

@HiltAndroidApp
class MoneylookApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        // One-way cleanup for installations upgrading from the persisted-session design.
        deleteSharedPreferences("session_store")
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
