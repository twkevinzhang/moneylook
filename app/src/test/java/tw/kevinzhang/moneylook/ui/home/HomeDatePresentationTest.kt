package tw.kevinzhang.moneylook.ui.home

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDatePresentationTest {
    @Test
    fun `overview title uses compact traditional Chinese device date format`() {
        assertEquals("7月22日(三)", homeOverviewTitle(LocalDate.of(2026, 7, 22)))
        assertEquals("1月1日(四)", homeOverviewTitle(LocalDate.of(2026, 1, 1)))
        assertEquals("12月31日(四)", homeOverviewTitle(LocalDate.of(2026, 12, 31)))
    }
}
