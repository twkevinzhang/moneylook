package tw.kevinzhang.core.data.classification

import java.text.Normalizer
import java.util.Locale
import tw.kevinzhang.core.data.model.Transfer

data class LocalClassificationFeatures(val accountId: String, val direction: Int, val identity: String, val transactionType: String) {
    override fun toString() = "LocalClassificationFeatures([REDACTED])"
}

private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
private val generic = setOf("轉帳", "消費", "存款", "提款", "匯款", "轉入", "轉出", "transfer", "payment", "交易", "支出", "收入")
fun featuresOf(transfer: Transfer): LocalClassificationFeatures {
    val identity = sequenceOf(transfer.merchantName, transfer.counterpartyName, transfer.description)
        .filterNotNull().map(::normalize)
        .firstOrNull { it.length >= 2 && it !in generic && !it.all { ch -> ch.isDigit() || ch.isWhitespace() } }.orEmpty()
    return LocalClassificationFeatures(transfer.accountId, transfer.amount.compareTo(0.0), identity, normalize(transfer.type.orEmpty()))
}

fun bigrams(value: String): Set<String> = value.windowed(2).toSet()

/** Only classifier-visible bank facts; balances, parser versions and raw documents are excluded. */
fun classificationInput(t: Transfer): List<Any?> = listOf(
    t.accountId, t.extensionId, t.amount, t.description, t.memo, t.type, t.status,
    t.merchantName, t.merchantCategoryCode, t.counterpartyName, t.purpose, t.channel,
    t.referenceNumber, t.merchantLocation, t.transactionCode, t.counterpartyAccount, t.counterpartyBank,
)
