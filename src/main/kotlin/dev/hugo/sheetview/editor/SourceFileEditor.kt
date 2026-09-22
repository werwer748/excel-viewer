package dev.hugo.sheetview.editor

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.hugo.sheetview.source.SourceDocument
import dev.hugo.sheetview.source.SourceReader
import dev.hugo.sheetview.source.ZipPart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import javax.swing.JComponent

/**
 * 파일의 **원본**을 보여주는 탭.
 *
 * 왜 필요한가: HTML 위장 `.xls` 의 원본을 플랫폼 텍스트 탭에 기댈 수 없다. `.xls` 의 파일
 * 타입은 (내 타입이든 번들 grid 의 `Data File` 이든) 바이너리라 텍스트 에디터가 붙지 않는다.
 * 그래서 원본은 이 탭이 직접 읽어 보여준다. 회귀 테스트는 `FileTypeAndTabsTest`.
 */
class SourceFileEditor(
    project: Project,
    file: VirtualFile,
) : AsyncFileEditor(project, file) {

    private val panel = SourcePanel(
        parentDisposable = this,
        project = project,
        onReload = ::reload,
        onSelectPart = ::openPart,
    )

    override val host: LoadingHost get() = panel

    /** 파트 읽기는 본문 로드와 별개로 취소된다 - 목록을 빠르게 훑어도 EDT 가 멈추면 안 된다. */
    private val partScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var partJob: Job? = null

    @Volatile
    private var loaded: SourceDocument? = null

    init {
        startLoadingFile()
    }

    override suspend fun load(path: Path, checkCancelled: () -> Unit) {
        val document = SourceReader.read(path, checkCancelled)
        loaded = document
        withContext(Dispatchers.EDT) { panel.showDocument(document) }
    }

    private fun openPart(part: ZipPart) {
        partJob?.cancel()
        partJob = partScope.launch {
            val content = runCatching { SourceReader.readPart(resolvePath(), part.name) }.getOrNull()
            withContext(Dispatchers.EDT) { panel.showPart(part, content) }
        }
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = panel.preferredFocus()

    override fun getName(): String = "원본"

    override fun dispose() {
        partScope.cancel()
        super.dispose()
    }
}
