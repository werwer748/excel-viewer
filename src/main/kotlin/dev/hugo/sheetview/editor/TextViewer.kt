package dev.hugo.sheetview.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBPanelWithEmptyText
import dev.hugo.sheetview.filetype.TextFileTypes
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 읽기 전용 에디터 뷰어. 구문 강조·검색·접기·줄번호를 플랫폼 에디터에서 그대로 얻는다.
 *
 * **[EditorFactory.releaseEditor] 를 반드시 부른다.** 빠뜨리면 탭을 닫아도 에디터가 살아남아
 * 누수된다. 그래서 이 클래스가 [Disposable] 이고, 내용을 갈아끼울 때도 먼저 놓아준다.
 */
class TextViewer(private val project: Project) : Disposable {

    private val panel = JPanel(BorderLayout())
    private var editor: EditorEx? = null

    val component: JComponent get() = panel

    init {
        panel.add(
            JBPanelWithEmptyText(BorderLayout()).apply { emptyText.text = "보여줄 내용이 없습니다." },
            BorderLayout.CENTER,
        )
    }

    /** EDT 에서만 부른다. */
    fun show(text: String, highlightTypeName: String?, softWrap: Boolean) {
        release()

        val factory = EditorFactory.getInstance()
        // IntelliJ Document 는 \r 을 담을 수 없다 (CRLF 파일을 그대로 넣으면 예외).
        // 줄 구분자만 바뀌고 줄 번호는 원본과 그대로 일치한다.
        val document = factory.createDocument(StringUtil.convertLineSeparators(text))
        document.setReadOnly(true)

        val created = factory.createViewer(document, project) as EditorEx

        // 원본은 IDE 테마와 무관하게 항상 밝게 본다. 배경색만 흰색으로 바꾸면 안 된다 —
        // 다크 테마의 구문 강조 색(밝은 노랑·연회색)이 흰 바탕에 얹혀 더 안 보인다.
        // createBoundColorSchemeDelegate 로 감싸면 **색만** 갈아끼우고 글꼴·크기는
        // 사용자 설정을 그대로 상속한다 (구성표를 통째로 대입하면 글꼴까지 바뀐다).
        created.colorsScheme = created.createBoundColorSchemeDelegate(LightEditorScheme.pick())
        created.backgroundColor = java.awt.Color.WHITE

        created.highlighter = EditorHighlighterFactory.getInstance()
            .createEditorHighlighter(project, TextFileTypes.byName(highlightTypeName))
        created.settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = true
            // 이건 원본을 보는 창이지 코드를 고치는 창이 아니다. 여백을 비운다.
            isLineMarkerAreaShown = false
            isIndentGuidesShown = false
            isCaretRowShown = true
            // OOXML 파트는 한 줄짜리 수 MB라 소프트랩 없이는 읽을 수 없다.
            isUseSoftWraps = softWrap
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
        created.setHorizontalScrollbarVisible(true)
        created.setVerticalScrollbarVisible(true)

        editor = created
        panel.removeAll()
        panel.add(created.component, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    private fun release() {
        editor?.let { EditorFactory.getInstance().releaseEditor(it) }
        editor = null
    }

    override fun dispose() = release()
}
