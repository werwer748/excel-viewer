---
paths:
  - "src/main/kotlin/dev/hugo/sheetview/source/**/*.kt"
  - "src/main/kotlin/dev/hugo/sheetview/preview/**/*.kt"
  - "src/main/kotlin/dev/hugo/sheetview/editor/Source*.kt"
  - "src/main/kotlin/dev/hugo/sheetview/editor/LightEditorScheme.kt"
  - "src/main/kotlin/dev/hugo/sheetview/editor/TextViewer.kt"
  - "src/test/kotlin/**/HtmlSanitizerTest.kt"
  - "src/test/kotlin/**/LightEditorSchemeTest.kt"
---

# 원본 탭은 IDE 테마와 무관하게 항상 밝다

원본 탭(미리보기 · 소스 · 파트 보기)과 그 정화기를 읽을 때 붙는다. 사용자 요청이자 가독성 문제다. 되돌리면 다크 테마에서 **글자가 사라진다.**

- **`color-scheme: only light` 를 지우지 않는다.** 이 파일들은 `color:windowtext` 같은 CSS 시스템 컬러를 쓴다(대상 파일에 4곳). 시스템 컬러는 페이지의 `color-scheme` 에 따라 해석이 바뀌어, 다크 모드 Chromium 에서는 밝은 색이 되어 글자가 보이지 않는다. 표 격자(`#808080`)와 헤더 배경(`#d9d9d9`)은 고정값이라 남는다 — 그래서 "표는 보이는데 글자가 안 보이는" 모양이 된다.
- **기준 스타일(`HtmlSanitizer.BASE_STYLE`)은 `<head>` 에서 CSP `<meta>` 바로 다음, 문서 자신의 `<style>` 보다 앞이다.** 뒤로 가면 우리 규칙이 원본 서식을 덮어써서 "원본대로 보여준다"가 거짓이 된다. 명시도도 일부러 낮게 둔다 — 캔버스만 정하고 나머지는 원본이 이긴다.
- **시스템 컬러 치환은 선언 값 안에서만 한다.** `\bwindow\b` 를 통째로 바꾸면 `.window{…}` 클래스 이름이 `.#ffffff{…}` 가 되어 스타일시트 전체가 깨진다. `:` 뒤부터 `;`/`{`/`}` 전까지를 값으로 본다(`a:hover` 는 값이 `hover` 라 안전). `color-scheme` 만으로 충분해 보이지만 브라우저 렌더링은 헤드리스로 확인할 수 없어 두 겹으로 막는다. 라이트 모드에서 의미가 같은 값이라 보이는 결과는 달라지지 않는다.
- **원본 훼손이 아니다.** 미리보기는 *렌더링*이고, 바이트 그대로는 `소스` 보기가 담당한다. 소스 본문은 손대지 않는다.
- **소스·파트 보기는 배경색만 바꾸면 안 된다.** 다크 테마의 구문 강조 색(밝은 노랑·연회색)이 흰 바탕에 그대로 얹혀 **지금보다 더 안 보인다.** `LightEditorScheme.pick()` 으로 색 구성표 자체를 갈아끼운다. 구성표를 이름 하나로만 찾지 않는 이유: 번들 구성표 이름은 IDE 버전·제품마다 다르다. "밝은 것 아무거나"가 이름보다 오래 간다. 회귀 테스트는 `LightEditorSchemeTest`.
- **`createBoundColorSchemeDelegate` 를 거친다.** 색만 갈아끼우고 글꼴·크기는 사용자 설정을 상속한다. 구성표를 통째로 대입하면 글꼴까지 바뀐다.
- **툴바·배너·탭 같은 IDE 크롬은 칠하지 않는다.** 내용 영역만 밝게 한다. 크롬까지 칠하면 IDE 안에서 이 탭만 이질적인 창이 된다 — 다크 크롬 안의 흰 페이지가 브라우저와 같은 자연스러운 모양이다.
