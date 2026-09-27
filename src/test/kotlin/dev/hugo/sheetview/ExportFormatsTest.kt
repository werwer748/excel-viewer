package dev.hugo.sheetview

import dev.hugo.sheetview.export.ExportFormat
import dev.hugo.sheetview.export.ExportFormats
import dev.hugo.sheetview.format.SpreadsheetReaders
import dev.hugo.sheetview.model.Cell
import dev.hugo.sheetview.model.CellType
import dev.hugo.sheetview.model.Sheet
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TSV 는 원래 "시트 전체 복사"의 클립보드 포맷이었고, 파일 내보내기 목록에도 올린다.
 *
 * TSV 에는 따옴표 이스케이프가 없다. 필드 안의 탭·줄바꿈은 그대로 두면 열과 행이 밀리므로
 * 공백으로 바꾸는 것 말고는 방법이 없다. `ExcelHtmlReader` 가 `<br>` 을 줄바꿈으로 바꾸므로
 * 다단 헤더에 줄바꿈이 들어올 수 있다 — 헤더 라벨도 셀과 똑같이 다뤄야 한다. CSV 리더는
 * 따옴표 필드 안의 CRLF 를 셀에 그대로 담으므로 `\r` 도 실제로 들어온다.
 * (CSV 리더의 클래스 이름은 여기 적지 않는다 — 미커버 래칫은 이름 언급만으로 커버로 친다.)
 */
class ExportFormatsTest {

    private fun sheet(vararg rows: List<String>, headerRows: Int = 1): Sheet {
        val cells = rows.map { row -> row.map { if (it.isEmpty()) Cell.BLANK else Cell(it, CellType.TEXT) } }
        return Sheet("s", cells, headerRows, rows.maxOf { it.size }, cells.size, truncated = false)
    }

    private fun lines(text: String) = text.removeSuffix("\n").split('\n')

    @Test
    fun `TSV 가 내보내기 형식 목록에 있다`() {
        assertTrue(ExportFormat.TSV in ExportFormat.entries)
        assertEquals("TSV", ExportFormat.TSV.label)
        assertEquals("tsv", ExportFormat.TSV.extension)
    }

    @Test
    fun `TSV 로 렌더링하면 클립보드 복사와 같은 내용이 나온다`() {
        val s = sheet(listOf("이름", "금액"), listOf("커피", "4500"))
        assertEquals(ExportFormats.toTsv(s, useHeader = true), ExportFormats.render(s, ExportFormat.TSV, useHeader = true))
        assertEquals("이름\t금액\n커피\t4500\n", ExportFormats.render(s, ExportFormat.TSV, useHeader = true))
    }

    @Test
    fun `Excel HTML 헤더의 br 은 리더를 거쳐도 TSV 한 줄 헤더가 된다`(@TempDir dir: Path) {
        // 합성 Sheet 가 아니라 실제 리더 -> headerLabels -> toTsv 경로를 고정한다.
        val file = dir.resolve("br-header.xls")
        Files.writeString(
            file,
            """
            <html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel">
            <head><meta http-equiv="Content-Type" content="text/html; charset=utf-8"></head>
            <body><table>
            <thead><tr><th>이번 달<br>입금하실 금액</th><th>비고</th></tr></thead>
            <tbody><tr><td>1,000원</td><td>x</td></tr></tbody>
            </table></body></html>
            """.trimIndent(),
        )
        val s = SpreadsheetReaders.read(file).sheets.single()
        assertEquals(listOf("이번 달 입금하실 금액\t비고", "1,000원\tx"), lines(ExportFormats.toTsv(s, useHeader = true)))
    }

    @Test
    fun `헤더 라벨에 줄바꿈이 있어도 한 줄로 쓴다`() {
        val s = sheet(listOf("이번 달\n입금하실 금액", "비고"), listOf("1000", "x"))
        val out = lines(ExportFormats.toTsv(s, useHeader = true))
        assertEquals(listOf("이번 달 입금하실 금액\t비고", "1000\tx"), out)
    }

    @Test
    fun `헤더 라벨에 탭이 있어도 열이 밀리지 않는다`() {
        val s = sheet(listOf("a\tb", "c"), listOf("1", "2"))
        assertEquals("a b\tc", lines(ExportFormats.toTsv(s, useHeader = true)).first())
    }

    @Test
    fun `셀 안의 CRLF 와 CR 도 공백 하나가 된다`() {
        val s = sheet(listOf("h1", "h2"), listOf("a\r\nb", "c\rd"))
        assertEquals("a b\tc d", lines(ExportFormats.toTsv(s, useHeader = true))[1])
    }

    @Test
    fun `셀 안의 탭과 LF 는 공백이 된다`() {
        val s = sheet(listOf("h1", "h2"), listOf("a\tb", "c\nd"))
        assertEquals(listOf("h1\th2", "a b\tc d"), lines(ExportFormats.toTsv(s, useHeader = true)))
    }

    @Test
    fun `헤더를 끄면 헤더 행도 데이터 행으로 나간다`() {
        val s = sheet(listOf("이름", "금액"), listOf("커피", "4500"))
        assertEquals("이름\t금액\n커피\t4500\n", ExportFormats.toTsv(s, useHeader = false))
    }

    @Test
    fun `빈 셀은 빈 필드로 남아 열 수가 유지된다`() {
        val s = sheet(listOf("a", "b", "c"), listOf("1", "", "3"), listOf("", "", ""))
        val out = lines(ExportFormats.toTsv(s, useHeader = true))
        assertEquals(listOf("a\tb\tc", "1\t\t3", "\t\t"), out)
        assertTrue(out.all { it.count { ch -> ch == '\t' } == 2 })
    }

    @Test
    fun `Markdown 셀의 CRLF 와 CR 도 br 하나가 되어 표 행이 갈라지지 않는다`() {
        // GFM 은 단독 CR 도 줄 끝으로 본다. LF 만 바꾸면 "첫줄\r<br>둘째줄" 이 나와 행이 둘로 갈린다.
        val s = sheet(listOf("메모", "비고"), listOf("첫줄\r\n둘째줄", "a\rb"))
        val out = lines(ExportFormats.toMarkdown(s, useHeader = true))
        assertEquals("| 첫줄<br>둘째줄 | a<br>b |", out[2])
        assertTrue(out.none { '\r' in it })
    }

    @Test
    fun `CSV 따옴표 필드 안의 CRLF 는 리더를 거쳐도 TSV 한 줄이 된다`(@TempDir dir: Path) {
        val file = dir.resolve("crlf.csv")
        Files.writeString(file, "h1,h2\r\n\"a\r\nb\",c\r\n")
        val s = SpreadsheetReaders.read(file).sheets.single()
        assertEquals(listOf("h1\th2", "a b\tc"), lines(ExportFormats.toTsv(s, useHeader = true)))
    }

    @Test
    fun `Markdown 셀의 파이프는 이스케이프한다`() {
        val s = sheet(listOf("a|b"), listOf("c|d"))
        assertEquals(listOf("| a\\|b |", "| --- |", "| c\\|d |"), lines(ExportFormats.toMarkdown(s, useHeader = true)))
    }

    @Test
    fun `CSV 는 쉼표 따옴표 줄바꿈이 든 필드만 따옴표로 감싼다`() {
        val s = sheet(listOf("h"), listOf("a,b"), listOf("say \"hi\""), listOf("x\ny"), listOf("plain"))
        assertEquals("h\n\"a,b\"\n\"say \"\"hi\"\"\"\n\"x\ny\"\nplain\n", ExportFormats.toCsv(s, useHeader = true))
    }

    @Test
    fun `JSON 은 숫자 셀을 숫자로 내고 제어 문자를 이스케이프한다`() {
        val cells = listOf(
            listOf(Cell("이름", CellType.TEXT), Cell("금액", CellType.TEXT)),
            listOf(Cell("탭\t줄\n\u0001", CellType.TEXT), Cell("4,500", CellType.NUMBER, 4500.0)),
        )
        val s = Sheet("s", cells, 1, 2, 2, truncated = false)
        assertEquals("[\n  {\"이름\": \"탭\\t줄\\n\\u0001\", \"금액\": 4500}\n]\n", ExportFormats.toJson(s, useHeader = true))
    }
}
