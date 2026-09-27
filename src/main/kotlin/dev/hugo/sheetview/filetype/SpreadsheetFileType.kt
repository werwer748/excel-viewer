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
 * 이 타입은 plugin.xml 의 `<fileType extensions="xls;xlsx;...">` 가 부여한다. 한때 내용 기반
 * fileTypeDetector 로 하려 했지만 확장자 매핑이 먼저 평가돼 탐지기가 불리지 않는다는 것을
 * 측정으로 확인했다 (근거는 CLAUDE.md '파일 타입과 탭'). 내용이 실제로 텍스트(HTML 위장 .xls 등)
 * 여도 이 타입은 binary 라 플랫폼 텍스트 에디터가 붙지 않으므로, 원본은 이 플러그인의
 * `원본` 탭이 직접 읽어 보여준다.
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
