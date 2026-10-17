package tw.kevinzhang.core.data.classification

import java.text.Normalizer
import tw.kevinzhang.core.data.model.Transfer

data class OwnedBankAccount(val accountId: String, val bankId: String, val accountNumber: String) {
    override fun toString() = "OwnedBankAccount([REDACTED])"
}

// Explicit aliases, verified against https://www.fisc.com.tw/TC/Download/atm.pdf.
private val bankAliases = mapOf(
    "004" to listOf("臺灣銀行", "台灣銀行", "臺銀", "台銀", "tw.com.bot"),
    "006" to listOf("合作金庫", "合作金庫商業銀行", "合庫", "tw.com.tcb-bank"),
    "009" to listOf("彰化銀行", "彰化商業銀行", "彰銀", "tw.com.chb"),
    "013" to listOf("國泰世華", "國泰世華銀行", "國泰世華商業銀行", "tw.com.cathaybk"),
    "103" to listOf("臺灣新光商銀", "新光銀行", "臺灣新光商業銀行", "tw.com.skbank"),
    "700" to listOf("中華郵政", "郵局", "tw.gov.post.ipost"),
    "803" to listOf("聯邦銀行", "聯邦商業銀行", "tw.com.ubot"),
    "805" to listOf("遠東銀行", "遠東國際商業銀行", "遠東商銀", "tw.com.feib"),
    "807" to listOf("永豐銀行", "永豐商業銀行", "tw.com.sinopac"),
    "808" to listOf("玉山銀行", "玉山商業銀行", "tw.com.esunbank"),
    "809" to listOf("凱基銀行", "凱基商業銀行", "tw.com.kgibank"),
    "812" to listOf("台新銀行", "台新國際商業銀行", "台新商業銀行", "tw.com.taishinbank"),
    "822" to listOf("中國信託", "中國信託銀行", "中國信託商業銀行", "tw.com.ctbcbank"),
)

fun normalizeBankIdentity(value: String?): String? {
    val normalized = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC).trim()
    if (normalized.matches(Regex("[0-9]{3}"))) return normalized
    return bankAliases.entries.firstOrNull { normalized in it.value }?.key
}

private fun account(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .replace(Regex("[\\s-]"), "")

fun matchesOwnedAccount(transfer: Transfer, accounts: List<OwnedBankAccount>): Boolean {
    val semantics = listOfNotNull(transfer.type, transfer.description, transfer.purpose, transfer.transactionCode)
        .joinToString(" ").lowercase()
    if (!Regex("轉帳|轉入|轉出|跨行轉|匯入|匯出|匯款|transfer|remittance").containsMatchIn(semantics)) return false
    val bank = normalizeBankIdentity(transfer.counterpartyBank) ?: return false
    val incoming = account(transfer.counterpartyAccount ?: return false)
    if (!incoming.matches(Regex("[0-9*xX●•]+"))) return false
    val suffix = incoming.takeLast(5).takeIf { it.matches(Regex("[0-9]{5}")) } ?: return false
    return accounts.any { target ->
        if (target.accountId == transfer.accountId || normalizeBankIdentity(target.bankId) != bank) return@any false
        val own = account(target.accountNumber)
        if (!own.matches(Regex("[0-9*xX●•]+"))) return@any false
        val incomingComplete = incoming.length > 5 && incoming.all(Char::isDigit)
        val ownComplete = own.length > 5 && own.all(Char::isDigit)
        if (incomingComplete && ownComplete) incoming == own
        else own.length >= 5 && own.takeLast(5) == suffix
    }
}
