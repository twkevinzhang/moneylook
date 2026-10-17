package tw.kevinzhang.core.data.classification

import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.core.data.model.Transfer

class ClassificationInputTest {
    private val t = Transfer("t", "a", "e", "2026-10-18", "轉帳", -100.0, 500.0, "")
    @Test fun `balance parser and raw record changes do not trigger reclassification`() {
        assertEquals(classificationInput(t), classificationInput(t.copy(balance = 600.0, parserVersion = "2", sourceRecordJson = "{}")))
    }
    @Test fun `merchant counterparty and rule visible changes trigger reclassification`() {
        listOf(t.copy(merchantName = "店家"), t.copy(counterpartyAccount = "12345"),
            t.copy(counterpartyBank = "013"), t.copy(memo = "memo"), t.copy(status = "posted"),
            t.copy(referenceNumber = "ref"), t.copy(amount = -101.0)).forEach {
            assertNotEquals(classificationInput(t), classificationInput(it))
        }
    }
}
