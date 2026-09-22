package dev.hugo.sheetview

import dev.hugo.sheetview.source.ZipParts
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `.xlsx` 는 사실 ZIP 이다. 원본 탭은 바이너리 워크북에서 "원본"을 보여줄 방법이 이것뿐이라
 * 내부 파트 목록과 선택한 파트의 XML 원문을 낸다.
 *
 * 목록 **순서를 고정하는 것**이 이 테스트의 핵심이다. ZIP 엔트리의 물리 순서는 만든 도구마다
 * 다르고 같은 도구도 매번 같지 않다. 순서를 파일에 맡기면 사용자는 열 때마다 다른 목록을 본다.
 * 그래서 `shuffled-parts.xlsx` 는 일부러 역순으로 기록돼 있다.
 */
class ZipPartsTest {

    private fun fixture(name: String): Path = Path.of("src/test/resources/fixtures/$name")

    @Test
    fun `물리 순서가 뒤집혀 있어도 읽는 순서대로 정렬한다`() {
        // 픽스처는 [Content_Types].xml 이 맨 뒤에, sheet3 이 맨 앞에 기록돼 있다.
        val names = ZipParts.list(fixture("shuffled-parts.xlsx")).parts.map { it.name }
        assertEquals(
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml",
                "xl/worksheets/sheet2.xml",
                "xl/worksheets/sheet3.xml",
                "xl/worksheets/sheet10.xml",
                "xl/sharedStrings.xml",
                "xl/styles.xml",
                "docProps/core.xml",
            ),
            names,
        )
    }

    @Test
    fun `시트는 사전순이 아니라 숫자순이다`() {
        val sheets = ZipParts.list(fixture("shuffled-parts.xlsx")).parts
            .map { it.name }
            .filter { it.startsWith("xl/worksheets/") }
        // 사전순이면 sheet10 이 sheet2 앞에 온다. 엑셀의 시트 순서와 어긋나 헷갈린다.
        assertTrue(
            sheets.indexOf("xl/worksheets/sheet2.xml") < sheets.indexOf("xl/worksheets/sheet10.xml"),
            "sheet10 이 sheet2 보다 앞이다: $sheets",
        )
    }

    @Test
    fun `디렉터리 엔트리는 목록에 넣지 않는다`() {
        // 픽스처에 "xl/worksheets/" 빈 엔트리를 일부러 넣어 두었다.
        val names = ZipParts.list(fixture("shuffled-parts.xlsx")).parts.map { it.name }
        assertFalse(names.any { it.endsWith("/") }, "디렉터리가 섞였다: $names")
    }

    @Test
    fun `압축 전 크기를 알려준다`() {
        val part = ZipParts.list(fixture("sheet-order.xlsx")).parts
            .single { it.name == "xl/workbook.xml" }
        // 압축된 크기가 아니라 원본 크기여야 "이 XML 이 얼마나 큰가"가 맞는다.
        assertEquals(315L, part.size)
    }

    @Test
    fun `깨진 ZIP 은 예외가 아니라 빈 목록과 사유로 끝난다`() {
        val listing = ZipParts.list(fixture("corrupt.xlsx"))
        assertTrue(listing.parts.isEmpty(), "깨진 파일에서 파트가 나왔다")
        assertNotNull(listing.problem, "사유가 없으면 사용자는 빈 화면만 본다")
    }

    @Test
    fun `0바이트 파일도 예외 없이 사유로 끝난다`() {
        val listing = ZipParts.list(fixture("empty.xls"))
        assertTrue(listing.parts.isEmpty())
        assertNotNull(listing.problem)
    }

    @Test
    fun `xlsx 가 아닌 ZIP 도 목록은 보여준다`() {
        // notsheet.xlsx 는 word/document.xml 하나뿐인 ZIP 이다. 표로는 못 읽지만
        // "안에 뭐가 들었는지"는 보여줄 수 있어야 사용자가 상황을 판단한다.
        val listing = ZipParts.list(fixture("notsheet.xlsx"))
        assertNull(listing.problem)
        assertEquals(listOf("word/document.xml"), listing.parts.map { it.name })
    }

    @Test
    fun `파트 원문을 읽는다`() {
        val content = ZipParts.read(fixture("sheet-order.xlsx"), "xl/workbook.xml", 1 shl 20)
        assertNotNull(content)
        assertTrue(content.text.contains("<workbook"), "XML 원문이 아니다: ${content.text.take(80)}")
        assertFalse(content.truncated)
    }

    @Test
    fun `없는 파트는 null 이다`() {
        assertNull(ZipParts.read(fixture("sheet-order.xlsx"), "xl/없는파트.xml", 1 shl 20))
    }

    @Test
    fun `상한을 넘는 파트는 잘라서 주고 잘랐다고 알린다`() {
        // big.xlsx 의 시트는 3MB다. 뷰어가 통째로 올리면 메모리도 문제지만
        // 한 줄짜리 3MB XML 을 에디터에 넣는 순간 UI 가 멈춘다.
        val content = ZipParts.read(fixture("big.xlsx"), "xl/worksheets/sheet1.xml", 64 * 1024)
        assertNotNull(content)
        assertTrue(content.truncated, "3MB 파트가 잘리지 않았다")
        assertTrue(content.text.length <= 64 * 1024, "상한을 넘겼다: ${content.text.length}")
    }

    @Test
    fun `바이너리 파트는 텍스트가 아니라고 알려준다`() {
        // .xlsb 의 시트는 XML 이 아니라 바이너리다. 그대로 에디터에 넣으면 깨진 글자만 뜨고
        // 사용자는 플러그인이 고장난 줄 안다. 읽는 쪽에서 미리 알려줘야 한다.
        val bin = ZipParts.read(fixture("binary.xlsb"), "xl/workbook.bin", 1 shl 20)
        assertNotNull(bin)
        assertTrue(bin.looksBinary, "바이너리 파트를 텍스트로 판단했다")

        val xml = ZipParts.read(fixture("binary.xlsb"), "[Content_Types].xml", 1 shl 20)
        assertNotNull(xml)
        assertFalse(xml.looksBinary, "XML 파트를 바이너리로 판단했다")
    }

    @Test
    fun `깨진 ZIP 에서 파트를 읽어도 예외가 아니라 null 이다`() {
        assertNull(ZipParts.read(fixture("corrupt.xlsx"), "xl/workbook.xml", 1 shl 20))
    }
}
