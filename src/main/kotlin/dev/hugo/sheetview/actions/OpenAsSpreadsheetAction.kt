package dev.hugo.sheetview.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import dev.hugo.sheetview.editor.FORCE_SPREADSHEET_VIEW

/**
 * 확장자로 자동 선점하지 않는 파일(csv, tsv, html, 확장자 없는 파일 등)을
 * 명시적으로 표로 여는 진입점.
 *
 * 파일에 표식을 찍고 다시 열면 `SheetEditorProvider.accept()`가 통과시킨다.
 */
class OpenAsSpreadsheetAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && !file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        file.putUserData(FORCE_SPREADSHEET_VIEW, true)
        val manager = FileEditorManager.getInstance(project)
        manager.closeFile(file)
        manager.openFile(file, true)
    }
}
