package dev.hugo.sheetview.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

/** "표로 열기" 액션이 임의 파일을 통과시키기 위해 파일에 찍는 표식. */
val FORCE_SPREADSHEET_VIEW: Key<Boolean> = Key.create("sheetview.forceOpen")

class SheetEditorProvider : FileEditorProvider, DumbAware {

    /**
     * PSI를 전혀 건드리지 않으므로 플랫폼이 우리를 위해 읽기 락을 잡을 필요가 없다.
     */
    override fun acceptRequiresReadAction(): Boolean = false

    /**
     * 파일을 열 때마다 등록된 모든 provider에 대해 호출되므로 O(1)로 유지한다.
     * **여기서 파일 내용을 읽지 않는다** — 포맷 판별은 에디터 안에서, 파일 타입 판별은
     * `SpreadsheetFileTypeDetector`에서 한다. 위장된 파일도 이름은 여전히 .xls 이므로
     * 확장자 검사만으로 충분하다.
     */
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.isDirectory || !file.isValid) return false
        if (file.getUserData(FORCE_SPREADSHEET_VIEW) == true) return true
        val extension = file.extension?.lowercase() ?: return false
        // csv/tsv 는 일부러 제외한다. 번들 grid 플러그인이 csv-data-editor 로 이미
        // 담당하고 그쪽은 편집까지 지원한다. html/htm 도 제외 (HTML 에디터가 이겨야 한다).
        return extension in SPREADSHEET_EXTENSIONS
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        SheetFileEditor(project, file)

    override fun getEditorTypeId(): String = "sheetview.table"

    /** 표 탭이 기본으로 선택되고, 텍스트 내용인 파일은 아래에 Text 탭이 함께 남는다. */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR

    private companion object {
        val SPREADSHEET_EXTENSIONS = setOf("xls", "xlsx", "xlsm", "xltx", "xltm")
    }
}
