package dev.hugo.sheetview

import dev.hugo.sheetview.format.SpreadsheetFormat
import dev.hugo.sheetview.format.SpreadsheetParseException
import dev.hugo.sheetview.format.SpreadsheetReaders
import dev.hugo.sheetview.format.UnsupportedSpreadsheetException
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 이 플러그인의 존재 이유. 기존 플러그인은 여기서 스택트레이스를 뿜고 죽는다.
 * 모든 실패는 사용자에게 보여줄 한국어 설명이 붙은 두 예외 중 하나여야 한다.
 */
class RobustnessTest {

    private fun fixture(name: String): Path = Path.of("src/test/resources/fixtures", name)

    @Test
    fun `0바이트 파일`() {
        val e = assertFailsWith<UnsupportedSpreadsheetException> { SpreadsheetReaders.read(fixture("empty.xls")) }
        assertTrue("비어" in e.userMessage, e.userMessage)
    }

    @Test
    fun `진짜 OLE2 바이너리 xls는 판별해 내고 안내한다`() {
        val path = fixture("legacy.xls")
        assertEquals(SpreadsheetFormat.LEGACY_XLS_BIFF, SpreadsheetReaders.sniff(path).format)
        val e = assertFailsWith<UnsupportedSpreadsheetException> { SpreadsheetReaders.read(path) }
        assertTrue("97-2003" in e.userMessage, e.userMessage)
        assertTrue(e.hint!!.contains("xlsx"), "대안을 안내해야 한다")
    }

    @Test
    fun `깨진 ZIP`() {
        val e = assertFailsWith<SpreadsheetParseException> { SpreadsheetReaders.read(fixture("corrupt.xlsx")) }
        assertTrue("ZIP" in e.userMessage, e.userMessage)
    }

    @Test
    fun `xlsb는 ZIP이지만 구분해서 안내한다`() {
        // .xlsb 도 PK 시그니처에 걸리므로 workbook.bin 을 보고 갈라내야 한다.
        val e = assertFailsWith<UnsupportedSpreadsheetException> { SpreadsheetReaders.read(fixture("binary.xlsb")) }
        assertTrue("xlsb" in e.userMessage, e.userMessage)
    }

    @Test
    fun `스프레드시트가 아닌 ZIP`() {
        val e = assertFailsWith<UnsupportedSpreadsheetException> { SpreadsheetReaders.read(fixture("notsheet.xlsx")) }
        assertTrue("워크북이 아닙니다" in e.userMessage, e.userMessage)
    }

    @Test
    fun `표가 없는 HTML`() {
        val path = fixture("no-table.xls")
        // 선행 CRLF 가 있어도 HTML 로 판별해야 한다.
        assertEquals(SpreadsheetFormat.EXCEL_HTML, SpreadsheetReaders.sniff(path).format)
        val e = assertFailsWith<UnsupportedSpreadsheetException> { SpreadsheetReaders.read(path) }
        assertTrue("표" in e.userMessage, e.userMessage)
    }
}
