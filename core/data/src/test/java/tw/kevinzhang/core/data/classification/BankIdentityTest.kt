package tw.kevinzhang.core.data.classification

import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.core.data.model.Transfer

class BankIdentityTest {
    private val owned = listOf(OwnedBankAccount("own", "013", "001234567890"))
    private fun transfer(bank: String? = "013", number: String? = "001234567890") =
        Transfer("t", "source", "ext", "2026-10-18T12:00:00", "轉帳", -100.0, null, "",
            counterpartyBank = bank, counterpartyAccount = number)

    @Test fun `full and masked accounts match without a paired transaction`() {
        assertTrue(matchesOwnedAccount(transfer(), owned))
        assertTrue(matchesOwnedAccount(transfer(number = "***67890"), owned))
        assertTrue(matchesOwnedAccount(transfer(number = "67890"), owned))
        assertTrue(matchesOwnedAccount(transfer(number = "001-234-567890"), owned))
    }
    @Test fun `contradictory complete account never falls back to matching suffix`() {
        assertFalse(matchesOwnedAccount(transfer(number = "991234567890"), owned))
    }
    @Test fun `bank evidence and distinct owned account are required`() {
        assertFalse(matchesOwnedAccount(transfer(bank = "004"), owned))
        assertFalse(matchesOwnedAccount(transfer(bank = null), owned))
        assertFalse(matchesOwnedAccount(transfer(number = "7890"), owned))
        assertFalse(matchesOwnedAccount(transfer().copy(accountId = "own"), owned))
        assertFalse(matchesOwnedAccount(transfer().copy(description = "購物"), owned))
    }
    @Test fun `unknown bank labels are not fuzzy matched`() {
        assertNull(normalizeBankIdentity("可能是國泰"))
        assertEquals("013", normalizeBankIdentity("013"))
    }
    @Test fun `matching multiple own suffixes does not invent a unique counterpart`() {
        assertTrue(matchesOwnedAccount(transfer(number = "***67890"),
            owned + OwnedBankAccount("own2", "013", "991234567890")))
    }
}
