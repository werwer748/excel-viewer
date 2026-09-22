package dev.hugo.sheetview.source

import dev.hugo.sheetview.format.SpreadsheetFormat

/**
 * 원본 탭이 띄울 수 있는 보기.
 *
 * 포맷마다 "원본"의 의미가 다르다. HTML 위장 `.xls` 는 렌더된 모습과 마크업 둘 다 뜻이 있지만,
 * `.xlsx` 는 ZIP 이라 렌더할 것도 읽을 텍스트도 없고 내부 파트를 보여주는 것이 원본이다.
 */
enum class SourceMode { PREVIEW, TEXT, PARTS }

/**
 * 원본 탭이 그릴 것 전부. 불변이고 플랫폼 클래스를 전혀 참조하지 않아 헤드리스로 테스트된다.
 *
 * [modes] 의 첫 원소가 기본 보기다. 비어 있는 일은 없다 — 최소한 [SourceMode.TEXT] 는 있고,
 * 읽을 것이 없으면 [notice] 가 이유를 말한다.
 */
class SourceDocument(
    val format: SpreadsheetFormat,
    /** 텍스트로 디코드한 경우 실제로 쓴 인코딩. 바이너리 컨테이너면 null. */
    val charsetName: String?,
    val byteSize: Long,
    val modes: List<SourceMode>,
    /** [SourceMode.TEXT] 본문. 바이너리면 16진수 덤프다. */
    val text: String,
    /** 구문 강조에 쓸 파일 타입 이름(`"HTML"` / `"XML"`). 강조하지 않으려면 null. */
    val highlightTypeName: String?,
    val softWrap: Boolean,
    /** [SourceMode.PREVIEW] 용. 이미 [HtmlSanitizer] 를 거친 것만 들어온다. */
    val previewHtml: String?,
    /** [SourceMode.PARTS] 용. 다른 포맷에서는 빈 목록. */
    val parts: List<ZipPart>,
    /** 상한에 걸려 본문을 잘랐는가. */
    val truncated: Boolean,
    /** 배너에 띄울 안내. 표로 못 읽는 이유, 잘린 사실, 차단한 것 등. */
    val notice: String?,
    /** 원본에 이미지가 있었지만 CSP 가 막았는가 (미리보기 배너용). */
    val imagesBlocked: Boolean = false,
    /** 미리보기에서 제거한 `<script>` 개수 (0이면 배너를 띄우지 않는다). */
    val removedScripts: Int = 0,
)
