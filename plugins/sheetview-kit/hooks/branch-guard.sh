#!/bin/sh
# PreToolUse — main/master 에서 코드를 직접 고치려 하면 사람에게 한 번 묻는다.
#
# 차단하지 않는다. 급할 때 main 에서 한 줄 고치는 것까지 막으면 훅을 끄게 된다.
# `ask` 로 올려서 "브랜치를 먼저 만들까요?" 를 그 순간에 묻는 것으로 충분하다.
#
# UserPromptSubmit 으로 "작업 지시 같으면 브랜치를 만들라"는 컨텍스트를 주입하는 방법도
# 있지만, "작업 지시인가"를 판정해야 해서 오탐이 생긴다. **실제로 쓰려는 순간에 묻는 쪽**이
# 정확하다 — 읽기만 하는 세션에서는 아예 뜨지 않는다.
#
# 같은 브랜치에서 두 번 묻지 않는다. 브랜치를 만들면 이름이 달라져 표시가 자연히 무효가 된다.
set -u

payload=$(cat)
. "$(dirname -- "$0")/_common.sh" 2>/dev/null || exit 0
PROJECT_DIR=$(sheetview_project_dir) || exit 0

branch=$(git -C "$PROJECT_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null) || exit 0
case "$branch" in
  main|master) ;;
  *) exit 0 ;;
esac

ACK="$PROJECT_DIR/.claude/.branch-ack"
if [ -f "$ACK" ] && [ "$(cat "$ACK" 2>/dev/null)" = "$branch" ]; then
  exit 0
fi

# 소스를 건드리는 경우만 묻는다. 문서·설정 수정까지 묻으면 성가시기만 하다.
hit=$(printf '%s' "$payload" | python3 -c '
import json, re, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
ti = d.get("tool_input") or {}
if not isinstance(ti, dict):
    sys.exit(1)
cand = []
for k in ("file_path", "path", "filePath", "pathInProject", "absolutePath", "notebook_path"):
    v = ti.get(k)
    if isinstance(v, str) and v:
        cand.append(v)
cmd = ti.get("command")
if isinstance(cmd, str) and cmd:
    for m in re.finditer(r">>?\s*([^\s;|&<>()]+)", cmd):
        cand.append(m.group(1))
for c in cand:
    if re.search(r"(^|/)src/(main|test)/", c):
        print(c); break
' 2>/dev/null) || exit 0

[ -n "$hit" ] || exit 0

mkdir -p "$PROJECT_DIR/.claude" 2>/dev/null
printf '%s' "$branch" > "$ACK" 2>/dev/null

printf '%s' "$branch|$hit" | python3 -c '
import json, sys
branch, hit = sys.stdin.read().split("|", 1)
print(json.dumps({"hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "ask",
    "permissionDecisionReason": (
        "지금 " + branch + " 브랜치에서 소스를 고치려 합니다 (" + hit + ").\n"
        "작업 브랜치를 먼저 만드는 편이 좋습니다: git switch -c <이름>\n"
        "(이 물음은 같은 브랜치에서 한 번만 뜹니다.)"
    ),
}}))
' 2>/dev/null || exit 0
exit 0
