---
paths:
  - "src/main/kotlin/dev/hugo/sheetview/preview/**/*.kt"
  - "src/main/resources/META-INF/sheetview-jcef.xml"
---

# JCEF 미리보기

미리보기 구현과 optional 디스크립터를 읽을 때 붙는다. JCEF 는 헤드리스로 띄울 수 없어 전부 샌드박스에서만 확인된다.

- **JCEF 는 플랫폼이 아니라 번들 *플러그인*이다.** `com.intellij.modules.jcef`(`plugins/jcef-plugin/`)이고 디스크립터가 `modules.os.mac` / `modules.arch.arm64` 에 의존한다 — 환경에 따라 없을 수 있다. 필수 의존으로 걸면 없는 환경에서 **플러그인 전체가 로드되지 않으므로** `<depends optional="true" config-file="sheetview-jcef.xml">` 로 두고, JCEF 를 참조하는 구현은 그 파일에서만 등록한다. 메인 모듈은 `JBCefApp` 이라는 이름조차 모른다.
- **없는 것이 정상인 구현은 서비스가 아니라 확장 포인트로 찾는다.** 등록되지 않은 서비스를 `getService` 로 찾으면 플랫폼이 "not registered as a service" 오류를 로그에 남겨 진짜 문제를 찾을 때 방해가 된다.
- **`JBCefBrowser` 를 툴바 `update()` 에서 만들면 안 된다.** `update()` 는 EDT 에서 수시로 불리는데 브라우저 생성은 Chromium 초기화를 끌고 와 IDE 를 멈춘다. "미리보기를 쓸 수 있는가"(확장 포인트 조회)와 "브라우저를 만든다"를 분리한다.
- **`Jsoup.clean(Safelist)` 로 HTML 을 정화하면 안 된다.** `<style>` 블록과 `class` 속성을 날려 "원본을 보여준다"가 거짓이 된다. 이 파일들의 스타일은 전부 거기 있다. 위험한 태그·속성만 지목해 제거하고, 실행·네트워크는 CSP(`default-src 'none'`)로 막는다.
