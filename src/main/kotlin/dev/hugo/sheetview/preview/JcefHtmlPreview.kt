package dev.hugo.sheetview.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandlerAdapter
import javax.swing.JComponent

/**
 * JCEF(내장 Chromium)로 원본을 렌더한다. `sheetview-jcef.xml` 이 등록하므로
 * **JCEF 가 있는 환경에서만** 로드된다. 메인 모듈은 이 파일의 클래스를 이름으로도 참조하지 않는다.
 */
class JcefHtmlPreviewFactory : HtmlPreviewFactory {

    override fun create(parent: Disposable): HtmlPreview? {
        // 플러그인이 있어도 못 쓰는 경우가 있다 (원격 개발, GPU 없음, 사용자가 끈 경우).
        if (!runCatching { JBCefApp.isSupported() }.getOrDefault(false)) return null
        return runCatching { JcefHtmlPreview(parent) }.getOrNull()
    }
}

private class JcefHtmlPreview(parent: Disposable) : HtmlPreview {

    private val browser = JBCefBrowser.createBuilder()
        .setEnableOpenDevToolsMenuItem(false)
        .build()

    init {
        // 등록하지 않으면 탭을 닫아도 Chromium 프로세스가 남는다.
        Disposer.register(parent, browser)

        // 페이지가 뜨기 전이나 페이지보다 컴포넌트가 클 때 IDE 테마의 어두운 여백이 비친다.
        // 페이지 자체는 HtmlSanitizer 의 기준 스타일이 희게 만든다 — 여기는 그 바깥이다.
        // (JBCefBrowserBase.setPageBackgroundColor 는 JS 를 주입하는데 우리 CSP 아래에서
        //  동작을 보장할 수 없어 쓰지 않는다. CSS 와 Swing 배경으로 하면 결정적이다.)
        browser.component.background = java.awt.Color.WHITE
        browser.component.isOpaque = true

        // CSP(`default-src 'none'`) 위의 세 번째 방어선. 정화를 뚫고 남은 링크를 눌러도
        // 이 뷰어가 딴 곳으로 이동하지 않게 한다. 최초 loadHTML 만 통과시킨다.
        browser.jbCefClient.addRequestHandler(
            object : CefRequestHandlerAdapter() {
                override fun onBeforeBrowse(
                    browser: CefBrowser?,
                    frame: CefFrame?,
                    request: org.cef.network.CefRequest?,
                    userGesture: Boolean,
                    isRedirect: Boolean,
                ): Boolean = userGesture || isRedirect   // true = 이동 취소
            },
            browser.cefBrowser,
        )
    }

    override val component: JComponent get() = browser.component

    override fun load(html: String) {
        browser.loadHTML(html)
    }
}
