package dev.hugo.sheetview.editor

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import dev.hugo.sheetview.export.ExportFormat
import dev.hugo.sheetview.export.ExportFormats
import dev.hugo.sheetview.format.SpreadsheetParseException
import dev.hugo.sheetview.format.SpreadsheetReaders
import dev.hugo.sheetview.format.UnsupportedSpreadsheetException
import dev.hugo.sheetview.model.Sheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.io.path.deleteIfExists

class SheetFileEditor(
    private val project: Project,
    private val file: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val panel = SheetPanel(
        parentDisposable = this,
        onReload = ::reload,
        onExport = ::export,
    )

    /** 로컬 파일이 아니어서 임시 파일로 복사한 경우의 경로. dispose에서 지운다. */
    @Volatile
    private var tempPath: Path? = null

    @Volatile
    private var loadJob: Job? = null

    init {
        subscribeToExternalChanges()
        reload()
    }

    private fun reload() {
        loadJob?.cancel()
        panel.startLoading()
        loadJob = scope.launch {
            try {
                val path = resolvePath()
                val context = currentCoroutineContext()
                val book = SpreadsheetReaders.read(
                    path = path,
                    checkCancelled = { context.ensureActive() },
                )
                withContext(Dispatchers.EDT) { panel.showWorkbook(book) }
            } catch (e: ProcessCanceledException) {
                // 플랫폼 취소 신호는 절대 삼키지 않는다.
                throw e
            } catch (e: UnsupportedSpreadsheetException) {
                showMessage(e.userMessage, e.hint)
            } catch (e: SpreadsheetParseException) {
                showMessage(e.userMessage, e.hint)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 탭을 닫아 취소된 경우. 코루틴 취소도 절대 삼키지 않는다.
                throw e
            } catch (t: Throwable) {
                // OutOfMemoryError까지 포함해 사용자에게는 패널로만 보여준다.
                showMessage(
                    "파일을 읽는 중 오류가 발생했습니다.",
                    listOfNotNull(t::class.simpleName, t.message).joinToString(": "),
                )
            } finally {
                // 취소 중이어도 로딩 표시는 반드시 걷어낸다.
                withContext(Dispatchers.EDT + NonCancellable) { panel.stopLoading() }
            }
        }
    }

    private suspend fun showMessage(title: String, detail: String?) {
        withContext(Dispatchers.EDT + NonCancellable) { panel.showMessage(title, detail) }
    }

    /**
     * 리더들은 `ZipFile`(랜덤 액세스)을 쓰기 위해 실제 파일 경로가 필요하다.
     * 로컬 파일이 아니면 임시 파일로 한 번 복사한다.
     */
    private fun resolvePath(): Path {
        runCatching { file.toNioPath() }.getOrNull()?.let { return it }
        tempPath?.let { return it }
        val suffix = file.extension?.let { ".$it" } ?: ".bin"
        val temp = Files.createTempFile("sheetview-", suffix)
        temp.toFile().deleteOnExit()
        Files.write(temp, file.contentsToByteArray())
        tempPath = temp
        return temp
    }

    /** 엑셀은 저장할 때 파일을 통째로 다시 쓴다. 표가 낡은 채로 남지 않게 한다. */
    private fun subscribeToExternalChanges() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.file == file }) {
                        tempPath = null
                        reload()
                    }
                }
            },
        )
    }

    private fun export(sheet: Sheet, format: ExportFormat, useHeader: Boolean) {
        val descriptor = FileSaverDescriptor(
            "시트 내보내기",
            "'${sheet.name}' 을(를) ${format.label} 로 저장합니다",
            format.extension,
        )
        val baseName = file.nameWithoutExtension.ifEmpty { "sheet" }
        val suggested = "$baseName-${sheet.name}.${format.extension}".replace(Regex("""[/\\:*?"<>|]"""), "_")
        val parent = runCatching { file.parent?.toNioPath() }.getOrNull()
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

    // ---------- FileEditor ----------

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocus()

    override fun getName(): String = "Spreadsheet"

    /** 기본 구현이 deprecated 경고를 남기므로 반드시 재정의한다. */
    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun dispose() {
        scope.cancel()
        tempPath?.let { path -> runCatching { path.deleteIfExists() } }
        tempPath = null
    }

    private companion object {
        const val NOTIFICATION_GROUP = "Spreadsheet Viewer"
    }
}
