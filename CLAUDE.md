# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

읽기 전용 스프레드시트 뷰어 JetBrains 플러그인이다. **확장자가 아니라 파일 내용으로 형식을 판별**하고, 한 ZIP을 WebStorm · IntelliJ IDEA · PyCharm · CLion · DataGrip에 그대로 설치한다. 입력은 신뢰할 수 없는 파일이고 파싱은 IDE 프로세스 안에서 일어난다 — 이 두 전제가 아래 제약 대부분의 이유다.

## 문서 경계

같은 사실을 다섯 곳에 적지 않는다. 시점으로 나뉜다.

| 문서 | 읽는 시점 | 담는 것 |
|---|---|---|
| `README.md` | 플러그인을 **쓰기** 전 | 소개 · 지원 형식 · 사용법 · 설치 · 배포 |
| **`CLAUDE.md`** (이 문서) | 코드를 **고치기** 전 | 무엇이 막히나, 무엇을 되돌리면 안 되나, 어떻게 빌드·검증하나 |
| `plugins/sheetview-kit/skills/spreadsheet-review/SKILL.md` | 코드를 **고친 뒤** | 코드 지도 · 리뷰 체크리스트 8개 · 보고 형식 |
| `plugins/sheetview-kit/skills/feature-cycle/SKILL.md` | 코드를 **고치기 시작할 때** | 브랜치→구현→리뷰→검증→인계 사이클과 각 단계를 무엇이 강제하는가 |
| `plugins/sheetview-kit/skills/sandbox-verify/SKILL.md` | 사이클의 **검증 단계** | 기계가 관측할 수 있는 것과 없는 것의 경계 · 실측으로 닫힌 길 |
| `plugins/sheetview-kit/skills/sandbox-run/SKILL.md` | 코드를 고친 뒤 **눈으로 확인할 때** | 샌드박스 기동 절차 · 픽스처별 확인 항목 · 로그 훑는 법 |
| `plugins/sheetview-kit/README.md` | **작업 도구 자체**를 설치·수정할 때 | 설치 방법 · 훅·스킬·에이전트 목록 · 프로젝트에 남는 상태 |
| `TODO.md` | 다음 일을 고를 때 | 마켓플레이스 등록 남은 일 · 추가할 기능 |

파일 트리와 코드 지도는 SKILL.md에 있다. 여기에 옮겨 적지 않는다.

## 하지 말 것

- **`./gradlew runIde` 를 포그라운드로 실행하지 않는다.** IDE가 떠서 세션이 멈춘다. 예외는 `sheetview-kit:sandbox-run` 스킬 경로 하나뿐이고, 거기서도 **반드시 백그라운드로** 띄운다. 띄우는 것까지가 끝이다 — 샌드박스 확인은 여전히 사람이 한다.
- **`./gradlew publishPlugin` 을 실행하지 않는다.** 첫 게시는 수동이어야 하고 되돌리기 어렵다.
- **코틀린 파일을 셸 heredoc·리다이렉트로 쓰지 않는다.** `tdd-guard.sh` 가 `>`·`>>`·`tee` 의 대상 경로까지 검사해 차단한다. 편집 툴을 쓴다.
- **Apache POI 를 넣지 않는다. jsoup 스코프를 바꾸지 않는다.** `check.sh` lint 가 막는다. 근거는 아래 '되돌리면 안 되는 결정'.

## 작업이 막히는 지점: TDD 훅

이 프로젝트에서 낸 버그는 전부 테스트가 없던 자리에서 나왔다 (파일 타입 판별, `JBLoadingPanel` 조립, `XlsxReader` 희소 행). 그래서 커밋 시점이 아니라 **파일을 만드는 시점**에 막는다. git 훅이 아니라 Claude Code 훅이다 — 사람이 에디터로 직접 만드는 파일은 막지 않는다.

**훅·스킬·에이전트는 이 저장소의 `plugins/sheetview-kit` 플러그인이 싣는다.** `hooks/hooks.json` 이 배선하고, `.claude/settings.json` 은 더 이상 훅을 걸지 않는다. 설치하지 않으면 **아무것도 막지 않는다** — clone 직후 한 번:

```
/plugin marketplace add .
/plugin install sheetview-kit@sheetview
```

| 훅 (`plugins/sheetview-kit/hooks/`) | 시점 | 하는 일 |
|---|---|---|
| `session-brief.sh` | `SessionStart` | 하네스가 살아 있는지 보고한다. **막지 않는다** — 마커 깨짐·유령 표시·래칫 밀림·미커버 파일 수·`main` 브랜치 |
| `tdd-guard.sh` | `PreToolUse(쓰기 툴 전체)` | 본체 `.kt` 를 **새로** 만들려면 그 클래스를 언급하는 테스트가 먼저 있어야 한다. 없으면 `exit 2`. 게이트 자신을 고치려 하면 `ask` |
| `branch-guard.sh` | `PreToolUse(쓰기 툴 전체)` | `main`/`master` 에서 `src/` 를 고치려 하면 한 번 `ask`. 같은 브랜치에서 두 번 묻지 않는다 |
| `pre-commit-check.sh` | `PreToolUse(Bash)` | `tool_input.command` 에 커밋을 만드는 git 명령이 있으면 `scripts/check.sh` 를 먼저 실행 |
| `tdd-red.sh` | `PostToolUse(쓰기 툴)` | 새 테스트 파일을 만들면 그 클래스만 돌려 **실패(red)하는지** 확인. 처음부터 통과하면 `exit 2` |
| `cycle-review.sh` | `SubagentStop(sheet-reviewer)` | 리뷰 보고의 `### 🔴` 를 세어 `.claude/.cycle-state` 에 기록 |
| `cycle-verify.sh` | `SubagentStop(sandbox-runner)` | 샌드박스 판정 줄을 읽어 상태에 기록 |
| `cycle-stop.sh` | `Stop` | 사이클이 안 끝났으면 `exit 2` 로 세션을 되돌린다 |
| `agent-guard.sh` | 에이전트 전용 `PreToolUse(Bash)` | 읽기 전용 에이전트가 `sed -i`·리다이렉션 등으로 파일을 못 바꾸게 한다 |

**쓰기 툴 matcher 에는 MCP 도구가 포함된다** (`mcp__*__create|write|apply|…`). `Write` 만 막으면 `mcp__webstorm__create_new_file` 로 그냥 빠져나간다 — 실제로 그 구멍이 있었다.

설계 원칙 다섯:

1. **새 파일 생성만 게이트한다.** 기존 파일 수정까지 막으면 리팩터가 불가능해지고 훅을 우회하는 습관만 생긴다.
2. **판단이 안 되면 통과시킨다.** payload 가 깨졌든 `python3` 가 없든 모르면 `exit 0` 이다. 훅의 실패가 작업을 막는 실패로 번져선 안 된다. **따라서 훅 통과는 품질 보증이 아니다.**
3. **툴을 바꿔 우회하는 것을 막는다.** `cat > X.kt <<EOF` 도, `touch X.kt` 도, MCP 의 `create_new_file` 도 `Write` 툴을 타지 않는다. 특히 **`touch` 두 번이면 게이트가 완전히 사라졌다** — 빈 파일이 먼저 생기고, 그 다음 `Write` 는 "이미 있는 파일" 규칙으로 통과했다.
4. **게이트 자신을 고치는 것은 막지 않고 사람에게 올린다.** `tdd-exempt.txt` · `tdd-baseline` · `tdd-uncovered` · 훅 · `check.sh` 는 전부 `SRC_ROOT` 밖이라 어느 검사에도 걸리지 않았고, 차단 메시지가 면제 파일을 고치라고 **직접 가르쳐 주기까지 했다**. 차단하면 리팩터가 불가능해지므로 `ask` 로 올린다. 리뷰 지적을 없애는 가장 쉬운 길이 "면제에 추가"이기 때문에, 사이클에서 특히 중요하다.
5. **fail-open 하되 침묵하지 않는다.** 모르면 통과시키는 원칙은 유지하되, `session-brief.sh` 가 세션마다 하네스 상태를 말한다. 마커(`SHEETVIEW_SRC_ROOT`)가 하드코딩이라 **패키지를 리네임하면 훅 셋이 동시에 무력화되는데 아무 신호도 없었다.**

훅을 읽어야만 아는 것들:

- **게이트 대상은 `src/main/kotlin/dev/hugo/sheetview/**.kt` 신규 생성뿐이다.** 기존 파일 수정, `plugin.xml`, `build.gradle.kts`, `*.md`, `*.svg`, 테스트, 테스트 리소스는 전부 통과한다 (`tdd-guard.sh` 의 `check_one`). 문서만 고치는 작업은 훅에 걸리지 않는다.
- **통과 조건은 같은 이름의 테스트 파일이 아니다.** `src/test/kotlin` 아래 **어느 파일이든** 그 클래스 이름 문자열을 포함하면 인정한다 (`grep -rqlF`). 로직을 순수 클래스로 빼내 기존 테스트에서 쓰는 것이 가장 쉬운 통과 경로다.
- **면제는 `.claude/tdd-exempt.txt` 에 `<글로브><공백><사유>`.** 사유가 빈 줄은 무효다. 글로브는 `src/main/kotlin/dev/hugo/sheetview/` **기준 상대경로**(`editor/SheetPanel.kt` 형태)다. 면제는 "테스트하지 않아도 된다"가 아니라 "샌드박스에서 사람이 확인한다"는 뜻이다.
- **면제를 안내하는 경로는 `editor|filetype|preview` 뿐이다.** 다른 경로에서 막히면 훅이 "순수 로직이라 면제 대상이 아니다"라고 거절한다. 그 경우 답은 면제가 아니라 테스트다.
- **`tdd-red.sh` 는 한 번만 판정한다.** `tdd-guard.sh` 가 `.claude/.tdd-new` 에 남긴 표시를 **읽고 지운다**. 새 테스트를 만든 직후 그 파일을 또 수정하면 두 번째부터는 red 검사가 돌지 않는다.
- **컴파일 실패는 red 가 아니다.** `tdd-red.sh` 가 구분해서 `exit 2` 로 돌려준다.
- **훅은 마커로 프로젝트 루트를 찾는다** — `gradlew` 와 `src/main/kotlin/dev/hugo/sheetview/` 가 함께 있는 디렉터리다 (`hooks/_common.sh`). `CLAUDE_PROJECT_DIR` 과 cwd 를 각각 위로 훑고, 못 찾으면 통과한다. 그래서 플러그인을 사용자 범위로 설치해도 다른 프로젝트에서는 무해하다. **스크립트 위치에서 역산하던 예전 방식(`dirname "$0"/../..`)으로 되돌리지 않는다** — 이제 스크립트는 플러그인 설치 경로에 있어서 프로젝트와 무관하다.
- **훅을 고치면 `sh plugins/sheetview-kit/scripts/test-hooks.sh` 를 먼저 돌린다.** 훅이 틀리면 전체 작업이 막히거나, 더 나쁘게는 조용히 통과한다.

## 작업 사이클

코드를 바꾸는 작업은 `sheetview-kit:feature-cycle` 스킬로 시작한다. 브랜치 → TDD 구현 → 리뷰 → 수정 → 샌드박스 검증 → (문제가 있으면 반복) → 사람에게 인계.

**스킬은 안내하고 훅이 강제한다.** `.claude/.cycle-state` 가 그 둘을 잇는 유일한 상태다:

- `cycle-review.sh` 가 리뷰 보고의 `### 🔴` 를 세어 `review=red:N` / `clean` / `unknown` 을 기록한다. **`unknown` 은 통과가 아니다** — 보고 형식을 못 읽었다는 뜻이고 사람이 확인해야 한다.
- `cycle-verify.sh` 가 샌드박스 판정 줄을 읽어 `verify=` 를 기록한다.
- `cycle-stop.sh` 가 둘 다 `clean` 이 아니면 `Stop` 에서 `exit 2` 로 세션을 되돌린다.

**`.cycle-state` 가 없으면 이 훅들은 아무 일도 하지 않는다.** 문서만 고치는 작업에 사이클은 필요 없다.

탈출구가 셋이다 (세션이 갇히면 안 되므로): 파일이 없으면 통과 / `stop_blocked` 가 3에 닿으면 포기하고 통과 / 리뷰 라운드가 `max_rounds` 에 닿으면 사람을 부르고 통과. **플랫폼은 `Stop` 훅의 무한루프를 막아주지 않는다 — 훅이 직접 막아야 한다.**

`.cycle-state` 는 `tdd-red.sh` 의 교훈을 적용해 **읽고 지우지 않는다.** 그 훅은 `.tdd-new` 표시를 소비해서 "한 번만 판정"하는 버그를 냈다.

## 빌드와 검증

`./scripts/check.sh` 가 유일한 게이트다: lint(불변식 grep) → `compileKotlin compileTestKotlin` → `buildPlugin` → `test` → 테스트 수 래칫 → **미커버 래칫**. CI(`.github/workflows/check.yml`)가 같은 스크립트를 돌린다 — 훅은 플러그인을 설치한 사람에게만 걸리므로, 재현 가능한 검증 지점은 거기 하나다. `pre-commit-check.sh` 가 커밋 앞에서 부르는 것과 같다. lint 는 ktlint/detekt 가 아니라 **컴파일러가 잡아주지 않는 불변식 6개**를 grep 으로 지킨다 (POI 금지 / jsoup 스코프 / `<idea-version>` / csv·tsv·html 선점 / `XMLInputFactory` 의 `SUPPORT_DTD` / `printStackTrace()`).

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
- **테스트가 두 종류다.** (a) 순수 JVM JUnit 5 — 리더·모델·export. 플랫폼 클래스를 쓰지 않으므로 IDE 없이 몇 초에 돈다. 이 자산을 지키려면 리더 쪽에 플랫폼 의존을 들이지 않는다. (b) `BasePlatformTestCase` — 파일 타입과 탭 구성처럼 `FileTypeRegistry` · `FileEditorProviderManager` 가 필요한 것. **JUnit3 계열이라 메서드 이름이 `test` 로 시작해야 발견**되고 vintage engine 으로 돈다.
- **`verifyPlugin` 은 `plugin.xml` · `build.gradle.kts` · 플랫폼 API 를 건드렸을 때만** 돌린다. 생략했으면 그 사실을 보고에 적는다.
- **JDK 25 가 필요하다.** 플랫폼 262 클래스가 Java 25 바이트코드(major 69)다. 없으면 foojay 리졸버가 받아 오고, 플랫폼 의존성도 지정이 없으면 원격 아티팩트를 받는다 — clone 직후 아무 설정 없이 빌드된다.
- **개인 경로는 저장소에 두지 않는다.** `~/.gradle/gradle.properties` 에 `org.gradle.java.installations.paths` / `localIdePath` / `verifyIdePaths` 를 넣으면 ~1GB 다운로드가 사라진다(설치된 IDE의 번들 JBR이 `javac 25` 를 포함한 완전한 JDK다). 저장소의 `gradle.properties` 에는 빈 키와 설명만 있다.
- **테스트 개수를 문서에 하드코딩하지 않는다.** 이미 한 번 썩었다.

### 미커버 래칫

`tdd-guard` 는 **신규 생성만** 막는다. 훅이 생기기 전부터 있던 본체 파일은 면제 목록에도 없이 테스트 없이 계속 고칠 수 있다 — 지금 6개 파일 약 590줄이 그렇다(`.claude/tdd-uncovered`). 그 사각지대가 조용히 넓어지는 것을 `check.sh` 가 막는다: 목록보다 **늘면 실패**하고, 줄이는 것은 자유다.

테스트를 붙였으면 해당 줄을 지운다. 정말 헤드리스가 불가능하면 `tdd-exempt.txt` 로 옮기되, 면제는 "샌드박스에서 사람이 확인한다"는 **청구서**다.

### 테스트 수 래칫

`.claude/tdd-baseline` 보다 줄면 `check.sh` 가 실패한다 — 테스트를 줄이는 것은 명시적 결정이어야 한다. **늘어날 때는 자동으로 올라가지 않고 안내만 한다.** 그래서 **커밋 전 마지막 단계로 직접 갱신한다.** 숫자는 `check.sh` 가 출력하는 `(N개 통과)` 를 그대로 쓴다 — `@Test` 개수가 아니라 JUnit XML 의 `tests=` 합계이고, `BasePlatformTestCase` 쪽은 `@Test` 가 없으므로 세어서 맞추려 하면 틀린다.

### 테스트가 red 인데 내가 고친 것과 무관해 보이면

진행 중인 기능이 테스트를 먼저 올려 둔 상태일 수 있다. `TODO.md` 의 '추가할 기능'을 먼저 확인하고, 내 변경분만 돌려 본다(`./gradlew test --tests "…"`). 그 상태에서는 `check.sh` 가 실패하므로 **Claude 는 커밋할 수 없다** — 사람이 터미널에서 직접 커밋해야 한다.

## 되돌리면 안 되는 결정

전부 측정해서 얻은 결론이다. 이상해 보이는 코드에는 대개 주석에 이유가 적혀 있다.

### 파일 타입과 탭

- **`extensions="xls;xlsx;xlsm;xltx;xltm"` 를 반드시 등록한다.** 한때 "확장자 매핑이 이름 기반으로 이겨서 내 탐지기를 가린다"고 판단해 지웠는데 측정해 보니 **반대**였다. `extensions` 가 없으면 `.xls` 는 번들 grid 플러그인의 `Data File`(`DataLoaderManager$DataFileType`)이 되고, 그 타입은 `FileTypeIdentifiableByVirtualFile` 이라 **내용 기반 탐지기보다 먼저** 평가되므로 탐지기는 영원히 호출되지 않는다. 확장자를 놓으면 파일 타입을 남에게 넘기는 것이고, grid 가 없는 IDE(IDEA/PyCharm)에서는 `UNKNOWN` 이 된다. → **탐지기로는 이길 수 없다.** 회귀 테스트는 `FileTypeAndTabsTest`.
- **그래서 HTML 위장 `.xls` 의 원본을 플랫폼 텍스트 탭에 기댈 수 없다.** 어느 쪽이든 파일 타입이 binary 라 텍스트 에디터가 붙지 않는다. 원본은 이 플러그인의 `원본` 탭이 직접 읽어 보여준다.
- **자동 선점 확장자는 그 다섯뿐이다.** `csv`/`tsv` 는 번들 `grid-core-plugin` 의 `csv-data-editor` 가 이미 담당하고 편집까지 지원한다. `html` 도 제외. 그런 파일은 우클릭 → **표로 열기**로 명시 진입한다. `check.sh` lint 가 `csv|tsv|html` 선점을 막는다.
- **아래쪽 `Data` 탭은 이 플러그인이 만든 것이 아니다.** `com.intellij.grid.scripting.impl.ScriptedTableFileEditorProvider`(`scripted-data-editor`)다. `csv-data-editor` 가 아니다 — 처음엔 그쪽이라고 판단했지만 `FileEditorProviderManager.getProviderList()` 로 확인해 보니 달랐다. 파일 타입을 내 타입으로 바꿔도 이 탭은 그대로 붙고, 실제로 읽어 줄 `grid-loader-*` 는 DataGrip 전용이라 WebStorm 에서는 `No loader for …` 만 뜬다. **내 쪽에서 없앨 수 없다.**
- **`plugin.xml` 의 `fileEditorProvider` 등록 순서가 탭 순서다.** 표가 먼저 선택되어야 한다.
- **추측하지 말고 `FileEditorProviderManager.getProviderList(project, file)` 를 테스트에서 찍어 보라.** 어떤 탭이 붙는지, 파일 타입이 무엇인지는 `BasePlatformTestCase` 로 몇 초 만에 확인된다. 위 항목들의 오판은 전부 이걸 안 해서 생겼다.

### 표 파싱

- **헤더 행은 `<thead>` 기준이다.** "`<th>` 를 포함한 선두 행" 규칙은 **틀린다** — 요약내역 표 본문 첫 행에 `<th>이번달</th>`(rowspan=3, 행 방향 헤더)가 있어 헤더를 2행으로 오인한다. 이 규칙으로 되돌리는 변경은 무조건 지적 대상이다.
- **colspan/rowspan 은 점유 맵으로 펼친다.** 상세내역 표가 `rowspan="2"` + `colspan="2"` 2단 헤더라 이게 없으면 컬럼 정렬이 깨진다. 결과 그리드는 직사각형이어야 한다.
- **셀 타입은 텍스트에서 추론한다.** 대상 파일에는 `mso-number-format`·`x:num` 이 하나도 없다. `x:num` 이 있으면 그쪽을 우선한다.

### 의존성과 이식성

- **Apache POI 를 넣지 않는다.** `xmlbeans`·`log4j-api`·`SparseBitSet` 등 약 7MB가 따라오고 IDE 클래스로더와 충돌 여지가 생긴다. 진짜 BIFF `.xls` 는 예외 대신 **미지원 안내 패널**로 처리한다 — 여기서 안내를 띄우는 것이 POI 를 끌고 오는 것보다 낫다는 판단이다. (참고: DataGrip 도 POI 를 플러그인 클래스로더 **밖** Grape 캐시에 격리해 쓴다.)
- **jsoup 은 번들하지 않는다.** IDE 가 1.22.1 을 부트 클래스패스에 이미 싣고 있어 `compileOnly` 로만 참조한다. 번들하면 버전이 충돌한다.
- **원격 플랫폼 의존성에 `create("IC", …)` 를 쓰지 않는다.** IntelliJ IDEA Community 는 **2025.3(253)부터 배포가 중단**되어 `idea:ideaIC:<버전>` 이 존재하지 않는다. `intellijIdea(version)` 을 쓴다(좌표가 `idea:idea` 가 된다). 이 자리는 `localIdePath` 가 설정된 로컬에서는 **아예 타지 않으므로** 깨져 있어도 눈치채기 어렵다 — 실제로 CI 를 붙이고 나서야 처음 드러났다. "clone 직후 아무 설정 없이 빌드된다"는 전제는 CI 만이 지켜준다.
- **`<idea-version>` 을 `plugin.xml` 에 두지 않는다.** `build.gradle.kts` 의 `ideaVersion` DSL 이 `patchPluginXml` 로 주입한다. 두 곳에 두면 소스가 갈린다. id·name·vendor·description 은 반대로 `plugin.xml` 에만 둔다.
- **`untilBuild` 를 되살리지 않는다.** 무제한이라 이후 IDE 업데이트에도 깨지지 않는다. 기본값 `"262.*"` 로 되돌리면 263 계열 IDE(설치된 PyCharm)가 로드를 거부한다. 공식 권장도 이 속성을 설정하지 않는 쪽이다.
- **플러그인 description 은 라틴 문자로 시작해야 한다.** 한국어로 시작하면 Plugin Verifier 가 구조 오류로 반려한다(Marketplace 규칙). 로컬 설치에는 영향 없지만 `verifyPlugin` 이 막힌다.
- **플랫폼 모듈(`com.intellij.modules.platform`) 밖의 클래스를 쓰지 않는다.** 특정 IDE 전용 클래스가 섞이면 다섯 IDE 중 일부에서 깨진다. `verifyPlugin` 이 이걸 잡는다.

### Swing 과 에디터 플랫폼

- **`JBLoadingPanel` 을 직접 비우면 안 된다.** `add()` 는 재정의해 내부 content 패널로 위임하지만 `removeAll()` 은 재정의하지 않는다. `removeAll()` 을 부르면 화면에 붙어 있는 `LoadingDecorator` 가 떨어져 나가고, 이후 `add()` 한 내용은 분리된 패널로 들어가 **화면이 빈 채로 아무 오류도 안 난다**. 전용 content 패널(`SheetPanel.content`)을 하나 넣고 그 자식만 교체한다.
- **`EditorFactory.createViewer` 로 만든 에디터는 `releaseEditor` 로 놓아준다.** 빠뜨리면 탭을 닫아도 살아남는다. 에디터 탭은 수십 번 열리고 닫히므로 누수는 반드시 쌓인다.
- **IntelliJ `Document` 는 `\r` 을 담을 수 없다.** CRLF 파일을 그대로 `createDocument` 에 넣으면 예외가 난다. `StringUtil.convertLineSeparators` 를 거친다(줄 번호는 원본과 그대로 일치한다).
- **배너를 쌓는 칸과 갈아끼우는 칸을 분리한다.** 파트를 고를 때마다 같은 칸에 배너를 add 하면 5개 고르면 5줄 쌓인다.
- **`EditorNotificationPanel(Status)` 단일 인자 생성자는 없다.** `(Color?, Status)` 를 쓴다.
- **`TableSpeedSearch` 생성자는 deprecated.** `TableSpeedSearch.installOn(table)` 을 쓴다.
- **컬럼 폭은 앞 200행만 측정한다.** 전체 행×열을 측정하는 것이 표 플러그인이 파일을 열 때 멈춰버리는 전형적 원인이다. 가상 스크롤은 일부러 두지 않았다 — Swing 이 보이는 셀만 조회하고 리더가 이미 그리드를 메모리에 만들었으므로, 대용량은 리더 쪽 상한으로 막는다.

### JCEF 미리보기

- **JCEF 는 플랫폼이 아니라 번들 *플러그인*이다.** `com.intellij.modules.jcef`(`plugins/jcef-plugin/`)이고 디스크립터가 `modules.os.mac` / `modules.arch.arm64` 에 의존한다 — 환경에 따라 없을 수 있다. 필수 의존으로 걸면 없는 환경에서 **플러그인 전체가 로드되지 않으므로** `<depends optional="true" config-file="sheetview-jcef.xml">` 로 두고, JCEF 를 참조하는 구현은 그 파일에서만 등록한다. 메인 모듈은 `JBCefApp` 이라는 이름조차 모른다.
- **없는 것이 정상인 구현은 서비스가 아니라 확장 포인트로 찾는다.** 등록되지 않은 서비스를 `getService` 로 찾으면 플랫폼이 "not registered as a service" 오류를 로그에 남겨 진짜 문제를 찾을 때 방해가 된다.
- **`JBCefBrowser` 를 툴바 `update()` 에서 만들면 안 된다.** `update()` 는 EDT 에서 수시로 불리는데 브라우저 생성은 Chromium 초기화를 끌고 와 IDE 를 멈춘다. "미리보기를 쓸 수 있는가"(확장 포인트 조회)와 "브라우저를 만든다"를 분리한다.
- **`Jsoup.clean(Safelist)` 로 HTML 을 정화하면 안 된다.** `<style>` 블록과 `class` 속성을 날려 "원본을 보여준다"가 거짓이 된다. 이 파일들의 스타일은 전부 거기 있다. 위험한 태그·속성만 지목해 제거하고, 실행·네트워크는 CSP(`default-src 'none'`)로 막는다.

### 원본 탭은 IDE 테마와 무관하게 항상 밝다

사용자 요청이자 가독성 문제다. 되돌리면 다크 테마에서 **글자가 사라진다.**

- **`color-scheme: only light` 를 지우지 않는다.** 이 파일들은 `color:windowtext` 같은 CSS 시스템 컬러를 쓴다(대상 파일에 4곳). 시스템 컬러는 페이지의 `color-scheme` 에 따라 해석이 바뀌어, 다크 모드 Chromium 에서는 밝은 색이 되어 글자가 보이지 않는다. 표 격자(`#808080`)와 헤더 배경(`#d9d9d9`)은 고정값이라 남는다 — 그래서 "표는 보이는데 글자가 안 보이는" 모양이 된다.
- **기준 스타일(`HtmlSanitizer.BASE_STYLE`)은 `<head>` 에서 CSP `<meta>` 바로 다음, 문서 자신의 `<style>` 보다 앞이다.** 뒤로 가면 우리 규칙이 원본 서식을 덮어써서 "원본대로 보여준다"가 거짓이 된다. 명시도도 일부러 낮게 둔다 — 캔버스만 정하고 나머지는 원본이 이긴다.
- **시스템 컬러 치환은 선언 값 안에서만 한다.** `\bwindow\b` 를 통째로 바꾸면 `.window{…}` 클래스 이름이 `.#ffffff{…}` 가 되어 스타일시트 전체가 깨진다. `:` 뒤부터 `;`/`{`/`}` 전까지를 값으로 본다(`a:hover` 는 값이 `hover` 라 안전). `color-scheme` 만으로 충분해 보이지만 브라우저 렌더링은 헤드리스로 확인할 수 없어 두 겹으로 막는다. 라이트 모드에서 의미가 같은 값이라 보이는 결과는 달라지지 않는다.
- **원본 훼손이 아니다.** 미리보기는 *렌더링*이고, 바이트 그대로는 `소스` 보기가 담당한다. 소스 본문은 손대지 않는다.
- **소스·파트 보기는 배경색만 바꾸면 안 된다.** 다크 테마의 구문 강조 색(밝은 노랑·연회색)이 흰 바탕에 그대로 얹혀 **지금보다 더 안 보인다.** `LightEditorScheme.pick()` 으로 색 구성표 자체를 갈아끼운다. 구성표를 이름 하나로만 찾지 않는 이유: 번들 구성표 이름은 IDE 버전·제품마다 다르다. "밝은 것 아무거나"가 이름보다 오래 간다. 회귀 테스트는 `LightEditorSchemeTest`.
- **`createBoundColorSchemeDelegate` 를 거친다.** 색만 갈아끼우고 글꼴·크기는 사용자 설정을 상속한다. 구성표를 통째로 대입하면 글꼴까지 바뀐다.
- **툴바·배너·탭 같은 IDE 크롬은 칠하지 않는다.** 내용 영역만 밝게 한다. 크롬까지 칠하면 IDE 안에서 이 탭만 이질적인 창이 된다 — 다크 크롬 안의 흰 페이지가 브라우저와 같은 자연스러운 모양이다.

## 실패와 예외 규약

- 사용자에게 보이는 실패는 **두 타입으로만** 모인다: `UnsupportedSpreadsheetException`(정상적인 미지원) / `SpreadsheetParseException`(형식은 맞지만 깨짐). 둘 다 `userMessage` 와 선택적 `hint` 를 들고 패널에 **한국어로** 표시된다.
- `userMessage` 는 무엇이 안 됐는지 한 문장. `hint` 는 사용자가 할 수 있는 다음 행동. **"파싱 실패"는 메시지가 아니다.**
- **`ProcessCanceledException` 과 `CancellationException` 은 절대 삼키지 않는다.** 재던진다. `catch` 순서를 눈으로 확인해야 한다 — 넓은 `catch (t: Throwable)` 가 위에 오면 조용히 먹는다. 정리 코드는 `NonCancellable` 로 보장한다.
- **`printStackTrace()` 를 쓰지 않는다.** 스택트레이스를 콘솔에 뱉는 경로는 위 규약을 깨고, `check.sh` lint 가 막는다.
- 취소는 `ReadContext.checkCancelled` 하나로 전파된다. 리더는 행·항목 일정 간격마다 이걸 부른다.
- 새 StAX 파서를 만들면 `SUPPORT_DTD` 와 `IS_SUPPORTING_EXTERNAL_ENTITIES` 를 **반드시** `false` 로 둔다(XXE·billion-laughs). `check.sh` lint 가 `XMLInputFactory.newInstance()` 를 쓰는 파일에서 이걸 검사한다.
- 자원 상한은 `ReadLimits` 에 모여 있다. 새 리더를 추가하면 상한을 **실제로 적용**해야 한다 — 선언만 있고 적용이 빠진 자리가 이미 있다(`TODO.md` 참고).

## 고친 뒤

- 리뷰는 `sheetview-kit:spreadsheet-review` 스킬 또는 `sheetview-kit:sheet-reviewer` 에이전트에 맡긴다. 코드 지도와 체크리스트 8개가 거기 있다.
- 테스트로 확인할 수 없는 것(Swing 조립 · 탭 순서 · JCEF · 탭을 닫을 때의 취소)은 `sheetview-kit:sandbox-run` 스킬 또는 `sheetview-kit:sandbox-runner` 에이전트로 샌드박스에 띄워 사람이 확인한다. `.claude/tdd-exempt.txt` 의 면제가 바로 이 확인을 전제로 한 것이다.
- 커밋 전: `./scripts/check.sh` 통과 → `.claude/tdd-baseline` 갱신.
- 다음에 할 일은 `TODO.md`.
