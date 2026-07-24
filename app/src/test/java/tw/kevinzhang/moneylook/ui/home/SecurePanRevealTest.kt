package tw.kevinzhang.moneylook.ui.home

import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class SecurePanRevealTest {
    @Test
    fun `revealed fictional PAN is protected and clears after thirty seconds`() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val view = PanTextView(activity, activity)
        val fictionalPan = "4242424242424242"

        view.showPan(fictionalPan)

        assertEquals(fictionalPan, view.text.toString())
        assertEquals(View.VISIBLE, view.visibility)
        assertFalse(view.isTextSelectable)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, view.importantForAccessibility)
        assertTrue(
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
        )

        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)

        assertEquals("", view.text.toString())
        assertEquals(View.GONE, view.visibility)
        assertFalse(
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
        )
    }
}
