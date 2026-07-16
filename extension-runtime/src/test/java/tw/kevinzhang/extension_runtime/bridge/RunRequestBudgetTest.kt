package tw.kevinzhang.extension_runtime.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RunRequestBudgetTest {
    @Test
    fun rejectsTheSharedOneHundredAndFirstOperation() {
        val budget = RunRequestBudget()

        repeat(100) { budget.acquire() }

        val error = assertThrows(SafeHttpException::class.java) { budget.acquire() }
        assertEquals("REQUEST_LIMIT", error.code)
        assertEquals(101, budget.used())
    }
}
