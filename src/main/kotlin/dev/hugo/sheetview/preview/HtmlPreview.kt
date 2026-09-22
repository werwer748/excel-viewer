package dev.hugo.sheetview.preview

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import javax.swing.JComponent

/**
 * 정화된 HTML 을 렌더해 보여주는 컴포넌트.
 *
 * 구현은 [JcefHtmlPreviewFactory] 하나뿐이지만, 이 인터페이스가 있어야 **메인 모듈이 JCEF
 * 클래스를 전혀 참조하지 않는다.** JCEF 는 WebStorm 에 번들 *플러그인*
 * (`com.intellij.modules.jcef`)으로 들어 있고 OS·아키텍처에 묶여 있어서, 필수 의존으로 걸면
 * 원격 개발·JetBrains Client 처럼 그것이 없는 환경에서 플러그인 전체가 로드되지 않는다.
 */
interface HtmlPreview {
    val component: JComponent

    /** 이미 [dev.hugo.sheetview.source.HtmlSanitizer] 를 거친 HTML 만 넘어온다. */
    fun load(html: String)
}

/**
 * 확장 포인트는 메인 `plugin.xml` 이 선언하고, 구현은 `sheetview-jcef.xml` 이
 * **JCEF 가 있을 때만** 등록한다. 그래서 JCEF 가 없으면 [find] 가 그냥 null 이다.
 *
 * 서비스 대신 확장 포인트를 쓴 이유: 등록되지 않은 서비스를 `getService` 로 찾으면
 * 플랫폼이 "not registered as a service" 오류를 로그에 남긴다. 없는 것이 정상인 상황에서
 * 오류 로그를 남기면 진짜 문제를 찾을 때 방해가 된다.
 */
interface HtmlPreviewFactory {

    /** 지원하지 못하면 null. 생명주기는 [parent] 에 묶는다. */
    fun create(parent: Disposable): HtmlPreview?

    companion object {
        private val EP_NAME =
            ExtensionPointName<HtmlPreviewFactory>("dev.hugo.sheetview.htmlPreviewFactory")

        fun find(): HtmlPreviewFactory? =
            runCatching { EP_NAME.extensionList.firstOrNull() }.getOrNull()
    }
}
