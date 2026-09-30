# AGENTS.md

읽기 전용 스프레드시트 뷰어 JetBrains 플러그인이다. **확장자가 아니라 파일 내용으로 형식을 판별**하고, 한 ZIP을 WebStorm · IntelliJ IDEA · PyCharm · CLion · DataGrip에 그대로 설치한다. 입력은 신뢰할 수 없는 파일이고 파싱은 IDE 프로세스 안에서 일어난다 — 이 두 전제가 아래 제약 대부분의 이유다.

## 하지 말 것

- **`./gradlew runIde` 를 포그라운드로 실행하지 않는다.** IDE가 떠서 세션이 멈춘다.
- **`./gradlew publishPlugin` 을 실행하지 않는다.** 첫 게시는 수동이어야 하고 되돌리기 어렵다.
- **Apache POI 를 넣지 않는다. jsoup 스코프를 바꾸지 않는다.** `check.sh` lint 가 막는다. 근거는 `.claude/rules/build-and-deps.md`.

## 빌드와 검증

`./scripts/check.sh` 가 유일한 게이트다: lint(컴파일러가 잡아주지 않는 불변식 grep) → `compileKotlin compileTestKotlin` → `buildPlugin` → `test` → 테스트 수 래칫 → 미커버 래칫. CI(`.github/workflows/check.yml`)와 커밋 앞 훅(`pre-commit-check.sh`)이 같은 스크립트를 돌린다.

```bash
./scripts/check.sh      # lint -> build -> test 한 번에
./gradlew test
./gradlew buildPlugin   # -> build/distributions/excel-viewer-<version>.zip
./gradlew verifyPlugin  # 다섯 IDE 호환성. 몇 분 걸린다
sh plugins/sheetview-kit/scripts/test-hooks.sh

# 단일 테스트 (클래스 단위)
./gradlew test --tests "dev.hugo.sheetview.XlsxReaderTest"
```

- **테스트는 프로젝트 루트에서만 돈다.** 픽스처를 `Path.of("src/test/resources/fixtures", …)` 상대경로로 읽으므로 cwd 가 다르면 전부 깨진다. 하위 디렉터리에서 `../gradlew test` 를 부르지 않는다.
- **커밋 전: `./scripts/check.sh` 통과 → `.claude/tdd-baseline` 을 출력의 `(N개 통과)` 값으로 갱신.** 테스트가 늘어도 자동으로 오르지 않으므로 직접 올린다. 줄면 `check.sh` 가 실패한다.
- **`check.sh` 가 실패하는 상태로는 커밋하지 않는다.** 내 변경과 무관한 red 로 보이면 `.claude/rules/testing.md`.

## 영역 규칙 — 해당 영역을 고치기 전에 읽는다

전부 측정해서 얻은 결론이다. 이상해 보이는 코드에는 대개 주석이나 이 규칙에 이유가 적혀 있다. 경로는 `src/main/kotlin/dev/hugo/sheetview/` 기준으로 줄여 적었다.

| 규칙 (`.claude/rules/`) | 붙는 파일 | 요지 |
|---|---|---|
| `errors-and-limits.md` | 본체 `.kt` 전부 | 실패는 두 예외 타입으로만 · 취소 예외를 삼키지 않는다 · StAX XXE · `ReadLimits` 실제 적용 |
| `filetype-and-tabs.md` | `filetype/` · `editor/*EditorProvider.kt` · `actions/` · `plugin.xml` | `extensions` 등록 필수 · 선점은 다섯 확장자뿐 · `Data` 탭은 남의 것 · 등록 순서가 탭 순서 |
| `table-parsing.md` | `format/` · `model/` · 픽스처 | 헤더는 `<thead>` 기준 · colspan/rowspan 점유 맵 · 셀 타입은 텍스트 추론 |
| `swing-editor.md` | `editor/` · `actions/` | `JBLoadingPanel` 직접 비우기 금지 · `releaseEditor` · `Document` 의 `\r` · 컬럼 폭 200행 |
| `jcef-preview.md` | `preview/` · `sheetview-jcef.xml` | JCEF 는 optional 번들 플러그인 · 확장 포인트로 찾기 · `update()` 에서 생성 금지 · `Jsoup.clean` 금지 |
| `source-tab-light.md` | `source/` · `preview/` · `editor/Source*` · `LightEditorScheme` · `TextViewer` | 원본 탭은 항상 밝다 — `color-scheme: only light` · `BASE_STYLE` 위치 · 색 구성표째 교체 |
| `build-and-deps.md` | `build.gradle.kts` · `gradle.properties` · `META-INF/*.xml` · CI | POI·jsoup 번들 금지 · `create("IC")` 금지 · `<idea-version>`·`untilBuild` · description 라틴 문자 · JDK 25 · `verifyPlugin` 조건 |
| `testing.md` | 테스트 `.kt` | 테스트 두 종류 · 메서드 필터 인용 · 게이트 통과 조건 · `tdd-red` 1회 판정 · 래칫 숫자 셈법 · 무관한 red |
| `harness.md` | `plugins/sheetview-kit/` · `check.sh` · `.claude/tdd-*` | 훅 설계 원칙 다섯 · 사이클 상태 · 래칫 · lint |
| `docs.md` | `AGENTS.md` · `CLAUDE.md` · `README.md` · `TODO.md` · 규칙·스킬·에이전트 문서 | 무엇을 어느 문서에 적는가 · 개수 하드코딩 금지 · 규칙 파일 유지 |

다음에 할 일은 `TODO.md`.
