package dev.hugo.sheetview.editor

import dev.hugo.sheetview.model.Cell
import dev.hugo.sheetview.model.CellType
import dev.hugo.sheetview.model.Sheet
import javax.swing.table.AbstractTableModel

/**
 * 읽기 전용 테이블 모델.
 *
 * 별도의 윈도잉(가상 스크롤) 구현은 두지 않는다. Swing은 보이는 셀에 대해서만
 * `getValueAt`을 호출하므로, 리더가 이미 그리드를 메모리에 만들어 둔 이상
 * 추가로 가상화할 것이 없다. 대용량은 리더 쪽 행/셀 상한으로 막는다.
 */
class SheetTableModel(
    val sheet: Sheet,
    /** 헤더행을 컬럼 이름으로 올릴지 여부. 끄면 헤더행도 데이터로 보인다. */
    useHeader: Boolean,
) : AbstractTableModel() {

    private val skippedRows = if (useHeader) sheet.headerRowCount else 0
    private val labels = sheet.headerLabels(skippedRows)

    override fun getRowCount(): Int = (sheet.rows.size - skippedRows).coerceAtLeast(0)

    override fun getColumnCount(): Int = sheet.columnCount

    override fun getColumnName(column: Int): String =
        labels.getOrElse(column) { Sheet.columnName(column) }

    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = cellAt(rowIndex, columnIndex).text

    fun cellAt(rowIndex: Int, columnIndex: Int): Cell = sheet.cell(rowIndex + skippedRows, columnIndex)

    /** 원본 시트에서의 행 번호 (1-based). 행 머리글 표시에 쓴다. */
    fun sourceRowNumber(rowIndex: Int): Int = rowIndex + skippedRows + 1

    fun isNumeric(rowIndex: Int, columnIndex: Int): Boolean =
        cellAt(rowIndex, columnIndex).type == CellType.NUMBER
}
