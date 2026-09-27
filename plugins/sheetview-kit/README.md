# sheetview-kit

Spreadsheet Viewer 저장소에서 쓰는 작업 도구. 훅 7개 · 스킬 4개 · 에이전트 2개.

이 저장소 전용이다. 모든 훅이 **마커**(`gradlew` + `src/main/kotlin/dev/hugo/sheetview/`)로 프로젝트를 판별하고, 마커가 없으면 조용히 통과한다 — 사용자 범위로 설치해도 다른 프로젝트에서는 무해하다.

## 설치

```
/plugin marketplace add .
/plugin install sheetview-kit@sheetview
```

로컬 디렉터리 마켓플레이스는 복사 없이 제자리에서 로드된다 — 고치면 바로 반영된다.

## 훅

| 훅 | 시점 | 하는 일 |
|---|---|---|
| `session-brief.sh` | `SessionStart` | 하네스가 살아 있는지 보고한다. 마커 깨짐·유령 표시·래칫 밀림·미커버 파일 수·`main` 브랜치 |
| `tdd-guard.sh` | `PreToolUse` (쓰기 툴 전체) | 본체 `.kt` **신규 생성**에 테스트를 요구한다. 게이트 자신을 고치려 하면 `ask` |
| `branch-guard.sh` | `PreToolUse` (쓰기 툴 전체) | `main`/`master` 에서 소스를 고치려 하면 한 번 `ask` |
| `pre-commit-check.sh` | `PreToolUse(Bash)` | 커밋 앞에서 `scripts/check.sh` 를 돌린다 |
| `tdd-red.sh` | `PostToolUse` | 새 테스트가 **실패(red)하는지** 확인한다 |
| `cycle-review.sh` | `SubagentStop(sheet-reviewer)` | 리뷰 보고의 `### 🔴` 를 세어 사이클 상태에 기록 |
| `cycle-verify.sh` | `SubagentStop(sandbox-runner)` | 샌드박스 판정 줄을 읽어 사이클 상태에 기록 |
| `cycle-stop.sh` | `Stop` | 사이클이 안 끝났으면 세션을 되돌린다 (탈출구 3중) |
| `agent-guard.sh` | 에이전트 전용 `PreToolUse(Bash)` | 읽기 전용 에이전트가 `sed -i`·리다이렉션 등으로 파일을 못 바꾸게 한다 |

**쓰기 툴 matcher 에는 MCP 도구가 포함된다** (`mcp__*__create|write|apply|…`). `Write` 만 막으면 `mcp__webstorm__create_new_file` 로 그냥 빠져나간다.

## 스킬

| 스킬 | 언제 |
|---|---|
| `feature-cycle` | 코드를 바꾸는 작업의 처음부터 끝까지 |
| `spreadsheet-review` | 고친 뒤 리뷰할 때 |
| `sandbox-verify` | 사이클의 검증 단계 — 기계가 볼 수 있는 것만 보고 판정 한 줄로 끝낸다 |
| `sandbox-run` | 사람에게 인계할 때 — 띄우고 "확인 끝"을 기다린다 |

## 에이전트

| 에이전트 | 역할 |
|---|---|
| `sheet-reviewer` | 코드 리뷰. `runIde` 를 포함해 쓰기 명령이 전부 거절된다 |
| `sandbox-runner` | 샌드박스 기동. `runIde` 는 허용되고 쓰기만 거절된다 |

## 스크립트

| 스크립트 | 하는 일 |
|---|---|
| `scripts/test-hooks.sh` | 훅 자체의 테스트 (93 케이스) |
| `scripts/sandbox-up.sh` | `status` / `preflight` / `wait` / `down` |
| `scripts/sandbox-log.sh` | `mark` / `since` / `path` — 이번 기동 구간만 잘라 읽는다 |

`sandbox-log.sh` 가 따로 있는 이유: **Bash 호출 사이에 셸 상태가 유지되지 않는다.** `MARK=$(wc -l …)` 를 잡아 두고 나중에 `tail -n +$MARK` 하는 절차는 그 사이에 변수가 사라져서 깨진다. 경계를 파일에 둔다.

## 프로젝트에 남는 상태

| 파일 | 누가 쓰나 | git |
|---|---|---|
| `.claude/tdd-exempt.txt` | 사람 (수정 시 `ask`) | 추적 |
| `.claude/tdd-baseline` | 사람 (수정 시 `ask`) | 추적 |
| `.claude/.tdd-new` | `tdd-guard` → `tdd-red` | 무시 |
| `.claude/.cycle-state` | 사이클 훅 셋 | 무시 |
| `.claude/.sandbox-mark` | `sandbox-log.sh` | 무시 |
| `.claude/.branch-ack` | `branch-guard` | 무시 |

`.cycle-state` 가 **없으면 사이클 훅들은 아무 일도 하지 않는다.** 평소 세션에 영향이 없어야 한다.

## 훅을 고쳤다면

```
sh plugins/sheetview-kit/scripts/test-hooks.sh
```

훅이 틀리면 전체 작업이 막히거나, 더 나쁘게는 **조용히 통과한다.** `session-brief.sh` 가 있는 이유가 그 두 번째 때문이다.
