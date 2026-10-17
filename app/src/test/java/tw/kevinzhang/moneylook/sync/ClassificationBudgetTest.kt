package tw.kevinzhang.moneylook.sync

import org.junit.Assert.*
import org.junit.Test

class ClassificationBudgetTest {
    @Test fun tenMinuteDeadlinePreventsAnotherRequest() {
        var nanos=0L
        val budget=ClassificationBudget { nanos }
        assertTrue(budget.canRequest())
        nanos=599_000_000_000L
        assertTrue(budget.canRequest())
        assertEquals(1000L,budget.remainingMillis())
        nanos=600_000_000_000L
        assertFalse(budget.canRequest())
        assertNotNull(budget.stopReason)
    }
}
