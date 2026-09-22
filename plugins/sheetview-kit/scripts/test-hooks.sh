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
SRC="$PROJECT_DIR/src/main/kotlin/dev/hugo/sheetview"
TST="$PROJECT_DIR/src/test/kotlin/dev/hugo/sheetview"

pass=0; fail=0
TMP_TEST=""
cleanup() {
  [ -n "$TMP_TEST" ] && rm -f "$TMP_TEST"
  rm -f "$NEW_TESTS.tmp"
  # 중간에 끊겨도 원본 .tdd-new 를 돌려놓는다. 안 그러면 .tdd-new.saved 가
  # 워킹트리에 유령으로 남는다 (gitignore 되지 않는 이름이다).
  [ -f "$NEW_TESTS.saved" ] && mv -f "$NEW_TESTS.saved" "$NEW_TESTS"
  return 0
}
trap cleanup EXIT INT TERM

# .tdd-new 를 건드리므로 원본을 잠시 치워 둔다.
[ -f "$NEW_TESTS" ] && mv "$NEW_TESTS" "$NEW_TESTS.saved"

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
  pass=$((pass + 1)); printf '  ok    마커 없는 디렉터리에서는 관여 안 함\n'
else
  fail=$((fail + 1)); printf '  FAIL  마커 없는 디렉터리에서 차단했다\n'
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

echo "tdd-red.sh"

run "$REDH" 0 "본체 파일 -> 관여 안 함"        "$(payload Write "$SRC/format/XlsxReader.kt")"
run "$REDH" 0 "테스트 리소스 -> 관여 안 함"     "$(payload Write "$PROJECT_DIR/src/test/resources/fixtures/cp949.csv")"
run "$REDH" 0 "신규 표시 없는 기존 테스트 수정" "$(payload Edit "$TST/XlsxReaderTest.kt")"
run "$REDH" 0 "깨진 JSON"                     'not json'

# guard 가 남긴 신규 표시를 red 훅이 읽고 지우는지. gradle 을 타지 않게 없는 클래스를 쓴다.
printf 'src/test/kotlin/dev/hugo/sheetview/GhostTest.kt\n' > "$NEW_TESTS"
printf '%s' "$(payload Write "$TST/GhostTest.kt")" | sh "$REDH" >/dev/null 2>&1
if [ -s "$NEW_TESTS" ]; then
  fail=$((fail + 1)); printf '  FAIL  신규 표시를 소비하지 않았다 (.tdd-new 가 그대로다)\n'
else
  pass=$((pass + 1)); printf '  ok    신규 표시를 읽고 지웠다\n'
fi
rm -f "$NEW_TESTS"
[ -f "$NEW_TESTS.saved" ] && mv "$NEW_TESTS.saved" "$NEW_TESTS"

# ─────────────────────────────────────────────────────────────────────────────
# 사이클 훅은 .cycle-state 를 읽고 쓴다. 저장소를 오염시키지 않도록 합성 프로젝트에서 돈다.
CYC=$(mktemp -d)
touch "$CYC/gradlew"
mkdir -p "$CYC/src/main/kotlin/dev/hugo/sheetview" "$CYC/.claude"

cyc_state() { rm -f "$CYC/.claude/.cycle-state"
  for kv in "$@"; do printf '%s\n' "$kv" >> "$CYC/.claude/.cycle-state"; done; }
cyc_get() { sed -n "s/^$1=//p" "$CYC/.claude/.cycle-state" 2>/dev/null; }

echo "cycle-stop.sh — 세션을 가두지 않는 탈출구"

stop_case() {
  desc=$1; want=$2; shift 2
  if [ "${1:-}" = "NOSTATE" ]; then rm -f "$CYC/.claude/.cycle-state"; else cyc_state "$@"; fi
  (cd "$CYC" && CLAUDE_PROJECT_DIR="$CYC" sh "$PLUGIN_DIR/hooks/cycle-stop.sh" >/dev/null 2>&1)
  got=$?
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); printf '  ok    %s\n' "$desc"
  else fail=$((fail + 1)); printf '  FAIL  %s  (기대 exit %s, 실제 %s)\n' "$desc" "$want" "$got"; fi
}

stop_case "사이클이 없으면 관여 안 함" 0 NOSTATE
stop_case "리뷰·검증 둘 다 끝났으면 통과" 0 "review=clean" "verify=clean"
stop_case "되돌리기 한도에 닿으면 포기하고 통과" 0 "review=pending" "verify=pending" "stop_blocked=3"
stop_case "라운드 상한 + 🔴 남으면 사람 부르고 통과" 0 "review=red:2" "verify=pending" "round=3"
stop_case "값이 깨져도 갇히지 않는다" 0 "review=clean" "verify=clean" "stop_blocked=abc" "round=xyz"
stop_case "아직 리뷰 전이면 되돌림" 2 "review=pending" "verify=pending"
stop_case "리뷰만 끝났으면 되돌림" 2 "review=clean" "verify=pending"
stop_case "🔴 가 남았으면 되돌림" 2 "review=red:3" "verify=pending"
stop_case "판정 불가는 통과가 아니다" 2 "review=unknown" "verify=pending"

# 무한루프 방지의 근거: 되돌릴 때마다 카운터가 올라야 한다.
cyc_state "review=pending" "verify=pending"
_n=0
for _i in 1 2 3 4; do
  (cd "$CYC" && CLAUDE_PROJECT_DIR="$CYC" sh "$PLUGIN_DIR/hooks/cycle-stop.sh" >/dev/null 2>&1)
  [ $? = 0 ] && _n=$((_n + 1))
done
if [ "$_n" = 1 ]; then pass=$((pass + 1)); printf '  ok    4회 되돌린 뒤 스스로 포기한다\n'
else fail=$((fail + 1)); printf '  FAIL  포기하지 않았다 (통과 횟수 %s, 기대 1)\n' "$_n"; fi

echo "cycle-review.sh — 리뷰 보고를 기계가 읽는다"

agentpay() {
  python3 -c 'import json,sys;print(json.dumps({"agent_type":sys.argv[1],"last_assistant_message":sys.argv[2]}))' "$1" "$2"
}
rv_case() {
  desc=$1; want=$2; body=$3
  cyc_state "review=pending" "verify=pending" "round=0"
  printf '%s' "$body" | (cd "$CYC" && CLAUDE_PROJECT_DIR="$CYC" sh "$PLUGIN_DIR/hooks/cycle-review.sh" >/dev/null 2>&1)
  got=$(cyc_get review)
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); printf '  ok    %s -> %s\n' "$desc" "$got"
  else fail=$((fail + 1)); printf '  FAIL  %s  (기대 %s, 실제 %s)\n' "$desc" "$want" "$got"; fi
}

rv_case "🔴 섹션의 번호 항목을 센다" "red:3" \
  "$(agentpay sheet-reviewer '### 🔴 고치고 커밋해야 함
1. **하나** — `a.kt:1`
2. **둘** — `b.kt:2`
3. **셋** — `c.kt:3`

### 🟡 고치면 좋음
1. **사소** — `d.kt`')"
rv_case "🔴 섹션이 비어 있으면 clean" "clean" \
  "$(agentpay sheet-reviewer '### 🔴 고치고 커밋해야 함
없음

### 🟢 참고
- 메모')"
rv_case "🔴 가 아예 없으면 clean" "clean" \
  "$(agentpay sheet-reviewer '### 🟢 참고
- 문제 없습니다')"
rv_case "형식을 벗어나면 unknown (통과로 밀지 않는다)" "unknown" \
  "$(agentpay sheet-reviewer '리뷰했는데 🔴 문제가 좀 있습니다')"
rv_case "빈 보고는 unknown" "unknown" "$(agentpay sheet-reviewer '')"
rv_case "깨진 payload 는 unknown" "unknown" 'not json'

# 🔴 면 라운드가 올라야 한다 — 이게 반복의 근거다.
cyc_state "review=pending" "verify=pending" "round=1"
printf '%s' "$(agentpay sheet-reviewer '### 🔴 고치고 커밋해야 함
1. **x** — `a.kt`')" | (cd "$CYC" && CLAUDE_PROJECT_DIR="$CYC" sh "$PLUGIN_DIR/hooks/cycle-review.sh" >/dev/null 2>&1)
if [ "$(cyc_get round)" = "2" ]; then pass=$((pass + 1)); printf '  ok    🔴 이면 라운드가 오른다 (1 -> 2)\n'
else fail=$((fail + 1)); printf '  FAIL  라운드가 오르지 않았다 (%s)\n' "$(cyc_get round)"; fi

echo "cycle-verify.sh — 판정 줄만 본다"

vf_case() {
  desc=$1; want=$2; body=$3
  cyc_state "review=clean" "verify=pending"
  printf '%s' "$body" | (cd "$CYC" && CLAUDE_PROJECT_DIR="$CYC" sh "$PLUGIN_DIR/hooks/cycle-verify.sh" >/dev/null 2>&1)
  got=$(cyc_get verify)
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); printf '  ok    %s -> %s\n' "$desc" "$got"
  else fail=$((fail + 1)); printf '  FAIL  %s  (기대 %s, 실제 %s)\n' "$desc" "$want" "$got"; fi
}
vf_case "통과 판정" "clean" "$(agentpay sandbox-runner '## 샌드박스 로그
확인했고 문제없던 것: 다수

샌드박스 확인 통과')"
vf_case "🔴 N건 판정" "red:2" "$(agentpay sandbox-runner '### 🔴
1. x
2. y

🔴 2건 — 샌드박스에서 실제로 깨졌다')"
vf_case "본문의 🔴 는 판정으로 오인하지 않는다" "clean" "$(agentpay sandbox-runner '## 보고
🔴 는 이런 뜻이라고 설명만 하는 줄

샌드박스 확인 통과')"

# 사이클이 없으면 상태 파일을 만들지도 않아야 한다.
rm -f "$CYC/.claude/.cycle-state"
printf '%s' "$(agentpay sheet-reviewer '### 🔴 고치고 커밋해야 함
1. **x** — `a.kt`')" | (cd "$CYC" && CLAUDE_PROJECT_DIR="$CYC" sh "$PLUGIN_DIR/hooks/cycle-review.sh" >/dev/null 2>&1)
if [ -f "$CYC/.claude/.cycle-state" ]; then
  fail=$((fail + 1)); printf '  FAIL  사이클이 없는데 상태 파일을 만들었다\n'
else
  pass=$((pass + 1)); printf '  ok    사이클이 없으면 아무 일도 하지 않는다\n'
fi

echo "agent-guard.sh — 읽기 전용 에이전트를 실제로 읽기 전용으로"

ag_case() {
  desc=$1; want=$2; cmd=$3; mode=${4:-}
  out=$(printf '%s' "$(bashpay "$cmd")" | sh "$PLUGIN_DIR/hooks/agent-guard.sh" $mode 2>/dev/null)
  case "$out" in *'"deny"'*) got=deny ;; *) got=allow ;; esac
  if [ "$got" = "$want" ]; then pass=$((pass + 1)); printf '  ok    %-40s %s\n' "$desc" "$got"
  else fail=$((fail + 1)); printf '  FAIL  %-40s (기대 %s, 실제 %s)\n' "$desc" "$want" "$got"; fi
}
ag_case "sed -i 로 소스 수정" deny 'sed -i "" s/a/b/ src/main/x.kt'
ag_case "git checkout -- 로 되돌리기" deny 'git checkout -- .'
ag_case "리다이렉션으로 쓰기" deny 'echo x > src/main/x.kt'
ag_case "gradlew clean" deny './gradlew clean'
ag_case "rm" deny 'rm -rf build'
ag_case "gradlew test 는 검사다" allow './gradlew test'
ag_case "git status 는 읽기다" allow 'git status --short'
ag_case "grep 은 읽기다" allow 'grep -rn Foo src/main/kotlin'
ag_case "/tmp 로 내보내기는 무해" allow './gradlew test > /tmp/out.txt'
ag_case "runIde (샌드박스 러너)" allow './gradlew runIde'
ag_case "runIde (리뷰어는 금지)" deny './gradlew runIde' no-runide

rm -rf "$CYC"

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
  *'"ask"'*) fail=$((fail + 1)); printf '  FAIL  같은 브랜치에서 두 번 물었다\n' ;;
  *) pass=$((pass + 1)); printf '  ok    같은 브랜치에서는 한 번만 묻는다\n' ;;
esac

# 작업 브랜치로 옮기면 묻지 않는다.
( cd "$BR" && git switch -q -c work ) >/dev/null 2>&1
out3=$(printf '%s' "$(payload Edit "src/main/kotlin/dev/hugo/sheetview/format/Z.kt")" |
  (cd "$BR" && CLAUDE_PROJECT_DIR="$BR" sh "$PLUGIN_DIR/hooks/branch-guard.sh" 2>/dev/null))
case "$out3" in
  *'"ask"'*) fail=$((fail + 1)); printf '  FAIL  작업 브랜치인데 물었다\n' ;;
  *) pass=$((pass + 1)); printf '  ok    작업 브랜치에서는 묻지 않는다\n' ;;
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

printf '\n%s개 통과 · %s개 실패\n' "$pass" "$fail"
[ "$fail" = 0 ] || exit 1
