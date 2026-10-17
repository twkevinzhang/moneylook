package tw.kevinzhang.core.data.classification

import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.core.data.model.Transfer

class LocalHistoryClassifierTest {
    private fun transfer(id: String = "t", merchant: String? = "晨光咖啡", amount: Double = -90.0) =
        Transfer(id, "a", "e", "2026-10-18", merchant ?: "轉帳", amount, null, "", merchantName = merchant)
    private fun example(id: String, category: String, sequence: Long, t: Transfer = transfer(id)) =
        LearningSample(id, category, featuresOf(t), sequence)

    @Test fun `generic channel descriptions do not identify a merchant`() {
        for (description in listOf("網銀轉帳", "行銀跨行轉", "ATM跨行轉", "轉帳支出", "網路轉帳 12345")) {
            val t = transfer(merchant = null).copy(description = description)
            assertEquals(description, "", featuresOf(t).identity)
            assertNull(chooseLearned(featuresOf(t), listOf(example("1", "food", 1, t))))
        }
        assertEquals("晨光咖啡", featuresOf(transfer().copy(description = "網銀轉帳")).identity)
    }
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
    @Test fun `history similarity threshold includes three of five bigrams but excludes three of six`() {
        val f = featuresOf(transfer(merchant = "abcd"))
        assertEquals("food", chooseHistory(f, listOf(example("1", "food", 0, transfer(merchant = "abcdef"))))?.categoryId)
        assertNull(chooseHistory(f, listOf(example("1", "food", 0, transfer(merchant = "abcdefg")))))
    }
    @Test fun `only the fifty highest ranked samples vote`() {
        val recent = (101L..150L).map { example("new-$it", "food", it) }
        val old = (1L..100L).map { example("old-$it", "work", it) }
        assertEquals("food", chooseHistory(featuresOf(transfer()), old + recent)?.categoryId)
    }
}
