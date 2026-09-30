---
paths:
  - "AGENTS.md"
  - "CLAUDE.md"
  - "README.md"
  - "TODO.md"
  - ".claude/rules/*.md"
  - "plugins/sheetview-kit/README.md"
  - "plugins/sheetview-kit/skills/*/SKILL.md"
  - "plugins/sheetview-kit/agents/*.md"
---

# 문서 — 무엇을 어디에 적는가

문서를 읽거나 고칠 때 붙는다. 같은 사실을 여러 곳에 적지 않는다. **언제 컨텍스트에 올라오는가**로 나뉜다.

| 문서 | 올라오는 시점 | 담는 것 |
|---|---|---|
| `AGENTS.md` | 세션 시작 — 항상 (Claude Code 는 `CLAUDE.md` 의 `@AGENTS.md` 로) | 어느 에이전트 도구에나 참인 것: 모르고 하면 되돌릴 수 없는 것 · 게이트 · 빌드와 커밋 절차 · 영역 규칙 색인 |
| `CLAUDE.md` | 세션 시작 — 항상 | Claude Code 에만 있고 훅·스킬이 대신 말해 주지 않는 것: Read 툴과 규칙이 붙는 방식. 훅이 강제하는 것·스킬 진입점은 훅 메시지와 스킬 description 이 전달하므로 적지 않는다 |
| `.claude/rules/*.md` | 매칭 파일을 **Read 할 때** | 영역별 "되돌리면 안 되는 결정"과 그 측정 근거 |
| `plugins/sheetview-kit/skills/*/SKILL.md` | 스킬을 부를 때 | 절차 — 사이클(`feature-cycle`) · 리뷰(`spreadsheet-review`, 코드 지도 포함) · 샌드박스(`sandbox-verify` / `sandbox-run`) |
| `plugins/sheetview-kit/README.md` | 작업 도구 자체를 고칠 때 직접 연다 | 훅·스킬·에이전트 목록 · 프로젝트에 남는 상태 |
| `README.md` | 플러그인을 쓰기 전 | 소개 · 지원 형식 · 사용법 · 설치 · 배포 |
| `TODO.md` | 다음 일을 고를 때 | 마켓플레이스 등록 남은 일 · 추가할 기능 |

- **매 세션 올라오는 두 문서에는 매 세션 필요한 것만 둔다.** 남기는 기준은 셋 중 하나다: (a) 모르고 하면 되돌릴 수 없다 (b) Read 없이 일어나는 경로(Bash·새 파일)에서 유일한 안내다 (c) 도구가 조용히 통과시켜 다른 신호가 없다 — `pre-commit-check` 는 성공하면 말이 없어서, baseline 갱신을 알려 줄 곳이 `AGENTS.md` 뿐이다. 훅 메시지·스킬·규칙이 필요한 순간에 이미 전달하는 것은 다시 적지 않는다.
- **`AGENTS.md` 와 `CLAUDE.md` 의 경계는 도구다.** 다른 에이전트 도구에도 참인 것은 `AGENTS.md`, 훅·스킬·Read 툴처럼 Claude Code 에만 있는 것은 `CLAUDE.md`.
- **`@import` 는 세션 시작에 펼쳐지므로 분할 수단이 아니다.** 즉시 로딩을 줄이려면 가져오기로 파일을 나누지 말고, 항목을 지연 로딩 자리(규칙·스킬·훅 메시지)로 옮긴다.
- **테스트·파일 개수를 문서에 하드코딩하지 않는다.** 이미 한 번 썩었다.

## 영역 규칙(`.claude/rules/`) 유지

- **규칙은 `paths:` 글로브에 맞는 파일을 Read 할 때만 붙는다.** 훅 마커와 같은 실패 모양이다 — 패키지를 옮기면 글로브가 아무것도 가리키지 않게 되고, 규칙은 조용히 영영 안 붙는다. `check.sh` lint 가 (a) `paths` 가 없는 규칙(매 세션 즉시 로드되므로 CLAUDE.md 와 갈라진다) (b) 중괄호 글로브(lint 가 검사할 수 없다 — 항목을 나눠 적는다) (c) 맞는 파일이 0개인 글로브를 막는다.
- **규칙은 소스 트리 밖에 둔다.** 하위 `CLAUDE.md` 를 `src/main/kotlin` 에 두면 lint 의 grep 이 본문에 적힌 `printStackTrace()` 를 위반으로 센다.
- 규칙을 추가·삭제하면 `AGENTS.md` 의 '영역 규칙' 색인 표도 고친다. 색인은 즉시 로드되고 본문은 지연 로드된다 — Read 없이 파일을 바꾸는 경로(Bash·MCP·새 파일)에서 색인이 유일한 안내다.
