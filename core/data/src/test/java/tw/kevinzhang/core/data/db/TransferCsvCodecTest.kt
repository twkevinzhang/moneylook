package tw.kevinzhang.core.data.db

import java.io.StringReader
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation

class TransferCsvCodecTest {
    @Test
    fun `writer and incremental decoder round trip every portable transfer field`() {
        val source = record()
        val writer = StringWriter()

        TransferCsvCodec.write(writer, sequenceOf(source))

        val decoded = mutableListOf<TransferCsvRecord>()
        val result = TransferCsvCodec.decode(StringReader(writer.toString())) { decoded += it }
        assertEquals(TransferCsvDecodeResult.Success(1), result)
        assertEquals(
            source.copy(
                transfer = source.transfer.copy(
                    sourceRecordJson = null,
                    sourceFieldsJson = null,
                    sourceFactsJson = null,
                ),
            ),
            decoded.single(),
        )
        assertFalse(writer.toString().contains("source-record-secret"))
        assertFalse(writer.toString().contains("source-fields-secret"))
        assertFalse(writer.toString().contains("source-facts-secret"))
        assertTrue(writer.toString().contains("\"Coffee, \"\"shop\"\"\nnext\""))
        assertTrue(writer.toString().contains("'=formula"))
    }

    @Test
    fun `tag rows are assembled without a Cartesian product`() {
        val first = record(tags = listOf(tag("coffee", AssignmentSource.AUTO), tag("receipt", AssignmentSource.MANUAL)))
        val second = record(id = "t-2", tags = listOf(tag("coffee", AssignmentSource.AUTO)))
        val writer = StringWriter()
        TransferCsvCodec.write(writer, sequenceOf(first, second))

        val decoded = mutableListOf<TransferCsvRecord>()
        val result = TransferCsvCodec.decode(StringReader(writer.toString())) { decoded += it }

        assertEquals(TransferCsvDecodeResult.Success(2), result)
        assertEquals(listOf("t-1", "t-2"), decoded.map { it.transfer.id })
        assertEquals(listOf("coffee", "receipt"), decoded.first().tags.map { it.tag.id })
        assertEquals(AssignmentSource.MANUAL, decoded.first().tags.single { it.tag.id == "receipt" }.source)
    }

    @Test
    fun `decoder rejects orphan duplicate unknown and malformed records`() {
        val valid = csv(record())
        val lines = valid.trimEnd().split("\r\n")
        val header = lines[1]
        val transfer = lines[2]
        val tag = lines[3]
        val orphanTag = listOf(lines[0], header, tag).joinToString("\r\n")
        val duplicateTransfer = listOf(lines[0], header, transfer, transfer).joinToString("\r\n")
        val duplicateTag = listOf(lines[0], header, transfer, tag, tag).joinToString("\r\n")
        val unknownColumn = valid.replace("tagAssignmentSource", "unexpected")
        val malformedQuote = valid.replace("Coffee", "Coff\"ee")

        listOf(orphanTag, duplicateTransfer, duplicateTag, unknownColumn, malformedQuote).forEach { input ->
            assertTrue(TransferCsvCodec.decode(StringReader(input)) {} is TransferCsvDecodeResult.Failure)
        }
    }

    @Test
    fun `decoder fails closed for unsupported versions nonfinite values and limits`() {
        val valid = csv(record())
        val unsupportedVersion = valid.replace("moneylook-transfers,1", "moneylook-transfers,2")
        val nan = valid.replace(",-123.45,", ",NaN,")
        val tooLargeCell = valid.replaceFirst("Coffee", "x".repeat(65_537))
        val manyRows = buildString {
            append("moneylook-transfers,1\r\n")
            append(valid.substringAfter("\r\n").substringBefore("\r\n", ""))
            append("\r\n")
            repeat(100_001) { append("\r\n") }
        }

        listOf(unsupportedVersion, nan, tooLargeCell, manyRows).forEach { input ->
            assertTrue(TransferCsvCodec.decode(StringReader(input)) {} is TransferCsvDecodeResult.Failure)
        }
    }

    @Test
    fun `legacy TRANSFER category imports as EXCLUDED while new exports write EXCLUDED`() {
        val legacy = csv(record()).replaceFirst(",EXPENSE,", ",TRANSFER,")
        val decoded = mutableListOf<TransferCsvRecord>()

        val result = TransferCsvCodec.decode(StringReader(legacy)) { decoded += it }

        assertEquals(TransferCsvDecodeResult.Success(1), result)
        assertEquals(CategoryReportingGroup.EXCLUDED, decoded.single().category?.reportingGroup)
        val current = csv(record(categoryReportingGroup = CategoryReportingGroup.EXCLUDED))
        assertTrue(current.contains(",EXCLUDED,"))
    }

    private fun csv(record: TransferCsvRecord): String = StringWriter().also { writer ->
        TransferCsvCodec.write(writer, sequenceOf(record))
    }.toString()

    private fun tag(id: String, source: AssignmentSource) = TransferCsvTagAssignment(Tag(id, id.replaceFirstChar(Char::uppercase), "#112233"), source)

    private fun record(
        id: String = "t-1",
        tags: List<TransferCsvTagAssignment> = listOf(tag("coffee", AssignmentSource.AUTO)),
        categoryReportingGroup: CategoryReportingGroup = CategoryReportingGroup.EXPENSE,
    ): TransferCsvRecord {
        val account = TransferCsvAccountMetadata("account-1", "ext-1", "source-key", AssetKind.CREDIT_CARD, "TWD", "主卡", "Example Bank")
        val transfer = Transfer(
            id = id, accountId = "account-1", extensionId = "ext-1", txnDateTime = "2026-07-27T12:34:56", description = "Coffee, \"shop\"\nnext",
            amount = -123.45, balance = 9876.54, memo = "=formula\r\n備註", type = "purchase", status = "POSTED", postingDateTime = "2026-07-28",
            cardInstrumentId = "card-1", merchantName = "Coffee", merchantCategoryCode = "5814", counterpartyName = "Jane", purpose = "lunch",
            authorizationDateTime = "2026-07-27T12:00:00", valueDateTime = "2026-07-28", referenceNumber = "ref", authorizationCode = "auth",
            channel = "APP", direction = "DEBIT", transactionCode = "P", originalAmount = 3.4, originalCurrency = "USD", settlementAmount = 123.45,
            settlementCurrency = "TWD", exchangeRate = 30.2, feeAmount = 1.5, feeCurrency = "TWD", taxAmount = 0.1, taxCurrency = "TWD",
            merchantLocation = "Taipei", counterpartyAccount = "masked", counterpartyBank = "Bank", installmentNumber = 1, installmentTotal = 3,
            isRefund = false, isReversal = true, originalTransactionSourceId = "source-id", sourceRecordJson = "source-record-secret",
            sourceFieldsJson = "source-fields-secret", sourceFactsJson = "source-facts-secret", parserVersion = "v4",
        )
        val annotation = TransferAnnotation(id, "ext-1", "food", "personal\nnote", AssignmentSource.MANUAL, true, null, null, null, "classifier")
        return TransferCsvRecord(account, transfer, annotation, Category("food", "餐飲", "#FF0000", "🍽️", categoryReportingGroup), tags)
    }
}
