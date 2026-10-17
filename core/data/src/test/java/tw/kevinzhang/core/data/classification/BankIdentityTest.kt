package tw.kevinzhang.core.data.classification

import org.junit.Assert.*
import org.junit.Test
import tw.kevinzhang.core.data.model.Transfer

class BankIdentityTest {
    private val source = OwnedBankAccount("source", "700", "999999999999")
    private val owned = listOf(source, OwnedBankAccount("own", "013", "001234567890"))
    private fun transfer(bank: String? = "013", number: String? = "001234567890") =
        Transfer("t", "source", "ext", "2026-10-18T12:00:00", "轉帳", -100.0, null, "",
            counterpartyBank = bank, counterpartyAccount = number)

    private fun post(number: String = "0012345678901234") = transfer(bank = null, number = null).copy(
        description = "網路跨行",
        sourceFactsJson = """{"branchNumber":{"value":"$number","rawKey":"COS_BRH_NO"}}""",
    )
    private val taishin = listOf(source, OwnedBankAccount("taishin", "812", "12345678901234"))

    @Test fun `post padded full account requires explicit matching bank`() {
        assertFalse(matchesOwnedAccount(post(), taishin, "tw.gov.post.ipost"))
        assertFalse(matchesOwnedAccount(post().copy(counterpartyAccount = "0012345678901234", sourceFactsJson = null), taishin, "tw.gov.post.ipost"))
        assertTrue(matchesOwnedAccount(post().copy(counterpartyBank = "812"), taishin, "tw.gov.post.ipost"))
        assertFalse(matchesOwnedAccount(post(), taishin + taishin.last().copy(accountId = "other-currency"), "tw.gov.post.ipost"))
    }
    @Test fun `post fallback rejects ambiguity unknown bank and contradictory bank`() {
        assertFalse(matchesOwnedAccount(post(), taishin + OwnedBankAccount("other", "013", "12345678901234"), "tw.gov.post.ipost"))
        assertFalse(matchesOwnedAccount(post(), taishin + OwnedBankAccount("other", "013", "0012345678901234"), "tw.gov.post.ipost"))
        assertFalse(matchesOwnedAccount(post().copy(counterpartyBank = "013"), taishin, "tw.gov.post.ipost"))
        assertFalse(matchesOwnedAccount(post().copy(counterpartyBank = "unknown"), taishin, "tw.gov.post.ipost"))
        assertFalse(matchesOwnedAccount(post(), emptyList(), "tw.gov.post.ipost"))
        assertFalse(matchesOwnedAccount(post().copy(accountId = "taishin"), taishin, "tw.gov.post.ipost"))
    }
    @Test fun `post fallback never infers ownership from a suffix mask or untrusted origin`() {
        for (number in listOf("01234", "***********01234", "9912345678901234")) {
            assertFalse(matchesOwnedAccount(post(number), taishin, "tw.gov.post.ipost"))
        }
        assertFalse(matchesOwnedAccount(post(), taishin, "tw.com.other"))
        assertFalse(matchesOwnedAccount(post().copy(description = "提款"), taishin, "tw.gov.post.ipost"))
        assertFalse(matchesOwnedAccount(post().copy(sourceFactsJson = "invalid"), taishin, "tw.gov.post.ipost"))
    }
    @Test fun `known mobile and ATM transfer descriptions preserve ownership proof`() {
        for (description in listOf("行銀跨行轉", "ATM跨行轉", "網路跨行")) {
            assertTrue(description, matchesOwnedAccount(transfer().copy(description = description), owned))
        }
    }
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
    @Test fun `source must be logged in and multiple currency rows do not count as multiple accounts`() {
        assertFalse(matchesOwnedAccount(transfer(), owned.filter { it.accountId != "source" }))
        assertFalse(matchesOwnedAccount(transfer(bank="700",number="999999999999"), listOf(source,source.copy(accountId="currency-row"))))
    }
    @Test fun `matching multiple own suffixes does not invent a unique counterpart`() {
        assertTrue(matchesOwnedAccount(transfer(number = "***67890"),
            owned + OwnedBankAccount("own2", "013", "991234567890")))
    }
}
