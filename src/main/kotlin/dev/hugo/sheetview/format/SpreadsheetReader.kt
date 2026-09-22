package dev.hugo.sheetview.format

import dev.hugo.sheetview.model.Workbook
import java.nio.charset.Charset
import java.nio.file.Path

class ReadLimits(
    val maxRows: Int = 50_000,
    val maxColumns: Int = 1_024,
    /** 잘린 행 수를 세기 위한 스캔 상한. 병적으로 큰 파일에서 무한 스캔을 막는다. */
    val maxScanRows: Int = 5_000_000,
    /**
     * 행 x 열 총 셀 수 상한. 행만 제한하면 부족하다 — Excel HTML은 빈 <td>를
     * 수천 열씩 뿜는 경우가 있어 행 상한 안에서도 메모리를 다 쓸 수 있다.
     */
    val maxCells: Int = 2_000_000,
    /** sharedStrings.xml 항목 상한. 워크시트보다 이쪽이 OOM 원인인 경우가 더 많다. */
    val maxSharedStrings: Int = 2_000_000,
) {
    /** 열 수를 알고 난 뒤 총 셀 수 상한까지 반영한 실제 행 상한. */
    fun rowLimit(columnCount: Int): Int =
        if (columnCount <= 0) maxRows else minOf(maxRows, maxCells / columnCount).coerceAtLeast(1)
}

class ReadContext(
    val path: Path,
    val charset: Charset?,
    val limits: ReadLimits = ReadLimits(),
    /** 취소되었으면 예외를 던진다. 리더는 루프 안에서 주기적으로 호출한다. */
    val checkCancelled: () -> Unit = {},
)

interface SpreadsheetReader {
    fun read(ctx: ReadContext): Workbook
}

/**
 * 읽을 수는 없지만 정상적인 상황 (지원하지 않는 포맷 등).
 * 사용자에게 스택트레이스가 아니라 [userMessage]와 [hint]를 보여준다.
 */
class UnsupportedSpreadsheetException(
    val userMessage: String,
    val hint: String? = null,
) : Exception(userMessage)

/** 포맷은 맞지만 내용이 깨진 경우. */
class SpreadsheetParseException(
    val userMessage: String,
    val hint: String? = null,
    cause: Throwable? = null,
) : Exception(userMessage, cause)
