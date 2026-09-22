package dev.hugo.sheetview.format

import dev.hugo.sheetview.model.Workbook
import java.io.BufferedInputStream
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream

/** 판별에서 실제 읽기까지의 단일 진입점. */
object SpreadsheetReaders {

    fun probe(path: Path): ByteArray = BufferedInputStream(path.inputStream()).use { ins ->
        val buffer = ByteArray(SpreadsheetSniffer.PROBE_BYTES)
        val read = ins.readNBytes(buffer, 0, buffer.size)
        if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    fun sniff(path: Path): SniffResult = SpreadsheetSniffer.sniff(probe(path))

    fun read(
        path: Path,
        forcedCharset: Charset? = null,
        limits: ReadLimits = ReadLimits(),
        checkCancelled: () -> Unit = {},
    ): Workbook {
        val size = path.fileSize()
        if (size == 0L) {
            throw UnsupportedSpreadsheetException("파일이 비어 있습니다 (0바이트).")
        }
        val sniffed = sniff(path)
        guardSize(size, sniffed.format)
        val reader = readerFor(sniffed.format)
        val ctx = ReadContext(
            path = path,
            charset = forcedCharset ?: sniffed.charset,
            limits = limits,
            checkCancelled = checkCancelled,
        )
        return reader.read(ctx)
    }

    /**
     * 파싱 전에 크기를 막는다. XLSX는 스트리밍으로 읽으므로 넉넉히 허용하지만,
     * HTML/XML/CSV 경로는 파일 전체를 String으로 올리기 때문에 (UTF-16 문자열이라
     * 바이트 수의 두 배 이상을 쓴다) 훨씬 낮게 잡아야 한다.
     */
    private fun guardSize(size: Long, format: SpreadsheetFormat) {
        val limit = when (format) {
            SpreadsheetFormat.XLSX -> STREAMING_LIMIT
            else -> IN_MEMORY_LIMIT
        }
        if (size > limit) {
            throw UnsupportedSpreadsheetException(
                "파일이 너무 큽니다 (%,.1f MB).".format(size / 1024.0 / 1024.0),
                "이 형식은 %,d MB 까지 엽니다. 엑셀에서 필요한 부분만 잘라 저장한 뒤 다시 열어 주세요."
                    .format(limit / 1024 / 1024),
            )
        }
    }

    private const val STREAMING_LIMIT = 512L * 1024 * 1024
    private const val IN_MEMORY_LIMIT = 64L * 1024 * 1024

    private fun readerFor(format: SpreadsheetFormat): SpreadsheetReader = when (format) {
        SpreadsheetFormat.XLSX -> XlsxReader()
        SpreadsheetFormat.EXCEL_HTML -> ExcelHtmlReader()
        SpreadsheetFormat.SPREADSHEET_ML -> SpreadsheetMlReader()
        SpreadsheetFormat.DELIMITED -> DelimitedReader()

        // 진짜 .xls 바이너리. 여기서 예외를 던지는 것이 POI를 끌고 오는 것보다 낫다는
        // 판단이다. 사용자에게는 스택트레이스가 아니라 안내 패널로 보인다.
        SpreadsheetFormat.LEGACY_XLS_BIFF -> SpreadsheetReader {
            throw UnsupportedSpreadsheetException(
                "Excel 97-2003 바이너리 형식(.xls)은 지원하지 않습니다.",
                "이 파일은 확장자와 내용이 모두 구형 .xls 바이너리입니다. " +
                    "엑셀이나 Numbers에서 .xlsx로 저장한 뒤 다시 열어 주세요.",
            )
        }

        SpreadsheetFormat.UNKNOWN -> SpreadsheetReader {
            throw UnsupportedSpreadsheetException(
                "표 형식으로 알아볼 수 없는 파일입니다.",
                "확장자가 아니라 파일 내용으로 판별했습니다. XLSX(ZIP), Excel HTML, " +
                    "Excel 2003 XML, CSV/TSV 중 어느 것과도 맞지 않습니다.",
            )
        }
    }

    /** 람다로 리더를 만들기 위한 SAM 변환 도우미. */
    private fun SpreadsheetReader(block: (ReadContext) -> Workbook): SpreadsheetReader =
        object : SpreadsheetReader {
            override fun read(ctx: ReadContext): Workbook = block(ctx)
        }
}
