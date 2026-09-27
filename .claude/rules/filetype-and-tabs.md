---
paths:
  - "src/main/kotlin/dev/hugo/sheetview/filetype/**/*.kt"
  - "src/main/kotlin/dev/hugo/sheetview/editor/*EditorProvider.kt"
  - "src/main/kotlin/dev/hugo/sheetview/actions/**/*.kt"
  - "src/main/resources/META-INF/plugin.xml"
  - "src/test/kotlin/**/FileTypeAndTabsTest.kt"
---

# 파일 타입과 탭

파일 타입 등록 · 에디터 provider · `plugin.xml` 을 읽을 때 붙는다. 아래는 전부 측정해서 얻은 결론이고, 오판은 전부 측정 없이 추측해서 생겼다.

- **`extensions="xls;xlsx;xlsm;xltx;xltm"` 를 반드시 등록한다.** 한때 "확장자 매핑이 이름 기반으로 이겨서 내 탐지기를 가린다"고 판단해 지웠는데 측정해 보니 **반대**였다. `extensions` 가 없으면 `.xls` 는 번들 grid 플러그인의 `Data File`(`DataLoaderManager$DataFileType`)이 되고, 그 타입은 `FileTypeIdentifiableByVirtualFile` 이라 **내용 기반 탐지기보다 먼저** 평가되므로 탐지기는 영원히 호출되지 않는다. 확장자를 놓으면 파일 타입을 남에게 넘기는 것이고, grid 가 없는 IDE(IDEA/PyCharm)에서는 `UNKNOWN` 이 된다. → **탐지기로는 이길 수 없다.** 회귀 테스트는 `FileTypeAndTabsTest`.
- **그래서 HTML 위장 `.xls` 의 원본을 플랫폼 텍스트 탭에 기댈 수 없다.** 어느 쪽이든 파일 타입이 binary 라 텍스트 에디터가 붙지 않는다. 원본은 이 플러그인의 `원본` 탭이 직접 읽어 보여준다.
- **자동 선점 확장자는 그 다섯뿐이다.** `csv`/`tsv` 는 번들 `grid-core-plugin` 의 `csv-data-editor` 가 이미 담당하고 편집까지 지원한다. `html` 도 제외. 그런 파일은 우클릭 → **표로 열기**로 명시 진입한다. `check.sh` lint 가 `csv|tsv|html` 선점을 막는다.
- **아래쪽 `Data` 탭은 이 플러그인이 만든 것이 아니다.** `com.intellij.grid.scripting.impl.ScriptedTableFileEditorProvider`(`scripted-data-editor`)다. `csv-data-editor` 가 아니다 — 처음엔 그쪽이라고 판단했지만 `FileEditorProviderManager.getProviderList()` 로 확인해 보니 달랐다. 파일 타입을 내 타입으로 바꿔도 이 탭은 그대로 붙고, 실제로 읽어 줄 `grid-loader-*` 는 DataGrip 전용이라 WebStorm 에서는 `No loader for …` 만 뜬다. **내 쪽에서 없앨 수 없다.**
- **`plugin.xml` 의 `fileEditorProvider` 등록 순서가 탭 순서다.** 표가 먼저 선택되어야 한다.
- **추측하지 말고 `FileEditorProviderManager.getProviderList(project, file)` 를 테스트에서 찍어 보라.** 어떤 탭이 붙는지, 파일 타입이 무엇인지는 `BasePlatformTestCase` 로 몇 초 만에 확인된다. 위 항목들의 오판은 전부 이걸 안 해서 생겼다.
