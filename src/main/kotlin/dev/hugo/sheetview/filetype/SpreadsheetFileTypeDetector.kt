package dev.hugo.sheetview.filetype

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.io.ByteSequence
import com.intellij.openapi.vfs.VirtualFile
import dev.hugo.sheetview.format.SpreadsheetFormat
import dev.hugo.sheetview.format.SpreadsheetSniffer

/**
 * 확장자가 아니라 내용으로 파일 타입을 정한다.
 *
 * 이게 없으면 HTML 위장 `.xls`는 `UnknownFileType`(바이너리)으로 남아서
 * 텍스트 에디터가 붙지 않고, `PLACE_BEFORE_DEFAULT_EDITOR`를 써도 **Text 탭이 아예
 * 생기지 않는다**. 내용이 텍스트일 때 PlainText를 돌려주면 표 탭과 원본 Text 탭이
 * 나란히 열린다.
 *
 * 판별 결과는 VFS에 캐시되므로 다시 열 때는 비용이 없다.
 */
class SpreadsheetFileTypeDetector : FileTypeRegistry.FileTypeDetector {

    override fun getDesiredContentPrefixLength(): Int = SpreadsheetSniffer.PROBE_BYTES

    override fun detect(
        file: VirtualFile,
        firstBytes: ByteSequence,
        firstCharsIfText: CharSequence?,
    ): FileType? {
        val extension = file.extension?.lowercase() ?: return null
        if (extension !in DETECTED_EXTENSIONS) return null

        return when (SpreadsheetSniffer.sniff(firstBytes.toBytes()).format) {
            // 내용이 텍스트다. 내용에 맞는 타입을 주면 두 번째 탭에서 원본을
            // 구문 강조와 함께 볼 수 있다. PlainText 로 두면 번들 grid 플러그인의
            // csv-data-editor 가 가로채 "Data" 탭이 되어 원본을 보기 어려워진다.
            SpreadsheetFormat.EXCEL_HTML -> textTypeOrPlain("HTML")
            SpreadsheetFormat.SPREADSHEET_ML -> textTypeOrPlain("XML")
            SpreadsheetFormat.DELIMITED -> PlainTextFileType.INSTANCE

            // 진짜 바이너리 워크북.
            SpreadsheetFormat.XLSX,
            SpreadsheetFormat.LEGACY_XLS_BIFF,
            -> SpreadsheetFileType

            SpreadsheetFormat.UNKNOWN -> null
        }
    }

    /**
     * 이름으로 파일 타입을 찾는다. 해당 언어 지원이 없는 IDE에서는 PlainText로 떨어진다.
     * 이름 조회는 단순 맵 조회이므로 탐지기를 재귀 호출하지 않는다.
     */
    private fun textTypeOrPlain(typeName: String): FileType {
        val found = runCatching { FileTypeRegistry.getInstance().findFileTypeByName(typeName) }.getOrNull()
        return if (found == null || found.isBinary) PlainTextFileType.INSTANCE else found
    }

    private companion object {
        val DETECTED_EXTENSIONS = setOf("xls", "xlsx", "xlsm", "xltx", "xltm")
    }
}
