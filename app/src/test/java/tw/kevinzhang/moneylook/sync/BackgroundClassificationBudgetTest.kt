package tw.kevinzhang.moneylook.sync

import org.junit.Assert.*
import org.junit.Test

class BackgroundClassificationBudgetTest {
    @Test fun requestLimitCannotBeResetByStartingAnotherBatch() {
        val budget = BackgroundClassificationBudget { 0L }
        repeat(100) { assertTrue(budget.reserveRequest()) }
        assertFalse(budget.reserveRequest())
        assertEquals(100, budget.requests)
    }
    @Test fun totalTransactionsAndDeadlineAreSharedByRestarts() {
        var clock = 0L
        val budget = BackgroundClassificationBudget { clock }
        repeat(100) { budget.consider(20) }
        assertEquals(0, budget.remainingTransactions())
        clock = 2_399_999L * 1_000_000L
        assertEquals(1L, budget.remainingMillis())
        assertTrue(budget.reserveRequest())
        clock = 2_400_000L * 1_000_000L
        assertFalse(budget.reserveRequest())
        assertEquals(0L, budget.remainingMillis())
    }
}
