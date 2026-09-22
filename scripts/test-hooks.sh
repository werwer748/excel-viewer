#!/bin/sh
# TDD 훅 자체의 테스트. 훅이 틀리면 전체 작업이 막히거나, 더 나쁘게는 조용히 통과한다.
#
# 특히 마지막 케이스들이 중요하다: **훅의 실패가 작업을 막는 실패로 번져선 안 된다.**
# payload 가 깨졌거나 경로를 모르겠으면 통과(exit 0)여야 한다.
set -u

cd "$(dirname "$0")/.." || exit 1
PROJECT_DIR=$(pwd)
GUARD=".claude/hooks/tdd-guard.sh"
REDH=".claude/hooks/tdd-red.sh"
NEW_TESTS=".claude/.tdd-new"

pass=0; fail=0
TMP_TEST=""
cleanup() { [ -n "$TMP_TEST" ] && rm -f "$TMP_TEST"; rm -f "$NEW_TESTS.bak"; }
trap cleanup EXIT INT TERM

# .tdd-new 를 건드리므로 원본을 잠시 치워 둔다.
[ -f "$NEW_TESTS" ] && mv "$NEW_TESTS" "$NEW_TESTS.bak"

# run <스크립트> <기대코드> <설명> <payload>
run() {
  script=$1; want=$2; desc=$3; body=$4
  out=$(printf '%s' "$body" | sh "$script" 2>&1)
  got=$?
  if [ "$got" = "$want" ]; then
    pass=$((pass + 1))
    printf '  ok    %s\n' "$desc"
  else
    fail=$((fail + 1))
    printf '  FAIL  %s  (기대 exit %s, 실제 %s)\n' "$desc" "$want" "$got"
    printf '%s\n' "$out" | sed 's/^/          /'
  fi
}

payload() { printf '{"tool_name":"%s","tool_input":{"file_path":"%s"}}' "$1" "$2"; }

echo "tdd-guard.sh"

# 1. 순수 로직 경로의 새 파일, 테스트 없음 -> 막는다.
run "$GUARD" 2 "새 source/HtmlSanitizer.kt, 테스트 없음 -> 차단" \
  "$(payload Write "$PROJECT_DIR/src/main/kotlin/dev/hugo/sheetview/source/HtmlSanitizer.kt")"

# 2. 같은 경로인데 클래스를 언급하는 테스트가 있으면 -> 통과.
TMP_TEST="src/test/kotlin/dev/hugo/sheetview/HookProbeTest.kt"
cat > "$TMP_TEST" <<'KT'
package dev.hugo.sheetview
// test-hooks.sh 가 만든 임시 파일. HtmlSanitizer 를 언급해 훅의 탐색을 확인한다.
class HookProbeTest { fun probe() = "HtmlSanitizer" }
KT
run "$GUARD" 0 "같은 경로, HtmlSanitizer 를 언급하는 테스트 있음 -> 통과" \
  "$(payload Write "$PROJECT_DIR/src/main/kotlin/dev/hugo/sheetview/source/HtmlSanitizer.kt")"
rm -f "$TMP_TEST"; TMP_TEST=""

# 3. 기존 파일 수정은 막지 않는다 (리팩터가 불가능해지면 훅을 우회하게 된다).
run "$GUARD" 0 "기존 format/XlsxReader.kt 수정 -> 통과" \
  "$(payload Edit "$PROJECT_DIR/src/main/kotlin/dev/hugo/sheetview/format/XlsxReader.kt")"

# 4. 면제 목록에 사유와 함께 올라간 경로.
run "$GUARD" 0 "editor/SourcePanel.kt (면제 목록 안) -> 통과" \
  "$(payload Write "$PROJECT_DIR/src/main/kotlin/dev/hugo/sheetview/editor/SourcePanel.kt")"

# 5. editor/ 라도 면제 목록에 없으면 막는다. 여기가 내가 실수한 자리다.
run "$GUARD" 2 "editor/SourceEditorProvider.kt (면제 아님), 테스트 없음 -> 차단" \
  "$(payload Write "$PROJECT_DIR/src/main/kotlin/dev/hugo/sheetview/editor/SourceEditorProvider.kt")"
run "$GUARD" 2 "filetype/SpreadsheetFileTypeDetector 를 지웠다 가정한 신규 -> 차단" \
  "$(payload Write "$PROJECT_DIR/src/main/kotlin/dev/hugo/sheetview/filetype/TextFileTypes.kt")"

# 6. 코틀린 본체가 아니면 관여하지 않는다.
run "$GUARD" 0 "README.md -> 통과"    "$(payload Write "$PROJECT_DIR/README.md")"
run "$GUARD" 0 "plugin.xml -> 통과"   "$(payload Edit  "$PROJECT_DIR/src/main/resources/META-INF/plugin.xml")"
run "$GUARD" 0 "새 테스트 파일 자체 -> 통과" \
  "$(payload Write "$PROJECT_DIR/src/test/kotlin/dev/hugo/sheetview/BrandNewTest.kt")"

# 7. 프로젝트 밖.
run "$GUARD" 0 "프로젝트 밖 절대경로 -> 통과" "$(payload Write "/tmp/somewhere/Else.kt")"

# 8. 훅이 판단할 수 없는 입력은 전부 통과여야 한다.
run "$GUARD" 0 "깨진 JSON -> 통과"        'not json at all'
run "$GUARD" 0 "빈 payload -> 통과"       ''
run "$GUARD" 0 "file_path 없음 -> 통과"   '{"tool_name":"Write","tool_input":{}}'
run "$GUARD" 0 "tool_input 이 배열 -> 통과" '{"tool_name":"Write","tool_input":[]}'
run "$GUARD" 0 "file_path 가 숫자 -> 통과"  '{"tool_name":"Write","tool_input":{"file_path":42}}'

echo "tdd-red.sh"

# 새 테스트 표시가 없으면 아무것도 하지 않는다 (gradle 을 돌리지 않는다).
run "$REDH" 0 "본체 파일 -> 관여 안 함" \
  "$(payload Write "$PROJECT_DIR/src/main/kotlin/dev/hugo/sheetview/format/XlsxReader.kt")"
run "$REDH" 0 "Test.kt 가 아닌 테스트 리소스 -> 관여 안 함" \
  "$(payload Write "$PROJECT_DIR/src/test/resources/fixtures/cp949.csv")"
run "$REDH" 0 "신규 표시 없는 기존 테스트 수정 -> 통과 (green 이어도 막지 않는다)" \
  "$(payload Edit "$PROJECT_DIR/src/test/kotlin/dev/hugo/sheetview/XlsxReaderTest.kt")"
run "$REDH" 0 "깨진 JSON -> 통과" 'not json'

# guard 가 남긴 표시를 red 훅이 읽고 지우는지 (gradle 은 타지 않도록 가짜 경로를 쓴다).
printf 'src/test/kotlin/dev/hugo/sheetview/GhostTest.kt\n' > "$NEW_TESTS"
printf '%s' "$(payload Write "$PROJECT_DIR/src/test/kotlin/dev/hugo/sheetview/GhostTest.kt")" |
  sh "$REDH" >/dev/null 2>&1
if [ -s "$NEW_TESTS" ]; then
  fail=$((fail + 1)); printf '  FAIL  신규 표시를 소비하지 않았다 (.tdd-new 가 그대로다)\n'
else
  pass=$((pass + 1)); printf '  ok    신규 표시를 읽고 지웠다\n'
fi
rm -f "$NEW_TESTS"
[ -f "$NEW_TESTS.bak" ] && mv "$NEW_TESTS.bak" "$NEW_TESTS"

printf '\n%s개 통과 · %s개 실패\n' "$pass" "$fail"
[ "$fail" = 0 ] || exit 1
