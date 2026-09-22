#!/bin/sh
# SubagentStop(sandbox-runner) — 샌드박스 검증 결과를 사이클 상태에 남긴다.
#
# sandbox-verify 스킬의 보고는 마지막에 판정 한 줄을 둔다. 그 줄만 본다 —
# 본문에 예시로 등장하는 🔴 를 판정으로 오인하지 않기 위해서다.
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
  clean) printf '사이클: 샌드박스 검증 통과.\n' >&2 ;;
  red:*) printf '사이클: 샌드박스에서 %s — 고친 뒤 리뷰부터 다시 돕니다.\n' "$verdict" >&2 ;;
  *)     printf '사이클: 샌드박스 검증 결과를 읽지 못했습니다. 사람이 확인해야 합니다.\n' >&2 ;;
esac
exit 0
