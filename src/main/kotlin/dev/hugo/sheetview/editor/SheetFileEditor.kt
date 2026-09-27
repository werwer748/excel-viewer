package dev.hugo.sheetview.editor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.hugo.sheetview.export.ExportFormat
import dev.hugo.sheetview.export.ExportFormats
import dev.hugo.sheetview.format.SpreadsheetReaders
import dev.hugo.sheetview.model.Sheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/** 스프레드시트를 표로 보여주는 탭. 읽기·취소·임시파일은 [AsyncFileEditor] 가 담당한다. */
class SheetFileEditor(
    project: Project,
    file: VirtualFile,
) : AsyncFileEditor(project, file) {

    private val panel = SheetPanel(
        parentDisposable = this,
        onReload = ::reload,
        onExport = ::export,
    )

    override val host: LoadingHost get() = panel

    init {
        startLoadingFile()
    }

    override suspend fun load(path: Path, checkCancelled: () -> Unit) {
        val book = SpreadsheetReaders.read(path = path, checkCancelled = checkCancelled)
        withContext(Dispatchers.EDT) { panel.showWorkbook(book) }
    }

    private fun export(sheet: Sheet, format: ExportFormat, useHeader: Boolean) {
        val descriptor = FileSaverDescriptor(
            "시트 내보내기",
            "'${sheet.name}' 을(를) ${format.label} 로 저장합니다",
            format.extension,
        )
        val baseName = editedFile.nameWithoutExtension.ifEmpty { "sheet" }
        val suggested = "$baseName-${sheet.name}.${format.extension}".replace(Regex("""[/\\:*?"<>|]"""), "_")
        val parent = runCatching { editedFile.parent?.toNioPath() }.getOrNull()
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val wrapper = (if (parent != null) dialog.save(parent, suggested) else dialog.save(suggested)) ?: return

        val target = wrapper.file.toPath()
        runCatching {
            Files.writeString(target, ExportFormats.render(sheet, format, useHeader))
        }.onSuccess {
            VirtualFileManager.getInstance().refreshAndFindFileByNioPath(target)
            notify("${format.label} 로 내보냈습니다: ${target.fileName}", NotificationType.INFORMATION)
        }.onFailure {
            notify("내보내기에 실패했습니다: ${it.message}", NotificationType.ERROR)
        }
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(text, type)
            .notify(project)
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocus()

    override fun getName(): String = "Spreadsheet"

    private companion object {
        const val NOTIFICATION_GROUP = "Spreadsheet Viewer"
    }
}
