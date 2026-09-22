package dev.hugo.sheetview.format

import dev.hugo.sheetview.model.Cell
import dev.hugo.sheetview.model.CellType
import dev.hugo.sheetview.model.Sheet
import dev.hugo.sheetview.model.Workbook
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import kotlin.io.path.readBytes

/**
 * 확장자만 .xls인 HTML 표를 읽는다. 대상 파일의 주 경로다.
 *
 * jsoup은 IDE가 `lib/intellij.libraries.jsoup.jar`(1.22.1)로 부트 클래스패스에 이미
 * 싣고 있어 compileOnly로만 참조하고 플러그인에는 넣지 않는다.
 */
class ExcelHtmlReader : SpreadsheetReader {

    override fun read(ctx: ReadContext): Workbook {
        val bytes = ctx.path.readBytes()
        val charset = ctx.charset ?: SpreadsheetSniffer.detectCharset(bytes)
        val bom = SpreadsheetSniffer.bomLength(bytes)
        val html = String(bytes, bom, bytes.size - bom, charset)

        val doc = Jsoup.parse(html)
        doc.outputSettings().prettyPrint(false)

        // 최상위 table만 시트로 삼는다 (중첩 table의 행을 두 번 세지 않도록).
        val tables = doc.select("table").filter { el -> el.parents().none { it.normalName() == "table" } }
        if (tables.isEmpty()) {
            throw UnsupportedSpreadsheetException(
                "HTML 문서지만 표(<table>)가 없습니다.",
                "이 파일은 확장자가 .xls지만 내용은 HTML입니다. 표가 포함된 파일인지 확인해 주세요.",
            )
        }

        val msoNames = msoSheetNames(doc)
        val usedNames = HashSet<String>()
        val sheets = tables.mapIndexed { index, table ->
            ctx.checkCancelled()
            parseTable(table, index, msoNames.getOrNull(index), usedNames, ctx)
        }
        return Workbook(sheets, SpreadsheetFormat.EXCEL_HTML.label, charset.name())
    }

    private fun parseTable(
        table: Element,
        index: Int,
        msoName: String?,
        usedNames: MutableSet<String>,
        ctx: ReadContext,
    ): Sheet {
        val trs = table.select("tr")
            .filter { tr -> tr.parents().firstOrNull { it.normalName() == "table" } === table }

        val headerRowCount = detectHeaderRowCount(trs)
        val grid = buildGrid(trs, ctx)

        return Sheet(
            name = sheetName(table, index, msoName, usedNames),
            rows = grid.rows,
            // 행이 잘려서 헤더까지 사라지는 일은 없게 한다.
            headerRowCount = headerRowCount.coerceAtMost(grid.rows.size),
            columnCount = grid.columnCount,
            totalRowCount = trs.size,
            truncated = grid.truncated,
        )
    }

    /**
     * `<thead>`가 있으면 그 안의 선두 행들이 헤더다.
     *
     * "`<th>`를 포함한 선두 행"이라는 더 단순한 규칙은 **틀린다**: 대상 파일의 요약내역 표는
     * 본문 첫 행에 `<th>이번달</th>`(rowspan=3인 행 방향 헤더)가 있어 헤더를 2행으로
     * 잘못 잡는다. 실측 결과 네 표 모두 `<thead>`를 제대로 쓰고 있어 그쪽을 기준으로 삼고,
     * `<thead>`가 없을 때만 "모든 셀이 `<th>`인 선두 행" 규칙으로 폴백한다.
     */
    private fun detectHeaderRowCount(trs: List<Element>): Int {
        val leadingThead = trs.asSequence()
            .map { tr -> tr.parents().any { it.normalName() == "thead" } }
            .takeWhile { it }
            .count()
        if (leadingThead > 0) return leadingThead

        return trs.takeWhile { tr ->
            val cells = tr.children().filter { it.normalName() == "td" || it.normalName() == "th" }
            cells.isNotEmpty() && cells.all { it.normalName() == "th" }
        }.size.coerceAtMost(MAX_FALLBACK_HEADER_ROWS)
    }

    private class Grid(val rows: List<List<Cell>>, val columnCount: Int, val truncated: Boolean)

    /**
     * colspan/rowspan을 직사각형 그리드로 펼친다.
     *
     * 점유 맵(row,col -> Cell)을 쓰면 colspan과 rowspan을 한 규칙으로 처리할 수 있고,
     * 비정상적으로 큰 span 값(Excel HTML은 `colspan="16384"`를 뿜기도 한다)도
     * 경계 검사로 자연히 막힌다.
     *
     * 대상 파일의 상세내역 표는 대부분 컬럼이 `rowspan="2"`인데 "이번 달 입금하실 금액"만
     * `colspan="2"`로 갈라지는 2단 헤더라, 이 펼치기가 없으면 컬럼 정렬이 깨진다.
     */
    private fun buildGrid(trs: List<Element>, ctx: ReadContext): Grid {
        val limits = ctx.limits
        val occupied = HashMap<Long, Cell>()
        var columnCount = 0
        var lastRow = 0
        var truncated = false

        for (r in trs.indices) {
            if (r % 256 == 0) ctx.checkCancelled()
            // 행 상한과 총 셀 수 상한을 함께 본다. 열 수를 알아가며 검사해야
            // 점유 맵 자체가 커지는 것을 막을 수 있다.
            if (r >= limits.maxRows || (columnCount > 0 && (r + 1).toLong() * columnCount > limits.maxCells)) {
                truncated = true
                break
            }
            lastRow = r + 1

            var c = 0
            for (cellEl in trs[r].children()) {
                val tag = cellEl.normalName()
                if (tag != "td" && tag != "th") continue

                // 위쪽 rowspan이나 왼쪽 colspan이 이미 차지한 칸은 건너뛴다.
                while (occupied.containsKey(key(r, c))) c++
                if (c >= limits.maxColumns) break

                val colSpan = span(cellEl, "colspan")
                val rowSpan = span(cellEl, "rowspan")
                val cell = toCell(cellEl)

                for (dr in 0 until rowSpan) {
                    val rr = r + dr
                    if (rr >= limits.maxRows) break
                    for (dc in 0 until colSpan) {
                        val cc = c + dc
                        if (cc >= limits.maxColumns) break
                        occupied[key(rr, cc)] = cell
                    }
                }
                c = (c + colSpan).coerceAtMost(limits.maxColumns)
                if (c > columnCount) columnCount = c
            }
        }

        val rows = (0 until lastRow).map { r ->
            (0 until columnCount).map { c -> occupied[key(r, c)] ?: Cell.BLANK }
        }
        return Grid(rows, columnCount, truncated || trs.size > lastRow)
    }

    private fun key(row: Int, col: Int): Long = (row.toLong() shl 20) or col.toLong()

    /** `rowspan="0"`은 HTML에서 "구역 끝까지"를 뜻하므로 1로 맞춘다. */
    private fun span(el: Element, attr: String): Int =
        el.attr(attr).trim().toIntOrNull()?.coerceIn(1, MAX_SPAN) ?: 1

    private fun toCell(el: Element): Cell {
        val display = cellText(el)
        // 엑셀이 만든 HTML은 서식 없는 원값을 x:num 에 남긴다. 있으면 지역화된
        // 표시 문자열을 파싱하는 것보다 정확하다 (`₩1,234`, `1.234,56` 오독 방지).
        val rawNumber = el.attr("x:num").trim()
        if (rawNumber.isNotEmpty()) {
            rawNumber.toDoubleOrNull()?.let { value ->
                return Cell(display.ifEmpty { rawNumber }, CellType.NUMBER, value)
            }
        }
        return CellTypeInference.infer(display)
    }

    /** `<br>`은 줄바꿈으로, `&nbsp;`(U+00A0)는 보통 공백으로 바꾼다. */
    private fun cellText(el: Element): String {
        val clone = el.clone()
        clone.select("br").forEach { it.replaceWith(TextNode(LINE_BREAK_MARK)) }
        return clone.text()
            .replace(' ', ' ')
            .split(LINE_BREAK_MARK)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * 시트명: MSO 블록의 진짜 시트명 -> `<caption>` -> 표 속성 -> 앞쪽으로 거슬러 올라가
     * 가장 가까운 `<h1>`~`<h6>` -> `표 N`.
     *
     * 대상 파일은 표마다 `<h2 class="x-tit2">■ 상세내역</h2>`이 바로 앞에 오고 마커 4개가
     * 표 4개와 1:1 대응한다. "직전 비어있지 않은 텍스트 줄"로 잡으면 통신요금 이월내역 표가
     * 앞선 긴 안내문을 물어서 실패하므로, 제목 태그만 인정한다.
     */
    private fun sheetName(
        table: Element,
        index: Int,
        msoName: String?,
        usedNames: MutableSet<String>,
    ): String {
        val fallback = "표 ${index + 1}"
        val raw = msoName?.takeIf { it.isNotBlank() }
            ?: table.selectFirst("caption")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: TABLE_NAME_ATTRS.asSequence()
                .map { table.attr(it).trim() }
                .firstOrNull { it.isNotEmpty() }
            ?: nearestHeading(table)
            ?: fallback

        var name = BULLET_PREFIX.replace(raw, "").replace(WHITESPACE, " ").trim()
        if (name.isEmpty()) name = fallback
        if (name.length > MAX_SHEET_NAME) name = name.take(MAX_SHEET_NAME - 1) + "…"

        var candidate = name
        var n = 2
        while (!usedNames.add(candidate)) {
            candidate = "$name ($n)"
            n++
        }
        return candidate
    }

    private fun nearestHeading(table: Element): String? {
        var node: Element? = table
        while (node != null) {
            var prev = node.previousElementSibling()
            while (prev != null) {
                headingText(prev)?.let { return it }
                prev = prev.previousElementSibling()
            }
            node = node.parent()
        }
        return null
    }

    private fun headingText(el: Element): String? {
        if (HEADING_TAG.matches(el.normalName())) {
            return el.text().trim().takeIf { it.isNotEmpty() }
        }
        // 컨테이너에 감싸인 경우 표에 가장 가까운(=마지막) 제목을 쓴다.
        return el.select("h1, h2, h3, h4, h5, h6").lastOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * 엑셀의 "웹 페이지로 저장"은 진짜 시트명을 조건부 주석 안에 남긴다:
     * `<!--[if gte mso 9]><xml><x:ExcelWorkbook>…<x:ExcelWorksheet><x:Name>Sheet1</x:Name>`
     *
     * jsoup은 조건부 주석을 Comment 노드로 두기 때문에 `select()`로는 찾히지 않는다.
     * 주석 본문을 XML로 다시 파싱해야 한다. (대상 파일에는 이 블록이 없어 h2 경로를 타지만,
     * 엑셀이 직접 만든 HTML에서는 이쪽이 가장 정확한 시트명이다.)
     */
    private fun msoSheetNames(doc: Document): List<String> {
        val comments = ArrayList<String>()
        collectComments(doc, comments)
        val names = ArrayList<String>()
        for (data in comments) {
            if (!data.contains("ExcelWorksheet", ignoreCase = true)) continue
            val parsed = runCatching { Jsoup.parse(data, "", Parser.xmlParser()) }.getOrNull() ?: continue
            parsed.select("*")
                .filter { it.tagName().substringAfter(':').equals("ExcelWorksheet", ignoreCase = true) }
                .forEach { worksheet ->
                    worksheet.children()
                        .firstOrNull { it.tagName().substringAfter(':').equals("Name", ignoreCase = true) }
                        ?.text()?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { names.add(it) }
                }
        }
        return names
    }

    private fun collectComments(node: Node, out: MutableList<String>) {
        if (node is Comment) out.add(node.data)
        for (child in node.childNodes()) collectComments(child, out)
    }

    private companion object {
        const val MAX_SPAN = 4_096
        const val MAX_SHEET_NAME = 30
        const val MAX_FALLBACK_HEADER_ROWS = 4
        const val LINE_BREAK_MARK = "\u0001"
        val TABLE_NAME_ATTRS = listOf("title", "summary", "id", "name")
        val HEADING_TAG = Regex("h[1-6]")
        val BULLET_PREFIX = Regex("""^[\s■▶◆※□•·]+""")
        val WHITESPACE = Regex("""\s+""")
    }
}
