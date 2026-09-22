package dev.hugo.sheetview.export

import dev.hugo.sheetview.model.Cell
import dev.hugo.sheetview.model.CellType
import dev.hugo.sheetview.model.Sheet

enum class ExportFormat(val label: String, val extension: String) {
    CSV("CSV", "csv"),
    JSON("JSON", "json"),
    MARKDOWN("Markdown", "md"),
}

/** 시트를 텍스트로 직렬화한다. 대시보드나 스크립트에서 바로 쓰도록 하는 것이 목적이다. */
object ExportFormats {

    fun render(sheet: Sheet, format: ExportFormat, useHeader: Boolean): String = when (format) {
        ExportFormat.CSV -> toCsv(sheet, useHeader)
        ExportFormat.JSON -> toJson(sheet, useHeader)
        ExportFormat.MARKDOWN -> toMarkdown(sheet, useHeader)
    }

    private fun headerRows(sheet: Sheet, useHeader: Boolean) = if (useHeader) sheet.headerRowCount else 0

    fun toCsv(sheet: Sheet, useHeader: Boolean): String {
        val skip = headerRows(sheet, useHeader)
        val sb = StringBuilder()
        if (skip > 0) sb.append(sheet.headerLabels(skip).joinToString(",") { csvField(it) }).append('\n')
        for (r in skip until sheet.rows.size) {
            sb.append((0 until sheet.columnCount).joinToString(",") { csvField(sheet.cell(r, it).text) })
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 탭 구분. 클립보드에 넣으면 엑셀·시트에 그대로 붙는다. */
    fun toTsv(sheet: Sheet, useHeader: Boolean): String {
        val skip = headerRows(sheet, useHeader)
        val sb = StringBuilder()
        if (skip > 0) sb.append(sheet.headerLabels(skip).joinToString("\t")).append('\n')
        for (r in skip until sheet.rows.size) {
            sb.append((0 until sheet.columnCount).joinToString("\t") {
                sheet.cell(r, it).text.replace('\t', ' ').replace('\n', ' ')
            })
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 헤더가 있으면 객체 배열, 없으면 배열의 배열.
     * 숫자로 추론된 셀은 문자열이 아니라 숫자로 내보낸다.
     */
    fun toJson(sheet: Sheet, useHeader: Boolean): String {
        val skip = headerRows(sheet, useHeader)
        val sb = StringBuilder("[\n")
        if (skip > 0) {
            val keys = uniqueKeys(sheet.headerLabels(skip))
            for (r in skip until sheet.rows.size) {
                sb.append("  {")
                sb.append((0 until sheet.columnCount).joinToString(", ") { c ->
                    "${jsonString(keys[c])}: ${jsonValue(sheet.cell(r, c))}"
                })
                sb.append(if (r == sheet.rows.size - 1) "}\n" else "},\n")
            }
        } else {
            for (r in 0 until sheet.rows.size) {
                sb.append("  [")
                sb.append((0 until sheet.columnCount).joinToString(", ") { jsonValue(sheet.cell(r, it)) })
                sb.append(if (r == sheet.rows.size - 1) "]\n" else "],\n")
            }
        }
        sb.append("]\n")
        return sb.toString()
    }

    fun toMarkdown(sheet: Sheet, useHeader: Boolean): String {
        val skip = headerRows(sheet, useHeader)
        val labels = if (skip > 0) sheet.headerLabels(skip) else (0 until sheet.columnCount).map { Sheet.columnName(it) }
        val sb = StringBuilder()
        sb.append("| ").append(labels.joinToString(" | ") { mdCell(it) }).append(" |\n")
        sb.append("|").append((0 until sheet.columnCount).joinToString("|") { " --- " }).append("|\n")
        for (r in skip until sheet.rows.size) {
            sb.append("| ")
            sb.append((0 until sheet.columnCount).joinToString(" | ") { mdCell(sheet.cell(r, it).text) })
            sb.append(" |\n")
        }
        return sb.toString()
    }

    /** 같은 헤더 이름이 겹치면 JSON 키가 덮이므로 접미사를 붙인다. */
    private fun uniqueKeys(labels: List<String>): List<String> {
        val used = HashSet<String>()
        return labels.mapIndexed { index, label ->
            val base = label.ifBlank { Sheet.columnName(index) }
            var candidate = base
            var n = 2
            while (!used.add(candidate)) {
                candidate = "$base ($n)"
                n++
            }
            candidate
        }
    }

    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    private fun jsonValue(cell: Cell): String = when {
        cell.type == CellType.BLANK -> "null"
        cell.type == CellType.NUMBER && cell.number != null -> plainNumber(cell.number)
        else -> jsonString(cell.text)
    }

    private fun plainNumber(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        return sb.append('"').toString()
    }

    private fun mdCell(value: String): String =
        value.replace("|", "\\|").replace("\n", "<br>")
}
