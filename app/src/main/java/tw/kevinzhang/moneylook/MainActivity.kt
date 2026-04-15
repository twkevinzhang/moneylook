package tw.kevinzhang.moneylook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.moneylook.schedule.SchedulerManager
import tw.kevinzhang.moneylook.ui.navigation.AppNavHost
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var schedulerManager: SchedulerManager
    @Inject lateinit var installedExtensionDao: InstalledExtensionDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        CoroutineScope(Dispatchers.IO).launch {
            val extensions = installedExtensionDao.getAll()
            schedulerManager.rescheduleAll(extensions)
        }

        setContent {
            MoneylookTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val navController = rememberNavController()
                    AppNavHost(navController = navController)
                }
            }
        }
    }
}
