package dev.hugo.sheetview.filetype

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * 스프레드시트용 파일 타입.
 *
 * 등록이 필요한 이유: 실측 결과 어떤 번들 플러그인도 `xls`/`xlsx`/`xlsm` 확장자를
 * 등록하지 않아서 이 파일들이 `UnknownFileType`으로 취급된다. 그러면 프로젝트 뷰에
 * 아이콘이 없고 IDE의 "알 수 없는 바이너리" 처리 경로를 타게 된다.
 *
 * 내용이 실제로 텍스트(HTML 위장 .xls 등)인 경우에는 [SpreadsheetFileTypeDetector]가
 * 내용을 보고 PlainText로 덮어써서 Text 탭이 함께 열리도록 한다.
 */
object SpreadsheetFileType : FileType {

    override fun getName(): String = "Spreadsheet"

    override fun getDescription(): String = "스프레드시트 워크북"

    override fun getDefaultExtension(): String = "xlsx"

    override fun getIcon(): Icon = ICON

    override fun isBinary(): Boolean = true

    private val ICON: Icon by lazy {
        IconLoader.getIcon("/icons/spreadsheet.svg", SpreadsheetFileType::class.java)
    }
}
