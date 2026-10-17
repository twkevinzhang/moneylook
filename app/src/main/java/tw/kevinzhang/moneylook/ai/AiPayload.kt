package tw.kevinzhang.moneylook.ai

import tw.kevinzhang.core.data.db.TransferClassificationCandidate
import tw.kevinzhang.core.data.model.Category

data class AiClassificationInput(val summary: String, val amount: Double, val currency: String, val categories: List<Category>)

fun redactAiText(value: String): String = value.replace(Regex("[0-9０-９][0-9０-９\\s-]{4,}[0-9０-９]"), "[帳號已遮罩]").take(1500)

fun aiInput(candidate: TransferClassificationCandidate, categories: List<Category>): AiClassificationInput {
    val t = candidate.transfer
    val fields = listOfNotNull(t.description, t.memo, t.type, t.merchantName, t.merchantCategoryCode,
        t.counterpartyName, t.purpose, t.channel, t.transactionCode)
    var text = fields.joinToString(" | ")
    t.counterpartyAccount?.takeIf { it.isNotBlank() }?.let { text = text.replace(it, "[帳號已遮罩]") }
    return AiClassificationInput(redactAiText(text), t.amount, candidate.currency, categories.filter { it.id != "transfer-account" })
}
