package dev.hugo.sheetview

import dev.hugo.sheetview.format.SpreadsheetFormat
import dev.hugo.sheetview.format.SpreadsheetReaders
import dev.hugo.sheetview.model.CellType
import dev.hugo.sheetview.model.Sheet
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 이 플러그인을 만든 계기는 확장자만 `.xls`인 실제 카드 명세서였다. 그 파일 자체는
 * 개인 금융정보라 저장소에 둘 수 없으므로, **깨지는 지점만 그대로 재현한** 합성 픽스처
 * (`fixtures/statement.xls`)로 검증한다. 재현한 특성은 다음과 같다:
 *
 * - `<html` 앞에 CRLF 34바이트 (선행 공백을 건너뛰지 않으면 판별이 깨진다)
 * - MS Office 네임스페이스가 붙은 HTML 표
 * - 요약내역: `<thead>` 1행이지만 **본문 첫 행에 `<th rowspan=3>`** 이 있다
 * - 상세내역: `rowspan=2` 와 `colspan=2` 가 섞인 2단 헤더
 * - `mso-number-format` 과 `x:num` 이 한 곳도 없고 금액은 `123,450원` 같은 순수 텍스트
 *
 * 값은 전부 지어낸 것이다. 픽스처를 고칠 일이 있으면 `<thead>` 구조와 span 값을 건드리지
 * 말 것 — 그게 이 테스트들이 지키는 회귀 지점이다.
 */
class ExcelHtmlReaderTest {

    private val file: Path = Path.of("src/test/resources/fixtures/statement.xls")

    private fun book() = SpreadsheetReaders.read(file)

    @Test
    fun `확장자가 아니라 내용으로 Excel HTML로 판별한다`() {
        // 선행 CRLF 34바이트 때문에 <html 이 오프셋 0에 없다 - 공백 건너뛰기가 동작해야 한다.
        assertEquals(SpreadsheetFormat.EXCEL_HTML, SpreadsheetReaders.sniff(file).format)
    }

    @Test
    fun `시트 4개를 앞선 h2 제목에서 이름을 따 만든다`() {
        val sheets = book().sheets
        assertEquals(
            listOf("요약내역", "상세내역", "통신요금 이월내역", "해외이용금액 상세내역"),
            sheets.map { it.name },
        )
    }

    @Test
    fun `thead 기준으로 헤더 행 수를 잡는다`() {
        val sheets = book().sheets.associateBy { it.name }

        // 상세내역은 thead 안에 tr 이 2개인 2단 헤더다.
        assertEquals(2, sheets.getValue("상세내역").headerRowCount)

        // 요약내역이 회귀 지점이다. 본문 첫 행에 <th>이번달</th>(rowspan=3, 행 방향 헤더)가
        // 있어서 "th를 포함한 선두 행" 규칙이면 2가 되어 틀린다. thead 기준이면 1이다.
        assertEquals(1, sheets.getValue("요약내역").headerRowCount)
    }

    @Test
    fun `2단 헤더의 colspan과 rowspan이 열에 맞게 펼쳐진다`() {
        val detail = book().sheets.first { it.name == "상세내역" }

        // "이번 달 입금하실 금액"(colspan=2)이 원금/수수료 두 열 위에 걸쳐야 한다.
        val labels = detail.headerLabels()
        val principal = labels.indexOfFirst { it.endsWith("원금") }
        val fee = labels.indexOfFirst { it.endsWith("수수료") }
        assertTrue(principal >= 0 && fee >= 0, "원금/수수료 열을 찾지 못했다: $labels")
        assertEquals(principal + 1, fee, "원금과 수수료는 인접해야 한다")
        assertTrue(
            labels[principal].startsWith("이번 달 입금하실 금액"),
            "colspan 헤더가 합쳐지지 않았다: ${labels[principal]}",
        )

        // rowspan=2 인 헤더는 두 행에 복제되지만 중복이 제거되어야 한다.
        assertEquals("이용일", labels.first())
    }

    @Test
    fun `표 바로 앞의 안내문이 아니라 제목 태그에서 시트 이름을 딴다`() {
        // 통신요금 이월내역 표 앞에는 긴 <p> 안내문이 h2 보다 가까이 있다.
        // "직전 비어있지 않은 텍스트 줄" 규칙이면 안내문을 시트 이름으로 물어서 틀린다.
        val names = book().sheets.map { it.name }
        assertTrue("통신요금 이월내역" in names, "제목 태그에서 이름을 따지 못했다: $names")
    }

    @Test
    fun `해외이용금액 상세내역의 알려진 행이 픽스처와 일치한다`() {
        val overseas = book().sheets.first { it.name == "해외이용금액 상세내역" }
        val row = dataRow(overseas, 1)
        assertTrue("2026.03.15" in row, "이용일자가 없다: $row")
        assertTrue(row.any { it.contains("EXAMPLE STORE") }, "가맹점이 없다: $row")
        assertTrue("98,760" in row, "이용금액이 없다: $row")
    }

    @Test
    fun `서식 메타데이터가 없어도 텍스트에서 숫자와 날짜를 추론한다`() {
        val detail = book().sheets.first { it.name == "상세내역" }
        val labels = detail.headerLabels()

        val dateColumn = labels.indexOfFirst { it.contains("이용일") }
        val amountColumn = labels.indexOfFirst { it.contains("이용총액") }
        assertTrue(dateColumn >= 0 && amountColumn >= 0, "열을 찾지 못했다: $labels")

        val firstDataRow = detail.headerRowCount
        assertEquals(CellType.DATE, detail.cell(firstDataRow, dateColumn).type)

        // `123,450원` 처럼 단위가 붙은 금액도 숫자로 인식해야 한다.
        val amount = detail.cell(firstDataRow, amountColumn)
        assertEquals(CellType.NUMBER, amount.type, "금액이 숫자로 인식되지 않았다: '${amount.text}'")
        assertEquals(123_450.0, amount.number)
    }

    @Test
    fun `모든 행이 같은 열 수를 갖는 직사각형 그리드가 된다`() {
        for (sheet in book().sheets) {
            assertTrue(sheet.columnCount > 0, "${sheet.name}: 열이 없다")
            sheet.rows.forEachIndexed { index, row ->
                assertEquals(sheet.columnCount, row.size, "${sheet.name} 행 $index 의 열 수가 다르다")
            }
        }
    }

    private fun dataRow(sheet: Sheet, dataIndex: Int): List<String> =
        (0 until sheet.columnCount).map { sheet.cell(sheet.headerRowCount + dataIndex, it).text }
}
