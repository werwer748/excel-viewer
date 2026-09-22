package dev.hugo.sheetview.format

import dev.hugo.sheetview.model.Cell
import dev.hugo.sheetview.model.CellType

/**
 * HTML·CSV 경로의 셀에는 서식 메타데이터가 없다. (이 플러그인을 만든 계기가 된 카드
 * 명세서를 실측한 결과 mso-number-format 과 x:num 속성이 단 한 곳도 없었고, 금액과
 * 날짜가 `123,450원`, `98,760`, `2026.03.11` 같은 순수 텍스트로만 들어 있었다.
 * 같은 형태를 `fixtures/statement.xls` 가 재현한다.)
 * 그래서 텍스트에서 타입을 추론해 숫자 우측 정렬과 JSON 내보내기의 숫자 타입을 살린다.
 */
object CellTypeInference {

    /** `2026.08.10`, `2026-09-25`, `2026/9/8` 형태. 3요소이므로 소수와 혼동되지 않는다. */
    private val DATE = Regex("""^(\d{4})[.\-/](\d{1,2})[.\-/](\d{1,2})\.?$""")

    private val NUMBER = Regex("""^[+-]?\d+(\.\d+)?$""")

    /** 금액 뒤에 붙는 단위. 명세서에는 `123,450원` 처럼 `원`이 붙은 값이 섞여 있다. */
    private val TRAILING_UNIT = Regex("""\s*(원|%|USD|KRW|달러)$""")

    /** 회계 표기의 음수: `(1,234)` -> -1234 */
    private val PARENTHESIZED = Regex("""^\((.+)\)$""")

    fun infer(raw: String): Cell {
        val text = raw.trim()
        if (text.isEmpty()) return Cell.BLANK

        DATE.matchEntire(text)?.let { m ->
            val month = m.groupValues[2].toInt()
            val day = m.groupValues[3].toInt()
            // 범위를 확인해 `1361.50` 같은 소수를 날짜로 오인하지 않는다.
            if (month in 1..12 && day in 1..31) return Cell(text, CellType.DATE)
        }

        var body = text
        var negative = false
        PARENTHESIZED.matchEntire(body)?.let { body = it.groupValues[1]; negative = true }
        body = TRAILING_UNIT.replace(body, "").replace(",", "").trim()

        // `USD` 처럼 단위만 있는 셀이 빈 문자열로 줄어드는 경우를 걸러낸다.
        if (body.isNotEmpty() && NUMBER.matches(body)) {
            body.toDoubleOrNull()?.let { n ->
                return Cell(text, CellType.NUMBER, if (negative) -n else n)
            }
        }
        return Cell(text, CellType.TEXT)
    }
}
