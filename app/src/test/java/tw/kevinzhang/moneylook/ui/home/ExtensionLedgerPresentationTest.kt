package tw.kevinzhang.moneylook.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.Transfer

class ExtensionLedgerPresentationTest {
    @Test
    fun `formats ledger grouping date account masking and signed values`() {
        val transfer = Transfer(
            id = "transfer",
            accountId = "account",
            extensionId = "extension",
            txnDateTime = "2026-07-21T11:25:30",
            description = "測試",
            amount = -123.0,
            balance = null,
            memo = "",
        )

        assertEquals("2026-07", ledgerMonthKey(transfer))
        assertEquals("2026 年 7 月", ledgerMonthLabel("2026-07"))
        assertEquals("7月" to "21", ledgerDate(transfer.txnDateTime))
        assertEquals("•••• 5678", maskLedgerAccountNo("0012345678"))
        assertEquals("••••", maskLedgerAccountNo("1234"))
        assertTrue(signedTransferAmount(-123.0, "TWD").startsWith("-"))
        assertEquals("應繳金額", ledgerPrimaryAmountLabel(AssetKind.CREDIT_CARD))
    }

    @Test
    fun `groups legacy non ISO transfer dates without hiding them`() {
        val transfer = Transfer("transfer", "account", "extension", "20260721112530", "測試", 1.0, null, "")

        assertEquals("other", ledgerMonthKey(transfer))
        assertEquals("其他交易", ledgerMonthLabel(ledgerMonthKey(transfer)))
        assertEquals("日期" to "20260721", ledgerDate(transfer.txnDateTime))
    }
}
