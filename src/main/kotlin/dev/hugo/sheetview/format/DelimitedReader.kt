package dev.hugo.sheetview.format

import dev.hugo.sheetview.model.Cell
import dev.hugo.sheetview.model.CellType
import dev.hugo.sheetview.model.Sheet
import dev.hugo.sheetview.model.Workbook
import kotlin.io.path.readBytes

/**
 * CSV/TSV. 확장자로 자동 선점하지 않는다 — 번들 grid 플러그인이 `csv-data-editor`로
 * csv/tsv를 이미 담당하고 그쪽이 편집까지 지원한다. 이 리더는 포맷 판별이
 * 다른 어떤 것에도 맞지 않을 때의 폴백과 "표로 열기" 명시 경로에서만 쓰인다.
 */
class DelimitedReader : SpreadsheetReader {

    override fun read(ctx: ReadContext): Workbook {
        val bytes = ctx.path.readBytes()
        val charset = ctx.charset ?: SpreadsheetSniffer.detectCharset(bytes)
        val bom = SpreadsheetSniffer.bomLength(bytes)
        val text = String(bytes, bom, bytes.size - bom, charset)

        val delimiter = detectDelimiter(text)
        val rows = ArrayList<List<Cell>>()
        var columnCount = 0
        var totalRows = 0
        var truncated = false

        parse(text, delimiter) { fields ->
            totalRows++
            if (totalRows % 2048 == 0) ctx.checkCancelled()
            if (rows.size < ctx.limits.maxRows) {
                val row = fields.take(ctx.limits.maxColumns).map { CellTypeInference.infer(it) }
                if (row.size > columnCount) columnCount = row.size
                rows.add(row)
            } else {
                truncated = true
            }
            totalRows <= ctx.limits.maxScanRows
        }

        if (rows.isEmpty()) {
            throw UnsupportedSpreadsheetException(
                "표로 해석할 내용이 없습니다.",
                "빈 파일이거나 구분자로 나눌 수 있는 텍스트가 아닙니다.",
            )
        }

        val padded = rows.map { row ->
            if (row.size >= columnCount) row else row + List(columnCount - row.size) { Cell.BLANK }
        }
        val label = "${SpreadsheetFormat.DELIMITED.label} (${delimiterName(delimiter)})"
        return Workbook(
            listOf(
                Sheet(
                    name = ctx.path.fileName?.toString()?.substringBeforeLast('.') ?: "Sheet 1",
                    rows = padded,
                    headerRowCount = if (looksLikeHeader(padded.firstOrNull())) 1 else 0,
                    columnCount = columnCount,
                    totalRowCount = totalRows,
                    truncated = truncated,
                ),
            ),
            label,
            charset.name(),
        )
    }

    /** 앞부분 여러 줄에서 줄마다 개수가 가장 일정한 구분자를 고른다. */
    private fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (sample.isEmpty()) return ','
        var best = ','
        var bestScore = -1.0
        for (candidate in CANDIDATES) {
            val counts = sample.map { line -> line.count { it == candidate } }
            val median = counts.sorted()[counts.size / 2]
            if (median == 0) continue
            val consistent = counts.count { it == median }
            // 줄마다 개수가 같을수록, 그리고 열이 많을수록 높은 점수.
            val score = consistent.toDouble() / counts.size * 100 + median
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun delimiterName(d: Char): String = when (d) {
        ',' -> "콤마"
        '\t' -> "탭"
        ';' -> "세미콜론"
        '|' -> "파이프"
        else -> d.toString()
    }

    /** RFC 4180 따옴표 규칙(`""` 이스케이프, 필드 내 개행 허용)을 지키며 줄 단위로 넘긴다. */
    private fun parse(text: String, delimiter: Char, onRow: (List<String>) -> Boolean) {
        val field = StringBuilder()
        var fields = ArrayList<String>()
        var inQuotes = false
        var i = 0
        var emitted = false

        fun endRow(): Boolean {
            fields.add(field.toString())
            field.setLength(0)
            val row = fields
            fields = ArrayList()
            emitted = true
            return onRow(row)
        }

        while (i < text.length) {
            val ch = text[i]
            when {
                inQuotes -> when {
                    ch == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    ch == '"' -> inQuotes = false
                    else -> field.append(ch)
                }
                ch == '"' && field.isEmpty() -> inQuotes = true
                ch == delimiter -> { fields.add(field.toString()); field.setLength(0) }
                ch == '\r' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    if (!endRow()) return
                }
                ch == '\n' -> if (!endRow()) return
                else -> field.append(ch)
            }
            i++
        }
        // 마지막 줄에 개행이 없는 경우.
        if (field.isNotEmpty() || fields.isNotEmpty() || !emitted) endRow()
    }

    private fun looksLikeHeader(row: List<Cell>?): Boolean {
        if (row == null) return false
        val filled = row.filter { !it.isBlank }
        return filled.isNotEmpty() && filled.all { it.type == CellType.TEXT }
    }

    private companion object {
        val CANDIDATES = charArrayOf(',', '\t', ';', '|')
    }
}
