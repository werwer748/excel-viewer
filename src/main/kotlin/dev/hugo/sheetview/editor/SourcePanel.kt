package dev.hugo.sheetview.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.hugo.sheetview.preview.HtmlPreview
import dev.hugo.sheetview.preview.HtmlPreviewFactory
import dev.hugo.sheetview.source.PartContent
import dev.hugo.sheetview.source.SourceDocument
import dev.hugo.sheetview.source.SourceMode
import dev.hugo.sheetview.source.ZipPart
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * 원본 탭의 화면. 포맷이 정한 보기([SourceDocument.modes]) 사이를 툴바 토글로 오간다.
 *
 * `SheetPanel` 에서 배운 함정을 그대로 피한다: **[JBLoadingPanel.removeAll] 을 부르지 않는다.**
 * `add()` 는 내부 content 로 위임되지만 `removeAll()` 은 재정의되어 있지 않아, 부르면 화면에
 * 붙어 있는 `LoadingDecorator` 가 떨어져 나가고 이후 `add()` 한 내용이 **아무 오류 없이**
 * 보이지 않게 된다. 내 소유의 [content] 를 한 번 넣고 그 자식만 교체한다.
 */
class SourcePanel(
    private val parentDisposable: Disposable,
    project: Project,
    private val onReload: () -> Unit,
    private val onSelectPart: (ZipPart) -> Unit,
) : JPanel(BorderLayout()), LoadingHost {

    private val loadingPanel = JBLoadingPanel(BorderLayout(), parentDisposable)
    private val content = JPanel(BorderLayout())
    private val banners = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    /**
     * 파트를 고를 때마다 뜨는 배너는 **따로** 둔다. 문서 배너와 같은 칸에 붙이면
     * 파트를 5개 고르면 배너가 5줄 쌓인다 (실제로 그랬다).
     */
    private val partBanner = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    private val textViewer = TextViewer(project).also { Disposer.register(parentDisposable, it) }
    private val partViewer = TextViewer(project).also { Disposer.register(parentDisposable, it) }

    private val partModel = DefaultListModel<ZipPart>()
    private val partList = JBList(partModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = PartRenderer()
    }

    /** JCEF 는 무겁다. 미리보기를 실제로 켤 때까지 만들지 않는다. */
    private var preview: HtmlPreview? = null
    private var previewUnavailable = false

    /** 확장 포인트 조회만 한다 - 브라우저를 만들지 않으므로 EDT 에서 불러도 안전하다. */
    private val previewSupported: Boolean by lazy { HtmlPreviewFactory.find() != null }

    private var document: SourceDocument? = null
    private var mode: SourceMode? = null

    init {
        val north = JPanel(BorderLayout())
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, buildActions(), true)
        toolbar.targetComponent = this
        north.add(toolbar.component, BorderLayout.WEST)
        val allBanners = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(banners)
            add(partBanner)
        }
        north.add(allBanners, BorderLayout.SOUTH)
        add(north, BorderLayout.NORTH)

        loadingPanel.add(content, BorderLayout.CENTER)
        add(loadingPanel, BorderLayout.CENTER)

        partList.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                partList.selectedValue?.let(onSelectPart)
            }
        }
    }

    // ---------- LoadingHost ----------

    override fun startLoading(text: String) {
        loadingPanel.setLoadingText(text)
        loadingPanel.startLoading()
    }

    override fun stopLoading() = loadingPanel.stopLoading()

    override fun showMessage(title: String, detail: String?) {
        document = null
        banners.removeAll()
        partBanner.removeAll()
        val panel = JBPanelWithEmptyText(BorderLayout())
        panel.emptyText.text = title
        if (!detail.isNullOrBlank()) detail.split('\n').forEach { panel.emptyText.appendLine(it) }
        setCenter(panel)
    }

    // ---------- 내용 ----------

    fun showDocument(doc: SourceDocument) {
        document = doc
        partModel.clear()
        doc.parts.forEach(partModel::addElement)
        mode = availableModes(doc).firstOrNull()
        rebuildBanners(doc)
        renderMode()
        // 파트 보기는 첫 파트를 자동으로 연다 - 빈 오른쪽 칸만 보여주면 뭘 해야 할지 모른다.
        if (mode == SourceMode.PARTS && partModel.size() > 0) partList.selectedIndex = 0
    }

    fun showPart(part: ZipPart, content: PartContent?) {
        val doc = document ?: return
        partBanner.removeAll()

        when {
            content == null ->
                partViewer.show("'${part.name}' 을(를) 읽지 못했습니다.", null, false)

            // 바이너리 파트를 에디터에 넣으면 깨진 글자만 뜨고 사용자는 플러그인이 고장난 줄 안다.
            content.looksBinary -> {
                partViewer.show(
                    "'${part.name}' 은 바이너리라 텍스트로 볼 수 없습니다.\n" +
                        "(.xlsb 의 시트가 이 경우입니다 — XML 이 아니라 바이너리 레코드입니다.)",
                    null,
                    false,
                )
                addPartBanner(
                    "'${part.name}' 은 바이너리 파트입니다.",
                    EditorNotificationPanel.Status.Info,
                )
            }

            else -> {
                partViewer.show(content.text, doc.highlightTypeName, doc.softWrap)
                if (content.truncated) {
                    addPartBanner(
                        "'${part.name}' 은 앞부분만 보여줍니다 (전체 %,d바이트).".format(part.size),
                        EditorNotificationPanel.Status.Warning,
                    )
                }
            }
        }
        partBanner.revalidate()
        partBanner.repaint()
    }

    fun preferredFocus(): JComponent = when (mode) {
        SourceMode.PARTS -> partList
        else -> this
    }

    fun currentText(): String? = document?.let { doc ->
        when (mode) {
            SourceMode.PARTS -> null     // 파트는 파일 전체가 아니라 조각이라 "전체 복사"의 대상이 아니다.
            else -> doc.text.takeIf { it.isNotEmpty() }
        }
    }

    // ---------- 모드 ----------

    /**
     * JCEF 가 없으면 미리보기를 아예 목록에서 뺀다 - 눌러도 안 되는 버튼을 두지 않는다.
     *
     * **여기서 브라우저를 만들면 안 된다.** 이 함수는 툴바가 갱신될 때마다 EDT 에서 불리는데,
     * `JBCefBrowser` 생성은 Chromium 초기화를 끌고 와 EDT 를 멈춘다. 확장 포인트 조회만 한다.
     */
    private fun availableModes(doc: SourceDocument): List<SourceMode> =
        doc.modes.filter { it != SourceMode.PREVIEW || previewSupported }

    /** 실제 생성은 미리보기를 정말 그릴 때만. EDT 에서 불리므로 한 번만 만든다. */
    private fun previewOrNull(): HtmlPreview? {
        if (preview == null && !previewUnavailable) {
            val created = HtmlPreviewFactory.find()?.create(parentDisposable)
            if (created == null) previewUnavailable = true else preview = created
        }
        return preview
    }

    private fun renderMode() {
        val doc = document ?: return
        when (mode) {
            SourceMode.PREVIEW -> {
                val html = doc.previewHtml
                val browser = previewOrNull()
                if (html == null || browser == null) {
                    // 미리보기를 못 만들면 원본을 못 보여주는 게 아니라 소스로 떨어진다.
                    mode = SourceMode.TEXT
                    renderMode()
                } else {
                    browser.load(html)
                    setCenter(browser.component)
                }
            }

            SourceMode.TEXT -> {
                textViewer.show(doc.text, doc.highlightTypeName, doc.softWrap)
                setCenter(textViewer.component)
            }

            SourceMode.PARTS -> {
                val splitter = JBSplitter(false, PART_SPLIT)
                splitter.firstComponent = JBScrollPane(partList)
                splitter.secondComponent = partViewer.component
                setCenter(splitter)
            }

            null -> setCenter(message("보여줄 내용이 없습니다."))
        }
    }

    private fun message(text: String): JComponent =
        JBPanelWithEmptyText(BorderLayout()).apply { emptyText.text = text }

    private fun setCenter(component: JComponent) {
        content.removeAll()
        content.add(component, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
    }

    // ---------- 배너 ----------

    private fun rebuildBanners(doc: SourceDocument) {
        banners.removeAll()
        partBanner.removeAll()
        addBanner(describe(doc), EditorNotificationPanel.Status.Info)
        doc.notice?.let { addBanner(it, EditorNotificationPanel.Status.Warning) }
        if (doc.removedScripts > 0) {
            addBanner(
                "미리보기에서 스크립트 %d개를 제거했습니다. 출처를 알 수 없는 파일이라 실행하지 않습니다."
                    .format(doc.removedScripts),
                EditorNotificationPanel.Status.Warning,
            )
        }
        if (doc.imagesBlocked) {
            addBanner(
                "원본에 있던 이미지는 차단했습니다 (외부 요청을 막기 위함). 소스 보기에서 경로는 확인할 수 있습니다.",
                EditorNotificationPanel.Status.Info,
            )
        }
        banners.revalidate()
        banners.repaint()
    }

    private fun addPartBanner(text: String, status: EditorNotificationPanel.Status) {
        partBanner.add(EditorNotificationPanel(null as java.awt.Color?, status).apply { this.text = text })
    }

    private fun addBanner(text: String, status: EditorNotificationPanel.Status) {
        // (Status) 단일 인자 생성자는 없다. (Color?, Status) 오버로드를 쓴다.
        banners.add(EditorNotificationPanel(null as java.awt.Color?, status).apply { this.text = text })
    }

    private fun describe(doc: SourceDocument): String = buildString {
        append("형식: ").append(doc.format.label)
        doc.charsetName?.let { append(" · 인코딩: ").append(it) }
        append(" · ").append("%,d바이트".format(doc.byteSize))
        when {
            doc.parts.isNotEmpty() -> append(" · 내부 파트 ").append(doc.parts.size).append("개")
            doc.text.isNotEmpty() -> append(" · ").append("%,d줄".format(doc.text.count { it == '\n' } + 1))
        }
    }

    // ---------- 툴바 ----------

    private fun buildActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(ModeAction(SourceMode.PREVIEW, "미리보기", "원본을 렌더해서 봅니다"))
        add(ModeAction(SourceMode.TEXT, "소스", "원본 텍스트를 그대로 봅니다"))
        add(ModeAction(SourceMode.PARTS, "내부 파트", "ZIP 안의 XML 파트를 봅니다"))
        addSeparator()
        add(ReloadAction())
        add(CopyAction())
    }

    private inner class ModeAction(
        private val target: SourceMode,
        text: String,
        description: String,
    ) : AnAction(text, description, null), Toggleable {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            val doc = document
            // 이 포맷에 없는 보기는 아예 숨긴다. 비활성 버튼만 늘어놓으면 무엇이 가능한지 흐려진다.
            e.presentation.isVisible = doc != null && target in availableModes(doc)
            Toggleable.setSelected(e.presentation, mode == target)
        }
        override fun actionPerformed(e: AnActionEvent) {
            if (mode == target) return
            mode = target
            renderMode()
            if (target == SourceMode.PARTS && partModel.size() > 0 && partList.selectedIndex < 0) {
                partList.selectedIndex = 0
            }
        }
    }

    private inner class ReloadAction :
        AnAction("다시 읽기", "파일을 다시 읽습니다", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = onReload()
    }

    private inner class CopyAction :
        AnAction("원본 전체 복사", "원본 텍스트를 클립보드에 복사합니다", AllIcons.Actions.Copy) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentText() != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            currentText()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
        }
    }

    /** 파트 목록: 이름은 그대로, 크기는 옅게. 경로가 길어 이름이 잘리는 쪽이 낫다. */
    private class PartRenderer : ListCellRenderer<ZipPart> {
        private val panel = JPanel(BorderLayout())
        private val name = JBLabel()
        private val size = JBLabel().apply {
            foreground = UIUtil.getInactiveTextColor()
            border = JBUI.Borders.emptyLeft(8)
        }

        init {
            panel.add(name, BorderLayout.CENTER)
            panel.add(size, BorderLayout.EAST)
            panel.border = JBUI.Borders.empty(1, 6)
        }

        override fun getListCellRendererComponent(
            list: JList<out ZipPart>,
            value: ZipPart?,
            index: Int,
            selected: Boolean,
            focused: Boolean,
        ): Component {
            name.text = value?.name ?: ""
            size.text = value?.size?.takeIf { it >= 0 }?.let { "%,d".format(it) } ?: ""
            panel.background = if (selected) list.selectionBackground else list.background
            name.foreground = if (selected) list.selectionForeground else list.foreground
            panel.isOpaque = true
            return panel
        }
    }

    private companion object {
        const val TOOLBAR_PLACE = "SheetView.SourceToolbar"
        const val PART_SPLIT = 0.32f
    }
}
