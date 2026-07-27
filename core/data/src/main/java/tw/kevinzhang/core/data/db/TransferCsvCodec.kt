package tw.kevinzhang.core.data.db

import java.io.Reader
import java.io.Writer
import tw.kevinzhang.core.data.model.AssetKind
import tw.kevinzhang.core.data.model.AssignmentSource
import tw.kevinzhang.core.data.model.Category
import tw.kevinzhang.core.data.model.CategoryReportingGroup
import tw.kevinzhang.core.data.model.Tag
import tw.kevinzhang.core.data.model.Transfer
import tw.kevinzhang.core.data.model.TransferAnnotation
import tw.kevinzhang.core.data.model.TransferTagCrossRef

/** The non-secret account facts used to decide whether an exported transaction can be imported. */
data class TransferCsvAccountMetadata(
    val exportedAccountId: String,
    val extensionId: String,
    val sourceAccountKey: String?,
    val kind: AssetKind,
    val currency: String,
    val accountName: String,
    val extensionName: String,
)

/** A tag definition together with the provenance of its assignment to one transfer. */
data class TransferCsvTagAssignment(
    val tag: Tag,
    val source: AssignmentSource,
)

/**
 * A database-independent transaction backup record. `transfer.accountId` is intentionally the
 * exported account id; import code must replace it with the matching local account id before
 * persisting it.
 */
data class TransferCsvRecord(
    val account: TransferCsvAccountMetadata,
    val transfer: Transfer,
    val annotation: TransferAnnotation?,
    val category: Category?,
    val tags: List<TransferCsvTagAssignment>,
)

sealed interface TransferCsvDecodeResult {
    data class Success(val recordCount: Int) : TransferCsvDecodeResult
    data class Failure(val reason: String) : TransferCsvDecodeResult
}

/**
 * Versioned, strict, streaming CSV codec for transaction backups.
 *
 * A transfer is followed by zero or more TAG rows. This avoids multiplying a transfer row by its
 * tags while allowing [decode] to hand one fully assembled transaction to its callback at a time.
 * The callback may be invoked before a later malformed row is discovered, therefore callers must
 * stage received records and only persist them after [TransferCsvDecodeResult.Success].
 */
object TransferCsvCodec {
    private const val MARKER = "moneylook-transfers"
    private const val VERSION = "1"
    private const val TRANSFER = "TRANSFER"
    private const val TAG = "TAG"
    private const val NULL = "n:"
    private const val STRING = "s:"
    private const val MAX_CHARS = 25_000_000
    private const val MAX_ROWS = 100_000
    private const val MAX_CELL_CHARS = 65_536
    private const val MAX_TRANSFERS = 50_000
    private const val MAX_TAGS_PER_TRANSFER = 100
    private const val MAX_ID_CHARS = 1_024

    private val header = listOf(
        "recordType", "exportedAccountId", "extensionId", "sourceAccountKey", "kind", "currency",
        "accountName", "extensionName", "id", "accountId", "txnDateTime", "description", "amount",
        "balance", "memo", "type", "status", "postingDateTime", "cardInstrumentId", "merchantName",
        "merchantCategoryCode", "counterpartyName", "purpose", "authorizationDateTime", "valueDateTime",
        "referenceNumber", "authorizationCode", "channel", "direction", "transactionCode", "originalAmount",
        "originalCurrency", "settlementAmount", "settlementCurrency", "exchangeRate", "feeAmount", "feeCurrency",
        "taxAmount", "taxCurrency", "merchantLocation", "counterpartyAccount", "counterpartyBank",
        "installmentNumber", "installmentTotal", "isRefund", "isReversal", "originalTransactionSourceId",
        "parserVersion", "annotationExtensionId", "categoryId", "note", "categoryAssignment", "manualOverride",
        "autoRuleId", "autoRuleSetId", "autoMatchScore", "classifierVersion", "categoryName", "categoryColor",
        "categoryEmoji", "categoryKind", "tagId", "tagName", "tagColor", "tagAssignmentSource",
    )
    private val column = header.withIndex().associate { it.value to it.index }

    /** Writes directly to [writer], without constructing a complete CSV string in memory. */
    fun write(writer: Writer, records: Sequence<TransferCsvRecord>) {
        val encoder = Encoder(writer)
        records.forEach(encoder::write)
        encoder.finish()
    }

    /**
     * Stateful encoder used by database callers that fetch bounded pages from suspend DAOs.
     * [finish] must be called exactly once after the final page.
     */
    class Encoder internal constructor(private val writer: Writer) {
        private val ids = mutableSetOf<String>()
        private val categories = mutableMapOf<String, Category>()
        private val tags = mutableMapOf<String, Tag>()
        private val sink = CsvWriter(writer)
        private var finished = false

        init {
            sink.writeRow(listOf(MARKER, VERSION))
            sink.writeRow(header)
        }

        fun write(record: TransferCsvRecord) {
            require(!finished) { "encoder is already finished" }
            validateRecord(record)
            require(ids.add(record.transfer.id)) { "duplicate transfer id" }
            record.category?.let { category ->
                require(categories.putIfAbsent(category.id, category)?.let { it == category } != false) {
                    "inconsistent category definition"
                }
            }
            record.tags.forEach { assignment ->
                require(tags.putIfAbsent(assignment.tag.id, assignment.tag)?.let { it == assignment.tag } != false) {
                    "inconsistent tag definition"
                }
            }
            sink.writeRow(transferRow(record))
            record.tags.sortedBy { it.tag.id }.forEach { assignment -> sink.writeRow(tagRow(record.transfer.id, assignment)) }
        }

        fun finish() {
            require(!finished) { "encoder is already finished" }
            writer.flush()
            finished = true
        }
    }

    fun encoder(writer: Writer): Encoder = Encoder(writer)

    fun write(writer: Writer, records: Iterable<TransferCsvRecord>) = write(writer, records.asSequence())

    /**
     * Parses [reader] incrementally. See the type documentation for the required staging rule for
     * [onRecord]. No complete CSV String or complete record list is retained by this codec.
     */
    fun decode(reader: Reader, onRecord: (TransferCsvRecord) -> Unit): TransferCsvDecodeResult = try {
        val source = CsvReader(reader)
        require(source.nextRow() == listOf(MARKER, VERSION)) { "missing marker or unsupported version" }
        require(source.nextRow() == header) { "unexpected header" }
        val transferIds = mutableSetOf<String>()
        val categories = mutableMapOf<String, Category>()
        val tags = mutableMapOf<String, Tag>()
        var pending: Pending? = null
        var count = 0
        while (true) {
            val row = source.nextRow() ?: break
            require(row.size == header.size) { "unexpected column count" }
            when (row.value("recordType")) {
                TRANSFER -> {
                    pending?.let { completed -> onRecord(completed.record); count++ }
                    require(count < MAX_TRANSFERS) { "too many transfers" }
                    val record = recordFromTransferRow(row)
                    validateRecord(record)
                    require(transferIds.add(record.transfer.id)) { "duplicate transfer id" }
                    record.category?.let { category ->
                        require(categories.putIfAbsent(category.id, category)?.let { it == category } != false) {
                            "inconsistent category definition"
                        }
                    }
                    pending = Pending(record, mutableListOf())
                }
                TAG -> {
                    val current = pending ?: fail("orphan tag")
                    require(row.value("id") == current.record.transfer.id) { "tag references a different transfer" }
                    require(row.isBlankExcept("recordType", "id", "tagId", "tagName", "tagColor", "tagAssignmentSource")) {
                        "tag row has transfer data"
                    }
                    require(current.tags.size < MAX_TAGS_PER_TRANSFER) { "too many tags for transfer" }
                    val assignment = TransferCsvTagAssignment(
                        tag = Tag(required(row.value("tagId"), "tag id"), required(row.value("tagName"), "tag name"), required(row.value("tagColor"), "tag color")),
                        source = enum(row.value("tagAssignmentSource"), "tag assignment source"),
                    )
                    require(current.tags.none { it.tag.id == assignment.tag.id }) { "duplicate tag assignment" }
                    require(tags.putIfAbsent(assignment.tag.id, assignment.tag)?.let { it == assignment.tag } != false) {
                        "inconsistent tag definition"
                    }
                    current.tags += assignment
                }
                else -> fail("unknown record type")
            }
        }
        pending?.let { completed -> onRecord(completed.record); count++ }
        TransferCsvDecodeResult.Success(count)
    } catch (error: IllegalArgumentException) {
        TransferCsvDecodeResult.Failure(error.message ?: "invalid transaction CSV")
    }

    /**
     * Suspend-aware counterpart used by Room imports. A later fatal parse error is returned as
     * [TransferCsvDecodeResult.Failure], allowing the caller's enclosing transaction to roll back
     * records already delivered to [onRecord].
     */
    suspend fun decodeSuspending(
        reader: Reader,
        onRecord: suspend (TransferCsvRecord) -> Unit,
    ): TransferCsvDecodeResult = try {
        val source = CsvReader(reader)
        require(source.nextRow() == listOf(MARKER, VERSION)) { "missing marker or unsupported version" }
        require(source.nextRow() == header) { "unexpected header" }
        val transferIds = mutableSetOf<String>()
        val categories = mutableMapOf<String, Category>()
        val tags = mutableMapOf<String, Tag>()
        var pending: Pending? = null
        var count = 0
        while (true) {
            val row = source.nextRow() ?: break
            require(row.size == header.size) { "unexpected column count" }
            when (row.value("recordType")) {
                TRANSFER -> {
                    pending?.let { completed -> onRecord(completed.record); count++ }
                    require(count < MAX_TRANSFERS) { "too many transfers" }
                    val record = recordFromTransferRow(row)
                    validateRecord(record)
                    require(transferIds.add(record.transfer.id)) { "duplicate transfer id" }
                    record.category?.let { category ->
                        require(categories.putIfAbsent(category.id, category)?.let { it == category } != false) {
                            "inconsistent category definition"
                        }
                    }
                    pending = Pending(record, mutableListOf())
                }
                TAG -> {
                    val current = pending ?: fail("orphan tag")
                    require(row.value("id") == current.record.transfer.id) { "tag references a different transfer" }
                    require(row.isBlankExcept("recordType", "id", "tagId", "tagName", "tagColor", "tagAssignmentSource")) {
                        "tag row has transfer data"
                    }
                    require(current.tags.size < MAX_TAGS_PER_TRANSFER) { "too many tags for transfer" }
                    val assignment = TransferCsvTagAssignment(
                        tag = Tag(
                            required(row.value("tagId"), "tag id"),
                            required(row.value("tagName"), "tag name"),
                            required(row.value("tagColor"), "tag color"),
                        ),
                        source = enum(row.value("tagAssignmentSource"), "tag assignment source"),
                    )
                    require(current.tags.none { it.tag.id == assignment.tag.id }) { "duplicate tag assignment" }
                    require(tags.putIfAbsent(assignment.tag.id, assignment.tag)?.let { it == assignment.tag } != false) {
                        "inconsistent tag definition"
                    }
                    current.tags += assignment
                }
                else -> fail("unknown record type")
            }
        }
        pending?.let { completed -> onRecord(completed.record); count++ }
        TransferCsvDecodeResult.Success(count)
    } catch (error: IllegalArgumentException) {
        TransferCsvDecodeResult.Failure(error.message ?: "invalid transaction CSV")
    }

    private class Pending(val base: TransferCsvRecord, val tags: MutableList<TransferCsvTagAssignment>) {
        val record get() = base.copy(tags = tags.toList())
    }

    private fun transferRow(record: TransferCsvRecord): List<String> = row().apply {
        put("recordType", TRANSFER)
        put("exportedAccountId", record.account.exportedAccountId); put("extensionId", record.account.extensionId)
        putNullable("sourceAccountKey", record.account.sourceAccountKey); put("kind", record.account.kind.name)
        put("currency", record.account.currency); put("accountName", record.account.accountName); put("extensionName", record.account.extensionName)
        val t = record.transfer
        put("id", t.id); put("accountId", t.accountId); put("txnDateTime", t.txnDateTime); put("description", t.description)
        put("amount", finite(t.amount, "amount")); putNullableNumber("balance", t.balance); put("memo", t.memo)
        putNullable("type", t.type); putNullable("status", t.status); putNullable("postingDateTime", t.postingDateTime)
        putNullable("cardInstrumentId", t.cardInstrumentId); putNullable("merchantName", t.merchantName); putNullable("merchantCategoryCode", t.merchantCategoryCode)
        putNullable("counterpartyName", t.counterpartyName); putNullable("purpose", t.purpose); putNullable("authorizationDateTime", t.authorizationDateTime)
        putNullable("valueDateTime", t.valueDateTime); putNullable("referenceNumber", t.referenceNumber); putNullable("authorizationCode", t.authorizationCode)
        putNullable("channel", t.channel); putNullable("direction", t.direction); putNullable("transactionCode", t.transactionCode)
        putNullableNumber("originalAmount", t.originalAmount); putNullable("originalCurrency", t.originalCurrency)
        putNullableNumber("settlementAmount", t.settlementAmount); putNullable("settlementCurrency", t.settlementCurrency)
        putNullableNumber("exchangeRate", t.exchangeRate); putNullableNumber("feeAmount", t.feeAmount); putNullable("feeCurrency", t.feeCurrency)
        putNullableNumber("taxAmount", t.taxAmount); putNullable("taxCurrency", t.taxCurrency); putNullable("merchantLocation", t.merchantLocation)
        putNullable("counterpartyAccount", t.counterpartyAccount); putNullable("counterpartyBank", t.counterpartyBank)
        putNullableInt("installmentNumber", t.installmentNumber); putNullableInt("installmentTotal", t.installmentTotal)
        putNullableBoolean("isRefund", t.isRefund); putNullableBoolean("isReversal", t.isReversal)
        putNullable("originalTransactionSourceId", t.originalTransactionSourceId); putNullable("parserVersion", t.parserVersion)
        record.annotation?.let { a ->
            put("annotationExtensionId", a.extensionId); putNullable("categoryId", a.categoryId); put("note", a.note)
            put("categoryAssignment", a.categoryAssignment.name); put("manualOverride", a.manualOverride.toString())
            putNullable("autoRuleId", a.autoRuleId); putNullable("autoRuleSetId", a.autoRuleSetId); putNullableInt("autoMatchScore", a.autoMatchScore)
            putNullable("classifierVersion", a.classifierVersion)
        }
        record.category?.let { c -> put("categoryName", c.name); put("categoryColor", c.color); put("categoryEmoji", c.emoji); put("categoryKind", c.reportingGroup.name) }
    }.values

    private fun tagRow(transferId: String, assignment: TransferCsvTagAssignment): List<String> = row().apply {
        put("recordType", TAG); put("id", transferId); put("tagId", assignment.tag.id); put("tagName", assignment.tag.name)
        put("tagColor", assignment.tag.color); put("tagAssignmentSource", assignment.source.name)
    }.values

    private fun recordFromTransferRow(row: List<String>): TransferCsvRecord {
        require(row.isBlank("tagId", "tagName", "tagColor", "tagAssignmentSource")) { "transfer row has tag data" }
        val account = TransferCsvAccountMetadata(
            exportedAccountId = required(row.value("exportedAccountId"), "exported account id"),
            extensionId = required(row.value("extensionId"), "extension id"), sourceAccountKey = decodeNullable(row.value("sourceAccountKey")),
            kind = enum(row.value("kind"), "account kind"), currency = required(row.value("currency"), "currency"),
            accountName = required(row.value("accountName"), "account name"), extensionName = required(row.value("extensionName"), "extension name"),
        )
        val transfer = Transfer(
            id = required(row.value("id"), "transfer id"), accountId = required(row.value("accountId"), "account id"),
            extensionId = account.extensionId, txnDateTime = required(row.value("txnDateTime"), "transaction date time"),
            description = required(row.value("description"), "description"), amount = finite(row.value("amount"), "amount"),
            balance = decodeNullableNumber(row.value("balance"), "balance"), memo = row.value("memo"), type = decodeNullable(row.value("type")),
            status = decodeNullable(row.value("status")), postingDateTime = decodeNullable(row.value("postingDateTime")), cardInstrumentId = decodeNullable(row.value("cardInstrumentId")),
            merchantName = decodeNullable(row.value("merchantName")), merchantCategoryCode = decodeNullable(row.value("merchantCategoryCode")),
            counterpartyName = decodeNullable(row.value("counterpartyName")), purpose = decodeNullable(row.value("purpose")), authorizationDateTime = decodeNullable(row.value("authorizationDateTime")),
            valueDateTime = decodeNullable(row.value("valueDateTime")), referenceNumber = decodeNullable(row.value("referenceNumber")), authorizationCode = decodeNullable(row.value("authorizationCode")),
            channel = decodeNullable(row.value("channel")), direction = decodeNullable(row.value("direction")), transactionCode = decodeNullable(row.value("transactionCode")),
            originalAmount = decodeNullableNumber(row.value("originalAmount"), "original amount"), originalCurrency = decodeNullable(row.value("originalCurrency")),
            settlementAmount = decodeNullableNumber(row.value("settlementAmount"), "settlement amount"), settlementCurrency = decodeNullable(row.value("settlementCurrency")),
            exchangeRate = decodeNullableNumber(row.value("exchangeRate"), "exchange rate"), feeAmount = decodeNullableNumber(row.value("feeAmount"), "fee amount"), feeCurrency = decodeNullable(row.value("feeCurrency")),
            taxAmount = decodeNullableNumber(row.value("taxAmount"), "tax amount"), taxCurrency = decodeNullable(row.value("taxCurrency")), merchantLocation = decodeNullable(row.value("merchantLocation")),
            counterpartyAccount = decodeNullable(row.value("counterpartyAccount")), counterpartyBank = decodeNullable(row.value("counterpartyBank")),
            installmentNumber = decodeNullableInt(row.value("installmentNumber"), "installment number"), installmentTotal = decodeNullableInt(row.value("installmentTotal"), "installment total"),
            isRefund = decodeNullableBoolean(row.value("isRefund"), "is refund"), isReversal = decodeNullableBoolean(row.value("isReversal"), "is reversal"),
            originalTransactionSourceId = decodeNullable(row.value("originalTransactionSourceId")), parserVersion = decodeNullable(row.value("parserVersion")),
        )
        require(transfer.accountId == account.exportedAccountId) { "transfer account id does not match exported account id" }
        val annotation = annotationFrom(row, transfer.id, account.extensionId)
        val category = categoryFrom(row, annotation)
        return TransferCsvRecord(account, transfer, annotation, category, emptyList())
    }

    private fun annotationFrom(row: List<String>, transferId: String, extensionId: String): TransferAnnotation? {
        val fields = listOf("annotationExtensionId", "categoryId", "note", "categoryAssignment", "manualOverride", "autoRuleId", "autoRuleSetId", "autoMatchScore", "classifierVersion")
        if (row.value("annotationExtensionId").isEmpty()) {
            require(fields.drop(1).all { row.value(it).isEmpty() }) { "partial annotation" }
            return null
        }
        val annotationExtensionId = required(row.value("annotationExtensionId"), "annotation extension id")
        require(annotationExtensionId == extensionId) { "annotation extension id does not match transfer" }
        return TransferAnnotation(
            transferId = transferId, extensionId = annotationExtensionId, categoryId = decodeNullable(row.value("categoryId")), note = row.value("note"),
            categoryAssignment = enum(row.value("categoryAssignment"), "category assignment"), manualOverride = boolean(row.value("manualOverride"), "manual override"),
            autoRuleId = decodeNullable(row.value("autoRuleId")), autoRuleSetId = decodeNullable(row.value("autoRuleSetId")), autoMatchScore = decodeNullableInt(row.value("autoMatchScore"), "auto match score"), classifierVersion = decodeNullable(row.value("classifierVersion")),
        )
    }

    private fun categoryFrom(row: List<String>, annotation: TransferAnnotation?): Category? {
        val fields = listOf("categoryName", "categoryColor", "categoryEmoji", "categoryKind")
        val id = annotation?.categoryId
        if (id == null) {
            require(fields.all { row.value(it).isEmpty() }) { "category definition without assignment" }
            return null
        }
        require(fields.none { row.value(it).isEmpty() }) { "partial category definition" }
        return Category(
            id,
            row.value("categoryName"),
            row.value("categoryColor"),
            row.value("categoryEmoji"),
            categoryReportingGroup(row.value("categoryKind")),
        )
    }

    private fun validateRecord(record: TransferCsvRecord) {
        val a = record.account; val t = record.transfer
        required(a.exportedAccountId, "exported account id"); required(a.extensionId, "extension id"); required(a.currency, "currency")
        required(a.accountName, "account name"); required(a.extensionName, "extension name")
        required(a.sourceAccountKey.orEmpty(), "source account key")
        required(t.id, "transfer id"); require(t.accountId == a.exportedAccountId) { "transfer account id does not match exported account id" }
        require(t.extensionId == a.extensionId) { "transfer extension id does not match account" }; required(t.txnDateTime, "transaction date time")
        required(t.description, "description"); finite(t.amount, "amount"); allFinite(t)
        val annotation = record.annotation
        if (annotation == null) require(record.category == null) { "category requires annotation" } else {
            require(annotation.transferId == t.id && annotation.extensionId == a.extensionId) { "annotation does not match transfer" }
            require(annotation.manualOverride == (annotation.categoryAssignment == AssignmentSource.MANUAL)) { "invalid manual override" }
            if (annotation.categoryId == null) require(record.category == null) { "category definition without category id" }
            else require(record.category?.id == annotation.categoryId) { "category does not match annotation" }
        }
        require(record.tags.map { it.tag.id }.distinct().size == record.tags.size) { "duplicate tag assignment" }
        require(record.tags.size <= MAX_TAGS_PER_TRANSFER) { "too many tags for transfer" }
        record.tags.forEach { required(it.tag.id, "tag id"); required(it.tag.name, "tag name"); required(it.tag.color, "tag color") }
    }

    private fun allFinite(t: Transfer) = listOf(t.balance, t.originalAmount, t.settlementAmount, t.exchangeRate, t.feeAmount, t.taxAmount).forEach { value -> if (value != null) finite(value, "numeric field") }
    private fun finite(value: Double, name: String): String { require(value.isFinite()) { "$name must be finite" }; return value.toString() }
    private fun finite(value: String, name: String): Double = value.toDoubleOrNull()?.takeIf(Double::isFinite) ?: fail("invalid $name")
    private fun nullableNumber(value: Double?): String = value?.let { finite(it, "numeric field") } ?: NULL
    private fun decodeNullableNumber(value: String, name: String): Double? = if (value == NULL) null else finite(value, name)
    private fun nullableInt(value: Int?): String = value?.toString() ?: NULL
    private fun decodeNullableInt(value: String, name: String): Int? = if (value == NULL) null else value.toIntOrNull() ?: fail("invalid $name")
    private fun nullableBoolean(value: Boolean?): String = value?.toString() ?: NULL
    private fun decodeNullableBoolean(value: String, name: String): Boolean? = if (value == NULL) null else boolean(value, name)
    private fun boolean(value: String, name: String): Boolean = when (value) { "true" -> true; "false" -> false; else -> fail("invalid $name") }
    private fun nullable(value: String?): String = value?.let { STRING + it } ?: NULL
    private fun decodeNullable(value: String): String? = when { value == NULL -> null; value.startsWith(STRING) -> value.removePrefix(STRING); else -> fail("invalid nullable value") }
    private inline fun <reified T : Enum<T>> enum(value: String, name: String): T = enumValues<T>().firstOrNull { it.name == value } ?: fail("invalid $name")
    /** Reads v1 category values while writing the precise v24 reporting-group contract. */
    private fun categoryReportingGroup(value: String): CategoryReportingGroup = when (value) {
        "TRANSFER" -> CategoryReportingGroup.EXCLUDED
        else -> enum(value, "category reporting group")
    }
    private fun required(value: String, name: String): String { require(value.isNotBlank()) { "missing $name" }; validateLength(value, name); return value }
    private fun validateLength(value: String, name: String) { require(value.length <= MAX_ID_CHARS) { "$name is too long" } }
    private fun fail(reason: String): Nothing = throw IllegalArgumentException(reason)

    private fun List<String>.value(name: String): String = this[column.getValue(name)]
    private fun List<String>.isBlank(vararg names: String): Boolean = names.all { value(it).isEmpty() }
    private fun List<String>.isBlankExcept(vararg names: String): Boolean { val allowed = names.toSet(); return header.all { it in allowed || value(it).isEmpty() } }
    private fun row() = Row()
    private class Row { val values = MutableList(header.size) { "" }; fun put(name: String, value: String) { values[column.getValue(name)] = value }; fun putNullable(name: String, value: String?) = put(name, nullable(value)); fun putNullableNumber(name: String, value: Double?) = put(name, nullableNumber(value)); fun putNullableInt(name: String, value: Int?) = put(name, nullableInt(value)); fun putNullableBoolean(name: String, value: Boolean?) = put(name, nullableBoolean(value)) }

    private class CsvWriter(private val writer: Writer) {
        private var writtenChars = 0
        private var writtenRows = 0

        fun writeRow(row: List<String>) {
            require(row.size <= header.size) { "too many CSV columns" }
            require(row.all { it.length <= MAX_CELL_CHARS }) { "CSV cell is too large" }
            val encoded = row.joinToString(",") { escape(it) } + "\r\n"
            require(encoded.length <= MAX_CHARS - writtenChars) { "CSV is too large" }
            require(++writtenRows <= MAX_ROWS) { "too many rows" }
            writer.write(encoded)
            writtenChars += encoded.length
        }
        private fun escape(value: String): String {
            val protected = if (shouldProtectFormula(value)) "'$value" else value
            return if (protected.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"${protected.replace("\"", "\"\"")}\""
            } else {
                protected
            }
        }

        /**
         * Leading apostrophes are doubled so protection is reversible. Signed finite numbers stay
         * numeric; other spreadsheet formula prefixes are emitted as text.
         */
        private fun shouldProtectFormula(value: String): Boolean = when (value.firstOrNull()) {
            '=', '@', '\'' -> true
            '+', '-' -> value.toDoubleOrNull()?.isFinite() != true
            else -> false
        }
    }

    /** Strict RFC 4180 reader with global, row and cell limits. */
    private class CsvReader(private val reader: Reader) {
        private var chars = 0; private var rows = 0; private var next = Int.MIN_VALUE
        fun nextRow(): List<String>? {
            val row = mutableListOf<String>(); val cell = StringBuilder(); var inQuotes = false; var afterQuote = false; var sawAny = false
            fun append(c: Char) { require(cell.length < MAX_CELL_CHARS) { "CSV cell is too large" }; cell.append(c) }
            while (true) {
                val code = read()
                if (code == -1) { require(!inQuotes) { "unterminated quoted cell" }; if (!sawAny && row.isEmpty() && cell.isEmpty()) return null; addCell(row, cell); return finish(row) }
                val c = code.toChar(); sawAny = true
                if (inQuotes) { if (c == '"') { if (peek() == '"'.code) { read(); append('"') } else { inQuotes = false; afterQuote = true } } else append(c); continue }
                if (afterQuote) when (c) { ',' -> { addCell(row, cell); cell.clear(); afterQuote = false }; '\n' -> { addCell(row, cell); return finish(row) }; '\r' -> { if (peek() == '\n'.code) read(); addCell(row, cell); return finish(row) }; else -> fail("unexpected character after quoted cell") }
                else when (c) { '"' -> { require(cell.isEmpty()) { "quote in unquoted cell" }; inQuotes = true }; ',' -> { addCell(row, cell); cell.clear() }; '\n' -> { addCell(row, cell); return finish(row) }; '\r' -> { if (peek() == '\n'.code) read(); addCell(row, cell); return finish(row) }; else -> append(c) }
            }
        }
        private fun addCell(row: MutableList<String>, cell: StringBuilder) {
            val value = cell.toString()
            row += if (value.startsWith("'") && value.length > 1 &&
                (value[1] == '\'' || value[1] == '=' || value[1] == '@' ||
                    (value[1] == '+' || value[1] == '-') &&
                    value.substring(1).toDoubleOrNull()?.isFinite() != true)
            ) {
                value.substring(1)
            } else {
                value
            }
            require(row.size <= header.size) { "too many CSV columns" }
        }
        private fun finish(row: List<String>): List<String> { rows++; require(rows <= MAX_ROWS) { "too many rows" }; return row }
        private fun read(): Int { val result = if (next != Int.MIN_VALUE) next.also { next = Int.MIN_VALUE } else reader.read(); if (result != -1) { chars++; require(chars <= MAX_CHARS) { "CSV is too large" } }; return result }
        private fun peek(): Int { if (next == Int.MIN_VALUE) next = reader.read(); return next }
    }
}
