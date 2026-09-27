package dev.hugo.sheetview.source

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** ZIP 안의 파트 하나. [size]는 압축 전 크기다 (모르면 -1). */
class ZipPart(val name: String, val size: Long)

/** [ZipParts.list] 결과. 읽지 못했으면 [parts]가 비고 [problem]에 사유가 담긴다. */
class ZipListing(val parts: List<ZipPart>, val problem: String?)

/**
 * [ZipParts.read] 결과.
 *
 * [looksBinary] 가 참이면 [text] 를 에디터에 넣어 봐야 깨진 글자만 뜬다 (.xlsb 의 시트가 그렇다).
 * 읽는 쪽에서 미리 알려주지 않으면 사용자는 플러그인이 고장난 줄 안다.
 */
class PartContent(val text: String, val truncated: Boolean, val looksBinary: Boolean)

/**
 * `.xlsx` 는 사실 ZIP 이다. 바이너리 워크북에서 "원본"을 보여줄 방법은 내부 파트를 그대로
 * 내보이는 것뿐이라, 원본 탭이 이 목록을 왼쪽에 놓고 선택한 파트의 XML 을 오른쪽에 보여준다.
 *
 * 예외를 던지지 않는다. 이 플러그인의 규약대로 실패는 전부 사유가 담긴 값으로 끝난다.
 */
object ZipParts {

    /**
     * 엔트리 순서는 ZIP 을 만든 도구마다 다르고 같은 도구도 매번 같지 않다. 파일에 맡기면
     * 사용자는 열 때마다 다른 목록을 본다. 그래서 OOXML 을 읽는 순서로 고정한다:
     * 어떤 파트가 있는지(Content_Types) -> 관계 -> 워크북 -> 시트 -> 값·서식 -> 나머지.
     */
    private fun rank(name: String): Int = when {
        name == "[Content_Types].xml" -> 0
        name.startsWith("_rels/") -> 1
        name == "xl/workbook.xml" || name == "xl/workbook.bin" -> 2
        name.startsWith("xl/_rels/") -> 3
        name.startsWith("xl/worksheets/") -> 4
        name == "xl/sharedStrings.xml" -> 5
        name == "xl/styles.xml" -> 6
        else -> 7
    }

    /**
     * 같은 등급 안에서는 이름순이되, 이름에 박힌 숫자는 숫자로 본다.
     * 사전순이면 `sheet10` 이 `sheet2` 앞에 와서 엑셀의 시트 순서와 어긋난다.
     */
    private val NUMBER = Regex("""\d+""")

    private fun naturalKey(name: String): List<String> {
        val out = ArrayList<String>()
        var last = 0
        for (m in NUMBER.findAll(name)) {
            out.add(name.substring(last, m.range.first))
            // 0 으로 채워 문자열 비교만으로 숫자 순서가 나오게 한다 (자리수 상한 12자리).
            out.add(m.value.trimStart('0').padStart(12, '0'))
            last = m.range.last + 1
        }
        out.add(name.substring(last))
        return out
    }

    private val ORDER = compareBy<ZipPart>({ rank(it.name) }, { naturalKey(it.name).joinToString("\u0000") })

    fun list(path: Path): ZipListing = try {
        ZipFile(path.toFile()).use { zip ->
            val parts = zip.entries().asSequence()
                .filter { !it.isDirectory && !it.name.endsWith("/") }
                .map { ZipPart(it.name, it.size) }
                .sortedWith(ORDER)
                .toList()
            if (parts.isEmpty()) {
                ZipListing(emptyList(), "ZIP 안에 파일이 없습니다.")
            } else {
                ZipListing(parts, null)
            }
        }
    } catch (t: Throwable) {
        // OutOfMemoryError 까지 포함해 값으로 끝낸다. 원본 탭은 어떤 파일에도 죽지 않아야 한다.
        ZipListing(emptyList(), describe(t))
    }

    /**
     * 파트 하나를 텍스트로 읽는다. 없으면 null.
     *
     * [maxBytes] 로 자르는 이유가 메모리만은 아니다. OOXML 시트는 보통 **한 줄짜리 수 MB** 라
     * 그대로 에디터에 넣으면 UI 가 멈춘다. 잘라서 주고 잘랐다고 말하는 편이 낫다.
     */
    fun read(path: Path, name: String, maxBytes: Int): PartContent? = try {
        ZipFile(path.toFile()).use { zip ->
            val entry: ZipEntry? = zip.getEntry(name)
            if (entry == null || entry.isDirectory) {
                null
            } else {
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(maxBytes)
                    val read = input.readNBytes(buffer, 0, maxBytes)
                    // 상한만큼 읽혔다고 끝난 것은 아니다. 한 바이트 더 읽어 봐야 안다.
                    val truncated = read == maxBytes && input.read() != -1
                    // NUL 바이트는 텍스트 파트에 나올 수 없다. OOXML 은 전부 UTF-8 XML 이다.
                    val binary = (0 until read).any { buffer[it] == 0.toByte() }
                    PartContent(
                        String(buffer, 0, read, StandardCharsets.UTF_8),
                        truncated,
                        binary,
                    )
                }
            }
        }
    } catch (t: Throwable) {
        null
    }

    private fun describe(t: Throwable): String = when (t) {
        is java.util.zip.ZipException -> "ZIP 으로 열 수 없습니다: ${t.message ?: "형식이 깨졌습니다"}"
        is java.io.FileNotFoundException -> "파일을 찾을 수 없습니다."
        is java.io.IOException -> "파일을 읽을 수 없습니다: ${t.message ?: t::class.simpleName}"
        else -> "ZIP 내부를 읽는 중 오류가 발생했습니다: ${t::class.simpleName}"
    }
}
