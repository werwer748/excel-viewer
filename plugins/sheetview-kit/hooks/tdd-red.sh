#!/bin/sh
# PostToolUse(Write|Edit|MultiEdit) 훅 — 새 테스트는 한 번은 실패해야 한다.
#
# 처음부터 통과하는 테스트는 아무것도 보장하지 않는다. 이미 있는 동작을 중복 검증하거나
# 단정이 비어 있을 뿐이다. 그래서 새로 만든 테스트 파일은 red 를 거쳤는지 확인한다.
# (tdd-guard.sh 가 PreToolUse 에서 신규 테스트 경로를 .claude/.tdd-new 에 적어 둔다.)
#
# 기존 테스트 파일에 케이스를 추가하는 경우는 green 이어도 막지 않는다 — 그 파일의
# 다른 테스트들이 이미 통과하고 있어서 red/green 을 파일 단위로 판정할 수 없다.
#
# 판단이 안 되면 통과시킨다. 훅의 실패가 작업을 막는 실패로 번져선 안 된다.
set -u

payload=$(cat)
# 프로젝트 루트는 _common.sh 가 마커(gradlew + 소스 루트)로 찾는다. 스크립트 위치에서
# 역산하지 않는다 — 이 파일은 플러그인 설치 경로에 있고 프로젝트와 무관하다.
# 상대경로가 한 칸 어긋나면 아래 case 패턴이 전부 빗나가 훅이 조용히 무력화된다.
. "$(dirname -- "$0")/_common.sh" 2>/dev/null || exit 0
PROJECT_DIR=$(sheetview_project_dir) || exit 0
TEST_ROOT="$SHEETVIEW_TEST_ROOT"
NEW_TESTS="$PROJECT_DIR/.claude/.tdd-new"

target=$(printf '%s' "$payload" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
ti = d.get("tool_input") or {}
if not isinstance(ti, dict):
    sys.exit(1)
p = ti.get("file_path") or ""
if not isinstance(p, str):
    sys.exit(1)
print(p)
' 2>/dev/null) || exit 0
[ -n "$target" ] || exit 0

case "$target" in
  "$PROJECT_DIR"/*) rel=${target#"$PROJECT_DIR"/} ;;
  /*)               exit 0 ;;
  *)                rel=$target ;;
esac

case "$rel" in
  "$TEST_ROOT"/*Test.kt) ;;
  *) exit 0 ;;
esac

# 이 파일이 방금 새로 만들어진 것인가? 표시를 읽고 지운다 (한 번만 판정한다).
was_new=no
if [ -f "$NEW_TESTS" ] && grep -qxF "$rel" "$NEW_TESTS" 2>/dev/null; then
  was_new=yes
  # grep 의 종료 코드를 조건으로 쓰면 안 된다: 남는 줄이 0개면 grep 이 1을 내서
  # mv 가 실행되지 않고 표시가 영원히 남는다 (표시가 하나뿐인 정상 경로가 바로 그 경우다).
  grep -vxF "$rel" "$NEW_TESTS" > "$NEW_TESTS.tmp" 2>/dev/null
  mv -f "$NEW_TESTS.tmp" "$NEW_TESTS" 2>/dev/null || rm -f "$NEW_TESTS.tmp"
fi
[ "$was_new" = yes ] || exit 0

[ -x "$PROJECT_DIR/gradlew" ] || exit 0

# runIde 같은 다른 Gradle 빌드가 프로젝트 락을 잡고 있으면 180초를 통째로 날린다.
# 그럴 때는 검사를 건너뛰되, 건너뛴 사실을 알린다.
if pgrep -f 'runIde' >/dev/null 2>&1; then
  echo "TDD: 다른 Gradle 빌드(runIde)가 돌고 있어 red 확인을 건너뛰었습니다. 직접 확인하세요." >&2
  exit 0
fi

pkg=$(sed -n 's/^package[[:space:]]\+\([A-Za-z0-9_.]*\).*/\1/p' "$PROJECT_DIR/$rel" 2>/dev/null | head -1)
class=$(basename "$rel" .kt)
if [ -n "$pkg" ]; then FQCN="$pkg.$class"; else FQCN="$class"; fi

out=$(cd "$PROJECT_DIR" && ./gradlew test --tests "$FQCN" --console=plain --quiet 2>&1)
status=$?

# 컴파일 실패는 red 가 아니다. 테스트가 아예 돌지 못한 것이라 그대로 돌려준다.
case "$out" in
  *"compileTestKotlin FAILED"*|*"compileKotlin FAILED"*|*"Compilation error"*)
    { echo "TDD: 테스트가 컴파일되지 않습니다 ($FQCN)."; echo; echo "$out"; } >&2
    exit 2
    ;;
esac

if [ "$status" -ne 0 ]; then
  case "$out" in
    *"No tests found for given includes"*)
      echo "TDD: $FQCN 에 실행할 @Test 가 없습니다. 실패하는 테스트를 먼저 하나 넣으세요." >&2
      exit 2
      ;;
  esac
  # 여기까지 오면 gradle 이 0 이 아닌 코드로 끝났다는 것뿐이다. 그게 곧 "테스트가 실패했다"는
  # 아니다 — 데몬 크래시·락 타임아웃·OOM·JDK 미발견도 전부 여기로 온다. 그것들을 RED 로
  # 인정하면 **환경 고장이 곧 게이트 통과**가 된다. 실제 실패의 흔적을 확인한다.
  results="$PROJECT_DIR/build/test-results/test"
  failed=""
  if [ -d "$results" ]; then
    failed=$(grep -l 'failures="[1-9]\|errors="[1-9]' "$results"/TEST-*.xml 2>/dev/null | head -1)
  fi
  case "$out" in
    *"tests completed"*|*" FAILED"*) failed="${failed:-marker}" ;;
  esac
  if [ -z "$failed" ]; then
    {
      echo "TDD: $FQCN 을 돌렸지만 실패했는지 판정할 수 없습니다."
      echo "  gradle 이 0 이 아닌 코드로 끝났는데 테스트 실패의 흔적이 없습니다."
      echo "  (데몬 크래시·락 대기·OOM 일 수 있습니다. 환경 문제를 RED 로 세지 않습니다.)"
      echo
      echo "$out" | tail -20
    } >&2
    exit 2
  fi
  echo "TDD: $FQCN RED 확인 — 이제 통과시키는 구현을 쓰세요."
  exit 0
fi

{
  echo "TDD: 새 테스트가 처음부터 통과합니다 ($FQCN). red 단계가 없었습니다."
  echo
  echo "  새 테스트는 아직 구현되지 않은 동작을 검증해야 합니다."
  echo "  지금 통과한다면 (a) 이미 있는 동작을 중복 검증하거나 (b) 단정이 비어 있을 가능성이 큽니다."
  echo "  둘 다 아니라면 (예: 이미 고쳐진 버그의 회귀 테스트) 그 이유를 한 줄로 말하고 진행하세요."
} >&2
exit 2
