package dev.hugo.sheetview.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 원본 탭을 붙인다. 수락 조건은 [acceptsSpreadsheet] 로 표 탭과 **공유**한다 —
 * 갈라지면 어떤 파일은 표만, 어떤 파일은 원본만 열려 사용자가 이유를 알 수 없다.
 */
class SourceEditorProvider : FileEditorProvider, DumbAware {

    /** PSI 를 건드리지 않으므로 플랫폼이 우리를 위해 읽기 락을 잡을 필요가 없다. */
    override fun acceptRequiresReadAction(): Boolean = false

    /** 파일을 열 때마다 등록된 모든 provider 에 대해 호출되므로 내용을 읽지 않고 O(1)로 끝낸다. */
    override fun accept(project: Project, file: VirtualFile): Boolean = acceptsSpreadsheet(file)

    override fun createEditor(project: Project, file: VirtualFile): FileEditor =
        SourceFileEditor(project, file)

    override fun getEditorTypeId(): String = "sheetview.source"

    /**
     * 표 탭과 같은 정책을 쓰고, `plugin.xml` 의 등록 순서로 표 다음에 오게 한다.
     * 기본으로 선택되는 것은 표 탭이어야 한다 — 대부분의 경우 사용자가 원하는 것은 값이다.
     */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}
