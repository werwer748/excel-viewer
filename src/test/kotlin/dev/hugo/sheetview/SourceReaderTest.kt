package dev.hugo.sheetview

import dev.hugo.sheetview.format.SpreadsheetFormat
import dev.hugo.sheetview.source.SourceDocument
import dev.hugo.sheetview.source.SourceMode
import dev.hugo.sheetview.source.SourceReader
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 원본 탭이 그릴 것을 전부 만들어 내는 단계. 표 뷰와 **같은 판별**(`SpreadsheetSniffer`)을
 * 쓰되, 결과를 표가 아니라 "사람이 볼 원본"으로 바꾼다.
 *
 * 포맷마다 원본의 의미가 다르다는 것이 설계의 전부다:
 *   HTML 위장 .xls -> 렌더된 미리보기 + 마크업        (PREVIEW, TEXT)
 *   CSV / 2003 XML -> 감지한 인코딩으로 디코드한 본문  (TEXT)
 *   진짜 .xlsx      -> ZIP 내부 파트 목록              (PARTS)
 *   BIFF / 불명     -> 16진수 덤프 + 왜 못 읽는지 안내 (TEXT)
 *
 * 어떤 입력에도 예외를 던지지 않는다. 실패는 전부 `notice` 로 끝난다.
 */
class SourceReaderTest {

    private fun fixture(name: String) = Path.of("src/test/resources/fixtures/$name")

    private fun read(name: String): SourceDocument = SourceReader.read(fixture(name))

    // ---------------------------------------------------------------- HTML 위장 .xls

    @Test
    fun `HTML 위장 xls 는 미리보기가 기본이고 소스로 전환할 수 있다`() {
        val doc = read("statement.xls")
        assertEquals(SpreadsheetFormat.EXCEL_HTML, doc.format)
        assertEquals(listOf(SourceMode.PREVIEW, SourceMode.TEXT), doc.modes)
        assertEquals("UTF-8", doc.charsetName)
        assertEquals("HTML", doc.highlightTypeName)
        // 실제 줄바꿈이 있는 문서라 소프트랩은 방해만 된다.
        assertFalse(doc.softWrap)
    }

    @Test
    fun `미리보기 HTML 은 정화를 거친 것이다`() {
        val doc = read("statement.xls")
        val html = assertNotNull(doc.previewHtml)
        // 정화를 건너뛰면 다음 달 파일의 스크립트가 IDE 안 Chromium 에서 그대로 돈다.
        assertTrue(html.contains("Content-Security-Policy"), "CSP 가 없다")
        // 동시에 원본처럼 보여야 한다 - 스타일과 표가 살아 있어야 한다.
        assertTrue(html.contains("<style", ignoreCase = true), "스타일이 사라졌다")
        assertTrue(html.contains("요약내역"), "본문이 사라졌다")
        // IDE 가 다크 테마여도 원본은 흰 바탕에 검은 글자로 본다.
        // 이 파일은 color:windowtext 를 쓰는데, 고정하지 않으면 다크에서 글자가 사라진다.
        assertTrue(html.contains("only light"), "라이트 고정이 실려 오지 않았다")
        assertFalse(html.contains("windowtext"), "시스템 컬러가 그대로 남았다")
    }

    @Test
    fun `소스 본문은 원본 바이트 그대로이며 줄 수가 원본과 같다`() {
        val doc = read("statement.xls")
        val raw = Files.readString(fixture("statement.xls"))
        // 줄번호로 원본과 대조하는 것이 이 탭의 용도라 줄이 하나라도 어긋나면 안 된다.
        assertEquals(raw.lines().size, doc.text.lines().size)
        assertTrue(doc.text.startsWith(raw.take(40)), "앞부분이 다르다")
    }

    @Test
    fun `표가 없는 HTML 도 원본은 보여준다`() {
        // 표 탭은 "표를 못 찾았다"로 끝나지만 원본 탭은 볼 것이 있다.
        val doc = read("no-table.xls")
        assertTrue(SourceMode.TEXT in doc.modes)
        assertTrue(doc.text.contains("표가 없는 문서"))
    }

    // ---------------------------------------------------------------- 텍스트 포맷

    @Test
    fun `CP949 CSV 를 한글이 깨지지 않게 디코드한다`() {
        val doc = read("cp949.csv")
        assertEquals(listOf(SourceMode.TEXT), doc.modes)
        assertTrue(doc.charsetName!!.contains("949") || doc.charsetName!!.contains("EUC"),
            "인코딩이 CP949 계열이 아니다: ${doc.charsetName}")
        assertTrue(doc.text.contains("가맹점"), "한글이 깨졌다: ${doc.text.take(40)}")
        assertTrue(doc.text.contains("홍길동커피"), "한글이 깨졌다")
    }

    @Test
    fun `Excel 2003 XML 은 XML 로 강조한다`() {
        val doc = read("sml2003.xml")
        assertEquals(SpreadsheetFormat.SPREADSHEET_ML, doc.format)
        assertEquals("XML", doc.highlightTypeName)
        assertEquals(listOf(SourceMode.TEXT), doc.modes)
        // 브라우저로 렌더링할 내용이 아니다. 미리보기를 주면 XML 트리만 보여 헷갈린다.
        assertNull(doc.previewHtml)
    }

    // ---------------------------------------------------------------- 바이너리

    @Test
    fun `진짜 xlsx 는 ZIP 파트 목록을 보여준다`() {
        val doc = read("sheet-order.xlsx")
        assertEquals(listOf(SourceMode.PARTS), doc.modes)
        assertEquals("xl/workbook.xml", doc.parts.map { it.name }.first { it.startsWith("xl/workbook") })
        // OOXML 시트는 보통 한 줄짜리 수 MB라 소프트랩 없이는 읽을 수 없다.
        assertTrue(doc.softWrap)
        assertEquals("XML", doc.highlightTypeName)
        assertNull(doc.charsetName, "바이너리 컨테이너에 문서 인코딩은 없다")
    }

    @Test
    fun `BIFF xls 는 16진수 덤프와 왜 못 읽는지를 함께 준다`() {
        val doc = read("legacy.xls")
        assertEquals(SpreadsheetFormat.LEGACY_XLS_BIFF, doc.format)
        assertEquals(listOf(SourceMode.TEXT), doc.modes)
        val first = doc.text.lineSequence().first()
        // OLE2 시그니처가 첫 줄에 그대로 보여야 "왜 엑셀 파일인데 못 읽나"가 설명된다.
        assertEquals(
            "00000000  D0 CF 11 E0 A1 B1 1A E1  00 00 00 00 00 00 00 00  |................|",
            first,
        )
        assertNotNull(doc.notice, "안내가 없으면 사용자는 16진수만 보고 영문을 모른다")
        assertNull(doc.highlightTypeName, "덤프에 구문 강조를 주면 엉뚱하게 칠해진다")
    }

    @Test
    fun `16진수 덤프는 줄이 덜 차도 ASCII 칸이 어긋나지 않는다`() {
        val temp = Files.createTempFile("sheetview-dump-", ".bin")
        try {
            // OLE2 시그니처 8바이트 + 19바이트 = 27바이트. 둘째 줄이 11바이트로 덜 찬다.
            val bytes = byteArrayOf(-48, -49, 17, -32, -95, -79, 26, -31) +
                ByteArray(19) { (0x41 + it).toByte() }
            Files.write(temp, bytes)
            val lines = SourceReader.read(temp).text.lines().filter { it.isNotBlank() }
            assertEquals(2, lines.size)
            assertTrue(lines[0].startsWith("00000000  D0 CF 11 E0 A1 B1 1A E1  41 42 43 44 45 46 47 48  |"))
            assertTrue(lines[1].startsWith("00000010  49 4A 4B 4C 4D 4E 4F 50  51 52 53 "))
            // 마지막 줄이 덜 차도 ASCII 칸의 시작 위치는 같아야 눈으로 대조가 된다.
            assertEquals(
                lines[0].indexOf('|'),
                lines[1].indexOf('|'),
                "ASCII 칸이 어긋났다:\n${lines[0]}\n${lines[1]}",
            )
            assertTrue(lines[1].endsWith("|IJKLMNOPQRS|"), "마지막 줄 ASCII: ${lines[1]}")
        } finally {
            temp.deleteIfExists()
        }
    }

    @Test
    fun `xlsb 는 파트 목록으로 보여주되 표로는 못 읽는다고 알린다`() {
        val doc = read("binary.xlsb")
        assertTrue(SourceMode.PARTS in doc.modes)
        assertTrue(doc.parts.any { it.name == "xl/workbook.bin" })
        assertNotNull(doc.notice, "xlsb 는 표 탭이 못 읽으므로 원본 탭이 이유를 말해야 한다")
    }

    // ---------------------------------------------------------------- 죽지 않기

    @Test
    fun `깨진 ZIP 은 예외 대신 안내로 끝난다`() {
        val doc = read("corrupt.xlsx")
        assertNotNull(doc.notice)
        assertTrue(doc.parts.isEmpty())
    }

    @Test
    fun `0바이트 파일도 예외 대신 안내로 끝난다`() {
        val doc = read("empty.xls")
        assertNotNull(doc.notice)
        assertEquals(0L, doc.byteSize)
    }

    @Test
    fun `상한을 넘는 텍스트는 잘라서 주고 잘랐다고 알린다`() {
        val temp = Files.createTempFile("sheetview-big-", ".csv")
        try {
            val line = "값,".repeat(40) + "\n"
            Files.newBufferedWriter(temp).use { w ->
                repeat(SourceReader.MAX_TEXT_BYTES / line.length + 100) { w.write(line) }
            }
            val doc = SourceReader.read(temp)
            assertTrue(doc.truncated, "${Files.size(temp)}바이트가 잘리지 않았다")
            assertTrue(doc.text.length <= SourceReader.MAX_TEXT_BYTES)
            assertNotNull(doc.notice)
        } finally {
            temp.deleteIfExists()
        }
    }

    @Test
    fun `미리보기 상한을 넘는 HTML 은 소스만 보여준다`() {
        // 미리보기는 jsoup DOM 을 한 벌 더 만든다. 수 MB짜리 HTML 에서 이걸 돌리면
        // 본문 문자열 + DOM 으로 메모리가 몇 배로 뛰고 렌더도 오래 멈춘다.
        val temp = Files.createTempFile("sheetview-preview-", ".xls")
        try {
            val row = "<tr><td>값</td><td>1,234</td></tr>\n"
            Files.newBufferedWriter(temp).use { w ->
                w.write("<html><body><table>\n")
                repeat(SourceReader.MAX_PREVIEW_BYTES / row.toByteArray().size + 200) { w.write(row) }
                w.write("</table></body></html>\n")
            }
            val doc = SourceReader.read(temp)
            assertEquals(listOf(SourceMode.TEXT), doc.modes, "상한을 넘었는데 미리보기를 만들었다")
            assertNull(doc.previewHtml)
            assertNotNull(doc.notice, "왜 미리보기가 없는지 말해야 한다")
            // 소스는 여전히 볼 수 있어야 한다 - 미리보기를 포기한 것이지 파일을 포기한 것이 아니다.
            assertTrue(doc.text.contains("<table"), "소스까지 사라졌다")
        } finally {
            temp.deleteIfExists()
        }
    }

    @Test
    fun `취소 신호를 존중한다`() {
        // 탭을 닫았는데 읽기가 계속되면 IDE 가 느려진다. 취소는 삼키지 않고 그대로 나가야 한다.
        val boom = object : RuntimeException("취소됨") {}
        val thrown = runCatching {
            SourceReader.read(fixture("big.xlsx"), checkCancelled = { throw boom })
        }.exceptionOrNull()
        assertEquals(boom, thrown)
    }
}
