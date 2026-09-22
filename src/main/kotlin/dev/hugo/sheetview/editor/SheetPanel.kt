package dev.hugo.sheetview.editor

import com.intellij.openapi.Disposable
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.TableSpeedSearch
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.hugo.sheetview.export.ExportFormat
import dev.hugo.sheetview.export.ExportFormats
import dev.hugo.sheetview.model.Sheet
import dev.hugo.sheetview.model.Workbook
import java.awt.BorderLayout
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

class SheetPanel(
    parentDisposable: Disposable,
    private val onReload: () -> Unit,
    private val onExport: (Sheet, ExportFormat, Boolean) -> Unit,
) : JPanel(BorderLayout()) {

    private val loadingPanel = JBLoadingPanel(BorderLayout(), parentDisposable)

    /**
     * 표/메시지를 갈아끼울 우리 소유의 패널.
     *
     * loadingPanel 을 직접 비우면 안 된다: JBLoadingPanel 은 add() 는 재정의해 내부
     * content 패널로 위임하지만 removeAll() 은 재정의하지 않는다. 그래서 removeAll() 을
     * 부르면 화면에 붙어 있는 LoadingDecorator 컴포넌트 자체가 떨어져 나가고,
     * 이후 add() 한 내용은 분리된 패널로 들어가 아무것도 보이지 않게 된다.
     */
    private val content = JPanel(BorderLayout())

    private val banners = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val tables = ArrayList<JBTable>()
    private var tabbedPane: JBTabbedPane? = null
    private var workbook: Workbook? = null
    private var useHeader = true

    init {
        val north = JPanel(BorderLayout())
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, buildActions(), true)
        toolbar.targetComponent = this
        north.add(toolbar.component, BorderLayout.WEST)
        north.add(banners, BorderLayout.SOUTH)
        add(north, BorderLayout.NORTH)
        loadingPanel.add(content, BorderLayout.CENTER)
        add(loadingPanel, BorderLayout.CENTER)
    }

    // ---------- 상태 ----------

    fun startLoading(text: String = "읽는 중…") {
        loadingPanel.setLoadingText(text)
        loadingPanel.startLoading()
    }

    fun stopLoading() = loadingPanel.stopLoading()

    fun preferredFocus(): JComponent = currentTable() ?: this

    fun currentTable(): JBTable? {
        val tabs = tabbedPane ?: return tables.firstOrNull()
        return tables.getOrNull(tabs.selectedIndex)
    }

    fun currentSheet(): Sheet? = (currentTable()?.model as? SheetTableModel)?.sheet

    // ---------- 렌더링 ----------

    fun showWorkbook(book: Workbook) {
        workbook = book
        rebuild()
    }

    private fun rebuild() {
        val book = workbook ?: return
        tables.clear()
        tabbedPane = null
        banners.removeAll()

        val center: JComponent = if (book.sheets.size == 1) {
            // 시트가 하나면 탭 바를 만들지 않는다.
            buildSheetView(book.sheets.single())
        } else {
            JBTabbedPane().also { tabs ->
                tabbedPane = tabs
                for (sheet in book.sheets) {
                    tabs.addTab(sheet.name, buildSheetView(sheet))
                }
            }
        }

        addBanner(describe(book), EditorNotificationPanel.Status.Info)
        book.sheets.filter { it.truncated }.forEach { sheet ->
            val shown = sheet.rows.size
            val total = if (sheet.totalRowCount > 0) sheet.totalRowCount else shown
            addBanner(
                "'${sheet.name}' 시트는 %,d행 중 앞 %,d행만 표시했습니다.".format(total, shown),
                EditorNotificationPanel.Status.Warning,
            )
        }

        setCenter(center)
    }

    fun showMessage(title: String, detail: String?) {
        banners.removeAll()
        val panel = JBPanelWithEmptyText(BorderLayout())
        panel.emptyText.text = title
        if (!detail.isNullOrBlank()) {
            detail.split('\n').forEach { panel.emptyText.appendLine(it) }
        }
        setCenter(panel)
    }

    private fun setCenter(component: JComponent) {
        content.removeAll()
        content.add(component, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
        banners.revalidate()
        banners.repaint()
    }

    private fun addBanner(text: String, status: EditorNotificationPanel.Status) {
        // (Status) 단일 인자 생성자는 없다. (Color?, Status) 오버로드를 쓴다.
        banners.add(EditorNotificationPanel(null as java.awt.Color?, status).apply { this.text = text })
    }

    private fun describe(book: Workbook): String = buildString {
        append("형식: ").append(book.formatLabel)
        book.charsetName?.let { append(" · 인코딩: ").append(it) }
        append(" · 시트 ").append(book.sheets.size).append("개")
        val cells = book.sheets.sumOf { it.rows.size.toLong() * it.columnCount }
        append(" · 셀 %,d개".format(cells))
    }

    // ---------- 시트 뷰 ----------

    private fun buildSheetView(sheet: Sheet): JComponent {
        val model = SheetTableModel(sheet, useHeader)
        val table = JBTable(model).apply {
            autoResizeMode = JTable.AUTO_RESIZE_OFF
            setShowGrid(true)
            cellSelectionEnabled = true
            setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
            setDefaultRenderer(String::class.java, SheetCellRenderer())
            emptyText.text = "빈 시트입니다."
        }
        TableSpeedSearch.installOn(table)
        fitColumnWidths(table, model)
        tables.add(table)

        return JBScrollPane(table).apply {
            // 행 머리글이 없으면 엑셀 원본과 행을 맞춰볼 수 없다.
            setRowHeaderView(RowHeader(table, model))
        }
    }

    /**
     * 컬럼 폭을 내용에 맞춘다. **앞 200행만 측정한다** — 전체 행 x 전체 열을 재는 것이
     * 표 플러그인이 파일을 열 때 멈춰버리는 전형적인 원인이다.
     */
    private fun fitColumnWidths(table: JBTable, model: SheetTableModel) {
        val metrics = table.getFontMetrics(table.font)
        val headerMetrics = table.tableHeader.getFontMetrics(table.tableHeader.font)
        val sampleRows = model.rowCount.coerceAtMost(WIDTH_SAMPLE_ROWS)

        for (c in 0 until model.columnCount) {
            var width = headerMetrics.stringWidth(model.getColumnName(c)) + HEADER_PADDING
            for (r in 0 until sampleRows) {
                val text = model.cellAt(r, c).text
                if (text.isEmpty()) continue
                // 여러 줄 셀은 가장 긴 줄로 잰다.
                val longest = if ('\n' in text) text.split('\n').maxOf { metrics.stringWidth(it) }
                else metrics.stringWidth(text)
                if (longest > width) width = longest + CELL_PADDING
                if (width >= MAX_COLUMN_WIDTH) break
            }
            table.columnModel.getColumn(c).preferredWidth = width.coerceIn(MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH)
        }
    }

    /** 숫자로 추론된 셀은 우측 정렬한다. */
    private class SheetCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val model = table.model as? SheetTableModel
            horizontalAlignment = if (model != null && model.isNumeric(row, column)) {
                SwingConstants.RIGHT
            } else {
                SwingConstants.LEFT
            }
            toolTipText = (value as? String)?.takeIf { it.isNotBlank() && it.length > TOOLTIP_MIN }
            return component
        }

        private companion object {
            const val TOOLTIP_MIN = 24
        }
    }

    /** 1..N 행 번호를 보여주는 왼쪽 머리글. */
    private class RowHeader(table: JBTable, private val sheetModel: SheetTableModel) : JBTable() {
        init {
            model = object : AbstractTableModel() {
                override fun getRowCount(): Int = sheetModel.rowCount
                override fun getColumnCount(): Int = 1
                override fun getValueAt(rowIndex: Int, columnIndex: Int): Any =
                    sheetModel.sourceRowNumber(rowIndex).toString()
            }
            selectionModel = table.selectionModel
            rowHeight = table.rowHeight
            setShowGrid(false)
            isFocusable = false
            tableHeader = null
            intercellSpacing = JBUI.emptySize()

            val digits = sheetModel.rowCount.coerceAtLeast(1).toString().length
            val width = getFontMetrics(font).charWidth('0') * digits + JBUI.scale(12)
            columnModel.getColumn(0).preferredWidth = width
            preferredScrollableViewportSize = java.awt.Dimension(width, 0)

            setDefaultRenderer(Any::class.java, rowNumberRenderer())
        }

        private fun rowNumberRenderer(): TableCellRenderer = DefaultTableCellRenderer().apply {
            horizontalAlignment = SwingConstants.RIGHT
            foreground = UIUtil.getInactiveTextColor()
            background = UIUtil.getPanelBackground()
            isOpaque = true
            border = JBUI.Borders.emptyRight(4)
        }
    }

    // ---------- 툴바 ----------

    private fun buildActions(): DefaultActionGroup = DefaultActionGroup().apply {
        add(ReloadAction())
        add(ToggleHeaderAction())
        addSeparator()
        add(CopyAllAction())
        add(ExportAction())
    }

    private inner class ReloadAction : AnAction("다시 읽기", "파일을 다시 읽습니다", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = onReload()
    }

    private inner class ToggleHeaderAction : AnAction("첫 행을 머리글로", "머리글 행을 컬럼 이름으로 올립니다", NO_ICON), Toggleable {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            Toggleable.setSelected(e.presentation, useHeader)
            e.presentation.isEnabled = workbook?.sheets?.any { it.headerRowCount > 0 } == true
        }
        override fun actionPerformed(e: AnActionEvent) {
            useHeader = !useHeader
            rebuild()
        }
    }

    private inner class CopyAllAction : AnAction("시트 전체 복사", "탭 구분 텍스트로 클립보드에 복사합니다", AllIcons.Actions.Copy) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentSheet() != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            val sheet = currentSheet() ?: return
            CopyPasteManager.getInstance().setContents(StringSelection(ExportFormats.toTsv(sheet, useHeader)))
        }
    }

    private inner class ExportAction : AnAction("내보내기…", "CSV / JSON / Markdown 으로 저장합니다", AllIcons.ToolbarDecorator.Export) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = currentSheet() != null
        }
        override fun actionPerformed(e: AnActionEvent) {
            val sheet = currentSheet() ?: return
            val group = DefaultActionGroup()
            for (format in ExportFormat.entries) {
                group.add(object : AnAction(format.label) {
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                    override fun actionPerformed(inner: AnActionEvent) = onExport(sheet, format, useHeader)
                })
            }
            JBPopupFactory.getInstance()
                .createActionGroupPopup("내보내기 형식", group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
                .showInBestPositionFor(e.dataContext)
        }
    }

    private companion object {
        val NO_ICON: javax.swing.Icon? = null
        const val TOOLBAR_PLACE = "SheetView.Toolbar"
        const val WIDTH_SAMPLE_ROWS = 200
        val HEADER_PADDING = JBUI.scale(24)
        val CELL_PADDING = JBUI.scale(16)
        val MIN_COLUMN_WIDTH = JBUI.scale(48)
        val MAX_COLUMN_WIDTH = JBUI.scale(400)
    }
}
