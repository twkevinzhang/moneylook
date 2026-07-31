package tw.kevinzhang.moneylook.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class FabAwareListPaddingTest {
    @Test
    fun contentPaddingKeepsFabClearanceAndRequestedTopSpacing() {
        val padding = fabAwareListContentPadding(horizontal = 16.dp, top = 16.dp)

        assertEquals(16.dp, padding.calculateTopPadding())
        assertEquals(96.dp, padding.calculateBottomPadding())
    }
}
