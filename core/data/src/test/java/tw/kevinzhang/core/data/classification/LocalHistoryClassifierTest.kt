package tw.kevinzhang.core.data.classification

import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.core.data.model.Transfer

class LocalHistoryClassifierTest {
    private fun transfer(id: String = "t", merchant: String? = "晨光咖啡", amount: Double = -90.0) =
        Transfer(id, "a", "e", "2026-10-18", merchant ?: "轉帳", amount, null, "", merchantName = merchant)
    private fun example(id: String, category: String, sequence: Long, t: Transfer = transfer(id)) =
        LearningSample(id, category, featuresOf(t), sequence)

    @Test fun `latest exact correction wins without crossing accounts or direction`() {
        val samples = listOf(example("1", "food", 1), example("2", "work", 2))
        assertEquals("work", chooseLearned(featuresOf(transfer()), samples)?.categoryId)
        assertNull(chooseLearned(featuresOf(transfer().copy(accountId = "b")), samples))
        assertNull(chooseLearned(featuresOf(transfer(amount = 90.0)), samples))
    }
    @Test fun `generic descriptions cannot teach a transfer category`() {
        assertNull(chooseLearned(featuresOf(transfer(merchant = null)),
            listOf(example("1", "food", 1, transfer(merchant = null)))))
        assertNull(chooseHistory(featuresOf(transfer()), listOf(example("1", "transfer-account", 1))))
    }
    @Test fun `legacy manual examples support history but are not recent corrections`() {
        val legacy = listOf(example("1", "food", 0))
        assertNull(chooseLearned(featuresOf(transfer()), legacy))
        assertEquals("food", chooseHistory(featuresOf(transfer()), legacy)?.categoryId)
    }
    @Test fun `history requires meaningful similarity and matching direction`() {
        assertNull(chooseHistory(featuresOf(transfer(merchant = "捷運")), listOf(example("1", "food", 1))))
        assertNull(chooseHistory(featuresOf(transfer(amount = 90.0)), listOf(example("1", "food", 1))))
        assertEquals("food", chooseHistory(featuresOf(transfer().copy(accountId = "b")),
            listOf(example("1", "food", 1)))?.categoryId)
    }
}
