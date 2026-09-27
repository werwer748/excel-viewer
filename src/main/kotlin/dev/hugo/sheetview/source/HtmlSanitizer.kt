package dev.hugo.sheetview.source

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document

/** [HtmlSanitizer.sanitize] 의 결과. [imagesBlocked] 와 [removedScripts] 는 배너 문구용이다. */
class SanitizedHtml(
    val html: String,
    val imagesBlocked: Boolean,
    val removedScripts: Int,
)

/**
 * 원본 미리보기에 넣기 전에 HTML 을 정화한다.
 *
 * 왜 필요한가: 미리보기는 **출처를 알 수 없는 파일**을 IDE 프로세스 안의 진짜
 * Chromium(JCEF)에 띄운다. 스크립트가 있으면 실행되고, 외부 참조가 있으면 네트워크로 나간다.
 * 실제 카드 명세서에는 스크립트·이미지·외부 URL 이 한 곳도 없지만(실측), 다음 달 파일은 모른다.
 *
 * 두 겹으로 막는다:
 *  1. **CSP 주입** — `default-src 'none'` 하나로 스크립트 실행과 모든 네트워크 요청이 끊긴다.
 *  2. **태그·속성 제거** — CSP 를 해석하지 않는 렌더 경로에 대비한 이중 방어.
 *
 * `Jsoup.clean(Safelist)` 는 **쓰지 않는다.** 이 파일들의 스타일은 전부 `<style>` 블록과
 * `class` 선택자에 있어서 Safelist 가 그것을 날려 버리면 "원본을 보여준다"는 말이 거짓이 된다.
 * 그래서 위험한 것만 지목해 제거하고 나머지는 건드리지 않는다.
 */
object HtmlSanitizer {

    /**
     * `style-src 'unsafe-inline'` 은 인라인 `<style>` 을 살리기 위해 필요하다 — 원본의 격자선·
     * 헤더 배경·서체가 전부 거기 있다. 스크립트는 `default-src 'none'` 이 이미 막으므로
     * 이 예외가 스크립트 실행을 허용하지는 않는다.
     * `img-src data:` 는 내장 이미지만 허용한다 — 외부·상대 경로 이미지는 네트워크·디스크를
     * 건드리므로 차단하고, 대신 [SanitizedHtml.imagesBlocked] 로 사용자에게 알린다.
     */
    const val CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:;"

    private const val CSP_HEADER = "Content-Security-Policy"

    /**
     * 원본은 IDE 테마와 **무관하게** 항상 밝게 본다.
     *
     * `color-scheme: only light` 가 핵심이다. 이 파일들은 `color:windowtext` 같은 CSS 시스템
     * 컬러를 쓰는데(대상 파일에 4곳), 시스템 컬러는 페이지의 color-scheme 에 따라 해석이
     * 바뀐다. 다크 모드 Chromium 에서는 밝은 색이 되어 글자가 사라진다. 표 격자(#808080)와
     * 헤더 배경(#d9d9d9)은 고정값이라 남아서 "표는 보이는데 글자가 안 보이는" 모양이 된다.
     *
     * 명시도를 일부러 낮게 둔다 — `<head>` 맨 앞(문서 자신의 `<style>` 보다 앞)에 넣으므로
     * `th{background:#d9d9d9}` 같은 원본 규칙은 그대로 이긴다. 우리는 캔버스만 정한다.
     */
    const val BASE_STYLE = ":root{color-scheme:only light}" +
        "html,body{background:#ffffff}" +
        "body{color:#1a1a1a}"

    /** 외부를 끌어오거나 탐색·입력을 일으키는 태그. `<style>` 과 `<img>` 는 여기 없다. */
    private val DANGEROUS_TAGS = listOf(
        "script", "iframe", "frame", "frameset", "object", "embed", "applet",
        "base", "link", "form", "portal",
    )

    /** URL 을 담을 수 있는 속성. `javascript:` 같은 스킴이 들어오면 속성째로 지운다. */
    private val URL_ATTRS = listOf(
        "href", "src", "srcset", "action", "formaction", "background",
        "data", "poster", "cite", "longdesc", "xlink:href",
    )

    private val DANGEROUS_SCHEMES = listOf("javascript:", "vbscript:", "livescript:", "mocha:")

    fun sanitize(html: String): SanitizedHtml {
        // Jsoup.parse 는 조각 입력도 html/head/body 를 갖춘 문서로 만들어 준다.
        // 그래서 "<head> 가 없는 조각" 을 따로 분기할 필요가 없다.
        val doc = Jsoup.parse(html)
        doc.outputSettings().charset("UTF-8")

        val removedScripts = doc.select("script").size
        for (tag in DANGEROUS_TAGS) doc.select(tag).remove()

        // meta 는 전부 남기되 페이지를 옮기는 refresh 만 뺀다. 문자 인코딩 meta 는 무해하고,
        // 지우면 원본 소스와 미리보기가 달라 보인다.
        doc.select("meta").filter { it.attr("http-equiv").trim().equals("refresh", ignoreCase = true) }
            .forEach { it.remove() }

        val imagesBlocked = doc.select("img").any { img ->
            val src = img.attr("src").trim()
            src.isNotEmpty() && !src.startsWith("data:", ignoreCase = true)
        }

        for (element in doc.getAllElements()) {
            // 이벤트 핸들러: on* 전부. 속성 이름은 순회 중에 지우면 안 되므로 먼저 모은다.
            element.attributes().asList()
                .map { it.key }
                .filter { it.startsWith("on", ignoreCase = true) && it.length > 2 }
                .forEach { element.removeAttr(it) }

            for (attr in URL_ATTRS) {
                val value = element.attr(attr)
                if (value.isNotEmpty() && isDangerousUrl(value)) element.removeAttr(attr)
            }

            // 인라인 style 안의 시스템 컬러도 라이트 값으로 굳힌다.
            val style = element.attr("style")
            if (style.isNotEmpty()) {
                val fixed = freezeSystemColors(style)
                if (fixed != style) element.attr("style", fixed)
            }
        }

        // <style> 블록 안의 시스템 컬러. DataNode 를 직접 바꾼다 (텍스트 노드가 아니다).
        for (styleTag in doc.select("style")) {
            for (node in styleTag.dataNodes()) {
                val fixed = freezeSystemColors(node.wholeData)
                if (fixed != node.wholeData) node.setWholeData(fixed)
            }
        }

        injectCsp(doc)
        injectBaseStyle(doc)
        return SanitizedHtml(doc.outerHtml(), imagesBlocked, removedScripts)
    }

    /**
     * CSS 시스템 컬러를 라이트 모드에서의 값으로 굳힌다. [BASE_STYLE] 의 `color-scheme` 만으로
     * 충분할 것으로 보지만, 브라우저 렌더링은 헤드리스 테스트로 확인할 수 없어 두 겹으로 막는다.
     * 라이트 모드에서 의미가 같은 값이라 보이는 결과는 달라지지 않는다.
     *
     * **선언 값 안에서만 바꾼다.** `\bwindow\b` 를 통째로 치환하면 `.window{…}` 같은 클래스
     * 이름이 `.#ffffff{…}` 가 되어 스타일시트 전체가 깨진다. `:` 뒤부터 `;`/`{`/`}` 전까지를
     * 값으로 보므로 `a:hover` 는 값이 `hover` 라 걸리지 않고, `@media (…:dark)` 도 안전하다.
     */
    private fun freezeSystemColors(css: String): String =
        DECLARATION_VALUE.replace(css) { match ->
            ":" + SYSTEM_COLOR.replace(match.groupValues[1]) { color ->
                if (color.value.equals("windowtext", ignoreCase = true)) "#000000" else "#ffffff"
            }
        }

    private val DECLARATION_VALUE = Regex(""":([^;{}]*)""")

    /** 긴 것을 먼저 둬야 `windowtext` 가 `window` 로 잘리지 않는다. */
    private val SYSTEM_COLOR = Regex("""\b(?:windowtext|window)\b""", RegexOption.IGNORE_CASE)

    private fun injectBaseStyle(doc: Document) {
        val style = doc.createElement("style").attr("data-sheetview", "base")
        style.appendChild(DataNode(BASE_STYLE))
        // CSP meta 다음, 문서 자신의 <style> 보다는 앞.
        doc.head().insertChildren(if (doc.head().childrenSize() > 0) 1 else 0, style)
    }

    /**
     * 파일이 자기 CSP 를 갖고 있을 수 있다. 그쪽이 우리 것보다 느슨하면 방어가 무력해지므로
     * 기존 것을 지우고 우리 것을 `<head>` **맨 앞**에 넣는다. 뒤에 넣으면 그 앞의 리소스
     * 선언이 정책 적용 전에 평가될 수 있다.
     */
    private fun injectCsp(doc: Document) {
        val head = doc.head()
        head.select("meta")
            .filter { it.attr("http-equiv").trim().equals(CSP_HEADER, ignoreCase = true) }
            .forEach { it.remove() }
        head.prependChild(
            doc.createElement("meta")
                .attr("http-equiv", CSP_HEADER)
                .attr("content", CSP),
        )
    }

    /**
     * 스킴 앞에 끼워 넣은 공백·제어문자(`java\tscript:`)로 검사를 피하는 고전적인 우회가 있어
     * 비교 전에 전부 걷어낸다. 문서 내부 앵커(`#…`)는 위험하지 않으므로 남긴다.
     */
    private fun isDangerousUrl(value: String): Boolean {
        val normalized = value.filterNot { it.isWhitespace() || it.code < 0x20 }.lowercase()
        if (DANGEROUS_SCHEMES.any { normalized.startsWith(it) }) return true
        if (!normalized.startsWith("data:")) return false
        // data: 중에서도 스크립트를 실행시킬 수 있는 것만 막는다 (이미지 data URI 는 허용).
        return normalized.startsWith("data:text/html") ||
            normalized.startsWith("data:image/svg") ||
            normalized.startsWith("data:application/xhtml")
    }
}
