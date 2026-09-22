package dev.hugo.sheetview.model

enum class CellType { BLANK, TEXT, NUMBER, DATE }

/**
 * 한 셀. [text]는 화면·내보내기에 쓰는 표시 문자열이고, [number]는 숫자로 판별된
 * 경우에만 채워진다 (JSON 내보내기에서 문자열이 아닌 숫자로 내보내기 위함).
 */
class Cell(
    @JvmField val text: String,
    @JvmField val type: CellType,
    @JvmField val number: Double? = null,
) {
    val isBlank: Boolean get() = type == CellType.BLANK

    companion object {
        @JvmField
        val BLANK = Cell("", CellType.BLANK)
    }
}

/**
 * 시트 하나. [rows]는 헤더행과 데이터행을 **모두** 담고, 앞쪽 [headerRowCount]개가
 * 헤더다. 이렇게 분리해 두면 뷰에서 "첫 행을 헤더로" 토글을 데이터 재파싱 없이 처리할 수 있다.
 */
class Sheet(
    val name: String,
    val rows: List<List<Cell>>,
    val headerRowCount: Int,
    val columnCount: Int,
    /** 상한을 적용하기 전 원본 행 수. 모르면 -1. */
    val totalRowCount: Int,
    val truncated: Boolean,
) {
    val dataRowCount: Int get() = (rows.size - headerRowCount).coerceAtLeast(0)

    fun cell(row: Int, col: Int): Cell = rows.getOrNull(row)?.getOrNull(col) ?: Cell.BLANK

    /**
     * JTable 헤더는 한 줄이라 다단 헤더를 열 단위로 합친다.
     * rowspan 때문에 같은 값이 여러 행에 복제되므로 중복은 제거한다.
     * 예) "이번 달 입금하실 금액" + "원금" -> "이번 달 입금하실 금액 / 원금"
     */
    fun headerLabels(headerRows: Int = headerRowCount): List<String> = (0 until columnCount).map { col ->
        val parts = (0 until headerRows.coerceAtMost(rows.size))
            .map { cell(it, col).text.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (parts.isEmpty()) columnName(col) else parts.joinToString(" / ")
    }

    companion object {
        /** 헤더가 없을 때 쓰는 A, B, ... Z, AA 형식의 열 이름. */
        fun columnName(index: Int): String {
            var n = index
            val sb = StringBuilder()
            while (true) {
                sb.append('A' + n % 26)
                n = n / 26 - 1
                if (n < 0) break
            }
            return sb.reverse().toString()
        }
    }
}

class Workbook(
    val sheets: List<Sheet>,
    /** UI 배너에 띄울 판별된 포맷 이름. */
    val formatLabel: String,
    /** 텍스트 기반 포맷에서 실제로 쓴 인코딩. 바이너리면 null. */
    val charsetName: String? = null,
)
