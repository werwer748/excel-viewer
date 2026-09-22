package dev.hugo.sheetview.format

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

class SniffResult(val format: SpreadsheetFormat, val charset: Charset?)

/**
 * 확장자가 아니라 **내용**으로 포맷을 판별한다. 이 플러그인의 핵심이며,
 * 기존 엑셀 뷰어 플러그인들이 죽는 지점이다.
 */
object SpreadsheetSniffer {

    const val PROBE_BYTES = 8 * 1024

    private val OLE2 = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    private val META_CHARSET = Regex(
        """charset\s*=\s*["']?\s*([A-Za-z0-9_\-]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val HTML_START = listOf("<html", "<table", "<!doctype html", "<body", "<div", "<meta", "<!--")

    fun sniff(probe: ByteArray): SniffResult {
        if (probe.isEmpty()) {
            return SniffResult(SpreadsheetFormat.UNKNOWN, null)
        }
        if (probe.startsWith(ZIP)) return SniffResult(SpreadsheetFormat.XLSX, null)
        if (probe.startsWith(OLE2)) return SniffResult(SpreadsheetFormat.LEGACY_XLS_BIFF, null)

        val bom = bomLength(probe)
        // 태그 이름은 전부 ASCII이므로 latin-1로 훑어도 안전하고 인코딩에 영향받지 않는다.
        val text = String(probe, bom, probe.size - bom, StandardCharsets.ISO_8859_1)
        val charset = detectCharset(probe, bom, text)

        // 선행 공백 건너뛰기는 필수다. 대상 파일은 CRLF 34바이트가 앞에 붙어 있어
        // `<html`이 오프셋 0에 없다.
        val head = text.trimStart().lowercase()

        if (HTML_START.any { head.startsWith(it) }) {
            return SniffResult(SpreadsheetFormat.EXCEL_HTML, charset)
        }
        if (head.startsWith("<?xml")) {
            val lower = head
            // `<?mso-application progid="Excel.Sheet"?>` 가 네임스페이스보다 신뢰도가 높다.
            // 일부 생성기가 urn: 네임스페이스를 빠뜨린다.
            val isSpreadsheetMl = (lower.contains("mso-application") && lower.contains("excel")) ||
                lower.contains("urn:schemas-microsoft-com:office:spreadsheet") ||
                lower.contains("<workbook")
            if (isSpreadsheetMl) return SniffResult(SpreadsheetFormat.SPREADSHEET_ML, charset)
            if (lower.contains("<table") || lower.contains("<html")) {
                return SniffResult(SpreadsheetFormat.EXCEL_HTML, charset)
            }
            return SniffResult(SpreadsheetFormat.UNKNOWN, charset)
        }
        // 선두 태그가 없어도 앞부분에 표가 있으면 HTML로 본다.
        if (head.contains("<table")) return SniffResult(SpreadsheetFormat.EXCEL_HTML, charset)

        return SniffResult(SpreadsheetFormat.DELIMITED, charset)
    }

    fun bomLength(b: ByteArray): Int = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> 3
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> 2
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> 2
        else -> 0
    }

    /**
     * BOM -> 문서에 선언된 charset -> UTF-8 유효성 검사 -> CP949 순으로 결정한다.
     * 같은 경로로 내려오는 국내 파일이 EUC-KR인 경우가 흔해서 폴백이 필요하다.
     */
    fun detectCharset(probe: ByteArray, bomLen: Int = bomLength(probe), asciiView: String? = null): Charset {
        when (bomLen) {
            3 -> return StandardCharsets.UTF_8
            2 -> return if (probe[0] == 0xFF.toByte()) StandardCharsets.UTF_16LE else StandardCharsets.UTF_16BE
        }
        val view = asciiView
            ?: String(probe, bomLen, probe.size - bomLen, StandardCharsets.ISO_8859_1)
        META_CHARSET.find(view)?.groupValues?.get(1)?.let { declared ->
            runCatching { Charset.forName(declared) }.getOrNull()?.let { return it }
        }
        return if (isValidUtf8(probe, bomLen)) StandardCharsets.UTF_8 else cp949()
    }

    private fun cp949(): Charset = sequenceOf("x-windows-949", "windows-949", "EUC-KR")
        .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
        .firstOrNull() ?: StandardCharsets.UTF_8

    private fun isValidUtf8(b: ByteArray, from: Int): Boolean {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        // 프로브 끝에서 멀티바이트 문자가 잘렸을 수 있으므로 마지막 3바이트는 눈감아 준다.
        val usable = (b.size - from - 3).coerceAtLeast(0)
        return runCatching { decoder.decode(ByteBuffer.wrap(b, from, usable)) }.isSuccess
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }
}
