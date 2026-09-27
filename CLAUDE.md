# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

읽기 전용 스프레드시트 뷰어 JetBrains 플러그인이다. **확장자가 아니라 파일 내용으로 형식을 판별**하고, 한 ZIP을 WebStorm · IntelliJ IDEA · PyCharm · CLion · DataGrip에 그대로 설치한다. 입력은 신뢰할 수 없는 파일이고 파싱은 IDE 프로세스 안에서 일어난다 — 이 두 전제가 아래 제약 대부분의 이유다.

## 문서 경계

같은 사실을 여러 곳에 적지 않는다. **언제 컨텍스트에 올라오는가**로 나뉜다.

| 문서 | 올라오는 시점 | 담는 것 |
|---|---|---|
| **`CLAUDE.md`** (이 문서) | 세션 시작 — 항상 | 모르고 하면 되돌릴 수 없는 것 · 게이트 · 빌드와 커밋 절차 · 영역 규칙 색인 |
| `.claude/rules/*.md` | 매칭 파일을 **Read 할 때** | 영역별 "되돌리면 안 되는 결정"과 그 측정 근거 |
| `plugins/sheetview-kit/skills/*/SKILL.md` | 스킬을 부를 때 | 절차 — 사이클(`feature-cycle`) · 리뷰(`spreadsheet-review`, 코드 지도 포함) · 샌드박스(`sandbox-verify` / `sandbox-run`) |
| `plugins/sheetview-kit/README.md` | 작업 도구 자체를 고칠 때 직접 연다 | 훅·스킬·에이전트 목록 · 프로젝트에 남는 상태 |
| `README.md` | 플러그인을 쓰기 전 | 소개 · 지원 형식 · 사용법 · 설치 · 배포 |
| `TODO.md` | 다음 일을 고를 때 | 마켓플레이스 등록 남은 일 · 추가할 기능 |

- **여기에는 매 세션 필요한 것만 둔다.** 특정 파일을 고칠 때만 필요한 결정은 `.claude/rules/` 에, 절차는 스킬에 둔다. `@import` 는 세션 시작에 펼쳐지므로 분할 수단이 아니다.
- **테스트·파일 개수를 문서에 하드코딩하지 않는다.** 이미 한 번 썩었다.

## 하지 말 것

- **`./gradlew runIde` 를 포그라운드로 실행하지 않는다.** IDE가 떠서 세션이 멈춘다. 예외는 `sheetview-kit:sandbox-run` 스킬 경로 하나뿐이고, 거기서도 **반드시 백그라운드로** 띄운다. 띄우는 것까지가 끝이다 — 샌드박스 확인은 여전히 사람이 한다.
- **`./gradlew publishPlugin` 을 실행하지 않는다.** 첫 게시는 수동이어야 하고 되돌리기 어렵다.
- **코틀린 파일을 셸 heredoc·리다이렉트로 쓰지 않는다.** `tdd-guard.sh` 가 `>`·`>>`·`tee` 의 대상 경로까지 검사해 차단한다. 편집 툴을 쓴다.
- **Apache POI 를 넣지 않는다. jsoup 스코프를 바꾸지 않는다.** `check.sh` lint 가 막는다. 근거는 `.claude/rules/build-and-deps.md`.

## 훅과 작업 사이클

훅·스킬·에이전트는 이 저장소의 `plugins/sheetview-kit` 플러그인이 싣는다. **설치하지 않으면 아무것도 막지 않는다** — clone 직후 한 번:

```
/plugin marketplace add .
/plugin install sheetview-kit@sheetview
```

- **본체 `.kt` 를 새로 만들려면 그 클래스를 언급하는 테스트가 먼저 있어야 한다** (`tdd-guard.sh`, 없으면 `exit 2`). 막히면 답은 로직을 순수 클래스로 빼내 테스트하는 것이다 — 면제 추가도, `touch`·MCP `create_new_file` 같은 다른 툴로 우회하는 것도 아니다.
- **게이트 파일(`.claude/tdd-*` · 훅 · `scripts/check.sh`)을 고치면 `ask` 가 뜬다.** 정상이다 — 리뷰 지적을 없애는 가장 쉬운 길이 "면제에 추가"이기 때문에 사람에게 올린다.
- **훅은 판단이 안 되면 통과시킨다. 따라서 훅 통과는 품질 보증이 아니다.** 재현 가능한 게이트는 `check.sh` 하나다.
- **코드를 바꾸는 작업은 `sheetview-kit:feature-cycle` 스킬로 시작한다.** `.claude/.cycle-state` 가 있는 동안 `cycle-stop.sh` 가 리뷰·검증이 `clean` 이 아니면 세션 종료를 되돌린다. 그만두려면 `rm -f .claude/.cycle-state`. 문서만 고치는 작업에는 사이클이 필요 없다.
- 훅 목록은 `plugins/sheetview-kit/README.md`, 설계 원칙과 훅을 읽어야만 아는 것들은 `.claude/rules/harness.md`.

## 빌드와 검증

`./scripts/check.sh` 가 유일한 게이트다: lint(컴파일러가 잡아주지 않는 불변식 grep) → `compileKotlin compileTestKotlin` → `buildPlugin` → `test` → 테스트 수 래칫 → 미커버 래칫. CI(`.github/workflows/check.yml`)와 커밋 앞의 `pre-commit-check.sh` 가 같은 스크립트를 돌린다.

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
- **메서드 이름이 백틱으로 감싼 한국어 문장이다.** 메서드 단위로 필터하려면 공백이 들어가므로 반드시 인용한다.
- **`verifyPlugin` 은 `plugin.xml` · `build.gradle.kts` · 플랫폼 API 를 건드렸을 때만** 돌린다. 생략했으면 그 사실을 보고에 적는다.
- **JDK 25 가 필요하다.** 없으면 foojay 리졸버가 받아 온다 — clone 직후 아무 설정 없이 빌드된다. 로컬 설정으로 다운로드를 없애는 법은 `.claude/rules/build-and-deps.md`.
- **커밋 전: `./scripts/check.sh` 통과 → `.claude/tdd-baseline` 을 출력의 `(N개 통과)` 값으로 갱신.** 테스트가 늘어도 자동으로 오르지 않으므로 직접 올린다. 줄면 `check.sh` 가 실패한다.
- **테스트가 red 인데 내가 고친 것과 무관해 보이면** 진행 중인 기능이 테스트를 먼저 올려 둔 상태일 수 있다. `TODO.md` 의 '추가할 기능'을 먼저 확인하고, 내 변경분만 돌려 본다(`./gradlew test --tests "…"`). 그 상태에서는 `check.sh` 가 실패하므로 **Claude 는 커밋할 수 없다** — 사람이 터미널에서 직접 커밋해야 한다.

## 영역 규칙 — 해당 파일을 Read 할 때 붙는다

전부 측정해서 얻은 결론이다. 이상해 보이는 코드에는 대개 주석이나 이 규칙에 이유가 적혀 있다. 경로는 `src/main/kotlin/dev/hugo/sheetview/` 기준으로 줄여 적었다.

| 규칙 (`.claude/rules/`) | 붙는 파일 | 요지 |
|---|---|---|
| `errors-and-limits.md` | 본체 `.kt` 전부 | 실패는 두 예외 타입으로만 · 취소 예외를 삼키지 않는다 · StAX XXE · `ReadLimits` 실제 적용 |
| `filetype-and-tabs.md` | `filetype/` · `editor/*EditorProvider.kt` · `actions/` · `plugin.xml` | `extensions` 등록 필수 · 선점은 다섯 확장자뿐 · `Data` 탭은 남의 것 · 등록 순서가 탭 순서 |
| `table-parsing.md` | `format/` · `model/` · 픽스처 | 헤더는 `<thead>` 기준 · colspan/rowspan 점유 맵 · 셀 타입은 텍스트 추론 |
| `swing-editor.md` | `editor/` · `actions/` | `JBLoadingPanel` 직접 비우기 금지 · `releaseEditor` · `Document` 의 `\r` · 컬럼 폭 200행 |
| `jcef-preview.md` | `preview/` · `sheetview-jcef.xml` | JCEF 는 optional 번들 플러그인 · 확장 포인트로 찾기 · `update()` 에서 생성 금지 · `Jsoup.clean` 금지 |
| `source-tab-light.md` | `source/` · `preview/` · `editor/Source*` · `LightEditorScheme` · `TextViewer` | 원본 탭은 항상 밝다 — `color-scheme: only light` · `BASE_STYLE` 위치 · 색 구성표째 교체 |
| `build-and-deps.md` | `build.gradle.kts` · `gradle.properties` · `META-INF/*.xml` · CI | POI·jsoup 번들 금지 · `create("IC")` 금지 · `<idea-version>`·`untilBuild` · description 라틴 문자 · JDK 25 |
| `testing.md` | 테스트 `.kt` | 테스트 두 종류 · 게이트 통과 조건 · `tdd-red` 1회 판정 · 래칫 숫자 셈법 |
| `harness.md` | `plugins/sheetview-kit/` · `check.sh` · `.claude/tdd-*` | 훅 설계 원칙 다섯 · 사이클 상태 · 래칫 · 규칙 파일 유지 |

**규칙은 Read 툴로 매칭 파일을 열 때만 붙는다.** Bash·MCP 툴로 읽었거나 Read 없이 새 파일을 만들 때는 붙지 않으니, 그 영역을 고치기 전에 위 규칙을 직접 Read 한다. 서브에이전트에게 맡길 때도 관련 규칙 파일 경로를 넘긴다.

## 고친 뒤

- 리뷰는 `sheetview-kit:spreadsheet-review` 스킬 또는 `sheetview-kit:sheet-reviewer` 에이전트에 맡긴다. 코드 지도와 체크리스트 8개가 거기 있다.
- 테스트로 확인할 수 없는 것(Swing 조립 · 탭 순서 · JCEF · 탭을 닫을 때의 취소)은 `sheetview-kit:sandbox-run` 스킬 또는 `sheetview-kit:sandbox-runner` 에이전트로 샌드박스에 띄워 사람이 확인한다. `.claude/tdd-exempt.txt` 의 면제가 바로 이 확인을 전제로 한 것이다.
- 다음에 할 일은 `TODO.md`.
