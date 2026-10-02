#!/bin/sh
# TDD 훅 자체의 테스트. 훅이 틀리면 전체 작업이 막히거나, 더 나쁘게는 조용히 통과한다.
#
# 두 가지가 특히 중요하다:
#  - **훅의 실패가 작업을 막는 실패로 번져선 안 된다.** payload 가 깨졌거나 경로를 모르겠으면 통과.
#  - **Bash 우회를 막아야 한다.** `cat > src/main/.../X.kt <<EOF` 는 Write 툴을 타지 않는다.
#
# 경로는 저장소에 실제로 존재하지 않는 합성 이름(__NeverExists*)을 쓴다. 실제 파일 이름을 쓰면
# 그 파일을 만든 순간 이 테스트가 깨진다 (한 번 겪었다).
set -u

# 훅은 플러그인 안에 있고 검사 대상 프로젝트는 밖에 있다. 루트는 훅이 실제로 쓰는 것과
# 같은 방법(_common.sh 의 마커 탐색)으로 찾는다 — 여기서 다른 방법을 쓰면 그 차이가 버그가 된다.
PLUGIN_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd) || exit 1
. "$PLUGIN_DIR/hooks/_common.sh" || exit 1
PROJECT_DIR=$(sheetview_project_dir) || {
  echo "excel-viewer 저장소 안에서 돌려야 합니다 (gradlew 와 $SHEETVIEW_SRC_ROOT 를 찾지 못했습니다)." >&2
  exit 1
}
cd "$PROJECT_DIR" || exit 1
export CLAUDE_PROJECT_DIR="$PROJECT_DIR"

GUARD="$PLUGIN_DIR/hooks/tdd-guard.sh"
REDH="$PLUGIN_DIR/hooks/tdd-red.sh"
NEW_TESTS=".claude/.tdd-new"
HARNESS_ACK=".claude/.harness-ack"
SRC="$PROJECT_DIR/src/main/kotlin/dev/hugo/sheetview"
TST="$PROJECT_DIR/src/test/kotlin/dev/hugo/sheetview"

pass=0; fail=0
TMP_TEST=""
PROBE_PIDS=""
cleanup() {
  [ -n "$TMP_TEST" ] && rm -f "$TMP_TEST"
  rm -f "$NEW_TESTS.tmp"
  # 중간에 끊겨도 원본 .tdd-new 를 돌려놓는다. 안 그러면 .tdd-new.saved 가
  # 워킹트리에 유령으로 남는다 (gitignore 되지 않는 이름이다).
  [ -f "$NEW_TESTS.saved" ] && mv -f "$NEW_TESTS.saved" "$NEW_TESTS"
  [ -f "$HARNESS_ACK.saved" ] && mv -f "$HARNESS_ACK.saved" "$HARNESS_ACK"
  # 프로세스 판정 테스트가 띄운 탐침이 남지 않게 한다.
  for _p in $PROBE_PIDS; do kill "$_p" 2>/dev/null; done
  return 0
}
trap cleanup EXIT INT TERM

# .tdd-new 를 건드리므로 원본을 잠시 치워 둔다.
[ -f "$NEW_TESTS" ] && mv "$NEW_TESTS" "$NEW_TESTS.saved"
# 이 브랜치의 승인 표시가 남아 있으면 아래 "게이트 자신 -> ask" 검사가 조용해진다. 같이 치워 둔다.
[ -f "$HARNESS_ACK" ] && mv "$HARNESS_ACK" "$HARNESS_ACK.saved"

ok()  { pass=$((pass + 1)); printf '  ok    %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf '  FAIL  %s\n' "$1"; }

run() {
  script=$1; want=$2; desc=$3; body=$4
  out=$(printf '%s' "$body" | sh "$script" 2>&1)
  got=$?
  if [ "$got" = "$want" ]; then
    pass=$((pass + 1)); printf '  ok    %s\n' "$desc"
  else
    fail=$((fail + 1)); printf '  FAIL  %s  (기대 exit %s, 실제 %s)\n' "$desc" "$want" "$got"
    printf '%s\n' "$out" | sed 's/^/          /'
  fi
}

mcppay() { printf '{"tool_name":"%s","tool_input":{"%s":"%s"}}' "$1" "$2" "$3"; }
payload() { printf '{"tool_name":"%s","tool_input":{"file_path":"%s"}}' "$1" "$2"; }
bashpay() {
  printf '{"tool_name":"Bash","tool_input":{"command":%s}}' \
    "$(printf '%s' "$1" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')"
}

# 합성 프로젝트 하나. 마커(gradlew + 소스 루트)만 갖춘다. 물리 경로로 잡는다 —
# macOS 의 임시 디렉터리는 심볼릭 링크 아래에 있고, lsof 는 물리 경로로 답한다.
synth_project() {
  _d=$(mktemp -d) && _d=$(CDPATH= cd -P -- "$_d" && pwd -P) || return 1
  touch "$_d/gradlew"
  mkdir -p "$_d/src/main/kotlin/dev/hugo/sheetview" "$_d/src/test/kotlin/dev/hugo/sheetview" "$_d/.claude"
  printf '%s\n' "$_d"
}

# argv 에 표지를 단 진짜 프로세스를 cwd 를 정해 띄운다. pgrep -f 는 argv 를 보고,
# 주인 판정은 cwd 와 명령줄을 본다. PID 를 돌려준다.
spawn_probe() {   # <cwd> <argv 에 넣을 문자열>
  (cd "$1" && exec python3 -c 'import time; time.sleep(60)' "$2") >/dev/null 2>&1 &
  _pid=$!
  PROBE_PIDS="$PROBE_PIDS $_pid"
  # argv 가 바뀌어 pgrep 에 보일 때까지 잠깐 기다린다.
  _i=0
  while [ $_i -lt 20 ] && ! pgrep -f -- "$2" 2>/dev/null | grep -qx "$_pid"; do
    sleep 0.1; _i=$((_i + 1))
  done
  printf '%s\n' "$_pid"
}
kill_probe() { kill "$1" 2>/dev/null; wait "$1" 2>/dev/null; }

# ─────────────────────────────────────────────────────────────────────────────
echo "hooks.json 배선 — 매처가 실제 에이전트와 맞는가"

# 아래의 스크립트 테스트는 전부 합성 payload 로 스크립트를 **직접** 부른다. 그래서 hooks.json 의
# 매처가 틀려 훅이 한 번도 불리지 않아도 전부 통과했다 — SubagentStop 매처가 "sheet-reviewer"
# 였던 동안 사이클 훅은 실사용에서 한 번도 돌지 않았다. 플러그인 에이전트의 실제 타입은
# "sheetview-kit:sheet-reviewer" 다. 여기서는 배선 자체를 본다.
#
# 매처 규칙은 문서와 실측(헤드리스 세션에 탐침 플러그인)으로 확인한 것이다: 영숫자·_·-·공백·|
# 만 있으면 정확 일치(| 로 여럿), 그 밖의 문자가 있으면 앵커 없는 정규식, 비어 있으면 전부.
# 같은 실측에서 **플러그인 에이전트의 frontmatter hooks: 는 불리지 않았다.**
wiring=$(python3 - "$PLUGIN_DIR" <<'PY' 2>&1
import json, os, re, sys
root = sys.argv[1]
out = []
def check(ok, desc):
    out.append(("  ok    " if ok else "  FAIL  ") + desc)

def matches(matcher, value):
    if not matcher or matcher == "*":
        return True
    if re.fullmatch(r"[A-Za-z0-9_\- |]+", matcher):
        return value in [s.strip() for s in matcher.split("|")]
    return re.search(matcher, value) is not None

def scripts_of(entry):
    found = []
    for h in entry.get("hooks", []):
        m = re.search(r"hooks/([\w.-]+\.sh)", h.get("command", ""))
        if m:
            found.append(m.group(1))
    return found

plugin = json.load(open(os.path.join(root, ".claude-plugin", "plugin.json")))["name"]
hooks = json.load(open(os.path.join(root, "hooks", "hooks.json")))["hooks"]

agents = {}
for f in sorted(os.listdir(os.path.join(root, "agents"))):
    if f.endswith(".md"):
        text = open(os.path.join(root, "agents", f), encoding="utf-8").read()
        fm = text.split("---")[1] if text.startswith("---") else ""
        m = re.search(r"^name:\s*(\S+)", fm, re.M)
        if m:
            agents[m.group(1)] = fm

# 1. 사이클 훅의 SubagentStop 매처가 실제 에이전트 타입(<플러그인>:<이름>)에 맞는가
for script, agent in (("cycle-review.sh", "sheet-reviewer"), ("cycle-verify.sh", "sandbox-runner")):
    check(agent in agents, f"agents/ 에 {agent} 가 있다")
    entries = [e for e in hooks.get("SubagentStop", []) if script in scripts_of(e)]
    check(len(entries) == 1, f"{script} 가 SubagentStop 에 한 번 걸려 있다")
    for e in entries:
        m = e.get("matcher", "")
        check(matches(m, f"{plugin}:{agent}"), f"{script} 매처 {m!r} 가 {plugin}:{agent} 에 맞는다")
        for other in sorted(agents):
            if other != agent:
                check(not matches(m, f"{plugin}:{other}"), f"{script} 매처가 {plugin}:{other} 에는 안 맞는다")

# 2. 플러그인 에이전트는 frontmatter hooks: 를 무시한다. 거기 걸면 아무것도 막지 않는다.
for name in sorted(agents):
    check(not re.search(r"^hooks:", agents[name], re.M),
          f"{name} frontmatter 에 hooks: 가 없다 (플러그인 에이전트는 무시한다)")

# 3. agent-guard 는 세션 수준 PreToolUse(Bash) 에 있어야 서브에이전트의 Bash 를 본다.
pre_bash = [e for e in hooks.get("PreToolUse", []) if matches(e.get("matcher", ""), "Bash")]
check(any("agent-guard.sh" in scripts_of(e) for e in pre_bash),
      "agent-guard.sh 가 PreToolUse(Bash) 에 걸려 있다")

# 3a. safety-guard 는 Bash 만이 아니라 읽기·쓰기 툴과 MCP 에도 걸려야 한다.
#     Read 가 빠지면 .env 가 그대로 열리고, Write 가 빠지면 키 리터럴이 파일에 적힌다.
for tool in ("Bash", "Read", "Grep", "Write", "Edit", "mcp__webstorm__get_file_text_by_path"):
    entries = [e for e in hooks.get("PreToolUse", []) if matches(e.get("matcher", ""), tool)]
    check(any("safety-guard.sh" in scripts_of(e) for e in entries),
          f"safety-guard.sh 가 PreToolUse({tool}) 에 걸려 있다")

# 3b. tdd-guard 의 ack 모드는 PostToolUse 에, 쓰기 툴 전체에 걸려 있어야 한다. 승인 표시는
#     툴이 실제로 돈 뒤에만 남는다 — 이 배선이 빠지면 표시가 영영 안 생겨 다시 매번 묻는다.
acks = [e for e in hooks.get("PostToolUse", [])
        if any(re.search(r'hooks/tdd-guard\.sh"? ack\b', h.get("command", "")) for h in e.get("hooks", []))]
check(len(acks) == 1, "tdd-guard.sh ack 가 PostToolUse 에 한 번 걸려 있다")
for e in acks:
    for tool in ("Edit", "Write", "Bash"):
        check(matches(e.get("matcher", ""), tool), f"tdd-guard.sh ack 매처가 {tool} 에 맞는다")

# 4. 배선된 스크립트가 전부 실제로 있다.
for event in sorted(hooks):
    for e in hooks[event]:
        for s in scripts_of(e):
            check(os.path.isfile(os.path.join(root, "hooks", s)), f"{event} -> hooks/{s} 가 있다")
print("\n".join(out))
PY
) || wiring="  FAIL  배선 검사를 돌리지 못했다: $wiring"

while IFS= read -r line; do
  [ -n "$line" ] || continue
  case "$line" in
    "  ok"*) pass=$((pass + 1)) ;;
    *)       fail=$((fail + 1)) ;;
  esac
  printf '%s\n' "$line"
done <<EOF
$wiring
EOF

# ─────────────────────────────────────────────────────────────────────────────
echo "tdd-guard.sh — 본체보다 테스트가 먼저"

run "$GUARD" 2 "순수 로직 경로의 신규 파일, 테스트 없음 -> 차단" \
  "$(payload Write "$SRC/source/__NeverExists.kt")"

# 클래스를 언급하는 테스트가 있으면 통과. 이름이 같은 파일이 아니어도 인정한다.
TMP_TEST="src/test/kotlin/dev/hugo/sheetview/HookProbeTest.kt"
cat > "$TMP_TEST" <<'KT'
package dev.hugo.sheetview
// test-hooks.sh 가 만든 임시 파일. __NeverExists 를 언급해 훅의 탐색 경로를 확인한다.
class HookProbeTest { fun probe() = "__NeverExists" }
KT
run "$GUARD" 0 "같은 경로, 클래스를 언급하는 테스트 있음 -> 통과" \
  "$(payload Write "$SRC/source/__NeverExists.kt")"
rm -f "$TMP_TEST"; TMP_TEST=""

run "$GUARD" 0 "기존 format/XlsxReader.kt 수정 -> 통과" \
  "$(payload Edit "$SRC/format/XlsxReader.kt")"
run "$GUARD" 0 "기존 source/HtmlSanitizer.kt 수정 -> 통과 (리팩터를 막지 않는다)" \
  "$(payload Edit "$SRC/source/HtmlSanitizer.kt")"

# 소스 루트 바로 아래 파일이면 "하위 폴더" 가 없다. 예전에는 파일 이름을 폴더 자리에 찍었다.
top=$(printf '%s' "$(payload Write "$SRC/__NeverExistsTop.kt")" | sh "$GUARD" 2>&1)
case "$top" in
  *"__NeverExistsTop.kt/)"*) bad "소스 루트 바로 아래 파일의 안내가 파일 이름을 폴더로 찍는다" ;;
  *"순수 로직"*)             ok  "소스 루트 바로 아래 파일도 안내가 맞다" ;;
  *)                         bad "소스 루트 바로 아래 파일을 막지 않았다" ;;
esac

echo "tdd-guard.sh — 면제 목록"

run "$GUARD" 0 "editor/SourcePanel.kt (사유와 함께 면제) -> 통과" \
  "$(payload Write "$SRC/editor/SourcePanel.kt")"
run "$GUARD" 2 "editor/ 의 면제 아닌 신규 파일 -> 차단" \
  "$(payload Write "$SRC/editor/__NeverExists.kt")"
run "$GUARD" 2 "filetype/ 의 면제 아닌 신규 파일 -> 차단" \
  "$(payload Write "$SRC/filetype/__NeverExists.kt")"

echo "tdd-guard.sh — 관여하지 않는 것"

run "$GUARD" 0 "README.md -> 통과"  "$(payload Write "$PROJECT_DIR/README.md")"
run "$GUARD" 0 "plugin.xml -> 통과" "$(payload Edit "$PROJECT_DIR/src/main/resources/META-INF/plugin.xml")"
run "$GUARD" 0 "build.gradle.kts -> 통과" "$(payload Edit "$PROJECT_DIR/build.gradle.kts")"
run "$GUARD" 0 "새 테스트 파일 자체 -> 통과" "$(payload Write "$TST/__NeverExistsTest.kt")"
run "$GUARD" 0 "프로젝트 밖 절대경로 -> 통과" "$(payload Write "/tmp/somewhere/Else.kt")"

echo "tdd-guard.sh — Bash 우회 차단"

run "$GUARD" 2 "cat > 새 source/*.kt -> 차단" \
  "$(bashpay "cat > $SRC/source/__NeverExists2.kt <<'EOF'")"
run "$GUARD" 2 "tee 로 새 source/*.kt -> 차단" \
  "$(bashpay "printf x | tee $SRC/source/__NeverExists3.kt")"
run "$GUARD" 2 ">> 로 새 source/*.kt 에 덧붙이기 -> 차단" \
  "$(bashpay "echo x >> $SRC/source/__NeverExists4.kt")"
run "$GUARD" 0 "같은 경로를 읽기만 하면 -> 통과" \
  "$(bashpay "grep -rn Foo $SRC/source/__NeverExists2.kt")"
run "$GUARD" 0 "기존 파일로 리다이렉션 -> 통과" \
  "$(bashpay "cat > $SRC/format/XlsxReader.kt")"
run "$GUARD" 0 "./gradlew test > /tmp/out -> 통과" "$(bashpay './gradlew test > /tmp/out.txt')"
run "$GUARD" 0 "git commit (다른 훅 담당) -> 통과" "$(bashpay 'git add -A && git commit -m x')"

echo "tdd-guard.sh — 판단 불가는 전부 통과"

run "$GUARD" 0 "깨진 JSON"          'not json at all'
run "$GUARD" 0 "빈 payload"         ''
run "$GUARD" 0 "file_path 없음"     '{"tool_name":"Write","tool_input":{}}'
run "$GUARD" 0 "tool_input 이 배열"  '{"tool_name":"Write","tool_input":[]}'
run "$GUARD" 0 "file_path 가 숫자"   '{"tool_name":"Write","tool_input":{"file_path":42}}'
run "$GUARD" 0 "command 없는 Bash"   '{"tool_name":"Bash","tool_input":{}}'

echo "tdd-guard.sh — 프로젝트 루트 판정"

# 상대경로도 프로젝트 기준으로 해석한다 (payload 가 늘 절대경로인 것은 아니다).
run "$GUARD" 2 "상대경로 신규 본체 -> 차단" \
  "$(payload Write "$SHEETVIEW_SRC_ROOT/source/__NeverExists5.kt")"

# 플러그인은 사용자 범위로 설치될 수 있다. 마커가 없는 곳에서는 조용히 통과해야 한다 —
# 여기가 틀리면 남의 프로젝트에서 이 훅이 파일 생성을 막는다.
outside=$(printf '%s' "$(payload Write "$SHEETVIEW_SRC_ROOT/source/__NeverExists5.kt")" |
  (cd / && CLAUDE_PROJECT_DIR=/ sh "$GUARD") 2>&1)
if [ $? = 0 ]; then
  ok "마커 없는 디렉터리에서는 관여 안 함"
else
  bad "마커 없는 디렉터리에서 차단했다"
  printf '%s\n' "$outside" | sed 's/^/          /'
fi

echo "tdd-guard.sh — 파일을 만드는 다른 명령들"

# 리다이렉션과 tee 만 보던 동안, 아래 두 줄이면 게이트가 완전히 사라졌다:
#   touch <본체>.kt   (미탐지)  ->  Write <본체>.kt  ("이미 있는 파일" 로 통과)
run "$GUARD" 2 "touch 로 새 본체 .kt -> 차단" \
  "$(bashpay "touch $SRC/source/__NeverExists6.kt")"
run "$GUARD" 2 "cp 로 새 본체 .kt -> 차단" \
  "$(bashpay "cp /tmp/x.kt $SRC/source/__NeverExists7.kt")"
run "$GUARD" 2 "mv 로 새 본체 .kt -> 차단" \
  "$(bashpay "mv /tmp/x.kt $SRC/source/__NeverExists8.kt")"
run "$GUARD" 2 "dd of= 로 새 본체 .kt -> 차단" \
  "$(bashpay "dd if=/dev/zero of=$SRC/source/__NeverExists9.kt")"
run "$GUARD" 2 "세미콜론 뒤의 touch -> 차단" \
  "$(bashpay "echo hi; touch $SRC/source/__NeverExistsA.kt")"
run "$GUARD" 2 "&& 뒤의 touch -> 차단" \
  "$(bashpay "mkdir -p /tmp/z && touch $SRC/source/__NeverExistsB.kt")"
run "$GUARD" 2 "touch 인자 여럿 중 하나가 본체 -> 차단" \
  "$(bashpay "touch /tmp/ok.txt $SRC/source/__NeverExistsC.kt")"
run "$GUARD" 0 "touch 로 무관한 파일 -> 통과" "$(bashpay 'touch /tmp/whatever.txt')"
run "$GUARD" 0 "cp 로 기존 본체 덮어쓰기 -> 통과 (리팩터를 막지 않는다)" \
  "$(bashpay "cp /tmp/x.kt $SRC/format/XlsxReader.kt")"
run "$GUARD" 0 "touch 로 새 테스트 파일 -> 통과" \
  "$(bashpay "touch $TST/__NeverExistsDTest.kt")"

echo "tdd-guard.sh — MCP 쓰기 툴 (Write 툴을 타지 않는 경로)"

run "$GUARD" 2 "create_new_file (pathInProject) -> 차단" \
  "$(mcppay mcp__webstorm__create_new_file pathInProject "$SHEETVIEW_SRC_ROOT/source/__NeverExistsE.kt")"
run "$GUARD" 2 "apply_patch (filePath) -> 차단" \
  "$(mcppay mcp__webstorm__apply_patch filePath "$SRC/source/__NeverExistsF.kt")"
run "$GUARD" 0 "MCP 로 기존 본체 수정 -> 통과" \
  "$(mcppay mcp__webstorm__apply_patch filePath "$SRC/format/XlsxReader.kt")"
run "$GUARD" 0 "MCP 로 무관한 파일 -> 통과" \
  "$(mcppay mcp__webstorm__create_new_file pathInProject "README.md")"

echo "tdd-guard.sh — 게이트 자신은 차단이 아니라 사람에게 올린다"

# ask 는 exit 0 + stdout JSON 이다. 종료코드만 보면 통과와 구별되지 않으므로 출력을 본다.
ask_case() {
  desc=$1; want=$2; body=$3
  out=$(printf '%s' "$body" | sh "$GUARD" 2>/dev/null)
  case "$out" in
    *'"permissionDecision": "ask"'*) got=ask ;;
    *) got=silent ;;
  esac
  if [ "$got" = "$want" ]; then
    pass=$((pass + 1)); printf '  ok    %s\n' "$desc"
  else
    fail=$((fail + 1)); printf '  FAIL  %s  (기대 %s, 실제 %s)\n' "$desc" "$want" "$got"
  fi
}

ask_case "면제 목록 수정 -> ask" ask "$(payload Edit "$PROJECT_DIR/.claude/tdd-exempt.txt")"
ask_case "테스트 래칫 수정 -> ask" ask "$(payload Write "$PROJECT_DIR/.claude/tdd-baseline")"
ask_case "훅 스크립트 수정 -> ask" ask "$(payload Edit "$PROJECT_DIR/plugins/sheetview-kit/hooks/tdd-guard.sh")"
ask_case "check.sh 수정 -> ask" ask "$(payload Edit "$PROJECT_DIR/scripts/check.sh")"
ask_case "Bash 로 면제 목록에 덧붙이기 -> ask" ask \
  "$(bashpay "echo '*  whatever' >> $PROJECT_DIR/.claude/tdd-exempt.txt")"
ask_case "평범한 본체 수정은 ask 아님" silent "$(payload Edit "$SRC/format/XlsxReader.kt")"
ask_case "README 수정은 ask 아님" silent "$(payload Edit "$PROJECT_DIR/README.md")"

echo "tdd-guard.sh — 게이트 수정은 브랜치마다 한 번만 묻는다"

# 표시(.harness-ack)는 묻는 순간이 아니라 **승인된 수정이 실제로 돈 뒤**(PostToolUse, ack 모드)에
# 남는다. 묻는 순간에 남기면 사람이 거절한 뒤의 수정이 전부 조용히 통과한다.
HA=$(synth_project)
( cd "$HA" && git init -q -b work . && git config user.email t@t && git config user.name t &&
  git commit -q --allow-empty -m init ) >/dev/null 2>&1
ha_pre() { printf '%s' "$1" | (cd "$HA" && CLAUDE_PROJECT_DIR="$HA" sh "$GUARD" 2>/dev/null); }
ha_ack() { printf '%s' "$1" | (cd "$HA" && CLAUDE_PROJECT_DIR="$HA" sh "$GUARD" ack >/dev/null 2>&1); }
ha_memo() { cat "$HA/$HARNESS_ACK" 2>/dev/null; }
ha_case() {   # <설명> <ask|silent> <payload>
  case "$(ha_pre "$3")" in *'"permissionDecision": "ask"'*) got=ask ;; *) got=silent ;; esac
  if [ "$got" = "$2" ]; then ok "$1"; else bad "$1  (기대 $2, 실제 $got)"; fi
}
HA_HOOK=$(payload Edit "$HA/plugins/sheetview-kit/hooks/tdd-guard.sh")
HA_EXEMPT=$(payload Edit "$HA/.claude/tdd-exempt.txt")

ha_case "처음 고치면 묻는다" ask "$HA_HOOK"
if [ -e "$HA/$HARNESS_ACK" ]; then bad "묻기만 했는데 표시를 남겼다 (거절해도 조용해진다)"
else ok "묻는 것만으로는 표시를 남기지 않는다"; fi
ha_case "승인 전에는 다시 묻는다" ask "$HA_HOOK"

# 표시 파일 자신도 게이트다. 아니면 모델이 브랜치 이름을 직접 써 넣어 승인을 위조한다 —
# 사람이 한 번도 승인하지 않았는데 면제·래칫 수정이 조용히 지나간다.
HA_FORGE=$(bashpay "printf work > .claude/.harness-ack")
ha_case "Bash 로 승인 표시를 직접 쓰려 하면 묻는다" ask "$HA_FORGE"
ha_case "Write 로 승인 표시를 쓰려 해도 묻는다" ask "$(payload Write "$HA/$HARNESS_ACK")"
ha_ack "$HA_FORGE"
if [ -e "$HA/$HARNESS_ACK" ]; then bad "표시 파일을 쓴 것으로 표시를 남겼다 ($(ha_memo))"
else ok "표시 파일을 쓴 것은 승인으로 치지 않는다"; fi

# 사이클은 .cycle-state 를 쓰는 것으로 시작한다. 그 승인이 면제 수정까지 열어 주면 안 된다.
ha_ack "$(bashpay "printf 'review=pending\n' > .claude/.cycle-state")"
ha_ack "$(payload Edit "$HA/README.md")"
if [ -e "$HA/$HARNESS_ACK" ]; then bad "게이트가 아닌 수정으로 표시를 남겼다 ($(ha_memo))"
else ok "사이클 상태 파일·무관한 파일은 승인으로 치지 않는다"; fi

ha_ack "$HA_HOOK"
if [ "$(ha_memo)" = "work" ]; then ok "승인된 수정이 돈 뒤 브랜치 이름을 남긴다"
else bad "승인된 수정이 돌았는데 표시가 없다 ($(ha_memo))"; fi
ha_case "같은 브랜치에서는 다시 묻지 않는다" silent "$HA_HOOK"
ha_case "면제 목록도 같은 표시로 조용하다" silent "$HA_EXEMPT"

# 묻지 않아도 사이클에는 남는다 — cycle-stop 이 마지막에 알린다.
printf 'review=pending\nharness_touched=no\n' > "$HA/.claude/.cycle-state"
ha_pre "$HA_EXEMPT" >/dev/null
if [ "$(sed -n 's/^harness_touched=//p' "$HA/.claude/.cycle-state")" = "yes" ]; then
  ok "묻지 않아도 harness_touched=yes 는 남는다"
else bad "표시가 있다고 사이클 기록까지 건너뛰었다"; fi
rm -f "$HA/.claude/.cycle-state"

( cd "$HA" && git switch -q -c other ) >/dev/null 2>&1
ha_case "브랜치를 바꾸면 다시 묻는다" ask "$HA_HOOK"

# detached HEAD 는 어느 커밋이든 이름이 "HEAD" 다. 표시를 믿지도 남기지도 않는다.
( cd "$HA" && git checkout -q --detach ) >/dev/null 2>&1
printf 'HEAD' > "$HA/$HARNESS_ACK"
ha_case "detached HEAD 는 표시가 있어도 묻는다" ask "$HA_HOOK"
rm -f "$HA/$HARNESS_ACK"
ha_ack "$HA_HOOK"
if [ -e "$HA/$HARNESS_ACK" ]; then bad "detached HEAD 에서 표시를 남겼다 ($(ha_memo))"
else ok "detached HEAD 에서는 표시를 남기지 않는다"; fi

# git 저장소가 아니면 브랜치를 모른다 — 매번 묻는다.
rm -rf "$HA/.git"
ha_ack "$HA_HOOK"
ha_case "브랜치를 모르면 매번 묻는다" ask "$HA_HOOK"

rm -rf "$HA"

# ─────────────────────────────────────────────────────────────────────────────
echo "_common.sh — package 선언 읽기"

# BRE 의 \+ 는 GNU 확장이라 macOS sed 에서 조용히 빈 값이 됐다. tdd-red 가 FQCN 대신
# 단순 이름으로 테스트를 돌리고 있었다.
PKG_F=$(mktemp)
printf '// 머리 주석\npackage dev.hugo.sheetview.format\n\nclass X\n' > "$PKG_F"
got=$(sheetview_kotlin_package "$PKG_F")
if [ "$got" = "dev.hugo.sheetview.format" ]; then ok "package 선언을 읽는다 ($got)"
else bad "package 선언을 못 읽었다 (실제: '$got')"; fi
printf 'class NoPackage\n' > "$PKG_F"
got=$(sheetview_kotlin_package "$PKG_F")
if [ -z "$got" ]; then ok "package 선언이 없으면 빈 값"
else bad "package 선언이 없는데 '$got' 를 읽었다"; fi
rm -f "$PKG_F"

echo "_common.sh — 이 체크아웃의 프로세스만 센다"

# 워크트리는 본체 안(.claude/worktrees/)에 중첩된다. pgrep -f 만 쓰던 동안 본체의 샌드박스가
# 워크트리의 커밋과 red 검사를 "락 때문에" 멈췄고, 워크트리의 `sandbox-up.sh down` 이 본체의
# IDE 를 죽였다. Gradle 프로젝트 락은 디렉터리마다 따로다. 진짜 프로세스를 띄워 주인을 가리는지 본다.
PA=$(synth_project); mkdir -p "$PA/.claude/worktrees/wt"
PB=$(synth_project)
TAG="sheetview-probe-$$"
pid_a=$(spawn_probe "$PA" "$TAG")
pid_w=$(spawn_probe "$PA/.claude/worktrees/wt" "$TAG")
pid_b=$(spawn_probe "$PB" "$TAG")
# 명령줄에 절대경로가 있으면 cwd 와 무관하게 그 경로의 주인 것이다 (gradle 래퍼 jar 처럼).
pid_ab=$(spawn_probe / "$TAG $PB/gradle/wrapper/gradle-wrapper.jar")
pid_aw=$(spawn_probe / "$TAG $PA/.claude/worktrees/wt/gradle/wrapper/gradle-wrapper.jar")

pids_eq() {   # <설명> <기대 PID 들> <디렉터리>
  got=$(sheetview_pids "$TAG" "$3" | sort | tr '\n' ' ')
  want=""
  [ -n "$2" ] && want=$(printf '%s\n' $2 | sort | tr '\n' ' ')
  if [ "$got" = "$want" ]; then ok "$1"
  else bad "$1  (기대 '$want', 실제 '$got')"; fi
}
pids_eq "본체는 제 cwd 의 프로세스만 센다 (워크트리·남의 것 제외)" "$pid_a" "$PA"
pids_eq "워크트리는 제 것만 센다" "$pid_w $pid_aw" "$PA/.claude/worktrees/wt"
pids_eq "다른 프로젝트는 명령줄의 절대경로로도 제 것을 찾는다" "$pid_b $pid_ab" "$PB"
for _p in $pid_a $pid_w $pid_b $pid_ab $pid_aw; do kill_probe "$_p"; done
pids_eq "끝난 프로세스는 세지 않는다" "" "$PA"

echo "sandbox-up.sh — 이 체크아웃의 IDE 만 끈다"

# 예전의 `pkill -f` 는 워크트리에서 부르면 본체의 IDE 까지 죽였다. 샌드박스 IDE 를 흉내 낸
# 탐침(명령줄에 표지 + 제 프로젝트 경로)을 양쪽에 띄우고, 한쪽의 down 이 제 것만 끄는지 본다.
SBX="idea.plugin.in.sandbox.mode=true"
pid_a=$(spawn_probe / "-D$SBX -Didea.log.path=$PA/.intellijPlatform/sandbox/log_runIde")
pid_w=$(spawn_probe / "-D$SBX -Didea.log.path=$PA/.claude/worktrees/wt/.intellijPlatform/sandbox/log_runIde")
sbx_status=$(cd "$PA" && CLAUDE_PROJECT_DIR="$PA" sh "$PLUGIN_DIR/scripts/sandbox-up.sh" status 2>&1)
case "$sbx_status" in
  *"PID=$pid_a") ok "status 는 이 체크아웃의 IDE 만 보고한다" ;;
  *) bad "status 가 틀렸다: $sbx_status (기대 PID=$pid_a)" ;;
esac
(cd "$PA" && CLAUDE_PROJECT_DIR="$PA" sh "$PLUGIN_DIR/scripts/sandbox-up.sh" down) >/dev/null 2>&1
sleep 0.3
if kill -0 "$pid_w" 2>/dev/null && ! kill -0 "$pid_a" 2>/dev/null; then
  ok "down 은 제 IDE 만 끄고 워크트리의 IDE 는 남긴다"
else
  bad "down 이 틀린 프로세스를 건드렸다 (본체 살아 있음: $(kill -0 "$pid_a" 2>/dev/null && echo 예 || echo 아니오), 워크트리 살아 있음: $(kill -0 "$pid_w" 2>/dev/null && echo 예 || echo 아니오))"
fi
kill_probe "$pid_a"; kill_probe "$pid_w"
rm -rf "$PA" "$PB"

# ─────────────────────────────────────────────────────────────────────────────
echo "tdd-red.sh"

run "$REDH" 0 "본체 파일 -> 관여 안 함"        "$(payload Write "$SRC/format/XlsxReader.kt")"
run "$REDH" 0 "테스트 리소스 -> 관여 안 함"     "$(payload Write "$PROJECT_DIR/src/test/resources/fixtures/cp949.csv")"
run "$REDH" 0 "신규 표시 없는 기존 테스트 수정" "$(payload Edit "$TST/XlsxReaderTest.kt")"
run "$REDH" 0 "깨진 JSON"                     'not json'

# guard 가 남긴 신규 표시를 red 훅이 읽고 지우는지. gradle 을 타지 않게 없는 클래스를 쓴다.
printf 'src/test/kotlin/dev/hugo/sheetview/GhostTest.kt\n' > "$NEW_TESTS"
printf '%s' "$(payload Write "$TST/GhostTest.kt")" | sh "$REDH" >/dev/null 2>&1
if [ -s "$NEW_TESTS" ]; then
  bad "신규 표시를 소비하지 않았다 (.tdd-new 가 그대로다)"
else
  ok "신규 표시를 읽고 지웠다"
fi
rm -f "$NEW_TESTS"
[ -f "$NEW_TESTS.saved" ] && mv "$NEW_TESTS.saved" "$NEW_TESTS"

# 합성 프로젝트의 가짜 gradlew 로 판정 경로를 돈다. 가짜는 받은 인자를 적어 두고,
# gradlew.fail 이 있으면 실패한 테스트처럼 말한다.
RD=$(synth_project); mkdir -p "$RD/.claude/worktrees/wt"
cat > "$RD/gradlew" <<'SH'
#!/bin/sh
here=$(dirname "$0")
printf '%s\n' "$@" > "$here/gradlew.args"
if [ -f "$here/gradlew.fail" ]; then echo "3 tests completed, 1 failed"; exit 1; fi
exit 0
SH
chmod +x "$RD/gradlew"
printf 'package dev.hugo.sheetview\nclass ProbeTest\n' > "$RD/src/test/kotlin/dev/hugo/sheetview/ProbeTest.kt"
red_run() {
  printf 'src/test/kotlin/dev/hugo/sheetview/ProbeTest.kt\n' > "$RD/.claude/.tdd-new"
  rm -f "$RD/gradlew.args"
  RED_OUT=$(printf '%s' "$(payload Write "$RD/src/test/kotlin/dev/hugo/sheetview/ProbeTest.kt")" |
    (cd "$RD" && CLAUDE_PROJECT_DIR="$RD" sh "$REDH" 2>/dev/null))
  RED_EXIT=$?
}

# red 를 확인했다는 말은 모델에게 가야 한다. PostToolUse 의 exit 0 stdout 은 모델에게 가지
# 않으므로 additionalContext 여야 한다 — TSV 사이클에서 "돌았는지 알 수 없다"가 여기서 나왔다.
touch "$RD/gradlew.fail"
red_run
case "$RED_EXIT:$RED_OUT" in
  0:*additionalContext*RED*) ok "RED 확인을 additionalContext 로 모델에게 알린다" ;;
  *) bad "RED 확인이 모델에게 가지 않는다 (exit $RED_EXIT: $RED_OUT)" ;;
esac
if grep -qx "dev.hugo.sheetview.ProbeTest" "$RD/gradlew.args" 2>/dev/null; then
  ok "FQCN 으로 테스트를 고른다 (--tests dev.hugo.sheetview.ProbeTest)"
else
  bad "FQCN 으로 테스트를 고르지 않는다: $(tr '\n' ' ' < "$RD/gradlew.args" 2>/dev/null)"
fi
rm -f "$RD/gradlew.fail"

# 이 프로젝트의 runIde 가 떠 있으면 건너뛰고, 건너뛴 사실도 모델에게 알린다.
pid=$(spawn_probe "$RD" "runIde-probe-$$")
red_run
case "$RED_EXIT:$RED_OUT" in
  0:*additionalContext*runIde*) ok "이 체크아웃의 runIde 가 있으면 건너뛰고 모델에게 알린다" ;;
  *) bad "runIde 가 떠 있는데 건너뛰지 않았거나 알리지 않았다 (exit $RED_EXIT: $RED_OUT)" ;;
esac
kill_probe "$pid"

# 다른 체크아웃(중첩 워크트리)의 runIde 는 이쪽 락과 무관하다 — 검사를 그대로 돈다.
# 가짜 gradlew 가 성공하므로 "처음부터 통과"(exit 2)가 나와야 검사가 돈 것이다.
pid=$(spawn_probe "$RD/.claude/worktrees/wt" "runIde-probe-$$")
red_run
if [ "$RED_EXIT" = 2 ] && [ -f "$RD/gradlew.args" ]; then
  ok "다른 체크아웃의 runIde 는 세지 않는다"
else
  bad "다른 체크아웃의 runIde 때문에 검사를 건너뛰었다 (exit $RED_EXIT)"
fi
kill_probe "$pid"
rm -rf "$RD"

# ─────────────────────────────────────────────────────────────────────────────
# 사이클 훅은 .cycle-state 를 읽고 쓴다. 저장소를 오염시키지 않도록 합성 프로젝트에서 돈다.
CYC=$(synth_project)

cyc_state() { rm -f "$CYC/.claude/.cycle-state"
  for kv in "$@"; do printf '%s\n' "$kv" >> "$CYC/.claude/.cycle-state"; done; }
cyc_get() { sed -n "s/^$1=//p" "$CYC/.claude/.cycle-state" 2>/dev/null; }

echo "cycle-stop.sh — 세션을 가두지 않는 탈출구"

# Stop 입력. background_tasks 는 실측한 모양 그대로다.
STOPPAY='{"hook_event_name":"Stop","stop_hook_active":false,"background_tasks":[]}'
bgpay() {   # <agent_type> <status>
  printf '{"hook_event_name":"Stop","stop_hook_active":false,"background_tasks":[{"id":"a1","type":"subagent","status":"%s","description":"x","agent_type":"%s"}]}' "$2" "$1"
}
stop_run() {   # <payload>  -> STOP_EXIT, STOP_ERR
  STOP_ERR=$(printf '%s' "$1" |
    (cd "$CYC" && CLAUDE_PROJECT_DIR="$CYC" sh "$PLUGIN_DIR/hooks/cycle-stop.sh" 2>&1 >/dev/null))
  STOP_EXIT=$?
}
stop_case() {
  desc=$1; want=$2; shift 2
  if [ "${1:-}" = "NOSTATE" ]; then rm -f "$CYC/.claude/.cycle-state"; else cyc_state "$@"; fi
  stop_run "${PAY:-$STOPPAY}"
  if [ "$STOP_EXIT" = "$want" ]; then ok "$desc"
  else bad "$desc  (기대 exit $want, 실제 $STOP_EXIT)"; fi
}

stop_case "사이클이 없으면 관여 안 함" 0 NOSTATE
stop_case "리뷰·검증 둘 다 끝났으면 통과" 0 "review=clean" "verify=clean"
stop_case "되돌리기 한도에 닿으면 포기하고 통과" 0 "review=pending" "verify=pending" "stop_blocked=3"
stop_case "라운드 상한 + 🔴 남으면 사람 부르고 통과" 0 "review=red:2" "verify=pending" "round=3"
stop_case "라운드 상한 + 검증 🔴 도 사람 부르고 통과" 0 "review=clean" "verify=red:1" "round=3"
stop_case "라운드 상한이어도 🔴 가 없으면 검증은 요구한다" 2 "review=clean" "verify=pending" "round=3"
stop_case "값이 깨져도 갇히지 않는다" 0 "review=clean" "verify=clean" "stop_blocked=abc" "round=xyz"
stop_case "아직 리뷰 전이면 되돌림" 2 "review=pending" "verify=pending"
stop_case "리뷰만 끝났으면 되돌림" 2 "review=clean" "verify=pending"
stop_case "🔴 가 남았으면 되돌림" 2 "review=red:3" "verify=pending"
stop_case "판정 불가는 통과가 아니다" 2 "review=unknown" "verify=pending"

# 리뷰어·러너는 백그라운드로 돈다. 메인이 결과를 기다리느라 턴을 끝낼 때마다 Stop 이 불리는데,
# 예전에는 그 대기만으로 탈출구 한도를 다 써서 리뷰가 끝나기도 전에 게이트가 꺼졌다(TSV 사이클).
PAY=$(bgpay sheetview-kit:sheet-reviewer running)
stop_case "리뷰어가 돌고 있으면 기다린다 (되돌리지 않는다)" 0 "review=pending" "verify=pending"
if [ -z "$(cyc_get stop_blocked)" ]; then ok "기다리는 동안은 되돌리기 횟수를 세지 않는다"
else bad "기다리는 동안 되돌리기 횟수가 올랐다 ($(cyc_get stop_blocked))"; fi
PAY=$(bgpay sheetview-kit:sandbox-runner running)
stop_case "샌드박스 러너가 돌고 있어도 기다린다" 0 "review=clean" "verify=pending"
PAY=$(bgpay sheetview-kit:sheet-reviewer completed)
stop_case "끝난 리뷰어는 기다릴 이유가 아니다" 2 "review=pending" "verify=pending"
PAY=$(bgpay Explore running)
stop_case "사이클과 무관한 백그라운드 작업은 기다릴 이유가 아니다" 2 "review=pending" "verify=pending"
PAY=""

# 무한루프 방지의 근거: 되돌릴 때마다 카운터가 올라야 한다.
cyc_state "review=pending" "verify=pending"
_n=0
for _i in 1 2 3 4; do
  stop_run "$STOPPAY"
  [ "$STOP_EXIT" = 0 ] && _n=$((_n + 1))
done
if [ "$_n" = 1 ]; then ok "4회 되돌린 뒤 스스로 포기한다"
else bad "포기하지 않았다 (통과 횟수 $_n, 기대 1)"; fi

# 포기했다는 말은 한 번만 한다. 예전에는 멈출 때마다 같은 문단을 되풀이했다.
cyc_state "review=red:1" "verify=pending" "stop_blocked=3"
stop_run "$STOPPAY"; first=$STOP_ERR
stop_run "$STOPPAY"; second=$STOP_ERR
if [ -n "$first" ] && [ -z "$second" ]; then ok "포기 알림은 한 번만 한다"
else bad "포기 알림 횟수가 틀렸다 (첫째 '${first:+있음}', 둘째 '${second:+있음}')"; fi
# 포기해도 결과를 덮어쓰지 않는다. 예전에는 verify=halted 로 덮어써서 나중의 clean 을 지웠다.
if [ "$(cyc_get verify)" = "pending" ] && [ "$(cyc_get review)" = "red:1" ]; then
  ok "포기해도 review·verify 를 덮어쓰지 않는다"
else
  bad "포기하면서 상태를 덮어썼다 (review=$(cyc_get review) verify=$(cyc_get verify))"
fi

echo "cycle-review.sh — 리뷰 보고를 기계가 읽는다"

# agent_type 은 실제로 오는 모양(<플러그인>:<에이전트>)으로 쓴다.
agentpay() {
  python3 -c 'import json,sys;print(json.dumps({"hook_event_name":"SubagentStop","agent_type":sys.argv[1],"last_assistant_message":sys.argv[2]}))' "$1" "$2"
}
REVIEWER=sheetview-kit:sheet-reviewer
RUNNER=sheetview-kit:sandbox-runner
review_run() { printf '%s' "$1" | (cd "$CYC" && CLAUDE_PROJECT_DIR="$CYC" sh "$PLUGIN_DIR/hooks/cycle-review.sh" >/dev/null 2>&1); }
verify_run() { printf '%s' "$1" | (cd "$CYC" && CLAUDE_PROJECT_DIR="$CYC" sh "$PLUGIN_DIR/hooks/cycle-verify.sh" >/dev/null 2>&1); }
rv_case() {
  desc=$1; want=$2; body=$3
  cyc_state "review=pending" "verify=pending" "round=0"
  review_run "$body"
  got=$(cyc_get review)
  if [ "$got" = "$want" ]; then ok "$desc -> $got"
  else bad "$desc  (기대 $want, 실제 $got)"; fi
}

rv_case "🔴 섹션의 번호 항목을 센다" "red:3" \
  "$(agentpay $REVIEWER '### 🔴 고치고 커밋해야 함
1. **하나** — `a.kt:1`
2. **둘** — `b.kt:2`
3. **셋** — `c.kt:3`

### 🟡 고치면 좋음
1. **사소** — `d.kt`')"
rv_case "🔴 섹션이 비어 있으면 clean" "clean" \
  "$(agentpay $REVIEWER '### 🔴 고치고 커밋해야 함
없음

### 🟢 참고
- 메모')"
rv_case "🔴 가 아예 없으면 clean" "clean" \
  "$(agentpay $REVIEWER '### 🟢 참고
- 문제 없습니다')"
rv_case "형식을 벗어나면 unknown (통과로 밀지 않는다)" "unknown" \
  "$(agentpay $REVIEWER '리뷰했는데 🔴 문제가 좀 있습니다')"
rv_case "빈 보고는 unknown" "unknown" "$(agentpay $REVIEWER '')"
rv_case "깨진 payload 는 unknown" "unknown" 'not json'

# 🔴 면 라운드가 올라야 한다 — 이게 반복의 근거다.
cyc_state "review=pending" "verify=pending" "round=1"
review_run "$(agentpay $REVIEWER '### 🔴 고치고 커밋해야 함
1. **x** — `a.kt`')"
if [ "$(cyc_get round)" = "2" ]; then ok "🔴 이면 라운드가 오른다 (1 -> 2)"
else bad "라운드가 오르지 않았다 ($(cyc_get round))"; fi

# 판정이 기록되면 진전이다 — 되돌리기 횟수를 0 으로 돌린다. 예전에는 한 번 오른 횟수가
# 사이클 내내 쌓여서, 단계마다 한 번씩 멈추기만 해도 게이트가 꺼졌다.
cyc_state "review=pending" "verify=pending" "stop_blocked=2"
review_run "$(agentpay $REVIEWER '### 🟢 참고
- 문제 없습니다')"
if [ "$(cyc_get stop_blocked)" = "0" ]; then ok "리뷰 판정이 기록되면 되돌리기 횟수가 0 으로"
else bad "리뷰 판정 뒤에도 되돌리기 횟수가 그대로다 ($(cyc_get stop_blocked))"; fi
# unknown 은 진전이 아니다. 형식을 못 읽는 리뷰가 되풀이되면 한도에 닿아 사람을 불러야 한다.
cyc_state "review=pending" "verify=pending" "stop_blocked=2"
review_run "$(agentpay $REVIEWER '리뷰했는데 🔴 문제가 좀 있습니다')"
if [ "$(cyc_get stop_blocked)" = "2" ]; then ok "unknown 은 진전으로 치지 않는다"
else bad "unknown 인데 되돌리기 횟수가 바뀌었다 ($(cyc_get stop_blocked))"; fi

echo "cycle-verify.sh — 판정 줄만 본다"

vf_case() {
  desc=$1; want=$2; body=$3
  cyc_state "review=clean" "verify=pending"
  verify_run "$body"
  got=$(cyc_get verify)
  if [ "$got" = "$want" ]; then ok "$desc -> $got"
  else bad "$desc  (기대 $want, 실제 $got)"; fi
}
vf_case "통과 판정" "clean" "$(agentpay $RUNNER '## 샌드박스 로그
확인했고 문제없던 것: 다수

샌드박스 확인 통과')"
vf_case "🔴 N건 판정" "red:2" "$(agentpay $RUNNER '### 🔴
1. x
2. y

🔴 2건 — 샌드박스에서 실제로 깨졌다')"
vf_case "본문의 🔴 는 판정으로 오인하지 않는다" "clean" "$(agentpay $RUNNER '## 보고
🔴 는 이런 뜻이라고 설명만 하는 줄

샌드박스 확인 통과')"

# 검증 🔴 도 "고치고 다시" 한 바퀴다. 라운드를 올리지 않으면 검증 실패의 반복에는 상한이 없다.
cyc_state "review=clean" "verify=pending" "round=1" "stop_blocked=2"
verify_run "$(agentpay $RUNNER '🔴 1건 — 깨졌다')"
if [ "$(cyc_get round)" = "2" ] && [ "$(cyc_get stop_blocked)" = "0" ]; then
  ok "검증 🔴 도 라운드를 올리고, 진전으로 친다"
else
  bad "검증 🔴 처리 (round=$(cyc_get round) stop_blocked=$(cyc_get stop_blocked))"
fi

# 사이클이 없으면 상태 파일을 만들지도 않아야 한다.
rm -f "$CYC/.claude/.cycle-state"
review_run "$(agentpay $REVIEWER '### 🔴 고치고 커밋해야 함
1. **x** — `a.kt`')"
if [ -f "$CYC/.claude/.cycle-state" ]; then
  bad "사이클이 없는데 상태 파일을 만들었다"
else
  ok "사이클이 없으면 아무 일도 하지 않는다"
fi

echo "tdd-guard.sh — 게이트를 건드린 사이클은 스스로 기록된다"

# 예전에는 모델이 harness_touched=yes 를 직접 sed -i 로 적었다. 게이트 파일 수정은
# tdd-guard 가 이미 알고 있으므로 거기서 적는다.
guard_cyc() { printf '%s' "$1" | (cd "$CYC" && CLAUDE_PROJECT_DIR="$CYC" sh "$GUARD" >/dev/null 2>&1); }
cyc_state "review=pending" "verify=pending" "harness_touched=no"
guard_cyc "$(payload Edit "$CYC/.claude/tdd-exempt.txt")"
if [ "$(cyc_get harness_touched)" = "yes" ]; then ok "사이클 중 면제 목록을 건드리면 harness_touched=yes"
else bad "면제 목록을 건드렸는데 기록되지 않았다 ($(cyc_get harness_touched))"; fi
# 사이클 상태 파일 자체는 게이트가 아니라 사이클의 일부다 — 만들 때마다 표시되면 안 된다.
cyc_state "review=pending" "verify=pending" "harness_touched=no"
guard_cyc "$(bashpay "printf 'review=pending\n' > .claude/.cycle-state")"
if [ "$(cyc_get harness_touched)" = "no" ]; then ok "사이클 상태 파일을 쓰는 것은 게이트 수정이 아니다"
else bad "사이클 상태 파일을 쓴 것을 게이트 수정으로 기록했다"; fi
rm -f "$CYC/.claude/.cycle-state"
guard_cyc "$(payload Edit "$CYC/.claude/tdd-exempt.txt")"
if [ -f "$CYC/.claude/.cycle-state" ]; then bad "사이클이 없는데 상태 파일을 만들었다"
else ok "사이클이 없으면 기록하지 않는다"; fi

rm -rf "$CYC"

# ─────────────────────────────────────────────────────────────────────────────
echo "agent-guard.sh — 읽기 전용 에이전트를 실제로 읽기 전용으로"

# 플러그인 에이전트의 frontmatter hooks: 는 불리지 않는다(실측). 그래서 이 훅은 세션 전체의
# PreToolUse(Bash) 에 걸리고, 입력의 agent_type 으로 대상을 가린다. 메인 세션에는 agent_type 이 없다.
agpay() {   # <agent_type 또는 빈 값> <command>
  python3 -c 'import json,sys
d={"tool_name":"Bash","tool_input":{"command":sys.argv[2]}}
if sys.argv[1]: d["agent_type"]=sys.argv[1]
print(json.dumps(d))' "$1" "$2"
}
ag_case() {
  desc=$1; want=$2; agent=$3; cmd=$4
  out=$(printf '%s' "$(agpay "$agent" "$cmd")" | sh "$PLUGIN_DIR/hooks/agent-guard.sh" 2>/dev/null)
  case "$out" in *'"deny"'*) got=deny ;; *) got=allow ;; esac
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); printf '  ok    %-44s %s\n' "$desc" "$got"
  else fail=$((fail + 1)); printf '  FAIL  %-44s (기대 %s, 실제 %s)\n' "$desc" "$want" "$got"; fi
}
ag_case "sed -i 로 소스 수정"                 deny  $REVIEWER 'sed -i "" s/a/b/ src/main/x.kt'
ag_case "git checkout -- 로 되돌리기"          deny  $REVIEWER 'git checkout -- .'
ag_case "git -C . reset --hard"               deny  $REVIEWER 'git -C . reset --hard'
ag_case "리다이렉션으로 쓰기"                 deny  $REVIEWER 'echo x > src/main/x.kt'
ag_case "gradlew clean"                       deny  $REVIEWER './gradlew clean'
ag_case "rm"                                  deny  $REVIEWER 'rm -rf build'
ag_case "bash -c 안의 rm"                     deny  $REVIEWER 'bash -c "rm -rf src"'
ag_case "find -delete"                        deny  $REVIEWER 'find src -name "*.kt" -delete'
ag_case "xargs rm"                            deny  $REVIEWER 'ls | xargs rm'
ag_case "\$( ) 안의 rm"                       deny  $REVIEWER 'echo $(rm -rf src)'
ag_case "gradlew test 는 검사다"              allow $REVIEWER './gradlew test'
ag_case "2>&1 | tail 은 쓰기가 아니다"        allow $REVIEWER './gradlew test 2>&1 | tail -20'
ag_case "git status 는 읽기다"                allow $REVIEWER 'git status --short'
ag_case "git log · diff 는 읽기다"            allow $REVIEWER 'git log --oneline -5 && git diff --stat'
ag_case "grep 은 읽기다"                      allow $REVIEWER 'grep -rn Foo src/main/kotlin'
ag_case "인자 속 단어는 명령이 아니다"        allow $REVIEWER 'grep -rn "install" README.md'
ag_case "따옴표 속 > 는 리다이렉션이 아니다"  allow $REVIEWER 'grep -n "a > b" src/main/x.kt'
ag_case "입력 리다이렉션은 읽기다"            allow $REVIEWER 'wc -l < build.gradle.kts'
ag_case "/tmp 로 내보내기는 무해"             allow $REVIEWER './gradlew test > /tmp/out.txt'
ag_case "밖으로 복사는 읽기다"                allow $RUNNER   'cp src/main/x.kt /tmp/x.kt'
# /tmp 가 **어딘가에** 있기만 하면 통과시키던 동안 아래 셋이 전부 빠져나갔다.
ag_case "mv 로 /tmp 에 옮기면 원본이 사라진다" deny $RUNNER   'mv src/main/x.kt /tmp/'
ag_case "rm 인자에 /tmp 가 섞여 있어도"       deny  $RUNNER   'rm -rf src /tmp/x'
ag_case "주석에 /tmp 를 적어도"               deny  $RUNNER   'echo x > src/a.kt # /tmp/'
ag_case "runIde (샌드박스 러너)"              allow $RUNNER   './gradlew runIde'
ag_case "runIde (리뷰어는 금지)"              deny  $REVIEWER './gradlew runIde'
ag_case "샌드박스 스크립트 실행"              allow $RUNNER   'sh plugins/sheetview-kit/scripts/sandbox-up.sh status'
# pr-reviewer 는 남이 쓴 PR 제목·설명·diff 를 읽는다. 로컬에서 부르면 CI 의 허용 목록이 없다.
ag_case "pr-reviewer 도 쓰기는 금지"          deny  sheetview-kit:pr-reviewer 'echo x > src/main/x.kt'
ag_case "pr-reviewer 도 runIde 는 금지"       deny  sheetview-kit:pr-reviewer './gradlew runIde'
ag_case "pr-reviewer 의 git diff 는 읽기다"   allow sheetview-kit:pr-reviewer 'git diff main...HEAD'
ag_case "메인 세션은 대상이 아니다"           allow ""        'rm -rf build'
ag_case "다른 에이전트도 대상이 아니다"       allow Explore   'rm -rf build'
ag_case "깨진 따옴표는 판단 불가 -> 통과"     allow $REVIEWER 'grep "unclosed'

# ─────────────────────────────────────────────────────────────────────────────
echo "safety-guard.sh — 위험한 삭제 · .env · 키 노출 · 강제 푸시"

# agent_type 을 보지 않는다 — 메인 세션과 모든 에이전트가 대상이다. 합성 프로젝트 안에서 돌린다:
# rm 대상이 "프로젝트 안인가 밖인가"가 판정의 절반이라 루트와 cwd 가 정해져 있어야 한다.
SG=$(synth_project)

# 가짜 키는 **실행 중에 조립한다.** 리터럴로 적으면 이 훅과 커밋 앞 키 스캔이 이 파일 자신을 막는다.
FAKE_KEY="sk-ant-api03-$(printf '%040d' 0)"

sgpay() {   # <tool_name> <필드> <값> [<필드> <값> …]
  python3 -c 'import json,sys
a=sys.argv[1:]
print(json.dumps({"tool_name":a[1],"cwd":a[0],"tool_input":dict(zip(a[2::2],a[3::2]))}))' "$SG" "$@"
}
sg_run() {   # <payload> [<돌릴 디렉터리>]
  printf '%s' "$1" |
    (cd "${2:-$SG}" && CLAUDE_PROJECT_DIR="${2:-$SG}" sh "$PLUGIN_DIR/hooks/safety-guard.sh" 2>/dev/null)
}
sg_case() {
  desc=$1; want=$2; body=$3
  out=$(sg_run "$body")
  case "$out" in *'"deny"'*) got=deny ;; *) got=allow ;; esac
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); printf '  ok    %-44s %s\n' "$desc" "$got"
  else fail=$((fail + 1)); printf '  FAIL  %-44s (기대 %s, 실제 %s)\n' "$desc" "$want" "$got"; fi
}
sg_bash() { sg_case "$1" "$2" "$(sgpay Bash command "$3")"; }

# ── 재귀 삭제: 위험한 대상만 막는다 ──
sg_bash "rm -rf /"                            deny  'rm -rf /'
sg_bash "rm -rf ~"                            deny  'rm -rf ~'
sg_bash "rm -rf ~/x (프로젝트 밖)"            deny  'rm -rf ~/x'
sg_bash "rm -fr . (프로젝트 루트)"            deny  'rm -fr .'
sg_bash "rm -rf .. (루트의 상위)"             deny  'rm -rf ..'
sg_bash "절대경로로 적은 프로젝트 루트"       deny  "rm -rf $SG"
sg_bash "rm -rf .git"                         deny  'rm -rf .git'
sg_bash "rm -rf * (루트에서의 맨 글로브)"     deny  'rm -rf *'
sg_bash "rm -r --recursive 긴 옵션"           deny  'rm --recursive --force /usr/local'
sg_bash "sudo rm -rf /usr"                    deny  'sudo rm -rf /usr'
sg_bash "bash -c 안의 rm -rf /"               deny  'bash -c "rm -rf /"'
sg_bash "cd / 뒤의 상대경로"                  deny  'cd / && rm -rf usr'
sg_bash "여러 대상 중 하나만 위험해도"        deny  'rm -rf build /usr/local'
sg_bash "rm -rf build 는 산출물이다"          allow 'rm -rf build'
sg_bash "rm -rf build/* (하위에서의 글로브)"  allow 'rm -rf build/*'
sg_bash "절대경로로 적은 프로젝트 안"         allow "rm -rf $SG/build/tmp"
sg_bash "rm -rf /tmp/x 는 임시 경로다"        allow 'rm -rf /tmp/sheetview-x'
sg_bash "변수가 든 대상은 판단 불가 -> 통과"  allow 'rm -rf "$D"'
sg_bash "재귀가 아닌 rm"                      allow 'rm -f a.txt'
sg_bash "인자 속 글자는 명령이 아니다"        allow 'grep -rn "rm -rf /" README.md'

# ── 강제 푸시: --force-with-lease 는 남의 커밋을 덮지 않으므로 통과시킨다 ──
sg_bash "git push --force"                    deny  'git push --force'
sg_bash "git push -f origin x"                deny  'git push -f origin x'
sg_bash "git -C . push -fu origin x"          deny  'git -C . push -fu origin x'
sg_bash "+refspec 도 강제 푸시다"             deny  'git push origin +main'
sg_bash "git add && git push --force"         deny  'git add -A && git push --force origin x'
sg_bash "git push"                            allow 'git push'
sg_bash "git push --force-with-lease"         allow 'git push --force-with-lease origin x'
sg_bash "git push -u origin x"                allow 'git push -u origin x'
sg_bash "git commit -m 속의 --force"          allow 'git commit -m "push --force 를 막는다"'

# ── .env: 읽는 툴과 읽는 명령만 막는다 ──
sg_case "Read .env"                           deny  "$(sgpay Read file_path "$SG/.env")"
sg_case "Read .env.local"                     deny  "$(sgpay Read file_path .env.local)"
sg_case "Grep path=.env"                      deny  "$(sgpay Grep pattern TOKEN path .env)"
sg_case "Grep glob=.env*"                     deny  "$(sgpay Grep pattern TOKEN glob '.env*')"
sg_case "MCP 로 .env 열기"                    deny  "$(sgpay mcp__webstorm__get_file_text_by_path pathInProject .env)"
sg_bash "cat .env"                            deny  'cat .env'
sg_bash "source .env"                         deny  'set -a; . ./.env; set +a'
sg_bash "git add -f .env"                     deny  'git add -f .env'
sg_bash "cp .env 밖으로"                      deny  'cp .env /tmp/sheetview-x'
sg_bash "입력 리다이렉션으로 읽기"            deny  'wc -l < .env'
sg_bash "--env-file=.env"                     deny  'docker run --env-file=.env img'
sg_case "Read .env.example 은 양식이다"       allow "$(sgpay Read file_path .env.example)"
sg_case "이름이 비슷한 소스 파일"             allow "$(sgpay Read file_path src/main/environment.kt)"
sg_case "Grep 으로 .gitignore 에서 찾기"      allow "$(sgpay Grep pattern '\.env' path .gitignore)"
sg_case "Write .env 는 읽기가 아니다"         allow "$(sgpay Write file_path .env content 'CLAUDE_CODE_OAUTH_TOKEN=')"
sg_bash "ls -la .env"                         allow 'ls -la .env'
sg_bash "[ -f .env ]"                         allow '[ -f .env ] && echo yes'
sg_bash "문서화된 흐름: gh secret set -f"     allow 'gh secret set -f .env --repo werwer748/excel-viewer'
sg_bash "cp .env.example .env"                allow 'cp .env.example .env'
sg_bash "echo .env >> .gitignore"             allow 'echo .env >> .gitignore'
sg_bash "git check-ignore .env"               allow 'git check-ignore -v .env'

# ── 비밀을 대화에 찍는 명령 ──
sg_bash "printenv (전부)"                     deny  'printenv'
sg_bash "env (전부)"                          deny  'env | sort'
sg_bash "printenv GH_TOKEN"                   deny  'printenv GH_TOKEN'
sg_bash "echo \$…TOKEN"                       deny  'echo $CLAUDE_CODE_OAUTH_TOKEN'
sg_bash "echo \${…API_KEY}"                   deny  'echo "key=${ANTHROPIC_API_KEY}"'
sg_bash "printenv JAVA_HOME"                  allow 'printenv JAVA_HOME'
sg_bash "env 는 래퍼로도 쓴다"                allow 'env FOO=1 ./gradlew test'
sg_bash "echo \$HOME"                         allow 'echo $HOME'
sg_bash "쓰는 것은 찍는 것이 아니다"          allow 'curl -sH "Authorization: Bearer $GH_TOKEN" https://api.github.com/user'

# ── 키 리터럴 ──
sg_case "Write 내용에 키"                     deny  "$(sgpay Write file_path src/main/Config.kt content "val key = \"$FAKE_KEY\"")"
sg_case "Edit new_string 에 키"               deny  "$(sgpay Edit file_path README.md old_string x new_string "$FAKE_KEY")"
sg_bash "Bash 명령에 키"                      deny  "gh pr comment 1 --body $FAKE_KEY"
sg_case "MultiEdit 의 중첩된 값에 키"         deny  "$(python3 -c 'import json,sys
print(json.dumps({"tool_name":"MultiEdit","cwd":sys.argv[1],"tool_input":{"file_path":"README.md",
  "edits":[{"old_string":"x","new_string":sys.argv[2]}]}}))' "$SG" "$FAKE_KEY")"
sg_case "키를 지우는 Edit 는 통과해야 한다"   allow "$(sgpay Edit file_path README.md old_string "$FAKE_KEY" new_string '<토큰>')"
sg_case "자리표시는 키가 아니다"              allow "$(sgpay Write file_path README.md content 'ANTHROPIC_API_KEY=sk-ant-...')"

# ── 판단 불가는 통과 ──
sg_case "깨진 JSON"                           allow 'not json at all'
sg_case "빈 payload"                          allow ''
sg_case "tool_input 이 없다"                  allow '{"tool_name":"Bash"}'
sg_bash "깨진 따옴표는 판단 불가 -> 통과"     allow 'rm -rf "/'

# 마커가 없으면 이 저장소가 아니다 — 다른 훅들처럼 조용히 통과한다.
NM=$(mktemp -d)
case "$(sg_run "$(sgpay Bash command 'rm -rf /')" "$NM")" in
  *'"deny"'*) bad "마커 없는 디렉터리에서 막았다" ;;
  *) ok "마커 없는 디렉터리에서는 통과" ;;
esac
rm -rf "$NM"

# 거절 사유는 대화에 그대로 실린다. 키 값을 되읊으면 훅이 유출 경로가 된다.
out=$(sg_run "$(sgpay Bash command "echo $FAKE_KEY > src/main/x.txt")")
case "$out" in
  *"$FAKE_KEY"*) bad "거절 사유에 키 값이 실렸다" ;;
  *'"deny"'*) ok "거절 사유에 키 값을 싣지 않는다" ;;
  *) bad "키가 든 명령을 막지 않았다" ;;
esac

rm -rf "$SG"

# ─────────────────────────────────────────────────────────────────────────────
echo "branch-guard.sh — main 에서 소스를 고치면 한 번 묻는다"

BR=$(mktemp -d)
touch "$BR/gradlew"; mkdir -p "$BR/src/main/kotlin/dev/hugo/sheetview" "$BR/.claude"
( cd "$BR" && git init -q -b main . && git config user.email t@t && git config user.name t &&
  git commit -q --allow-empty -m init ) >/dev/null 2>&1

br_case() {
  desc=$1; want=$2; body=$3
  rm -f "$BR/.claude/.branch-ack"
  out=$(printf '%s' "$body" | (cd "$BR" && CLAUDE_PROJECT_DIR="$BR" sh "$PLUGIN_DIR/hooks/branch-guard.sh" 2>/dev/null))
  case "$out" in *'"ask"'*) got=ask ;; *) got=silent ;; esac
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); printf '  ok    %-44s %s\n' "$desc" "$got"
  else fail=$((fail + 1)); printf '  FAIL  %-44s (기대 %s, 실제 %s)\n' "$desc" "$want" "$got"; fi
}

br_case "main 에서 src/main 수정" ask "$(payload Edit "src/main/kotlin/dev/hugo/sheetview/format/X.kt")"
br_case "main 에서 src/test 수정" ask "$(payload Write "src/test/kotlin/dev/hugo/sheetview/XTest.kt")"
br_case "main 에서 리다이렉션으로 src 쓰기" ask "$(bashpay 'echo x > src/main/kotlin/dev/hugo/sheetview/X.kt')"
br_case "main 이어도 문서 수정은 묻지 않는다" silent "$(payload Edit "README.md")"
br_case "main 이어도 빌드 파일은 묻지 않는다" silent "$(payload Edit "build.gradle.kts")"
br_case "읽기만 하는 명령은 묻지 않는다" silent "$(bashpay 'grep -rn Foo src/main/kotlin')"

# 같은 브랜치에서 두 번 묻지 않는다 (한 번 묻고 표시를 남긴다).
rm -f "$BR/.claude/.branch-ack"
printf '%s' "$(payload Edit "src/main/kotlin/dev/hugo/sheetview/format/X.kt")" |
  (cd "$BR" && CLAUDE_PROJECT_DIR="$BR" sh "$PLUGIN_DIR/hooks/branch-guard.sh" >/dev/null 2>&1)
out2=$(printf '%s' "$(payload Edit "src/main/kotlin/dev/hugo/sheetview/format/Y.kt")" |
  (cd "$BR" && CLAUDE_PROJECT_DIR="$BR" sh "$PLUGIN_DIR/hooks/branch-guard.sh" 2>/dev/null))
case "$out2" in
  *'"ask"'*) bad "같은 브랜치에서 두 번 물었다" ;;
  *) ok "같은 브랜치에서는 한 번만 묻는다" ;;
esac

# 작업 브랜치로 옮기면 묻지 않는다.
( cd "$BR" && git switch -q -c work ) >/dev/null 2>&1
out3=$(printf '%s' "$(payload Edit "src/main/kotlin/dev/hugo/sheetview/format/Z.kt")" |
  (cd "$BR" && CLAUDE_PROJECT_DIR="$BR" sh "$PLUGIN_DIR/hooks/branch-guard.sh" 2>/dev/null))
case "$out3" in
  *'"ask"'*) bad "작업 브랜치인데 물었다" ;;
  *) ok "작업 브랜치에서는 묻지 않는다" ;;
esac

rm -rf "$BR"

echo "pre-commit-check.sh — 커밋을 만드는 명령을 알아보는가"

# 합성 프로젝트에 **항상 실패하는** check.sh 를 둔다. 그래야 "탐지됐다"(exit 2)와
# "탐지 안 됐다"(exit 0)가 구별된다. 진짜 check.sh 를 돌리면 느리고, 통과해 버리면
# 두 경우가 똑같이 exit 0 이라 아무것도 검증하지 못한다.
PC=$(mktemp -d)
touch "$PC/gradlew"; mkdir -p "$PC/src/main/kotlin/dev/hugo/sheetview" "$PC/scripts"
printf '#!/bin/sh\necho "가짜 검사 실패"\nexit 1\n' > "$PC/scripts/check.sh"
chmod +x "$PC/scripts/check.sh"

pc_case() {
  desc=$1; want=$2; body=$3
  printf '%s' "$body" | (cd "$PC" && CLAUDE_PROJECT_DIR="$PC" sh "$PLUGIN_DIR/hooks/pre-commit-check.sh" >/dev/null 2>&1)
  got=$?
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); printf '  ok    %-44s exit %s\n' "$desc" "$got"
  else fail=$((fail + 1)); printf '  FAIL  %-44s (기대 %s, 실제 %s)\n' "$desc" "$want" "$got"; fi
}

pc_case "git commit" 2 "$(bashpay 'git commit -m x')"
pc_case "git  commit (공백 둘) — 예전엔 통과했다" 2 "$(bashpay 'git  commit -m x')"
pc_case "git add -A && git commit" 2 "$(bashpay 'git add -A && git commit -m x')"
pc_case "git -c user.name=x commit" 2 "$(bashpay 'git -c user.name=x commit -m y')"
pc_case "git merge (커밋을 만든다)" 2 "$(bashpay 'git merge feature')"
pc_case "git cherry-pick (커밋을 만든다)" 2 "$(bashpay 'git cherry-pick abc123')"
pc_case "git revert (커밋을 만든다)" 2 "$(bashpay 'git revert HEAD')"
pc_case "git rebase --continue" 2 "$(bashpay 'git rebase --continue')"
pc_case "git status 는 아니다" 0 "$(bashpay 'git status --short')"
pc_case "git log 는 아니다" 0 "$(bashpay 'git log --oneline')"
pc_case "gradlew test 는 아니다" 0 "$(bashpay './gradlew test')"
pc_case "command 없는 payload" 0 '{"tool_name":"Bash","tool_input":{}}'
pc_case "깨진 JSON" 0 'not json'

# description 에만 "git commit" 이 있으면 돌지 않아야 한다 (예전엔 풀 빌드가 돌았다).
pc_case "description 에만 있는 경우 -> 안 돈다" 0 \
  '{"tool_name":"Bash","tool_input":{"command":"ls","description":"git commit 준비"}}'

rm -rf "$PC"

echo "pre-commit-check.sh — 커밋에 실릴 파일에 키가 있는가"

# 이번에는 check.sh 가 **항상 통과**한다. 그래야 exit 2 가 키 스캔에서 나온 것임이 구별된다.
PK=$(synth_project)
mkdir -p "$PK/scripts"
printf '#!/bin/sh\nexit 0\n' > "$PK/scripts/check.sh"
chmod +x "$PK/scripts/check.sh"
printf '.env\n' > "$PK/.gitignore"
( cd "$PK" && git init -q -b main . && git config user.email t@t && git config user.name t &&
  git add -A && git commit -q -m init ) >/dev/null 2>&1

# `git add -A && git commit` 은 Bash 호출 하나다 — 훅이 돌 때 인덱스는 아직 비어 있다.
pk_case() {
  desc=$1; want=$2
  out=$(printf '%s' "$(bashpay 'git add -A && git commit -m x')" |
    (cd "$PK" && CLAUDE_PROJECT_DIR="$PK" sh "$PLUGIN_DIR/hooks/pre-commit-check.sh" 2>&1))
  got=$?
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); printf '  ok    %-44s exit %s\n' "$desc" "$got"
  else fail=$((fail + 1)); printf '  FAIL  %-44s (기대 %s, 실제 %s)\n' "$desc" "$want" "$got"; fi
}

pk_case "키가 없으면 통과" 0
printf 'CLAUDE_CODE_OAUTH_TOKEN=%s\n' "$FAKE_KEY" > "$PK/.env"
pk_case "gitignore 된 .env 는 커밋 대상이 아니다" 0
printf 'val key = "%s"\n' "$FAKE_KEY" > "$PK/Leak.kt"
pk_case "미추적 파일의 키 -> 차단" 2
case "$out" in
  *"$FAKE_KEY"*) bad "차단 메시지에 키 값이 실렸다" ;;
  *Leak.kt*) ok "차단 메시지는 파일 이름만 말한다" ;;
  *) bad "차단 메시지에 파일 이름이 없다" ;;
esac
( cd "$PK" && git add Leak.kt ) >/dev/null 2>&1
printf 'val key = ""\n' > "$PK/Leak.kt"
pk_case "작업 트리에서 지워도 인덱스에 남은 키 -> 차단" 2

rm -rf "$PK"

printf '\n%s개 통과 · %s개 실패\n' "$pass" "$fail"
[ "$fail" = 0 ] || exit 1
