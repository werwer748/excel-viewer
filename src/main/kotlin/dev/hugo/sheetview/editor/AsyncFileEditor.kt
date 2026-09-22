package dev.hugo.sheetview.editor

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.hugo.sheetview.format.SpreadsheetParseException
import dev.hugo.sheetview.format.UnsupportedSpreadsheetException
import kotlinx.coroutines.CancellationException
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
import kotlin.io.path.deleteIfExists

/** 로딩 표시와 오류 메시지를 띄울 수 있는 패널. [AsyncFileEditor] 가 EDT 에서만 호출한다. */
interface LoadingHost {
    fun startLoading(text: String = "읽는 중…")
    fun stopLoading()
    fun showMessage(title: String, detail: String?)
}

/**
 * 파일을 백그라운드에서 읽어 패널에 넘기는 [FileEditor] 의 공통 부분.
 *
 * 표 탭과 원본 탭이 똑같이 필요로 하는 것들을 한 곳에 둔다:
 *  - `VirtualFile` -> `Path` 해석 (로컬이 아니면 임시 파일로 복사하고 dispose 에서 지운다)
 *  - 취소 가능한 백그라운드 로드
 *  - **취소 신호를 절대 삼키지 않는** 예외 규약
 *  - 어떤 오류도 스택트레이스가 아니라 패널 메시지로 끝내는 규약
 *  - 파일이 밖에서 바뀌면 다시 읽기 (엑셀은 저장할 때 파일을 통째로 다시 쓴다)
 *
 * 복붙하지 않고 추출한 이유: 이 예외 규약은 눈으로 봐서는 맞는지 알기 어렵고, 실제로 한 번
 * 어긋나게 만든 적이 있다. 두 벌을 유지하면 반드시 갈라진다.
 */
abstract class AsyncFileEditor(
    protected val project: Project,
    // `file` 이라는 이름의 프로퍼티로 두면 Kotlin 이 만드는 getFile() 접근자가 아래
    // `override fun getFile()` 과 충돌한다. 그래서 이름을 달리한다.
    protected val editedFile: VirtualFile,
) : UserDataHolderBase(), FileEditor {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 로컬 파일이 아니어서 임시 파일로 복사한 경우의 경로. dispose 에서 지운다. */
    @Volatile
    private var tempPath: Path? = null

    @Volatile
    private var loadJob: Job? = null

    /** 서브클래스의 패널. 생성자에서 이미 만들어져 있어야 한다. */
    protected abstract val host: LoadingHost

    /**
     * 백그라운드에서 실제로 읽는 부분. 결과를 화면에 반영할 때는 스스로
     * `withContext(Dispatchers.EDT)` 로 옮겨야 한다.
     *
     * [checkCancelled] 는 긴 루프 안에서 주기적으로 부른다. 던지는 예외는 그대로 통과된다.
     */
    protected abstract suspend fun load(path: Path, checkCancelled: () -> Unit)

    /**
     * 서브클래스 생성자의 **마지막**에 부른다. 여기서 부르지 않고 이 클래스의 생성자에서
     * 부르면 서브클래스 필드가 아직 초기화되지 않은 채 [load] 가 돌아 NPE 가 난다.
     */
    protected fun startLoadingFile() {
        subscribeToExternalChanges()
        reload()
    }

    protected fun reload() {
        loadJob?.cancel()
        host.startLoading()
        loadJob = scope.launch {
            try {
                val path = resolvePath()
                val context = currentCoroutineContext()
                load(path) { context.ensureActive() }
            } catch (e: ProcessCanceledException) {
                // 플랫폼 취소 신호는 절대 삼키지 않는다.
                throw e
            } catch (e: CancellationException) {
                // 탭을 닫아 취소된 경우. 코루틴 취소도 절대 삼키지 않는다.
                throw e
            } catch (e: UnsupportedSpreadsheetException) {
                showMessage(e.userMessage, e.hint)
            } catch (e: SpreadsheetParseException) {
                showMessage(e.userMessage, e.hint)
            } catch (t: Throwable) {
                // OutOfMemoryError까지 포함해 사용자에게는 패널로만 보여준다.
                showMessage(
                    "파일을 읽는 중 오류가 발생했습니다.",
                    listOfNotNull(t::class.simpleName, t.message).joinToString(": "),
                )
            } finally {
                // 취소 중이어도 로딩 표시는 반드시 걷어낸다.
                withContext(Dispatchers.EDT + NonCancellable) { host.stopLoading() }
            }
        }
    }

    protected suspend fun showMessage(title: String, detail: String?) {
        withContext(Dispatchers.EDT + NonCancellable) { host.showMessage(title, detail) }
    }

    /**
     * 리더들은 `ZipFile`(랜덤 액세스)을 쓰기 위해 실제 파일 경로가 필요하다.
     * 로컬 파일이 아니면 임시 파일로 한 번 복사한다.
     */
    protected fun resolvePath(): Path {
        runCatching { editedFile.toNioPath() }.getOrNull()?.let { return it }
        tempPath?.let { return it }
        val suffix = editedFile.extension?.let { ".$it" } ?: ".bin"
        val temp = Files.createTempFile("sheetview-", suffix)
        temp.toFile().deleteOnExit()
        Files.write(temp, editedFile.contentsToByteArray())
        tempPath = temp
        return temp
    }

    /** 엑셀은 저장할 때 파일을 통째로 다시 쓴다. 화면이 낡은 채로 남지 않게 한다. */
    private fun subscribeToExternalChanges() {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.file == editedFile }) {
                        tempPath = null
                        reload()
                    }
                }
            },
        )
    }

    // ---------- FileEditor 공통 ----------

    /** 기본 구현이 deprecated 경고를 남기므로 반드시 재정의한다. */
    override fun getFile(): VirtualFile = editedFile

    override fun setState(state: FileEditorState) = Unit

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = editedFile.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

    /** 재정의할 때는 반드시 `super.dispose()` 를 부른다 — 코루틴과 임시 파일이 여기서 정리된다. */
    override fun dispose() {
        scope.cancel()
        tempPath?.let { path -> runCatching { path.deleteIfExists() } }
        tempPath = null
    }
}
