package dev.hugo.sheetview.format

enum class SpreadsheetFormat(val label: String) {
    XLSX("XLSX"),

    /**
     * 확장자는 .xls지만 내용은 MS Office 네임스페이스가 붙은 HTML 표.
     * 카드사·은행·관공서의 "엑셀로 저장"이 대부분 이 포맷이고,
     * Apache POI로 워크북을 열려는 플러그인이 여기서 죽는다.
     */
    EXCEL_HTML("Excel HTML"),

    /** Excel 2003 XML (SpreadsheetML). */
    SPREADSHEET_ML("Excel 2003 XML"),

    DELIMITED("CSV/TSV"),

    /** 진짜 .xls 바이너리. 이 플러그인은 의도적으로 지원하지 않는다. */
    LEGACY_XLS_BIFF("Excel 97-2003 (BIFF)"),

    UNKNOWN("알 수 없는 형식"),
}
