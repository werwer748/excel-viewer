#!/bin/sh
# PreToolUse(Write|Edit|MultiEdit) 훅 — 본체보다 테스트가 먼저 있어야 한다.
#
# 이 프로젝트에서 낸 버그는 전부 테스트가 없던 자리에서 나왔다. 그래서 커밋 시점이 아니라
# 파일을 만드는 시점에 막는다. (커밋 게이트는 pre-commit-check.sh 가 따로 담당한다.)
#
# 설계 원칙 두 개:
#  1. **새 파일 생성만** 막는다. 기존 파일 수정까지 막으면 리팩터가 불가능해지고
#     훅을 우회하는 습관만 생긴다.
#  2. **판단이 안 되면 통과시킨다.** 훅의 실패가 작업을 막는 실패로 번져선 안 된다.
#     payload 가 깨졌든 python3 가 없든, 모르면 exit 0 이다.
#
# 이건 git 훅이 아니라 Claude Code 훅이다. 사람이 에디터로 직접 만드는 파일은 막지 않는다.
set -u

payload=$(cat)
# 훅 스크립트는 <project>/.claude/hooks/ 에 있다. CLAUDE_PROJECT_DIR 이 상위 폴더를
# 가리키는 경우가 있어 (모노레포처럼 excel-viewer 가 하위일 때) 스크립트 위치에서 직접 구한다.
# 상대경로가 한 칸 어긋나면 아래 case 패턴이 전부 빗나가 훅이 조용히 무력화된다.
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd) || exit 0
SRC_ROOT="src/main/kotlin/dev/hugo/sheetview"
TEST_ROOT="src/test/kotlin"
EXEMPT="$PROJECT_DIR/.claude/tdd-exempt.txt"
NEW_TESTS="$PROJECT_DIR/.claude/.tdd-new"

# tool_name 과 file_path 만 뽑는다. 실패하면 관여하지 않는다.
# 검사할 경로 후보를 줄 단위로 뽑는다. Write/Edit 은 file_path 하나지만,
# Bash 는 `cat > src/main/.../X.kt <<EOF` 처럼 게이트를 우회할 수 있어서
# 리다이렉션·tee 대상도 후보로 넣는다. 이 구멍을 막지 않으면 훅이 장식이 된다.
targets=$(printf '%s' "$payload" | python3 -c '
import json, re, shlex, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
ti = d.get("tool_input") or {}
if not isinstance(ti, dict):
    sys.exit(1)

out = []
for key in ("file_path", "notebook_path"):
    v = ti.get(key)
    if isinstance(v, str) and v:
        out.append(v)

cmd = ti.get("command")
if isinstance(cmd, str) and cmd:
    # `>` / `>>` 다음 토큰, 그리고 tee 의 인자. 그 밖(예: grep 인자)은 쓰기가 아니므로 본다 한들
    # 통과시켜야 하고, 여기서 놓치는 경로는 "모르면 통과" 원칙에 맞는다.
    for m in re.finditer(r">>?\s*([^\s;|&<>()]+)", cmd):
        out.append(m.group(1))
    try:
        toks = shlex.split(cmd, comments=False)
    except ValueError:
        toks = cmd.split()
    for i, t in enumerate(toks):
        if t == "tee":
            out.extend(x for x in toks[i + 1 :] if not x.startswith("-"))
            break

seen = set()
for v in out:
    v = v.strip().strip("\"\x27")
    if v and v not in seen:
        seen.add(v)
        print(v)
' 2>/dev/null) || exit 0

[ -n "$targets" ] || exit 0

# 후보 하나라도 걸리면 막는다. 나머지는 통과.
printf '%s\n' "$targets" | while IFS= read -r target; do
  [ -n "$target" ] || continue
  check_one "$target" || exit 2
done
exit $?

# ---- 후보 1개 판정 ----
check_one() {
target=$1
# 프로젝트 안의 경로로 정규화. 밖이면 관여하지 않는다.
case "$target" in
  "$PROJECT_DIR"/*) rel=${target#"$PROJECT_DIR"/} ;;
  /*)               exit 0 ;;
  *)                rel=$target ;;
esac

# 파일이 이미 있으면 통과 (수정은 막지 않는다).
[ -e "$PROJECT_DIR/$rel" ] && {
  # 단, 새 테스트 파일이 아니었음을 tdd-red.sh 가 알 수 있게 아무것도 적지 않는다.
  exit 0
}

# 새 테스트 파일이면 tdd-red.sh 가 "red 를 거쳤는지" 판정할 수 있게 표시만 남기고 통과.
case "$rel" in
  "$TEST_ROOT"/*Test.kt)
    mkdir -p "$(dirname "$NEW_TESTS")" 2>/dev/null
    printf '%s\n' "$rel" >> "$NEW_TESTS" 2>/dev/null
    exit 0
    ;;
  "$TEST_ROOT"/*) exit 0 ;;
esac

# 본체 코틀린 파일만 게이트한다. plugin.xml, build.gradle.kts, README 등은 통과.
case "$rel" in
  "$SRC_ROOT"/*.kt) ;;
  *) exit 0 ;;
esac

inner=${rel#"$SRC_ROOT"/}          # 예: source/HtmlSanitizer.kt
class=$(basename "$rel" .kt)       # 예: HtmlSanitizer

# 면제 목록 확인. 형식은 "<글로브><공백><사유>" 이고 사유가 없는 줄은 무효로 본다.
exempt_reason=""
if [ -f "$EXEMPT" ]; then
  while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    glob=$(printf '%s' "$line" | awk '{print $1}')
    reason=$(printf '%s' "$line" | sed 's/^[^[:space:]]*[[:space:]]*//')
    [ -n "$glob" ] && [ -n "$reason" ] || continue
    # shellcheck disable=SC2254
    case "$inner" in
      $glob) exempt_reason=$reason; break ;;
    esac
  done < "$EXEMPT"
fi
[ -n "$exempt_reason" ] && exit 0

# 클래스 이름을 언급하는 테스트가 하나라도 있어야 한다.
# 이름이 같은 파일(<Class>Test.kt)이 없어도, 다른 테스트가 이 클래스를 쓰고 있으면 인정한다.
if [ -d "$PROJECT_DIR/$TEST_ROOT" ] &&
   grep -rqlF "$class" "$PROJECT_DIR/$TEST_ROOT" 2>/dev/null; then
  exit 0
fi

# 면제 대상인지에 따라 안내가 다르다. editor/filetype/preview 는 면제 여지가 있고,
# format/source/model/export 는 순수 로직이라 면제 여지가 없다.
dir=${inner%%/*}
{
  echo "TDD: 본체보다 테스트가 먼저입니다."
  echo "  만들려는 것:   $rel"
  echo "  먼저 필요한 것: $TEST_ROOT/dev/hugo/sheetview/${class}Test.kt  (실패하는 테스트 1개)"
  echo
  case "$dir" in
    editor|filetype|preview)
      echo "이 경로는 헤드리스 테스트가 어려울 수 있습니다. 둘 중 하나를 고르세요:"
      echo "  (a) 로직을 순수 클래스로 빼내 테스트한다 (권장)"
      echo "  (b) .claude/tdd-exempt.txt 에 '$inner  <사유>' 를 추가한다"
      echo "      사유 없이 경로만 넣는 것은 그 파일의 목적을 없애는 것입니다."
      ;;
    *)
      echo "이 경로($dir/)는 순수 로직이라 헤드리스 테스트가 가능합니다. 면제 대상이 아닙니다."
      ;;
  esac
  echo
  echo "직접 확인: grep -rl $class $TEST_ROOT"
} >&2
exit 2
