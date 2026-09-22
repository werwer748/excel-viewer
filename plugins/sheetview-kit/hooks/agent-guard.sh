#!/bin/sh
# 에이전트 전용 PreToolUse(Bash) — 읽기 전용 에이전트가 실제로 읽기 전용이 되게 한다.
#
# sheet-reviewer 와 sandbox-runner 는 `tools:` 에서 Edit·Write 를 뺐고, 본문에도
# "코드를 고치지 않는다"고 적어 두었다. 그런데 **둘 다 Bash 를 갖고 있어서**
# `sed -i`, `git checkout -- .`, `cat > file` 이 전부 가능했다. 산문 규약이
# 도구 권한과 어긋나 있었던 셈이다. 여기서 그 간극을 메운다.
#
# 인자:
#   no-runide   runIde 도 거절한다 (리뷰어용. 샌드박스 러너는 띄워야 하므로 빼둔다)
#
# 막는 것은 stdout JSON 의 permissionDecision=deny 다. exit 2 와 달리 이유가
# 에이전트에게 구조화되어 전달된다.
set -u

payload=$(cat)
MODE="${1:-}"

reason=$(printf '%s' "$payload" | MODE="$MODE" python3 -c '
import json, os, re, sys
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

mode = os.environ.get("MODE", "")

# 파일을 바꾸는 명령들. 읽기 전용 에이전트가 쓸 이유가 없다.
BAD = [
    (r"\bsed\b[^|;&]*\s-i\b",            "sed -i 로 파일을 바꾸려 합니다"),
    (r"\bgit\s+checkout\s+--",           "git checkout -- 으로 작업 트리를 되돌리려 합니다"),
    (r"\bgit\s+(reset|restore|stash|apply|am)\b", "git 으로 작업 트리를 바꾸려 합니다"),
    (r"\bgit\s+commit\b",                "커밋은 이 에이전트의 일이 아닙니다"),
    (r"\./gradlew[^|;&]*\bclean\b",      "gradlew clean 은 빌드 산출물을 지웁니다"),
    (r">>?\s*[^\s;|&<>()]+",             "리다이렉션으로 파일을 쓰려 합니다"),
    (r"\btee\b",                         "tee 로 파일을 쓰려 합니다"),
    (r"\b(rm|mv|cp|touch|install|truncate)\b", "파일을 만들거나 지우거나 옮기려 합니다"),
]
if mode == "no-runide":
    BAD.append((r"\brunIde\b", "이 에이전트는 IDE 를 띄우지 않습니다 (리뷰는 코드를 읽는 일입니다)"))

for part in re.split(r"[;&|]+", cmd):
    for pat, why in BAD:
        if re.search(pat, part):
            # /tmp 나 /dev/null 로 나가는 것은 작업 트리를 건드리지 않으므로 통과.
            if re.search(r"(/tmp/|/dev/null|/private/tmp/)", part):
                continue
            print(why + ": " + part.strip()[:120])
            sys.exit(0)
sys.exit(1)
' 2>/dev/null) || exit 0

[ -n "$reason" ] || exit 0

printf '%s' "$reason" | python3 -c '
import json, sys
r = sys.stdin.read().strip()
print(json.dumps({"hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "deny",
    "permissionDecisionReason": (
        r + "\n이 에이전트는 읽기 전용입니다. 문제를 발견했으면 **보고**하세요 — 고치는 것은 호출한 쪽의 일입니다."
    ),
}}))
' 2>/dev/null || exit 0
exit 0
