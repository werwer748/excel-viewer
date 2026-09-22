package dev.hugo.sheetview.format

import dev.hugo.sheetview.model.Cell
import dev.hugo.sheetview.model.CellType
import dev.hugo.sheetview.model.Sheet
import dev.hugo.sheetview.model.Workbook
import java.io.BufferedInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.io.path.inputStream

/** Excel 2003 XML (SpreadsheetML). StAX 스트리밍, 외부 의존성 없음. */
class SpreadsheetMlReader : SpreadsheetReader {

    override fun read(ctx: ReadContext): Workbook {
        val limits = ctx.limits
        val sheets = ArrayList<Sheet>()

        BufferedInputStream(ctx.path.inputStream()).use { ins ->
            val r = XML_INPUT.createXMLStreamReader(ins)

            var sheetName: String? = null
            var occupied: HashMap<Long, Cell>? = null
            var columnCount = 0
            var rowIndex = -1
            var colIndex = 0
            var totalRows = 0
            var truncated = false
            var mergeAcross = 0
            var mergeDown = 0
            var dataType: String? = null
            var dataText: String? = null

            fun flushSheet() {
                val cells = occupied ?: return
                val rowLimit = (rowIndex + 1).coerceAtMost(limits.maxRows)
                val rows = (0 until rowLimit).map { rr ->
                    (0 until columnCount).map { cc -> cells[key(rr, cc)] ?: Cell.BLANK }
                }
                sheets.add(
                    Sheet(
                        name = sheetName ?: "Sheet ${sheets.size + 1}",
                        rows = rows,
                        headerRowCount = if (looksLikeHeader(rows.firstOrNull())) 1 else 0,
                        columnCount = columnCount,
                        totalRowCount = totalRows,
                        truncated = truncated,
                    ),
                )
                occupied = null
            }

            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "Worksheet" -> {
                            sheetName = attr(r, "Name")
                            occupied = HashMap()
                            columnCount = 0
                            rowIndex = -1
                            totalRows = 0
                            truncated = false
                        }
                        "Row" -> {
                            // ss:Index 는 1-based 절대 행 번호다 (빈 행 생략).
                            rowIndex = attr(r, "Index")?.toIntOrNull()?.minus(1) ?: (rowIndex + 1)
                            totalRows = rowIndex + 1
                            colIndex = 0
                            if (rowIndex >= limits.maxRows) truncated = true
                            if (totalRows % 2048 == 0) ctx.checkCancelled()
                        }
                        "Cell" -> {
                            attr(r, "Index")?.toIntOrNull()?.let { colIndex = it - 1 }
                            mergeAcross = attr(r, "MergeAcross")?.toIntOrNull()?.coerceIn(0, MAX_SPAN) ?: 0
                            mergeDown = attr(r, "MergeDown")?.toIntOrNull()?.coerceIn(0, MAX_SPAN) ?: 0
                            dataType = null
                            dataText = null
                        }
                        "Data" -> {
                            dataType = attr(r, "Type")
                            dataText = runCatching { r.elementText }.getOrNull()
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "Cell" -> {
                            val cells = occupied
                            if (cells != null && rowIndex in 0 until limits.maxRows) {
                                while (cells.containsKey(key(rowIndex, colIndex))) colIndex++
                                if (colIndex < limits.maxColumns) {
                                    val cell = toCell(dataType, dataText)
                                    for (dr in 0..mergeDown) {
                                        val rr = rowIndex + dr
                                        if (rr >= limits.maxRows) break
                                        for (dc in 0..mergeAcross) {
                                            val cc = colIndex + dc
                                            if (cc >= limits.maxColumns) break
                                            cells[key(rr, cc)] = cell
                                        }
                                    }
                                    colIndex = (colIndex + mergeAcross + 1).coerceAtMost(limits.maxColumns)
                                    if (colIndex > columnCount) columnCount = colIndex
                                }
                            }
                        }
                        "Worksheet" -> flushSheet()
                    }
                }
            }
            flushSheet()
        }

        if (sheets.isEmpty()) {
            throw SpreadsheetParseException(
                "Excel 2003 XML 문서에서 시트를 찾지 못했습니다.",
                "<Worksheet> 요소가 없습니다.",
            )
        }
        return Workbook(sheets, SpreadsheetFormat.SPREADSHEET_ML.label)
    }

    /** 속성은 보통 ss: 네임스페이스에 있지만 접두사 선언을 빠뜨린 파일도 있어 둘 다 본다. */
    private fun attr(r: javax.xml.stream.XMLStreamReader, name: String): String? =
        r.getAttributeValue(SS_NS, name) ?: r.getAttributeValue(null, name)

    private fun toCell(type: String?, text: String?): Cell {
        val raw = text?.replace(' ', ' ')?.trim().orEmpty()
        if (raw.isEmpty()) return Cell.BLANK
        return when (type) {
            "Number" -> raw.toDoubleOrNull()?.let { Cell(raw, CellType.NUMBER, it) } ?: Cell(raw, CellType.TEXT)
            "DateTime" -> Cell(raw.substringBefore("T").ifEmpty { raw }, CellType.DATE)
            "Boolean" -> Cell(if (raw == "1") "TRUE" else "FALSE", CellType.TEXT)
            // String / Error / 미지정은 텍스트에서 추론한다.
            else -> CellTypeInference.infer(raw)
        }
    }

    private fun looksLikeHeader(row: List<Cell>?): Boolean {
        if (row == null) return false
        val filled = row.filter { !it.isBlank }
        return filled.isNotEmpty() && filled.all { it.type == CellType.TEXT }
    }

    private fun key(row: Int, col: Int): Long = (row.toLong() shl 20) or col.toLong()

    private companion object {
        const val SS_NS = "urn:schemas-microsoft-com:office:spreadsheet"
        const val MAX_SPAN = 4_096
        val XML_INPUT: XMLInputFactory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
    }
}
