#!/bin/sh
# SubagentStop(sandbox-runner) — 샌드박스 검증 결과를 사이클 상태에 남긴다.
#
# sandbox-verify 스킬의 보고는 마지막에 판정 한 줄을 둔다. 그 줄만 본다 —
# 본문에 예시로 등장하는 🔴 를 판정으로 오인하지 않기 위해서다.
#
# 매처가 "sandbox-runner" 였던 동안 이 훅은 한 번도 불리지 않았다(실제 타입은
# "sheetview-kit:sandbox-runner"). cycle-review.sh 의 머리 주석 참고.
set -u

payload=$(cat)
. "$(dirname -- "$0")/_common.sh" 2>/dev/null || exit 0
PROJECT_DIR=$(sheetview_project_dir) || exit 0
sheetview_cycle_active "$PROJECT_DIR" || exit 0

verdict=$(printf '%s' "$payload" | python3 -c '
import json, re, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
msg = d.get("last_assistant_message")
if not isinstance(msg, str) or not msg.strip():
    print("unknown"); sys.exit(0)

# 판정 줄을 뒤에서부터 찾는다. 보고 본문이 길어도 결론은 끝에 있다.
tail = [l.strip() for l in msg.strip().splitlines() if l.strip()][-8:]
for line in reversed(tail):
    if "샌드박스 확인 통과" in line:
        print("clean"); sys.exit(0)
    m = re.search(r"\U0001F534\s*(\d+)\s*건", line)
    if m:
        print("red:%s" % m.group(1)); sys.exit(0)
print("unknown")
' 2>/dev/null) || verdict="unknown"

[ -n "$verdict" ] || verdict="unknown"
sheetview_cycle_set "$PROJECT_DIR" verify "$verdict"

case "$verdict" in
  clean)
    sheetview_cycle_progress "$PROJECT_DIR"
    printf '사이클: 샌드박스 검증 통과.\n' >&2
    ;;
  red:*)
    # 검증 🔴 도 "고치고 다시" 한 바퀴다. 라운드를 올리지 않으면 검증 실패의 반복에는 상한이 없다.
    sheetview_cycle_progress "$PROJECT_DIR"
    round=$(sheetview_cycle_get "$PROJECT_DIR" round 2>/dev/null || echo 0)
    case "$round" in ''|*[!0-9]*) round=0 ;; esac
    sheetview_cycle_set "$PROJECT_DIR" round "$((round + 1))"
    printf '사이클: 샌드박스에서 %s — 고친 뒤 리뷰부터 다시 돕니다 (라운드 %s).\n' \
      "$verdict" "$((round + 1))" >&2
    ;;
  *)
    printf '사이클: 샌드박스 검증 결과를 읽지 못했습니다. 사람이 확인해야 합니다.\n' >&2
    ;;
esac
exit 0