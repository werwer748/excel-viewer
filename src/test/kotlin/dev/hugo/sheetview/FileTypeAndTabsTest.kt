package dev.hugo.sheetview

import com.intellij.openapi.fileEditor.ex.FileEditorProviderManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.hugo.sheetview.editor.SheetEditorProvider
import dev.hugo.sheetview.editor.SourceEditorProvider
import dev.hugo.sheetview.filetype.TextFileTypes

/**
 * 파일 타입과 **실제로 붙는 탭**을 고정하는 회귀 테스트.
 *
 * 이 파일이 존재하는 이유 — 순수 단위 테스트로는 잡히지 않는 실수를 두 번 했다:
 *
 * 1. `<fileType extensions="xls;…">` 를 등록해서 내가 붙인 `fileTypeDetector` 를
 *    내 손으로 가렸다고 판단하고 `extensions` 를 지웠다. 그런데 측정해 보니 **반대**였다:
 *    `extensions` 가 없으면 `.xls` 는 번들 grid 플러그인의 `Data File` 타입이 된다.
 *    그 타입은 `FileTypeIdentifiableByVirtualFile` 이라 내용 기반 탐지기보다 먼저 평가되고,
 *    내 탐지기는 영원히 호출되지 않는다. `extensions` 를 놓으면 파일 타입을 남에게 넘기는 것이다.
 * 2. 그래서 "HTML 위장 .xls 는 플랫폼이 HTML 탭을 만들어 줄 것"이라고 설명했는데 틀렸다.
 *    어느 경우든 파일 타입이 binary 라 텍스트 에디터는 붙지 않는다. 원본은 직접 보여줘야 한다.
 *
 * 여기서만 `BasePlatformTestCase`(JUnit3 계열)를 쓴다 — `FileTypeRegistry` 와
 * `FileEditorProviderManager` 가 필요하기 때문이다. 메서드 이름이 `test` 로 시작해야 발견된다.
 * 나머지 테스트는 플랫폼 없이 도는 순수 JUnit5 다.
 */
class FileTypeAndTabsTest : BasePlatformTestCase() {

    override fun getTestDataPath(): String = "src/test/resources"

    fun `test HTML 위장 xls 도 내 Spreadsheet 타입으로 잡힌다`() {
        val file = myFixture.copyFileToProject("fixtures/statement.xls", "statement.xls")
        // plugin.xml 의 extensions 를 지우면 여기가 "Data File" 로 바뀌며 깨진다.
        assertEquals("Spreadsheet", file.fileType.name)
    }

    fun `test 진짜 워크북 xlsx 도 Spreadsheet 타입이고 바이너리다`() {
        val file = myFixture.copyFileToProject("fixtures/sheet-order.xlsx", "book.xlsx")
        assertEquals("Spreadsheet", file.fileType.name)
        // 바이너리여야 플랫폼이 워크북 바이트를 텍스트 탭에 쏟아붓지 않는다.
        assertTrue(file.fileType.isBinary)
    }

    fun `test 스프레드시트에는 표 탭과 원본 탭이 둘 다 붙는다`() {
        val file = myFixture.copyFileToProject("fixtures/statement.xls", "tabs.xls")
        val ids = FileEditorProviderManager.getInstance()
            .getProviderList(project, file)
            .map { it.editorTypeId }
        assertTrue("표 탭이 없다: $ids", "sheetview.table" in ids)
        assertTrue("원본 탭이 없다: $ids", "sheetview.source" in ids)
        // 표가 먼저 선택되어야 한다.
        assertTrue("표 탭이 원본 탭보다 앞에 와야 한다: $ids",
            ids.indexOf("sheetview.table") < ids.indexOf("sheetview.source"))
    }

    fun `test 스프레드시트가 아닌 파일에는 내 탭이 붙지 않는다`() {
        val file = myFixture.configureByText("plain.txt", "hello").virtualFile
        val ids = FileEditorProviderManager.getInstance()
            .getProviderList(project, file)
            .map { it.editorTypeId }
        assertFalse("txt 를 가로채면 안 된다: $ids", ids.any { it.startsWith("sheetview.") })
    }

    fun `test 표 탭과 원본 탭은 정확히 같은 파일을 받는다`() {
        // 확장자 집합이 갈라지면 어떤 파일은 표만, 어떤 파일은 원본만 열려 사용자가 혼란스럽다.
        val sheet = SheetEditorProvider()
        val source = SourceEditorProvider()
        assertEquals("sheetview.source", source.editorTypeId)

        val cases = listOf(
            myFixture.copyFileToProject("fixtures/statement.xls", "both.xls") to true,
            myFixture.copyFileToProject("fixtures/sheet-order.xlsx", "both.xlsx") to true,
            myFixture.configureByText("both.txt", "x").virtualFile to false,
            myFixture.configureByText("both.csv", "a,b").virtualFile to false,
            myFixture.configureByText("both.html", "<table></table>").virtualFile to false,
        )
        for ((file, expected) in cases) {
            assertEquals("표 탭: ${file.name}", expected, sheet.accept(project, file))
            assertEquals("원본 탭: ${file.name}", expected, source.accept(project, file))
        }
    }

    fun `test TextFileTypes 는 이름으로 텍스트 타입을 찾고 없으면 PlainText 로 떨어진다`() {
        assertEquals("HTML", TextFileTypes.byName("HTML").name)
        assertEquals("XML", TextFileTypes.byName("XML").name)
        // 원본 탭은 구문 강조를 못 얻는 것은 감수하지만, 바이너리 타입을 쥐면
        // EditorHighlighter 가 깨진다. 그래서 바이너리·미등록·null 은 전부 PlainText 다.
        assertEquals(PlainTextFileType.INSTANCE, TextFileTypes.byName("Spreadsheet"))
        assertEquals(PlainTextFileType.INSTANCE, TextFileTypes.byName("존재하지않는타입"))
        assertEquals(PlainTextFileType.INSTANCE, TextFileTypes.byName(null))
    }
}
