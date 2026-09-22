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

/**
 * 표 탭과 원본 탭이 **같은** 집합을 본다. 한쪽만 고쳐 갈라지면 어떤 파일은 표만,
 * 어떤 파일은 원본만 열려 사용자가 이유를 알 수 없게 된다. 회귀 테스트는 `FileTypeAndTabsTest`.
 *
 * csv/tsv 는 일부러 제외한다 - 번들 grid 플러그인이 csv-data-editor 로 이미 담당하고
 * 그쪽은 편집까지 지원한다. html/htm 도 제외 (HTML 에디터가 이겨야 한다).
 * 이 확장자들은 "표로 열기" 액션으로 명시적으로 열 수 있다.
 */
internal val SPREADSHEET_EXTENSIONS = setOf("xls", "xlsx", "xlsm", "xltx", "xltm")

/** 두 프로바이더가 공유하는 수락 조건. */
internal fun acceptsSpreadsheet(file: com.intellij.openapi.vfs.VirtualFile): Boolean {
    if (file.isDirectory || !file.isValid) return false
    if (file.getUserData(FORCE_SPREADSHEET_VIEW) == true) return true
    return file.extension?.lowercase() in SPREADSHEET_EXTENSIONS
}

class SheetEditorProvider : FileEditorProvider, DumbAware {

    /**
     * PSI를 전혀 건드리지 않으므로 플랫폼이 우리를 위해 읽기 락을 잡을 필요가 없다.
     */
    override fun acceptRequiresReadAction(): Boolean = false

    /**
     * 파일을 열 때마다 등록된 모든 provider 에 대해 호출되므로 O(1)로 유지한다.
     * **여기서 파일 내용을 읽지 않는다** — 포맷 판별은 에디터 안 백그라운드에서 한다.
     * 위장된 파일도 이름은 여전히 .xls 이므로 확장자 검사만으로 충분하다.
     */
    override fun accept(project: Project, file: VirtualFile): Boolean = acceptsSpreadsheet(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        SheetFileEditor(project, file)

    override fun getEditorTypeId(): String = "sheetview.table"

    /** 표 탭이 기본으로 선택되고, 원본 탭이 그 다음에 온다 (plugin.xml 의 등록 순서). */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}
