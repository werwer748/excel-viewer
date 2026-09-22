package dev.hugo.sheetview.source

import dev.hugo.sheetview.format.SpreadsheetFormat
import dev.hugo.sheetview.format.SpreadsheetReaders
import java.io.BufferedInputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream

/**
 * 파일을 "사람이 볼 원본"으로 바꾼다.
 *
 * 포맷 판별은 표 뷰와 **같은** [SpreadsheetReaders.sniff] 를 쓴다 — 두 탭이 같은 파일을 다르게
 * 판별하면 사용자가 무엇을 믿어야 할지 알 수 없다.
 *
 * 예외를 던지지 않는다. 실패는 전부 [SourceDocument.notice] 로 끝난다. 유일한 예외가
 * `checkCancelled` 가 던지는 취소 신호인데, 그건 **반드시 그대로 통과시킨다** — 탭을 닫았는데
 * 읽기가 계속되면 IDE 가 느려진다. 그래서 이 함수에는 포괄 `catch (Throwable)` 을 두지 않고
 * 단계마다 좁게 방어한다.
 */
object SourceReader {

    /**
     * 디코드된 문자열은 UTF-16 이라 바이트 수의 2배 이상을 쓴다. 표 파싱용 상한(64MB)은
     * 뷰어에 과하다 — 에디터에 넣는 순간 UI 가 멈춘다.
     */
    const val MAX_TEXT_BYTES = 8 * 1024 * 1024

    /**
     * 미리보기는 본문 문자열 위에 jsoup DOM 을 한 벌 더 만들고, 그걸 다시 Chromium 에 넘긴다.
     * 본문 상한(8MB)을 그대로 쓰면 메모리가 몇 배로 뛰고 렌더도 오래 멈춘다.
     * 넘으면 미리보기만 포기하고 소스는 그대로 보여준다.
     */
    const val MAX_PREVIEW_BYTES = 2 * 1024 * 1024

    /** 16진수 덤프는 "무슨 파일인지" 알려주는 것이 목적이라 앞부분이면 충분하다. */
    const val HEX_DUMP_BYTES = 64 * 1024

    /** OOXML 시트는 보통 한 줄짜리 수 MB다. 통째로 넣으면 에디터가 멈춘다. */
    const val MAX_PART_BYTES = 4 * 1024 * 1024

    fun read(path: Path, checkCancelled: () -> Unit = {}): SourceDocument {
        checkCancelled()

        val size = runCatching { path.fileSize() }.getOrDefault(0L)
        if (size == 0L) {
            return empty(size, "파일이 비어 있습니다 (0바이트).")
        }

        val probe = runCatching { SpreadsheetReaders.probe(path) }.getOrNull()
            ?: return empty(size, "파일을 열 수 없습니다.")
        val sniffed = SpreadsheetReaders.sniff(path)
        checkCancelled()

        return when (sniffed.format) {
            SpreadsheetFormat.XLSX -> zipDocument(path, size, sniffed.format, checkCancelled)

            SpreadsheetFormat.LEGACY_XLS_BIFF -> hexDocument(
                path, size, sniffed.format,
                notice = "Excel 97-2003 바이너리(OLE2) 형식입니다. 표 탭에서는 읽을 수 없어 " +
                    "원본 바이트만 보여줍니다. 엑셀에서 .xlsx 로 저장하면 표로 볼 수 있습니다.",
                checkCancelled = checkCancelled,
            )

            SpreadsheetFormat.EXCEL_HTML -> htmlDocument(path, size, sniffed.charset, checkCancelled)

            SpreadsheetFormat.SPREADSHEET_ML ->
                textDocument(path, size, sniffed.format, sniffed.charset, "XML", checkCancelled)

            SpreadsheetFormat.DELIMITED ->
                textDocument(path, size, sniffed.format, sniffed.charset, null, checkCancelled)

            SpreadsheetFormat.UNKNOWN ->
                if (looksBinary(probe)) {
                    hexDocument(
                        path, size, sniffed.format,
                        notice = "표 형식으로 알아볼 수 없는 바이너리 파일입니다. " +
                            "확장자가 아니라 내용으로 판별했습니다.",
                        checkCancelled = checkCancelled,
                    )
                } else {
                    textDocument(
                        path, size, sniffed.format, sniffed.charset, null, checkCancelled,
                        notice = "표 형식으로 알아볼 수 없어 원본 텍스트만 보여줍니다.",
                    )
                }
        }
    }

    /** 원본 탭이 파트를 하나 열 때 쓴다. 상한을 한 곳에서만 정하려고 여기 둔다. */
    fun readPart(path: Path, name: String): PartContent? = ZipParts.read(path, name, MAX_PART_BYTES)

    // ---------------------------------------------------------------- 포맷별

    private fun zipDocument(
        path: Path,
        size: Long,
        format: SpreadsheetFormat,
        checkCancelled: () -> Unit,
    ): SourceDocument {
        val listing = ZipParts.list(path)
        checkCancelled()

        if (listing.parts.isEmpty()) {
            // ZIP 으로 열리지 않으면 보여줄 파트가 없다. 그래도 앞부분은 보여 준다 —
            // "PK 로 시작하는데 왜 안 열리나"를 사용자가 직접 확인할 수 있어야 한다.
            return hexDocument(path, size, format, listing.problem, checkCancelled)
        }

        val isXlsb = listing.parts.any { it.name.equals("xl/workbook.bin", ignoreCase = true) }
        return SourceDocument(
            format = format,
            charsetName = null,       // ZIP 컨테이너에는 문서 인코딩이 없다. 파트마다 따로다.
            byteSize = size,
            modes = listOf(SourceMode.PARTS),
            text = "",
            highlightTypeName = "XML",
            // OOXML 시트는 보통 한 줄짜리 수 MB라 소프트랩 없이는 읽을 수 없다.
            softWrap = true,
            previewHtml = null,
            parts = listing.parts,
            truncated = false,
            notice = if (isXlsb) {
                "Excel 바이너리 통합 문서(.xlsb)입니다. 시트가 XML 이 아니라 바이너리라 " +
                    "표 탭에서는 읽을 수 없습니다. 내부 파트는 아래에서 볼 수 있습니다."
            } else {
                null
            },
        )
    }

    private fun htmlDocument(
        path: Path,
        size: Long,
        charset: Charset?,
        checkCancelled: () -> Unit,
    ): SourceDocument {
        val used = charset ?: Charsets.UTF_8
        val (text, truncated) = readText(path, used)
        checkCancelled()

        // 미리보기는 잘리지 않은 본문에서만 만든다. 잘린 HTML 을 렌더하면 표가 중간에 끊겨
        // "원본이 이렇게 생겼다"는 말이 거짓이 된다.
        val tooBigToRender = size > MAX_PREVIEW_BYTES
        val sanitized = if (truncated || tooBigToRender) {
            null
        } else {
            runCatching { HtmlSanitizer.sanitize(text) }.getOrNull()
        }
        checkCancelled()
        val modes = if (sanitized != null) listOf(SourceMode.PREVIEW, SourceMode.TEXT)
        else listOf(SourceMode.TEXT)

        return SourceDocument(
            format = SpreadsheetFormat.EXCEL_HTML,
            charsetName = used.name(),
            byteSize = size,
            modes = modes,
            text = text,
            highlightTypeName = "HTML",
            softWrap = false,         // 실제 줄바꿈이 있는 문서라 소프트랩은 방해만 된다.
            previewHtml = sanitized?.html,
            parts = emptyList(),
            truncated = truncated,
            notice = truncatedNotice(truncated, size) ?: previewSkippedNotice(tooBigToRender, size),
            imagesBlocked = sanitized?.imagesBlocked ?: false,
            removedScripts = sanitized?.removedScripts ?: 0,
        )
    }

    private fun textDocument(
        path: Path,
        size: Long,
        format: SpreadsheetFormat,
        charset: Charset?,
        highlight: String?,
        checkCancelled: () -> Unit,
        notice: String? = null,
    ): SourceDocument {
        val used = charset ?: Charsets.UTF_8
        val (text, truncated) = readText(path, used)
        checkCancelled()
        return SourceDocument(
            format = format,
            charsetName = used.name(),
            byteSize = size,
            modes = listOf(SourceMode.TEXT),
            text = text,
            highlightTypeName = highlight,
            softWrap = false,
            previewHtml = null,
            parts = emptyList(),
            truncated = truncated,
            notice = truncatedNotice(truncated, size) ?: notice,
        )
    }

    private fun hexDocument(
        path: Path,
        size: Long,
        format: SpreadsheetFormat,
        notice: String?,
        checkCancelled: () -> Unit,
    ): SourceDocument {
        val bytes = readBytes(path, HEX_DUMP_BYTES)
        checkCancelled()
        val truncated = size > bytes.size
        return SourceDocument(
            format = format,
            charsetName = null,
            byteSize = size,
            modes = listOf(SourceMode.TEXT),
            text = hexDump(bytes, checkCancelled),
            // 덤프에 구문 강조를 주면 16진수가 엉뚱한 색으로 칠해져 읽기만 나빠진다.
            highlightTypeName = null,
            softWrap = false,
            previewHtml = null,
            parts = emptyList(),
            truncated = truncated,
            notice = listOfNotNull(
                notice,
                if (truncated) "앞 %,d바이트만 보여줍니다 (전체 %,d바이트).".format(bytes.size, size) else null,
            ).joinToString(" ").ifBlank { null },
        )
    }

    private fun empty(size: Long, notice: String) = SourceDocument(
        format = SpreadsheetFormat.UNKNOWN,
        charsetName = null,
        byteSize = size,
        modes = listOf(SourceMode.TEXT),
        text = "",
        highlightTypeName = null,
        softWrap = false,
        previewHtml = null,
        parts = emptyList(),
        truncated = false,
        notice = notice,
    )

    // ---------------------------------------------------------------- 도우미

    /**
     * BOM 은 떼지 않는다. 이 탭의 용도가 "원본과 줄·바이트를 대조하는 것"이라 보이지 않는
     * 문자까지 원본 그대로 두는 편이 맞다.
     *
     * 상한에서 잘리면 마지막 멀티바이트 문자가 쪼개져 U+FFFD 하나가 남을 수 있다.
     * 잘렸다는 사실을 배너로 이미 말하므로 그 한 글자를 숨기려고 복잡하게 만들지 않는다.
     */
    private fun readText(path: Path, charset: Charset): Pair<String, Boolean> {
        val bytes = readBytes(path, MAX_TEXT_BYTES)
        val truncated = runCatching { path.fileSize() }.getOrDefault(0L) > bytes.size
        return runCatching { String(bytes, charset) }.getOrDefault("") to truncated
    }

    private fun readBytes(path: Path, limit: Int): ByteArray = runCatching {
        BufferedInputStream(path.inputStream()).use { input ->
            val size = runCatching { Files.size(path) }.getOrDefault(limit.toLong())
            val want = minOf(size, limit.toLong()).toInt().coerceAtLeast(0)
            val buffer = ByteArray(want)
            val read = input.readNBytes(buffer, 0, want)
            if (read == want) buffer else buffer.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    /** 앞 8KB 에 NUL 이 있으면 텍스트로 보여줘 봐야 제어문자만 뜬다. */
    private fun looksBinary(probe: ByteArray): Boolean = probe.any { it == 0.toByte() }

    private fun previewSkippedNotice(tooBig: Boolean, size: Long): String? =
        if (tooBig) {
            "파일이 %,.1fMB 라 미리보기는 만들지 않았습니다 (상한 %,dMB). 소스는 그대로 볼 수 있습니다."
                .format(size / 1024.0 / 1024.0, MAX_PREVIEW_BYTES / 1024 / 1024)
        } else {
            null
        }

    private fun truncatedNotice(truncated: Boolean, size: Long): String? =
        if (truncated) {
            "파일이 커서 앞 %,dMB 만 보여줍니다 (전체 %,.1fMB).".format(
                MAX_TEXT_BYTES / 1024 / 1024, size / 1024.0 / 1024.0,
            )
        } else {
            null
        }

    private const val BYTES_PER_LINE = 16
    private const val HEX_COLUMN_WIDTH = BYTES_PER_LINE / 2 * 3 - 1   // "00 00 ... 00" 8개분

    /**
     * `hexdump -C` 와 같은 배치다. 8바이트마다 한 칸 더 벌려 두면 매직바이트를 눈으로 세기 쉽다.
     * 마지막 줄이 덜 차도 ASCII 칸의 시작 위치가 같아야 위아래를 대조할 수 있으므로
     * 16진수 칸을 반드시 고정폭으로 채운다.
     */
    private fun hexDump(bytes: ByteArray, checkCancelled: () -> Unit): String {
        val sb = StringBuilder(bytes.size / BYTES_PER_LINE * 80)
        var offset = 0
        while (offset < bytes.size) {
            if (offset % (BYTES_PER_LINE * 512) == 0) checkCancelled()
            val end = minOf(offset + BYTES_PER_LINE, bytes.size)
            sb.append("%08X".format(offset)).append("  ")
            appendHexGroup(sb, bytes, offset, minOf(offset + 8, end))
            sb.append("  ")
            appendHexGroup(sb, bytes, minOf(offset + 8, end), end)
            sb.append("  |")
            for (i in offset until end) {
                val c = bytes[i].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append("|\n")
            offset = end
        }
        return sb.toString()
    }

    private fun appendHexGroup(sb: StringBuilder, bytes: ByteArray, from: Int, to: Int) {
        val start = sb.length
        for (i in from until to) {
            if (i > from) sb.append(' ')
            sb.append("%02X".format(bytes[i].toInt() and 0xFF))
        }
        // 덜 찬 줄을 공백으로 메워 ASCII 칸을 고정한다.
        repeat(HEX_COLUMN_WIDTH - (sb.length - start)) { sb.append(' ') }
    }
}
