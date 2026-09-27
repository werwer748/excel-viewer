#!/bin/sh
# PreToolUse(Bash) — 읽기 전용 에이전트가 실제로 읽기 전용이 되게 한다.
#
# sheet-reviewer 와 sandbox-runner 는 `tools:` 에서 Edit·Write 를 뺐고, 본문에도
# "코드를 고치지 않는다"고 적어 두었다. 그런데 **둘 다 Bash 를 갖고 있어서**
# `sed -i`, `git checkout -- .`, `cat > file` 이 전부 가능했다. 산문 규약이
# 도구 권한과 어긋나 있었던 셈이다. 여기서 그 간극을 메운다.
#
# **배선은 에이전트 frontmatter 가 아니라 hooks.json 이다.** 처음에는 각 에이전트의
# frontmatter `hooks:` 에 걸었는데, 플러그인 에이전트는 그 필드를 무시한다(헤드리스 세션에
# 탐침 플러그인으로 실측했다). 그 동안 이 훅은 한 번도 돌지 않았다. 세션 수준 PreToolUse 는
# 서브에이전트의 Bash 에도 불리고 입력에 agent_type 이 실려 오므로, 그걸로 대상을 가린다.
# 메인 세션(agent_type 없음)과 다른 에이전트는 이 훅의 대상이 아니다.
#
#   *:sheet-reviewer   쓰기 전부 + runIde 거절 (리뷰는 코드를 읽는 일이다)
#   *:sandbox-runner   쓰기 전부 거절. runIde 는 이 에이전트의 일이라 허용
#
# 명령은 셸 토큰으로 읽는다. 정규식으로 명령 문자열 전체를 훑던 동안 두 방향으로 틀렸다:
#   오탐 — `grep -rn "install" README.md` 의 인자, `grep "a > b"` 의 따옴표 속 > 를 명령으로 봤다.
#   미탐 — 조각 어딘가에 /tmp/ 가 **있기만 하면** 통과시켜서 `mv src/x.kt /tmp/`,
#          `rm -rf src /tmp/x`, `echo x > src/a.kt # /tmp/` 가 전부 빠져나갔다.
# 이제 /tmp 예외는 **쓰기 대상 경로**에만 적용한다.
#
# 파싱이 안 되면(따옴표가 안 닫혔다) 통과시킨다 — 다른 훅들과 같은 원칙이다. 이 훅은 악의가
# 아니라 실수를 막는다. 인터프리터 안의 코드(python -c 등)도 같은 이유로 보지 않는다.
#
# 막는 것은 stdout JSON 의 permissionDecision=deny 다. exit 2 와 달리 이유가
# 에이전트에게 구조화되어 전달된다.
set -u

payload=$(cat)

reason=$(printf '%s' "$payload" | python3 -c '
import json, os, re, shlex, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
if not isinstance(d, dict):
    sys.exit(1)

agent = str(d.get("agent_type") or "")
if re.search(r"(^|:)sheet-reviewer$", agent):
    no_runide = True
elif re.search(r"(^|:)sandbox-runner$", agent):
    no_runide = False
else:
    sys.exit(1)

ti = d.get("tool_input") or {}
cmd = ti.get("command") if isinstance(ti, dict) else None
if not isinstance(cmd, str) or not cmd.strip():
    sys.exit(1)

PUNCT = set("();<>|&")
WRAPPERS = {"env", "command", "nohup", "time", "sudo", "exec", "do", "then", "else",
            "elif", "if", "while", "until", "!", "{"}
DELETES = {"rm", "rmdir", "unlink", "truncate", "shred", "touch", "tee"}
COPIES = {"cp", "install", "ln"}
FILE_CMDS = DELETES | COPIES | {"mv", "dd"}
GIT_WRITES = {"checkout", "switch", "reset", "restore", "stash", "apply", "am", "commit",
              "clean", "merge", "rebase", "cherry-pick", "revert", "pull", "rm", "mv"}

def temp(p):
    return p in ("/dev/null", "/dev/stdout", "/dev/stderr") or \
        p.startswith(("/tmp/", "/private/tmp/", "/var/folders/", "/private/var/folders/"))

def lex(s):
    # 줄바꿈은 명령 구분자다. shlex 는 공백으로 취급하므로 먼저 바꿔 둔다.
    lx = shlex.shlex(s.replace("\n", ";"), posix=True, punctuation_chars=True)
    lx.whitespace_split = True
    return list(lx)

def punct(t):
    return bool(t) and all(c in PUNCT for c in t)

def commands(tokens):
    cur = []
    for t in tokens:
        # 리다이렉션이 아닌 구두점 덩어리(; && || | & ( ) 등)는 명령의 경계다.
        # $( ) 도 여기서 갈라져서 안쪽 명령이 따로 검사된다.
        if punct(t) and "<" not in t and ">" not in t:
            if cur:
                yield cur
            cur = []
        else:
            cur.append(t)
    if cur:
        yield cur

def problems(tokens, depth=0):
    for part in commands(tokens):
        args, targets, i = [], [], 0
        while i < len(part):
            t = part[i]
            if punct(t) and ">" in t:
                # >&2 · 2>&1 은 파일이 아니라 fd 복제다.
                if not t.endswith("&") and i + 1 < len(part):
                    targets.append(part[i + 1])
                i += 2
                continue
            if punct(t) and "<" in t:
                i += 2          # 입력 리다이렉션·heredoc 은 읽기다
                continue
            args.append(t)
            i += 1
        text = " ".join(part)[:120]
        if any(not temp(p) for p in targets):
            yield "리다이렉션으로 파일을 쓰려 합니다: " + text
        while args and (args[0] in WRAPPERS or re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", args[0])):
            args = args[1:]
        if not args:
            continue
        head, rest = os.path.basename(args[0]), args[1:]
        ops = [a for a in rest if not a.startswith("-")]

        if head in ("sh", "bash", "zsh") and "-c" in rest and depth < 3:
            j = rest.index("-c")
            if j + 1 < len(rest):
                try:
                    yield from problems(lex(rest[j + 1]), depth + 1)
                except ValueError:
                    pass
        elif head == "sed" and any(a == "--in-place" or a.startswith("--in-place=")
                                   or re.match(r"^-[A-Za-z]*i", a) for a in rest):
            yield "sed -i 로 파일을 바꾸려 합니다: " + text
        elif head == "perl" and any(re.match(r"^-(?![IMmx])[A-Za-z]*i", a) for a in rest):
            yield "perl -i 로 파일을 바꾸려 합니다: " + text
        elif head in DELETES or head == "mv":
            # mv 는 원본도 사라지므로 양쪽 다 임시 경로여야 한다.
            if any(not temp(a) for a in ops):
                yield "파일을 만들거나 지우거나 옮기려 합니다: " + text
        elif head in COPIES:
            if ops and not temp(ops[-1]):
                yield "파일을 만들려 합니다: " + text
        elif head == "dd":
            if any(a.startswith("of=") and not temp(a[3:]) for a in rest):
                yield "dd 로 파일을 쓰려 합니다: " + text
        elif head == "xargs":
            # 인자가 stdin 에서 오므로 대상을 확인할 수 없다.
            if any(os.path.basename(a) in FILE_CMDS for a in rest):
                yield "xargs 로 파일을 바꾸려 합니다: " + text
        elif head == "find":
            if "-delete" in rest or any(
                    a in ("-exec", "-execdir", "-ok", "-okdir") and j + 1 < len(rest)
                    and os.path.basename(rest[j + 1]) in FILE_CMDS
                    for j, a in enumerate(rest)):
                yield "find 로 파일을 지우거나 바꾸려 합니다: " + text
        elif head == "git":
            j = 0
            while j < len(rest) and rest[j].startswith("-"):
                j += 2 if rest[j] in ("-C", "-c") else 1
            if j < len(rest) and rest[j] in GIT_WRITES:
                yield "git " + rest[j] + " 은 작업 트리를 바꿉니다: " + text
        elif head in ("gradlew", "gradle"):
            if any(a == "clean" or a.endswith(":clean") for a in rest):
                yield "gradlew clean 은 빌드 산출물을 지웁니다: " + text
            elif no_runide and any("runIde" in a for a in rest):
                yield "이 에이전트는 IDE 를 띄우지 않습니다 (리뷰는 코드를 읽는 일입니다): " + text

try:
    tokens = lex(cmd)
except ValueError:
    sys.exit(1)
for p in problems(tokens):
    print(p)
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
}}, ensure_ascii=False))
' 2>/dev/null || exit 0
exit 0