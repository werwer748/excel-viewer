#!/bin/sh
# SubagentStop(sheet-reviewer) — 리뷰 결과를 **기계가 읽어** 사이클 상태에 남긴다.
#
# 여기가 사이클의 핵심이다. 지금까지 "리뷰에서 🔴 가 나왔으면 고치고 다시 리뷰"는
# 순전히 모델의 선의에 달려 있었다. SubagentStop 은 서브에이전트의 마지막 답변
# (last_assistant_message)을 주므로, 그 판정을 상태로 굳힐 수 있다.
#
# 이 훅은 아무것도 막지 않는다. 기록만 한다. 막는 것은 cycle-stop.sh 의 일이다.
set -u

payload=$(cat)
. "$(dirname -- "$0")/_common.sh" 2>/dev/null || exit 0
PROJECT_DIR=$(sheetview_project_dir) || exit 0

# 사이클이 없으면 아무 일도 하지 않는다. 평소 세션에서 리뷰어를 부르는 것은 자유다.
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

# spreadsheet-review 의 보고 형식: "### 🔴 고치고 커밋해야 함" 아래 번호 목록.
m = re.search(r"###[^\n]*\U0001F534[^\n]*\n(.*?)(?=\n###|\Z)", msg, re.S)
if m is None:
    # 형식을 못 찾았다. 🔴 가 아예 없으면 깨끗한 것으로, 있으면 판단 불가로 둔다.
    # 판단 불가를 clean 으로 밀면 사이클이 거짓말을 한다.
    print("clean" if "\U0001F534" not in msg else "unknown"); sys.exit(0)
items = re.findall(r"^\s*\d+\.\s", m.group(1), re.M)
print("red:%d" % len(items) if items else "clean")
' 2>/dev/null) || verdict="unknown"

[ -n "$verdict" ] || verdict="unknown"
sheetview_cycle_set "$PROJECT_DIR" review "$verdict"

case "$verdict" in
  red:*)
    round=$(sheetview_cycle_get "$PROJECT_DIR" round 2>/dev/null || echo 0)
    case "$round" in ''|*[!0-9]*) round=0 ;; esac
    sheetview_cycle_set "$PROJECT_DIR" round "$((round + 1))"
    printf '사이클: 리뷰 %s — 고친 뒤 다시 리뷰해야 합니다 (라운드 %s).\n' \
      "$verdict" "$((round + 1))" >&2
    ;;
  clean)
    printf '사이클: 리뷰에 🔴 없음. 다음은 샌드박스 검증입니다.\n' >&2
    ;;
  *)
    printf '사이클: 리뷰 결과를 읽지 못했습니다(보고 형식 불일치). 사람이 확인해야 합니다.\n' >&2
    ;;
esac
exit 0
