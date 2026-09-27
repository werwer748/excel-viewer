package dev.hugo.sheetview

import dev.hugo.sheetview.source.HtmlSanitizer
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 원본 미리보기는 **출처를 알 수 없는 파일**을 IDE 프로세스 안의 진짜 Chromium(JCEF)에
 * 띄운다. 파일에 스크립트가 있으면 실행되고, 외부 참조가 있으면 네트워크로 나간다.
 *
 * 실제 카드 명세서에는 스크립트·이미지·외부 URL이 한 곳도 없다 (실측). 하지만 **다음 달
 * 파일에는 있을 수 있다.** 그래서 두 겹으로 막는다: CSP 주입 + 태그·속성 제거.
 *
 * 반대 방향도 똑같이 중요하다 — 정화가 과하면 원본이 원본처럼 보이지 않는다.
 * 이 파일의 스타일은 전부 `<style>` 블록 + `class` 선택자에 있어서
 * `Jsoup.clean(Safelist)` 처럼 그것을 날리는 방식은 쓸 수 없다.
 */
class HtmlSanitizerTest {

    private fun sanitize(html: String) = HtmlSanitizer.sanitize(html)

    @Test
    fun `script 태그를 제거하고 몇 개였는지 알려준다`() {
        val out = sanitize("<html><head><script>alert(1)</script></head><body>안녕</body></html>")
        assertFalse(out.html.contains("alert(1)"), "스크립트 본문이 남았다: ${out.html}")
        assertFalse(out.html.contains("<script", ignoreCase = true))
        assertEquals(1, out.removedScripts)
        assertTrue(out.html.contains("안녕"), "본문은 남아야 한다")
    }

    @Test
    fun `외부를 끌어오거나 탐색을 일으키는 태그를 제거한다`() {
        val out = sanitize(
            """
            <html><head><base href="http://evil.test/"><link rel="stylesheet" href="http://evil.test/a.css"></head>
            <body><iframe src="http://evil.test"></iframe><object data="x"></object>
            <embed src="y"><form action="http://evil.test"><input></form><table><tr><td>값</td></tr></table></body></html>
            """.trimIndent(),
        )
        for (tag in listOf("<base", "<link", "<iframe", "<object", "<embed", "<form")) {
            assertFalse(out.html.contains(tag, ignoreCase = true), "$tag 가 남았다")
        }
        assertFalse(out.html.contains("evil.test"), "외부 호스트 참조가 남았다")
        assertTrue(out.html.contains("값"), "표 내용은 남아야 한다")
    }

    @Test
    fun `meta refresh 로 페이지를 옮기지 못하게 한다`() {
        val out = sanitize("""<html><head><meta http-equiv="refresh" content="0;url=http://evil.test"></head><body>x</body></html>""")
        assertFalse(out.html.contains("refresh", ignoreCase = true))
        assertFalse(out.html.contains("evil.test"))
    }

    @Test
    fun `이벤트 핸들러 속성을 전부 제거한다`() {
        val out = sanitize("""<table><tr><td onclick="steal()" onmouseover="x()" ONLOAD="y()">값</td></tr></table>""")
        assertFalse(out.html.contains("onclick", ignoreCase = true))
        assertFalse(out.html.contains("onmouseover", ignoreCase = true))
        assertFalse(out.html.contains("onload", ignoreCase = true))
        assertTrue(out.html.contains("값"))
    }

    @Test
    fun `javascript 와 data html URL 을 제거한다`() {
        val out = sanitize(
            """<body><a href="javascript:steal()">a</a><a href="JAVASCRIPT:x">b</a>""" +
                """<a href="data:text/html,<script>x</script>">c</a><a href="#anchor">d</a></body>""",
        )
        assertFalse(out.html.contains("javascript:", ignoreCase = true))
        assertFalse(out.html.contains("data:text/html", ignoreCase = true))
        // 문서 내부 앵커는 위험하지 않으므로 남긴다.
        assertTrue(out.html.contains("#anchor"), "내부 앵커까지 지울 필요는 없다: ${out.html}")
    }

    @Test
    fun `style 블록과 class 속성은 반드시 보존한다`() {
        // 이게 깨지면 미리보기가 원본과 전혀 달라 보인다 - 정화의 실패와 같은 무게다.
        val out = sanitize(
            """
            <html><head><style>th{background:#d9d9d9}.x-right{text-align:right}</style></head>
            <body><table><tr><th>머리</th><td class="x-right">1,234</td></tr></table></body></html>
            """.trimIndent(),
        )
        assertTrue(out.html.contains("background:#d9d9d9"), "style 블록이 사라졌다: ${out.html}")
        assertTrue(out.html.contains(".x-right"), "CSS 클래스 규칙이 사라졌다")
        assertTrue(out.html.contains("""class="x-right""""), "class 속성이 사라졌다")
    }

    @Test
    fun `CSP meta 를 head 맨 앞에 정확히 하나 넣는다`() {
        val out = sanitize("<html><head><title>t</title></head><body>x</body></html>")
        assertEquals(
            1,
            Regex("Content-Security-Policy", RegexOption.IGNORE_CASE).findAll(out.html).count(),
            "CSP 가 없거나 중복이다: ${out.html}",
        )
        assertTrue(out.html.contains("default-src 'none'"), "스크립트·네트워크를 막는 지시가 없다")
        // 인라인 <style> 을 살리려면 style-src 가 필요하다.
        assertTrue(out.html.contains("style-src 'unsafe-inline'"))
        val head = out.html.substringAfter("<head>", "").trim()
        assertTrue(head.startsWith("<meta"), "CSP 가 head 맨 앞이 아니다: ${head.take(120)}")
    }

    @Test
    fun `head 가 없는 조각도 CSP 를 받는다`() {
        val out = sanitize("<table><tr><td>값</td></tr></table>")
        assertTrue(out.html.contains("Content-Security-Policy"), "조각 입력에서 CSP 를 놓쳤다")
        assertTrue(out.html.contains("값"))
    }

    @Test
    fun `이미지가 있었으면 차단 사실을 알려준다`() {
        // CSP img-src 가 data: 만 허용하므로 외부·상대 경로 이미지는 렌더되지 않는다.
        // 사용자에게 "원본에 있던 그림이 안 보인다"고 배너로 말해 줘야 한다.
        assertTrue(sanitize("""<body><img src="logo.gif"></body>""").imagesBlocked)
        assertFalse(sanitize("<body>그림 없음</body>").imagesBlocked)
        // data: URI 이미지는 실제로 보이므로 차단이라고 말하면 거짓말이다.
        assertFalse(sanitize("""<body><img src="data:image/gif;base64,R0lGOD"></body>""").imagesBlocked)
    }

    // ---------------------------------------------------------------- 항상 밝게 본다

    @Test
    fun `기준 스타일을 넣어 IDE 테마와 무관하게 밝게 본다`() {
        val out = sanitize("<html><head></head><body>안녕</body></html>")
        // color-scheme 을 고정하지 않으면 다크 모드 Chromium 이 시스템 컬러를 밝은 색으로
        // 해석해 글자가 사라진다. 실제 파일이 color:windowtext 를 쓴다.
        assertTrue(out.html.contains("color-scheme"), "color-scheme 선언이 없다: ${out.html}")
        assertTrue(out.html.contains("only light"), "라이트로 고정하지 않았다")
        assertTrue(out.html.contains("#ffffff"), "흰 배경 선언이 없다")
        assertEquals(
            1,
            Regex("color-scheme").findAll(out.html).count(),
            "기준 스타일이 중복됐다",
        )
    }

    @Test
    fun `기준 스타일은 문서 자신의 style 보다 앞에 온다`() {
        // 뒤에 오면 우리 규칙이 원본 서식을 덮어써서 "원본대로 보여준다"가 거짓이 된다.
        val out = sanitize(
            """<html><head><style>th{background:#d9d9d9}.x-right{text-align:right}</style></head>
               <body><table><tr><th>머리</th><td class="x-right">1,234</td></tr></table></body></html>""",
        )
        val ours = out.html.indexOf("only light")
        val theirs = out.html.indexOf("#d9d9d9")
        assertTrue(ours >= 0 && theirs >= 0, "둘 중 하나가 사라졌다: ${out.html}")
        assertTrue(ours < theirs, "기준 스타일이 원본 뒤에 왔다")
        // 원본 규칙은 그대로 살아 있어야 한다.
        assertTrue(out.html.contains(".x-right"))
        assertTrue(out.html.contains("""class="x-right""""))
    }

    @Test
    fun `시스템 컬러를 라이트 모드 값으로 치환한다`() {
        val out = sanitize(
            """<html><head><style>th,td{color:windowtext;background:window}</style></head>
               <body><td style="color:windowtext">값</td></body></html>""",
        )
        assertFalse(out.html.contains("windowtext"), "windowtext 가 남았다: ${out.html}")
        assertTrue(out.html.contains("#000000"), "검은색으로 치환하지 않았다")
        assertTrue(out.html.contains("background:#ffffff"), "window 를 흰색으로 치환하지 않았다")
    }

    @Test
    fun `클래스 이름이 window 여도 훼손하지 않는다`() {
        // 선언 값이 아니라 선택자까지 바꾸면 `.window{…}` 가 `.#ffffff{…}` 가 되어
        // 스타일시트 전체가 깨진다. 치환은 `:` 뒤 값 안에서만 일어나야 한다.
        val out = sanitize(
            """<html><head><style>.window{color:red}.windowtext td{color:blue}</style></head>
               <body><div class="window">값</div></body></html>""",
        )
        assertTrue(out.html.contains(".window{"), "클래스 선택자가 깨졌다: ${out.html}")
        assertTrue(out.html.contains(".windowtext td"), "클래스 선택자가 깨졌다")
        assertTrue(out.html.contains("""class="window""""), "class 속성이 깨졌다")
    }

    @Test
    fun `의사 클래스와 미디어 쿼리를 건드리지 않는다`() {
        val out = sanitize(
            """<html><head><style>a:hover{color:red}@media (prefers-color-scheme:dark){td{color:blue}}</style></head>
               <body>값</body></html>""",
        )
        assertTrue(out.html.contains("a:hover{color:red}"), "의사 클래스가 깨졌다: ${out.html}")
        assertTrue(out.html.contains("prefers-color-scheme:dark"), "미디어 쿼리가 깨졌다")
    }

    @Test
    fun `빈 입력에도 죽지 않는다`() {
        for (input in listOf("", "   ", "\u0000", "<", "<html>")) {
            val out = sanitize(input)
            assertTrue(out.html.contains("Content-Security-Policy"), "입력 '$input' 에서 CSP 누락")
        }
    }

    @Test
    fun `실제 명세서 픽스처를 정화해도 표와 스타일이 그대로 남는다`() {
        val raw = Files.readString(Path.of("src/test/resources/fixtures/statement.xls"))
        val out = sanitize(raw)
        assertEquals(0, out.removedScripts, "픽스처에는 스크립트가 없다 (실측)")
        assertFalse(out.imagesBlocked, "픽스처에는 이미지가 없다 (실측)")
        assertTrue(out.html.contains("<table", ignoreCase = true), "표가 사라졌다")
        assertTrue(out.html.contains("<style", ignoreCase = true), "스타일 블록이 사라졌다")
        assertTrue(out.html.contains("요약내역"), "한글 본문이 사라졌다 (인코딩/정화 문제)")
    }
}
