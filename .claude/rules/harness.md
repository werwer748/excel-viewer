---
paths:
  - "plugins/sheetview-kit/**/*"
  - "scripts/check.sh"
  - ".claude/tdd-exempt.txt"
  - ".claude/tdd-baseline"
  - ".claude/tdd-uncovered"
  - ".claude-plugin/*.json"
---

# 하네스 — 훅 · 사이클 · 래칫 · lint

훅 · 게이트 파일 · `check.sh` 를 읽을 때 붙는다. **훅을 고치면 `sh plugins/sheetview-kit/scripts/test-hooks.sh` 를 먼저 돌린다.** 훅이 틀리면 전체 작업이 막히거나, 더 나쁘게는 조용히 통과한다. 훅 목록은 `plugins/sheetview-kit/README.md` 에 있다.

이 프로젝트에서 낸 버그는 전부 테스트가 없던 자리에서 나왔다 (파일 타입 판별, `JBLoadingPanel` 조립, `XlsxReader` 희소 행). 그래서 커밋 시점이 아니라 **파일을 만드는 시점**에 막는다. git 훅이 아니라 Claude Code 훅이다 — 사람이 에디터로 직접 만드는 파일은 막지 않는다. `hooks/hooks.json` 이 배선하고, `.claude/settings.json` 은 더 이상 훅을 걸지 않는다.

**쓰기 툴 matcher 에는 MCP 도구가 포함된다** (`mcp__*__create|write|apply|…`). `Write` 만 막으면 `mcp__webstorm__create_new_file` 로 그냥 빠져나간다 — 실제로 그 구멍이 있었다.

## 설계 원칙 다섯

1. **새 파일 생성만 게이트한다.** 기존 파일 수정까지 막으면 리팩터가 불가능해지고 훅을 우회하는 습관만 생긴다.
2. **판단이 안 되면 통과시킨다.** payload 가 깨졌든 `python3` 가 없든 모르면 `exit 0` 이다. 훅의 실패가 작업을 막는 실패로 번져선 안 된다. **따라서 훅 통과는 품질 보증이 아니다.**
3. **툴을 바꿔 우회하는 것을 막는다.** `cat > X.kt <<EOF` 도, `touch X.kt` 도, MCP 의 `create_new_file` 도 `Write` 툴을 타지 않는다. 특히 **`touch` 두 번이면 게이트가 완전히 사라졌다** — 빈 파일이 먼저 생기고, 그 다음 `Write` 는 "이미 있는 파일" 규칙으로 통과했다.
4. **게이트 자신을 고치는 것은 막지 않고 사람에게 올린다.** `tdd-exempt.txt` · `tdd-baseline` · `tdd-uncovered` · 훅 · `check.sh` 는 전부 `SRC_ROOT` 밖이라 어느 검사에도 걸리지 않았고, 차단 메시지가 면제 파일을 고치라고 **직접 가르쳐 주기까지 했다**. 차단하면 리팩터가 불가능해지므로 `ask` 로 올린다. 리뷰 지적을 없애는 가장 쉬운 길이 "면제에 추가"이기 때문에, 사이클에서 특히 중요하다.
5. **fail-open 하되 침묵하지 않는다.** 모르면 통과시키는 원칙은 유지하되, `session-brief.sh` 가 세션마다 하네스 상태를 말한다. 마커(`SHEETVIEW_SRC_ROOT`)가 하드코딩이라 **패키지를 리네임하면 훅 셋이 동시에 무력화되는데 아무 신호도 없었다.**

## 훅을 읽어야만 아는 것들

- **게이트 대상은 `src/main/kotlin/dev/hugo/sheetview/**.kt` 신규 생성뿐이다.** 기존 파일 수정, `plugin.xml`, `build.gradle.kts`, `*.md`, `*.svg`, 테스트, 테스트 리소스는 전부 통과한다 (`tdd-guard.sh` 의 `check_one`). 문서만 고치는 작업은 훅에 걸리지 않는다.
- **면제는 `.claude/tdd-exempt.txt` 에 `<글로브><공백><사유>`.** 사유가 빈 줄은 무효다. 글로브는 `src/main/kotlin/dev/hugo/sheetview/` **기준 상대경로**(`editor/SheetPanel.kt` 형태)다. 면제는 "테스트하지 않아도 된다"가 아니라 "샌드박스에서 사람이 확인한다"는 뜻이다.
- **면제를 안내하는 경로는 `editor|filetype|preview` 뿐이다.** 다른 경로에서 막히면 훅이 "순수 로직이라 면제 대상이 아니다"라고 거절한다. 그 경우 답은 면제가 아니라 테스트다.
- **훅은 마커로 프로젝트 루트를 찾는다** — `gradlew` 와 `src/main/kotlin/dev/hugo/sheetview/` 가 함께 있는 디렉터리다 (`hooks/_common.sh`). `CLAUDE_PROJECT_DIR` 과 cwd 를 각각 위로 훑고, 못 찾으면 통과한다. 그래서 플러그인을 사용자 범위로 설치해도 다른 프로젝트에서는 무해하다. **스크립트 위치에서 역산하던 예전 방식(`dirname "$0"/../..`)으로 되돌리지 않는다** — 이제 스크립트는 플러그인 설치 경로에 있어서 프로젝트와 무관하다.
- 게이트 통과 조건(어느 테스트든 클래스 이름 언급)과 `tdd-red.sh` 의 1회 판정·컴파일 실패 구분은 테스트를 쓰는 쪽이 알아야 하는 사실이라 `.claude/rules/testing.md` 에 둔다.

## 작업 사이클의 기계

**스킬은 안내하고 훅이 강제한다.** `.claude/.cycle-state` 가 그 둘을 잇는 유일한 상태다. 단계 설명은 `feature-cycle` 스킬에 있다.

- `cycle-review.sh` 가 리뷰 보고의 `### 🔴` 를 세어 `review=red:N` / `clean` / `unknown` 을 기록한다. **`unknown` 은 통과가 아니다** — 보고 형식을 못 읽었다는 뜻이고 사람이 확인해야 한다.
- `cycle-verify.sh` 가 샌드박스 판정 줄을 읽어 `verify=` 를 기록한다.
- `cycle-stop.sh` 가 둘 다 `clean` 이 아니면 `Stop` 에서 `exit 2` 로 세션을 되돌린다.

**`.cycle-state` 가 없으면 이 훅들은 아무 일도 하지 않는다.**

탈출구가 셋이다 (세션이 갇히면 안 되므로): 파일이 없으면 통과 / `stop_blocked` 가 3에 닿으면 포기하고 통과 / 리뷰 라운드가 `max_rounds` 에 닿으면 사람을 부르고 통과. **플랫폼은 `Stop` 훅의 무한루프를 막아주지 않는다 — 훅이 직접 막아야 한다.**

`.cycle-state` 는 `tdd-red.sh` 의 교훈을 적용해 **읽고 지우지 않는다.** 그 훅은 `.tdd-new` 표시를 소비해서 "한 번만 판정"하는 버그를 냈다.

## 래칫 둘

**미커버 래칫.** `tdd-guard` 는 **신규 생성만** 막는다. 훅이 생기기 전부터 있던 본체 파일은 면제 목록에도 없이 테스트 없이 계속 고칠 수 있다 — 그 목록이 `.claude/tdd-uncovered` 다(개수는 `session-brief.sh` 가 세션마다 말한다). 그 사각지대가 조용히 넓어지는 것을 `check.sh` 가 막는다: 목록보다 **늘면 실패**하고, 줄이는 것은 자유다. 테스트를 붙였으면 해당 줄을 지운다. 정말 헤드리스가 불가능하면 `tdd-exempt.txt` 로 옮기되, 면제는 "샌드박스에서 사람이 확인한다"는 **청구서**다.

**테스트 수 래칫.** `.claude/tdd-baseline` 보다 줄면 `check.sh` 가 실패한다 — 테스트를 줄이는 것은 명시적 결정이어야 한다. **늘어날 때는 자동으로 올라가지 않고 안내만 한다.** 숫자 셈법은 `testing.md`.

## `check.sh` lint

ktlint/detekt 가 아니라 **컴파일러가 잡아주지 않는 불변식**을 grep 으로 지킨다: POI 금지 / jsoup 스코프 / `<idea-version>` / csv·tsv·html 선점 / `XMLInputFactory` 의 `SUPPORT_DTD` / `printStackTrace()` / marketplace 메타데이터 중복 / 영역 규칙의 `paths`. 컴파일러 경고를 게이트로 삼지 않는 이유는 `check.sh` 주석에 있다.

## 영역 규칙(`.claude/rules/`) 유지

- **규칙은 `paths:` 글로브에 맞는 파일을 Read 할 때만 붙는다.** 훅 마커와 같은 실패 모양이다 — 패키지를 옮기면 글로브가 아무것도 가리키지 않게 되고, 규칙은 조용히 영영 안 붙는다. `check.sh` lint 가 (a) `paths` 가 없는 규칙(매 세션 즉시 로드되므로 CLAUDE.md 와 갈라진다) (b) 중괄호 글로브(lint 가 검사할 수 없다 — 항목을 나눠 적는다) (c) 맞는 파일이 0개인 글로브를 막는다.
- **규칙은 소스 트리 밖에 둔다.** 하위 `CLAUDE.md` 를 `src/main/kotlin` 에 두면 lint 의 grep 이 본문에 적힌 `printStackTrace()` 를 위반으로 센다.
- 규칙을 추가·삭제하면 `CLAUDE.md` 의 '영역 규칙' 색인 표도 고친다. 색인은 즉시 로드되고 본문은 지연 로드된다 — Read 없이 파일을 바꾸는 경로(Bash·MCP·새 파일)에서 색인이 유일한 안내다.
