package dev.hugo.sheetview

import dev.hugo.sheetview.format.SpreadsheetFormat
import dev.hugo.sheetview.format.SpreadsheetReaders
import dev.hugo.sheetview.model.CellType
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XlsxReaderTest {

    private fun fixture(name: String): Path = Path.of("src/test/resources/fixtures", name)

    @Test
    fun `시트 순서와 경로를 파일 번호가 아니라 rels로 해석한다`() {
        // 탭 순서는 둘째 -> 첫째 인데 파일은 sheet1=첫째, sheet2=둘째 로 어긋나 있다.
        // 파일명 번호를 믿으면 순서가 뒤집히는 xlsx 리더의 대표적 버그.
        val book = SpreadsheetReaders.read(fixture("sheet-order.xlsx"))
        assertEquals(listOf("둘째", "첫째"), book.sheets.map { it.name })
        assertEquals("둘째시트값", book.sheets[0].cell(0, 0).text)
    }

    @Test
    fun `공유 문자열의 리치 텍스트 런을 이어붙이고 공백을 보존한다`() {
        val sheet = SpreadsheetReaders.read(fixture("sheet-order.xlsx")).sheets[1]
        assertEquals("가맹점", sheet.cell(0, 0).text)
        // <r><t>LG </t></r><r><t>U+ 통신요금</t></r> -> 하나의 문자열
        assertEquals("LG U+ 통신요금", sheet.cell(0, 1).text)
        assertEquals("인라인", sheet.cell(0, 2).text)
        // xml:space="preserve" 인 문자열은 앞뒤 공백이 남아야 한다.
        assertEquals(" 앞뒤공백 ", sheet.cell(2, 1).text)
    }

    @Test
    fun `numFmt으로 날짜와 숫자를 구분한다`() {
        val sheet = SpreadsheetReaders.read(fixture("sheet-order.xlsx")).sheets[1]

        // 내장 서식 14 -> 날짜
        assertEquals(CellType.DATE, sheet.cell(1, 0).type)
        assertEquals("2024-01-01", sheet.cell(1, 0).text)
        // 커스텀 서식 yyyy"년" m"월" d"일" -> 날짜
        assertEquals(CellType.DATE, sheet.cell(1, 2).type)
        assertEquals("2024-01-01", sheet.cell(1, 2).text)

        // 내장 서식 4 (#,##0.00) 는 날짜가 아니다
        assertEquals(CellType.NUMBER, sheet.cell(2, 0).type)
        assertEquals(1234.5, sheet.cell(2, 0).number)

        // 커스텀 서식 "May" #,##0 -> 따옴표 리터럴을 걷어내야 날짜로 오인하지 않는다.
        // (리터럴 제거를 빼먹으면 "May" 의 y 때문에 날짜가 된다.)
        assertEquals(CellType.NUMBER, sheet.cell(2, 2).type)
    }

    @Test
    fun `희소 행과 희소 열을 절대 위치로 채운다`() {
        val sheet = SpreadsheetReaders.read(fixture("sheet-order.xlsx")).sheets[1]

        // 파일에는 r=1,2,3,10 네 행만 있지만 엑셀 행 번호를 맞추려면 10행이어야 한다.
        assertEquals(10, sheet.rows.size)
        assertEquals(3, sheet.columnCount)

        // 생략된 4~9 행은 빈 행으로 채워진다.
        for (r in 3..8) {
            assertTrue(
                (0 until sheet.columnCount).all { sheet.cell(r, it).isBlank },
                "행 ${r + 1} 은 비어 있어야 한다",
            )
        }
        // r=10 의 값이 마지막 행에 온다. B 열에만 값이 있고 A 는 생략(희소 열)됐다.
        assertTrue(sheet.cell(9, 0).isBlank)
        assertEquals(42.0, sheet.cell(9, 1).number)
        assertEquals("TRUE", sheet.cell(9, 2).text)

        // 희소 열: 2행은 A 와 C 에만 값이 있고 B 는 비어 있다.
        assertTrue(sheet.cell(1, 1).isBlank)
    }

    @Test
    fun `행 상한을 넘으면 자르되 끝까지 세어 보고한다`() {
        val book = SpreadsheetReaders.read(fixture("big.xlsx"))
        val sheet = book.sheets.single()
        assertEquals(50_000, sheet.rows.size)
        assertTrue(sheet.truncated, "잘렸다고 표시되어야 한다")
        assertEquals(60_000, sheet.totalRowCount, "원본 행 수를 끝까지 세어야 배너에 쓸 수 있다")
    }

    @Test
    fun `mso-application PI로 Excel 2003 XML을 판별하고 병합을 펼친다`() {
        val path = fixture("sml2003.xml")
        // 이 파일에는 urn: 스프레드시트 네임스페이스 선언이 루트에 없고 PI 만 있다.
        assertEquals(SpreadsheetFormat.SPREADSHEET_ML, SpreadsheetReaders.sniff(path).format)

        val sheet = SpreadsheetReaders.read(path).sheets.single()
        assertEquals("명세", sheet.name)
        // MergeAcross="1" -> 금액이 두 열에 걸친다
        assertEquals("금액", sheet.cell(0, 1).text)
        assertEquals("금액", sheet.cell(0, 2).text)
        assertEquals(1500.0, sheet.cell(1, 1).number)
        // ss:Index="5" -> 3,4 행은 비고 5행에 값이 온다
        assertEquals(5, sheet.rows.size)
        assertEquals("건너뛴행", sheet.cell(4, 0).text)
    }

    @Test
    fun `CP949 CSV의 인코딩과 구분자를 감지한다`() {
        val book = SpreadsheetReaders.read(fixture("cp949.csv"))
        val sheet = book.sheets.single()
        assertTrue(book.charsetName!!.contains("949"), "CP949로 읽어야 한다: ${book.charsetName}")
        assertEquals("가맹점", sheet.cell(0, 0).text)
        assertEquals("홍길동커피", sheet.cell(1, 0).text)
        // 따옴표로 감싼 "1,500" 은 하나의 필드이고 숫자로 인식된다.
        assertEquals(1500.0, sheet.cell(1, 1).number)
        assertEquals(CellType.DATE, sheet.cell(1, 2).type)
    }
}
