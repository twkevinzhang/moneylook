package tw.kevinzhang.moneylook

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityOrientationTest {
    @Test
    fun mainActivityIsLockedToPortrait() {
        val context = ApplicationProvider.getApplicationContext<MoneylookApplication>()
        val component = ComponentName(context, MainActivity::class.java)
        val packageManager = context.packageManager
        val activityInfo = packageManager.getActivityInfo(component, 0)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activityInfo.screenOrientation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(
                packageManager.getProperty(
                    "android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY",
                    component,
                ).boolean,
            )
        }
    }
}
