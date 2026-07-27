package tw.kevinzhang.core.data.db

/**
 * Small RFC 4180-style CSV reader used by backup codecs.
 *
 * The parser deliberately rejects quotes in unquoted cells and characters after a closing quote.
 * This keeps malformed or polyglot input from being interpreted differently by another CSV
 * implementation before it is persisted.
 */
internal object StrictCsv {
    fun parse(
        csv: String,
        maxChars: Int,
        maxRows: Int,
        maxCellChars: Int,
    ): List<List<String>> {
        require(csv.length <= maxChars) { "CSV is too large" }

        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var afterQuote = false
        var index = 0
        var endedWithRowSeparator = false

        fun append(value: Char) {
            require(cell.length < maxCellChars) { "CSV cell is too large" }
            cell.append(value)
        }

        fun finishCell() {
            row += cell.toString()
            cell.clear()
            afterQuote = false
        }

        fun finishRow() {
            finishCell()
            rows += row
            require(rows.size <= maxRows) { "too many rows" }
            row = mutableListOf()
            endedWithRowSeparator = true
        }

        while (index < csv.length) {
            val char = csv[index]
            if (inQuotes) {
                if (char == '"') {
                    if (csv.getOrNull(index + 1) == '"') {
                        append('"')
                        index++
                    } else {
                        inQuotes = false
                        afterQuote = true
                    }
                } else {
                    append(char)
                }
                endedWithRowSeparator = false
                index++
                continue
            }

            if (afterQuote) {
                when (char) {
                    ',' -> {
                        finishCell()
                        endedWithRowSeparator = false
                    }
                    '\n' -> finishRow()
                    '\r' -> {
                        if (csv.getOrNull(index + 1) == '\n') index++
                        finishRow()
                    }
                    else -> throw IllegalArgumentException("unexpected character after quoted cell")
                }
                index++
                continue
            }

            when (char) {
                '"' -> {
                    require(cell.isEmpty()) { "quote in unquoted cell" }
                    inQuotes = true
                    endedWithRowSeparator = false
                }
                ',' -> {
                    finishCell()
                    endedWithRowSeparator = false
                }
                '\n' -> finishRow()
                '\r' -> {
                    if (csv.getOrNull(index + 1) == '\n') index++
                    finishRow()
                }
                else -> {
                    append(char)
                    endedWithRowSeparator = false
                }
            }
            index++
        }

        require(!inQuotes) { "unterminated quoted cell" }
        if (!endedWithRowSeparator || rows.isEmpty()) {
            finishCell()
            rows += row
            require(rows.size <= maxRows) { "too many rows" }
        }
        return rows
    }

    fun encode(rows: List<List<String>>): String =
        rows.joinToString("\r\n") { row -> row.joinToString(",") { escape(it) } }

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
}
