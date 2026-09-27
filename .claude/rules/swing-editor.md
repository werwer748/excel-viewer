---
paths:
  - "src/main/kotlin/dev/hugo/sheetview/editor/**/*.kt"
  - "src/main/kotlin/dev/hugo/sheetview/actions/**/*.kt"
---

# Swing 과 에디터 플랫폼

에디터 · 패널 · 액션을 읽을 때 붙는다. 이 영역은 대부분 헤드리스 테스트가 불가능해 `tdd-exempt.txt` 로 면제돼 있다 — 여기서 틀리면 샌드박스에서 사람이 보기 전까지 아무도 모른다.

- **`JBLoadingPanel` 을 직접 비우면 안 된다.** `add()` 는 재정의해 내부 content 패널로 위임하지만 `removeAll()` 은 재정의하지 않는다. `removeAll()` 을 부르면 화면에 붙어 있는 `LoadingDecorator` 가 떨어져 나가고, 이후 `add()` 한 내용은 분리된 패널로 들어가 **화면이 빈 채로 아무 오류도 안 난다**. 전용 content 패널(`SheetPanel.content`)을 하나 넣고 그 자식만 교체한다.
- **`EditorFactory.createViewer` 로 만든 에디터는 `releaseEditor` 로 놓아준다.** 빠뜨리면 탭을 닫아도 살아남는다. 에디터 탭은 수십 번 열리고 닫히므로 누수는 반드시 쌓인다.
- **IntelliJ `Document` 는 `\r` 을 담을 수 없다.** CRLF 파일을 그대로 `createDocument` 에 넣으면 예외가 난다. `StringUtil.convertLineSeparators` 를 거친다(줄 번호는 원본과 그대로 일치한다).
- **배너를 쌓는 칸과 갈아끼우는 칸을 분리한다.** 파트를 고를 때마다 같은 칸에 배너를 add 하면 5개 고르면 5줄 쌓인다.
- **`EditorNotificationPanel(Status)` 단일 인자 생성자는 없다.** `(Color?, Status)` 를 쓴다.
- **`TableSpeedSearch` 생성자는 deprecated.** `TableSpeedSearch.installOn(table)` 을 쓴다.
- **컬럼 폭은 앞 200행만 측정한다.** 전체 행×열을 측정하는 것이 표 플러그인이 파일을 열 때 멈춰버리는 전형적 원인이다. 가상 스크롤은 일부러 두지 않았다 — Swing 이 보이는 셀만 조회하고 리더가 이미 그리드를 메모리에 만들었으므로, 대용량은 리더 쪽 상한으로 막는다.
