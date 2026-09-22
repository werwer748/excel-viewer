#!/bin/sh
# PreToolUse(Bash) 훅 — Claude가 git commit 을 실행하려 할 때 scripts/check.sh 를 먼저 돌린다.
# 통과하면 조용히 빠지고(exit 0), 실패하면 exit 2 로 커밋을 막고 stderr 를 Claude에게 돌려준다.
#
# 이건 git 훅이 아니라 Claude Code 훅이다. 사람이 터미널에서 직접 치는 커밋은 막지 않는다.
# 그쪽까지 막으려면 .git/hooks/pre-commit 에서 같은 스크립트를 부르면 된다:
#     #!/bin/sh
#     exec ./scripts/check.sh
set -u

payload=$(cat)

# payload 전체를 substring 으로 훑던 때는 두 방향으로 다 틀렸다:
#   오탐 — tool_input.description 에 "git commit" 이라는 글자만 있어도 풀 빌드가 돌았다.
#   미탐 — `git  commit`(공백 둘)이면 통과했고, merge·revert·cherry-pick·rebase·am 처럼
#          **커밋을 만드는 다른 명령**은 아예 보이지도 않았다.
# 그래서 tool_input.command 만 파싱하고, 커밋을 만드는 명령 집합을 정규식으로 본다.
is_commit=$(printf '%s' "$payload" | python3 -c '
import json, re, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
ti = d.get("tool_input") or {}
if not isinstance(ti, dict):
    sys.exit(1)
cmd = ti.get("command")
if not isinstance(cmd, str) or not cmd.strip():
    sys.exit(1)
# git 의 전역 옵션(-c k=v, -C dir)을 건너뛰고 서브커맨드를 본다.
pat = r"\bgit\s+(?:-[cC]\s+\S+\s+)*(commit|merge|revert|cherry-pick|rebase|am)\b"
print("yes" if re.search(pat, cmd) else "")
' 2>/dev/null) || exit 0
[ "$is_commit" = "yes" ] || exit 0

# 이 저장소가 아니면(마커 없음) 막을 근거가 없다 — 플러그인은 다른 프로젝트에도 설치된다.
. "$(dirname -- "$0")/_common.sh" 2>/dev/null || exit 0
PROJECT_DIR=$(sheetview_project_dir) || exit 0
CHECK="$PROJECT_DIR/scripts/check.sh"

# 검사할 것이 없는 상태라면 커밋을 막을 근거도 없다.
[ -x "$CHECK" ] || exit 0

# runIde 가 프로젝트 락을 잡고 있으면 check.sh 가 통째로 대기한다(타임아웃까지).
# tdd-red.sh 는 이미 같은 방어를 하고 있었는데 이쪽에는 없었다.
if pgrep -f 'runIde' >/dev/null 2>&1; then
  {
    echo "샌드박스(runIde)가 떠 있어 커밋 전 검사를 돌릴 수 없습니다 — Gradle 프로젝트 락 때문입니다."
    echo "IDE 를 닫은 뒤 다시 커밋하세요:  sh plugins/sheetview-kit/scripts/sandbox-up.sh down"
  } >&2
  exit 2
fi

if output=$(cd "$PROJECT_DIR" && "$CHECK" 2>&1); then
  exit 0
fi

{
  echo "커밋 전 검사가 실패해서 커밋을 막았습니다."
  echo "아래 문제를 고친 뒤 다시 커밋하세요. 직접 돌려보려면: ./scripts/check.sh"
  echo
  echo "$output"
} >&2
exit 2
