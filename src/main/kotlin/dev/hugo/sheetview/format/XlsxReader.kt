package dev.hugo.sheetview.format

import dev.hugo.sheetview.model.Cell
import dev.hugo.sheetview.model.CellType
import dev.hugo.sheetview.model.Sheet
import dev.hugo.sheetview.model.Workbook
import java.io.InputStream
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.math.abs
import kotlin.math.floor

/**
 * 진짜 .xlsx(OOXML)를 **순수 JDK만으로** 읽는다. `java.util.zip` + StAX 스트리밍이라
 * 외부 의존성이 없고, 시트를 한 번에 메모리로 올리지 않아 큰 파일에서 OOM이 나지 않는다.
 * (기존 엑셀 뷰어 플러그인이 죽는 또 하나의 원인이 대용량 파일 OOM이다.)
 */
class XlsxReader : SpreadsheetReader {

    override fun read(ctx: ReadContext): Workbook {
        val zip = try {
            ZipFile(ctx.path.toFile())
        } catch (e: ZipException) {
            throw SpreadsheetParseException(
                "ZIP 구조가 깨져 있어 열 수 없습니다.",
                "파일이 전송 중 잘렸거나 손상된 것으로 보입니다. 다시 내려받아 주세요.",
                e,
            )
        }

        zip.use { z ->
            if (z.getEntry(WORKBOOK_PART) == null) {
                // .xlsb(바이너리 워크북)도 ZIP이라 PK 시그니처에 걸린다. 구분해서 안내한다.
                if (z.getEntry("xl/workbook.bin") != null) {
                    throw UnsupportedSpreadsheetException(
                        "Excel 바이너리 워크북(.xlsb) 형식은 지원하지 않습니다.",
                        "엑셀에서 .xlsx로 저장한 뒤 다시 열어 주세요.",
                    )
                }
                throw UnsupportedSpreadsheetException(
                    "ZIP 파일이지만 엑셀 워크북이 아닙니다.",
                    "$WORKBOOK_PART 항목이 없습니다. .docx, .ods, 일반 .zip 등 " +
                        "다른 ZIP 기반 파일일 수 있습니다.",
                )
            }
            val book = readWorkbookMeta(z)
            val rels = readRelationships(z)
            val strings = readSharedStrings(z, ctx)
            val styles = readStyles(z)

            val sheets = book.sheets.mapNotNull { ref ->
                ctx.checkCancelled()
                val part = rels[ref.relId]?.let(::resolvePart) ?: return@mapNotNull null
                readSheet(z, part, ref.name, strings, styles, book.date1904, ctx)
            }
            if (sheets.isEmpty()) {
                throw SpreadsheetParseException(
                    "워크북에서 시트를 찾지 못했습니다.",
                    "workbook.xml 과 관계 파일(_rels)이 서로 맞지 않습니다.",
                )
            }
            return Workbook(sheets, SpreadsheetFormat.XLSX.label)
        }
    }

    // ---------- workbook.xml ----------

    private class SheetRef(val name: String, val relId: String)
    private class WorkbookMeta(val sheets: List<SheetRef>, val date1904: Boolean)

    private fun readWorkbookMeta(zip: ZipFile): WorkbookMeta {
        val refs = ArrayList<SheetRef>()
        var date1904 = false
        stream(zip, WORKBOOK_PART)?.use { ins ->
            val r = newReader(ins)
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue
                when (r.localName) {
                    "workbookPr" -> date1904 = r.getAttributeValue(null, "date1904") in TRUE_VALUES
                    "sheet" -> {
                        val name = r.getAttributeValue(null, "name")
                        val rid = r.getAttributeValue(REL_NS, "id") ?: r.getAttributeValue(null, "id")
                        if (name != null && rid != null) refs.add(SheetRef(name, rid))
                    }
                }
            }
        }
        return WorkbookMeta(refs, date1904)
    }

    private fun readRelationships(zip: ZipFile): Map<String, String> {
        val map = HashMap<String, String>()
        stream(zip, WORKBOOK_RELS_PART)?.use { ins ->
            val r = newReader(ins)
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) continue
                if (r.localName != "Relationship") continue
                val id = r.getAttributeValue(null, "Id") ?: continue
                val target = r.getAttributeValue(null, "Target") ?: continue
                map[id] = target
            }
        }
        return map
    }

    /** 관계의 Target을 ZIP 내부 경로로 바꾼다. `/xl/...` 절대 경로와 `../`도 처리한다. */
    private fun resolvePart(target: String): String {
        val t = target.trim().removePrefix("./")
        if (t.startsWith("/")) return t.removePrefix("/")
        val segments = ArrayList<String>()
        segments.add("xl")
        for (seg in t.split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
            else -> segments.add(seg)
        }
        return segments.joinToString("/")
    }

    // ---------- sharedStrings.xml ----------

    private fun readSharedStrings(zip: ZipFile, ctx: ReadContext): List<String> {
        val result = ArrayList<String>()
        stream(zip, SHARED_STRINGS_PART)?.use { ins ->
            val r = newReader(ins)
            val sb = StringBuilder()
            var inSi = false
            var phoneticDepth = 0
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "si" -> { inSi = true; sb.setLength(0) }
                        // 후리가나(<rPh>)의 <t>는 문자열의 일부가 아니므로 건너뛴다.
                        "rPh" -> phoneticDepth++
                        "t" -> if (inSi && phoneticDepth == 0) sb.append(r.elementText)
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "si" -> {
                            result.add(sb.toString())
                            inSi = false
                            if (result.size % 8192 == 0) ctx.checkCancelled()
                            // sharedStrings.xml 이 워크시트보다 큰 경우가 흔하다.
                            if (result.size >= ctx.limits.maxSharedStrings) return@use
                        }
                        "rPh" -> if (phoneticDepth > 0) phoneticDepth--
                    }
                }
            }
        }
        return result
    }

    // ---------- styles.xml ----------

    private class Styles(private val dateStyleIndexes: Set<Int>) {
        fun isDate(styleIndex: Int): Boolean = styleIndex in dateStyleIndexes
    }

    private fun readStyles(zip: ZipFile): Styles {
        val customDateIds = HashSet<Int>()
        val xfNumFmtIds = ArrayList<Int>()
        stream(zip, STYLES_PART)?.use { ins ->
            val r = newReader(ins)
            var inNumFmts = false
            var inCellXfs = false
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "numFmts" -> inNumFmts = true
                        "cellXfs" -> inCellXfs = true
                        "numFmt" -> if (inNumFmts) {
                            val id = r.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                            val code = r.getAttributeValue(null, "formatCode")
                            if (id != null && code != null && looksLikeDate(code)) customDateIds.add(id)
                        }
                        "xf" -> if (inCellXfs) {
                            xfNumFmtIds.add(r.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0)
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "numFmts" -> inNumFmts = false
                        "cellXfs" -> inCellXfs = false
                    }
                }
            }
        }
        val dateIndexes = xfNumFmtIds.indices
            .filter { xfNumFmtIds[it] in BUILTIN_DATE_FORMAT_IDS || xfNumFmtIds[it] in customDateIds }
            .toSet()
        return Styles(dateIndexes)
    }

    /** 리터럴과 로케일 섹션을 걷어낸 뒤 날짜/시간 토큰이 남는지 본다. */
    private fun looksLikeDate(code: String): Boolean {
        val cleaned = code
            .replace(QUOTED, "")
            .replace(BRACKETED, "")
            .replace(ESCAPED, "")
        if (cleaned.any { it == 'y' || it == 'Y' || it == 'd' || it == 'D' }) return true
        return cleaned.contains(':') && cleaned.any { it == 'h' || it == 'H' || it == 's' || it == 'S' }
    }

    // ---------- 시트 ----------

    private fun readSheet(
        zip: ZipFile,
        part: String,
        name: String,
        strings: List<String>,
        styles: Styles,
        date1904: Boolean,
        ctx: ReadContext,
    ): Sheet {
        val limits = ctx.limits
        val rows = ArrayList<List<Cell>>()
        var columnCount = 0
        var totalRows = 0
        var truncated = false

        stream(zip, part)?.use { ins ->
            val r = newReader(ins)
            var current: MutableList<Cell>? = null
            var col = -1
            var type: String? = null
            var styleIdx = -1
            var value: String? = null
            var inline: StringBuilder? = null

            loop@ while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "row" -> {
                            totalRows++
                            if (totalRows % 2048 == 0) ctx.checkCancelled()

                            // 행은 희소하게 저장된다: <row r="17"> 처럼 빈 행은 생략된다.
                            // r 속성을 절대 행 번호로 받아 빈 행을 채워야 표의 행 번호가
                            // 엑셀 원본과 일치한다. 그냥 append 하면 행이 밀린다.
                            val target = r.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: rows.size
                            val rowLimit = limits.rowLimit(columnCount)
                            if (target >= rowLimit) {
                                current = null
                                truncated = true
                                if (totalRows > limits.maxScanRows || target > limits.maxScanRows) break@loop
                            } else {
                                // 생략된 빈 행 채우기.
                                while (rows.size < target) rows.add(emptyList())
                                current = ArrayList()
                            }
                            col = -1
                        }
                        "c" -> {
                            // r 속성이 없으면 (빈 셀 생략 없이) 이어지는 열로 간주한다.
                            col = r.getAttributeValue(null, "r")?.let(::columnFromRef) ?: (col + 1)
                            type = r.getAttributeValue(null, "t")
                            styleIdx = r.getAttributeValue(null, "s")?.toIntOrNull() ?: -1
                            value = null
                            inline = null
                        }
                        "v" -> if (current != null) value = r.elementText
                        "t" -> if (current != null) {
                            val sb = inline ?: StringBuilder().also { inline = it }
                            sb.append(r.elementText)
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> when (r.localName) {
                        "c" -> {
                            val row = current
                            if (row != null && col in 0 until limits.maxColumns) {
                                while (row.size <= col) row.add(Cell.BLANK)
                                row[col] = toCell(type, value, inline?.toString(), styleIdx, strings, styles, date1904)
                            }
                        }
                        "row" -> {
                            current?.let {
                                if (it.size > columnCount) columnCount = it.size
                                rows.add(it)
                            }
                            current = null
                        }
                        "sheetData" -> break@loop
                    }
                }
            }
        }

        // 행마다 열 수를 맞춰 준다 (뒤쪽 빈 셀은 생략되어 있다).
        val padded = rows.map { row ->
            if (row.size >= columnCount) row else row + List(columnCount - row.size) { Cell.BLANK }
        }
        return Sheet(
            name = name,
            rows = padded,
            headerRowCount = if (looksLikeHeaderRow(padded.firstOrNull())) 1 else 0,
            columnCount = columnCount,
            totalRowCount = totalRows,
            truncated = truncated,
        )
    }

    /** 첫 행에 숫자·날짜가 없고 글자가 있으면 머리글로 본다. */
    private fun looksLikeHeaderRow(row: List<Cell>?): Boolean {
        if (row == null || row.isEmpty()) return false
        val filled = row.filter { !it.isBlank }
        return filled.isNotEmpty() && filled.all { it.type == CellType.TEXT }
    }

    private fun toCell(
        type: String?,
        value: String?,
        inline: String?,
        styleIndex: Int,
        strings: List<String>,
        styles: Styles,
        date1904: Boolean,
    ): Cell = when (type) {
        "s" -> textCell(value?.toIntOrNull()?.let { strings.getOrNull(it) } ?: "")
        "inlineStr" -> textCell(inline ?: "")
        "str" -> textCell(value ?: "")
        "b" -> textCell(if (value == "1") "TRUE" else "FALSE")
        "e" -> textCell(value ?: "#ERR")
        else -> {
            val d = value?.toDoubleOrNull()
            when {
                d == null -> Cell.BLANK
                styles.isDate(styleIndex) -> Cell(formatExcelDate(d, date1904), CellType.DATE, d)
                else -> Cell(formatNumber(d), CellType.NUMBER, d)
            }
        }
    }

    private fun textCell(s: String): Cell =
        if (s.isEmpty()) Cell.BLANK else Cell(s.replace(' ', ' '), CellType.TEXT)

    private fun formatNumber(d: Double): String = when {
        d.isNaN() || d.isInfinite() -> d.toString()
        d == floor(d) && abs(d) < 1e15 -> d.toLong().toString()
        else -> BigDecimal(d).round(MathContext(15)).stripTrailingZeros().toPlainString()
    }

    /** 엑셀 날짜 일련번호를 문자열로. 1900 윤년 버그(존재하지 않는 1900-02-29)를 보정한다. */
    private fun formatExcelDate(serial: Double, date1904: Boolean): String {
        val base = if (date1904) EPOCH_1904 else EPOCH_1900
        val days = floor(serial).toLong()
        var date = base.plusDays(days)
        if (!date1904 && serial < 60) date = date.plusDays(1)

        var millis = Math.round((serial - days) * 86_400_000.0)
        if (millis >= 86_400_000L) {
            date = date.plusDays(1)
            millis = 0
        }
        return if (millis == 0L) {
            date.format(DATE_FORMAT)
        } else {
            LocalDateTime.of(date, LocalTime.ofNanoOfDay(millis * 1_000_000)).format(DATE_TIME_FORMAT)
        }
    }

    /** `BC12` -> 54 (0-based 열 인덱스). */
    private fun columnFromRef(ref: String): Int {
        var n = 0
        for (ch in ref) {
            val upper = ch.uppercaseChar()
            if (upper < 'A' || upper > 'Z') break
            n = n * 26 + (upper - 'A' + 1)
        }
        return (n - 1).coerceAtLeast(0)
    }

    // ---------- 공통 ----------

    private fun stream(zip: ZipFile, part: String): InputStream? =
        zip.getEntry(part)?.let { zip.getInputStream(it) }

    private fun newReader(ins: InputStream): XMLStreamReader = XML_INPUT.createXMLStreamReader(ins)

    private companion object {
        const val WORKBOOK_PART = "xl/workbook.xml"
        const val WORKBOOK_RELS_PART = "xl/_rels/workbook.xml.rels"
        const val SHARED_STRINGS_PART = "xl/sharedStrings.xml"
        const val STYLES_PART = "xl/styles.xml"
        const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

        val TRUE_VALUES = setOf("1", "true", "on")

        /** 엑셀 내장 날짜/시간 서식 ID. 27~36, 50~58은 CJK 로케일 날짜 서식이다. */
        val BUILTIN_DATE_FORMAT_IDS: Set<Int> =
            ((14..22) + (27..36) + (45..47) + (50..58)).toSet()

        val EPOCH_1900: LocalDate = LocalDate.of(1899, 12, 30)
        val EPOCH_1904: LocalDate = LocalDate.of(1904, 1, 1)
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val QUOTED = Regex("\"[^\"]*\"")
        val BRACKETED = Regex("""\[[^]]*]""")
        val ESCAPED = Regex("""\\.""")

        val XML_INPUT: XMLInputFactory = XMLInputFactory.newInstance().apply {
            // XXE 차단. 신뢰할 수 없는 파일을 여는 경로이므로 외부 참조를 모두 끈다.
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }
    }
}
