#!/bin/sh
# PreToolUse(Bash · Read · Grep · 쓰기 툴 · MCP) — 되돌릴 수 없는 실수 넷을 막는다.
#
#   1. 키 리터럴     도구 입력에 실제 키 형태가 있다. 공개 저장소라 파일·커밋 메시지·PR 본문에
#                    실리면 끝이다. 패턴은 _common.sh 의 SHEETVIEW_SECRET_RE 한 곳에 있다.
#   2. .env 읽기     루트의 .env 에는 실제 토큰이 들어 있다. 읽으면 값이 대화에 남는다.
#   3. 재귀 삭제     rm -r 의 대상이 홈·루트·프로젝트 루트·.git·프로젝트 밖일 때만.
#   4. 강제 푸시     --force · -f · +refspec. main 은 GitHub 브랜치 보호가 막지만 작업 브랜치는 아니다.
#   +  비밀 출력     printenv · env · echo $…TOKEN 처럼 값을 대화에 찍는 명령.
#
# agent-guard.sh 와 달리 agent_type 으로 가리지 않는다 — 메인 세션과 모든 에이전트가 대상이다.
#
# **막는 범위는 좁게 잡았다.** `rm -rf build` 나 `rm -rf "$D"`(mktemp 정리)까지 막으면 훅을
# 끄게 된다(branch-guard.sh 와 같은 이유). 그래서 변수가 든 rm 대상은 "판단 불가"로 통과시키고,
# --force-with-lease 는 남의 커밋을 덮지 않으므로 통과시킨다.
#
# **거절 사유에 키 값을 싣지 않는다.** 사유는 대화에 그대로 실리므로, 되읊으면 이 훅이 유출 경로가 된다.
#
# 명령은 agent-guard.sh 와 같은 방식으로 셸 토큰으로 읽는다(정규식으로 문자열 전체를 훑으면
# `grep "rm -rf /"` 의 인자를 명령으로 본다). 파싱이 안 되면 통과시킨다.
#
# 알고 두는 한계 — 이 훅은 악의가 아니라 실수를 막는다:
#   - 인터프리터 안의 코드(python -c, node -e)는 보지 않는다.
#   - 루트에서의 `grep -r` · `cat *` 처럼 .env 를 이름 없이 훑는 명령은 모른다.
#   - xargs rm · find -delete · git clean 은 대상을 알 수 없거나 범위 밖이다.
set -u

payload=$(cat)

# 이 저장소가 아니면(마커 없음) 조용히 통과한다. rm 대상이 "프로젝트 안인가"를 판정하려면
# 루트가 있어야 하기도 하다.
. "$(dirname -- "$0")/_common.sh" 2>/dev/null || exit 0
PROJECT_DIR=$(sheetview_project_dir) || exit 0

reason=$(printf '%s' "$payload" |
  SV_PROJECT="$PROJECT_DIR" SV_SECRET_RE="$SHEETVIEW_SECRET_RE" python3 -c '
import json, os, re, shlex, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
if not isinstance(d, dict):
    sys.exit(1)
ti = d.get("tool_input")
if not isinstance(ti, dict):
    sys.exit(1)
tool = str(d.get("tool_name") or "")

def hit(msg):
    print(msg)
    sys.exit(0)

# ── 1. 키 리터럴 ─────────────────────────────────────────────────────────────
# old_string 은 뺀다 — 파일에 들어간 키를 **지우는** Edit 는 통과해야 한다.
SECRET = re.compile(os.environ["SV_SECRET_RE"])

def strings(v, key=""):
    if key == "old_string":
        return
    if isinstance(v, str):
        yield v
    elif isinstance(v, dict):
        for k, x in v.items():
            yield from strings(x, k)
    elif isinstance(v, list):
        for x in v:
            yield from strings(x)

if any(SECRET.search(s) for s in strings(ti)):
    hit("키로 보이는 문자열이 도구 입력에 들어 있어서 막았습니다 (값은 여기에 적지 않습니다).\n"
        "공개 저장소라 파일 · 커밋 메시지 · PR 본문에 실리면 되돌릴 수 없습니다. "
        "값 대신 환경변수 이름이나 자리표시(<토큰>)를 쓰세요.")

# ── 2. .env 경로 ─────────────────────────────────────────────────────────────
ENV_SAFE = {".env.example", ".env.sample", ".env.template"}
ENV_HELP = ("실제 토큰이 들어 있고, 읽으면 값이 대화에 남습니다. 형식은 .env.example 에 있고, "
            "CI 시크릿으로 올리는 것은 `gh secret set -f .env` 입니다. 있는지만 볼 때는 `ls` · `test -f` 를 쓰세요.")

def is_env(p):
    b = os.path.basename(p.rstrip("/"))
    if b in ENV_SAFE:
        return False
    return b == ".env" or (b.startswith(".env") and b[4] in ".*?[{")

def is_env_ref(a):
    return is_env(a) or ("=" in a and is_env(a.rsplit("=", 1)[1]))

# 쓰기 툴은 읽기가 아니다. 그 밖의 툴(Read · Grep · MCP)은 경로 필드를 본다 — MCP 서버마다
# 이름이 다르므로 tdd-guard.sh 와 같은 목록을 쓴다.
if tool not in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
    for key in ("file_path", "notebook_path", "path", "filePath", "pathInProject",
                "absolutePath", "file", "target", "targetFile", "glob"):
        v = ti.get(key)
        if isinstance(v, str) and is_env(v):
            hit(".env 는 읽지 않습니다: " + v[:120] + "\n" + ENV_HELP)

# ── 3. 명령 ──────────────────────────────────────────────────────────────────
cmd = ti.get("command")
if not isinstance(cmd, str) or not cmd.strip():
    sys.exit(1)

PROJECT = os.path.realpath(os.environ["SV_PROJECT"])
HOME = os.path.realpath(os.path.expanduser("~"))
TEMP = ("/tmp/", "/private/tmp/", "/var/folders/", "/private/var/folders/")
PUNCT = set("();<>|&")
WRAPPERS = {"env", "command", "nohup", "time", "sudo", "exec", "do", "then", "else",
            "elif", "if", "while", "until", "!", "{"}
ASSIGN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
SENSITIVE = re.compile(r"TOKEN|SECRET|PASSWORD|PASSWD|API_?KEY|PRIVATE_KEY|CREDENTIAL", re.I)
# .env 를 인자로 받아도 내용을 읽거나 내보내지 않는 명령.
ENV_OK = {"ls", "test", "[", "[[", "stat", "chmod", "touch", "rm", "echo", "printf"}
GIT_ENV_OK = {"check-ignore", "ls-files", "status", "rm"}

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
        if punct(t) and "<" not in t and ">" not in t:
            if cur:
                yield cur
            cur = []
        else:
            cur.append(t)
    if cur:
        yield cur

def under(p, base):
    return p == base or p.startswith(base.rstrip("/") + "/")

def resolve(tok, cwd):
    # ~ 와 $HOME 만 전개한다. 다른 변수 · 명령 치환이 남아 있으면 어디인지 알 수 없다.
    tok = re.sub(r"^(\$HOME|\$\{HOME\})(?=/|$)", "~", tok)
    if "$" in tok or "`" in tok:
        return None
    if tok.startswith("~"):
        if tok != "~" and not tok.startswith("~/"):
            return None
        tok = os.path.expanduser(tok)
    if not os.path.isabs(tok):
        if cwd is None:
            return None
        tok = os.path.join(cwd, tok)
    return os.path.realpath(tok)

def danger(tok, cwd):
    # 글로브가 있으면 그 앞까지의 디렉터리가 대상이다: `*` 는 cwd, `build/*` 는 build.
    segs, base, glob = tok.split("/"), tok, False
    for i, seg in enumerate(segs):
        if any(c in seg for c in "*?["):
            base = "/".join(segs[:i]) or ("/" if tok.startswith("/") else ".")
            glob = True
            break
    p = resolve(base, cwd)
    if p is None:
        return None
    if under(PROJECT, p) or under(HOME, p):
        return "홈 · 루트 · 프로젝트 루트(또는 그 상위)를 통째로 지웁니다"
    if under(p, os.path.join(PROJECT, ".git")):
        return ".git 을 지우면 커밋하지 않은 이력까지 사라집니다"
    if under(p, PROJECT):
        return None
    if (p + "/").startswith(TEMP) if glob else p.startswith(TEMP):
        return None
    return "프로젝트 밖의 경로입니다"

def problems(tokens, cwd, depth=0):
    for part in commands(tokens):
        args, reads, i = [], [], 0
        while i < len(part):
            t = part[i]
            if punct(t):
                # `<` 하나만 파일 읽기다. `<<` · `<<<` 는 heredoc · herestring 이고 `>` 는 쓰기다.
                if t == "<" and i + 1 < len(part):
                    reads.append(part[i + 1])
                i += 2
                continue
            args.append(t)
            i += 1
        text = " ".join(part)[:120]
        if any(is_env_ref(r) for r in reads):
            yield ".env 를 읽는 명령입니다: " + text + "\n" + ENV_HELP

        bare_env = False
        while args and (args[0] in WRAPPERS or ASSIGN.match(args[0])):
            bare_env = bare_env or args[0] == "env"
            args = args[1:]
        if not args:
            if bare_env:
                yield ("환경변수 전부를 대화에 찍는 명령입니다: " + text + "\n"
                       "토큰이 섞여 나옵니다. 필요한 변수 하나만 `printenv <이름>` 으로 보세요.")
            continue
        head, rest = os.path.basename(args[0]), args[1:]
        ops = [a for a in rest if not a.startswith("-")]
        sub = ""

        if head == "cd":
            # 뒤따르는 상대경로의 기준이 바뀐다. 알 수 없는 곳이면 이후 상대경로는 판단 불가다.
            cwd = HOME if not rest else (resolve(ops[0], cwd) if len(ops) == 1 else None)
        elif head in ("sh", "bash", "zsh") and "-c" in rest and depth < 3:
            j = rest.index("-c")
            if j + 1 < len(rest):
                try:
                    yield from problems(lex(rest[j + 1]), cwd, depth + 1)
                except ValueError:
                    pass
        elif head == "rm":
            if any(a == "--recursive" or re.match(r"^-[A-Za-z]*[rR]", a) for a in rest):
                for a in ops:
                    why = danger(a, cwd)
                    if why:
                        yield ("되돌릴 수 없는 재귀 삭제입니다 — " + why + ": " + text + "\n"
                               "프로젝트 안의 하위 경로와 임시 디렉터리만 지울 수 있습니다. "
                               "정말 필요하면 사람에게 직접 실행해 달라고 요청하세요.")
        elif head == "git":
            j = 0
            while j < len(rest) and rest[j].startswith("-"):
                j += 2 if rest[j] in ("-C", "-c") else 1
            sub = rest[j] if j < len(rest) else ""
            if sub == "push" and any(a == "--force" or re.match(r"^-[A-Za-z]*f", a) or a.startswith("+")
                                     for a in rest[j + 1:]):
                yield ("강제 푸시는 원격의 커밋을 덮어씁니다: " + text + "\n"
                       "리베이스 뒤 브랜치를 갱신하려면 `git push --force-with-lease` 를 쓰세요.")
        elif head == "printenv":
            if not ops or any(SENSITIVE.search(a) for a in ops):
                yield ("환경변수의 비밀 값을 대화에 찍는 명령입니다: " + text + "\n"
                       "설정됐는지만 볼 때는 `[ -n \"$이름\" ]` 을 쓰세요.")
        elif head in ("echo", "printf"):
            if any(SENSITIVE.search(n) for a in rest
                   for n in re.findall(r"\$\{?([A-Za-z_][A-Za-z0-9_]*)", a)):
                yield ("환경변수의 비밀 값을 대화에 찍는 명령입니다: " + text + "\n"
                       "값이 필요한 명령에는 변수를 그대로 넘기고, 설정됐는지만 볼 때는 `[ -n \"$이름\" ]` 을 쓰세요.")

        if any(is_env_ref(a) for a in rest):
            if head in ENV_OK:
                pass
            elif head == "gh" and rest[:2] == ["secret", "set"]:
                pass
            elif head == "git" and sub in GIT_ENV_OK:
                pass
            elif head in ("cp", "mv") and not any(is_env_ref(a) for a in ops[:-1]):
                pass    # .env 가 대상(마지막 인자)일 뿐이다: cp .env.example .env
            else:
                yield ".env 를 읽거나 내보내는 명령입니다: " + text + "\n" + ENV_HELP

cwd = d.get("cwd")
if not isinstance(cwd, str) or not cwd:
    cwd = os.getcwd()
try:
    tokens = lex(cmd)
except ValueError:
    sys.exit(1)
for p in problems(tokens, os.path.realpath(cwd)):
    hit(p)
sys.exit(1)
' 2>/dev/null) || exit 0

[ -n "$reason" ] || exit 0

printf '%s' "$reason" | python3 -c '
import json, sys
print(json.dumps({"hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "deny",
    "permissionDecisionReason": sys.stdin.read().strip(),
}}, ensure_ascii=False))
' 2>/dev/null || exit 0
exit 0
