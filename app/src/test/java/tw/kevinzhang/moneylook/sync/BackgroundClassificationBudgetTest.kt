package tw.kevinzhang.moneylook.sync

import org.junit.Assert.*
import org.junit.Test

class BackgroundClassificationBudgetTest {
    @Test fun requestLimitCannotBeResetByStartingAnotherBatch() {
        val budget = BackgroundClassificationBudget { 0L }
        repeat(25) { assertTrue(budget.reserveRequest()) }
        assertFalse(budget.reserveRequest())
        assertEquals(25, budget.requests)
    }
    @Test fun totalTransactionsAndDeadlineAreSharedByRestarts() {
        var clock = 0L
        val budget = BackgroundClassificationBudget { clock }
        repeat(25) { budget.consider(20) }
        assertEquals(0, budget.remainingTransactions())
        clock = 600_000L * 1_000_000L
        assertFalse(budget.reserveRequest())
        assertEquals(0L, budget.remainingMillis())
    }
}
