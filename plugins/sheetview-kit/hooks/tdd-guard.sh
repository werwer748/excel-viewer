#!/bin/sh
# PreToolUse(Write|Edit|MultiEdit|NotebookEdit|Bash|MCP 쓰기툴) 훅 — 본체보다 테스트가 먼저 있어야 한다.
#
# 이 프로젝트에서 낸 버그는 전부 테스트가 없던 자리에서 나왔다. 그래서 커밋 시점이 아니라
# 파일을 만드는 시점에 막는다. (커밋 게이트는 pre-commit-check.sh 가 따로 담당한다.)
#
# 설계 원칙 네 개:
#  1. **새 파일 생성만** 막는다. 기존 파일 수정까지 막으면 리팩터가 불가능해지고
#     훅을 우회하는 습관만 생긴다.
#  2. **판단이 안 되면 통과시킨다.** 훅의 실패가 작업을 막는 실패로 번져선 안 된다.
#     payload 가 깨졌든 python3 가 없든, 모르면 exit 0 이다.
#  3. **툴을 바꿔 우회하는 것을 막는다.** `cat > X.kt <<EOF` 도, `touch X.kt` 도,
#     MCP 의 create_new_file 도 Write 툴을 타지 않는다. 이 구멍을 두면 훅이 장식이 된다.
#  4. **게이트 자신을 고치는 것은 막지 않고 사람에게 올린다.** 면제 목록·래칫·훅 스크립트는
#     전부 이 게이트 밖에 있어서 모델이 자유롭게 고칠 수 있었다. 차단하면 리팩터가
#     불가능해지므로 차단하지 않는다 — 대신 `ask` 로 올려 사람이 한 번 보게 한다.
#
# 이건 git 훅이 아니라 Claude Code 훅이다. 사람이 에디터로 직접 만드는 파일은 막지 않는다.
set -u

payload=$(cat)

# 프로젝트 루트는 _common.sh 가 마커(gradlew + 소스 루트)로 찾는다. 스크립트 위치에서
# 역산하지 않는다 — 이 파일은 플러그인 설치 경로에 있고 프로젝트와 무관하다.
# 상대경로가 한 칸 어긋나면 아래 case 패턴이 전부 빗나가 훅이 조용히 무력화된다.
. "$(dirname -- "$0")/_common.sh" 2>/dev/null || exit 0
PROJECT_DIR=$(sheetview_project_dir) || exit 0

SRC_ROOT="$SHEETVIEW_SRC_ROOT"
TEST_ROOT="$SHEETVIEW_TEST_ROOT"
EXEMPT="$PROJECT_DIR/.claude/tdd-exempt.txt"
NEW_TESTS="$PROJECT_DIR/.claude/.tdd-new"

# 검사할 경로 후보를 줄 단위로 뽑는다.
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

# Write/Edit/MultiEdit/NotebookEdit 과 MCP 쓰기 툴의 경로 필드.
# MCP 서버마다 이름이 다르므로 흔한 것을 모두 본다 — 모르는 필드는 어차피 못 막는다.
for key in ("file_path", "notebook_path", "path", "filePath",
            "pathInProject", "absolutePath", "file", "target", "targetFile"):
    v = ti.get(key)
    if isinstance(v, str) and v:
        out.append(v)

cmd = ti.get("command")
if isinstance(cmd, str) and cmd:
    # (a) 리다이렉션 대상. grep 인자처럼 읽기 전용으로 등장하는 경로는 어차피
    #     통과시켜야 하고, 여기서 놓치는 경로는 "모르면 통과" 원칙에 맞는다.
    for m in re.finditer(r">>?\s*([^\s;|&<>()]+)", cmd):
        out.append(m.group(1))

    # (b) 파일을 "만드는" 명령들. 이게 없던 동안 `touch X.kt` 두 번이면 게이트가
    #     완전히 사라졌다 — 빈 파일이 먼저 생기고, 그 다음 Write 는 아래
    #     "이미 있는 파일은 막지 않는다" 규칙에 걸려 통과했기 때문이다.
    for part in re.split(r"[;&|]+", cmd):
        try:
            toks = shlex.split(part, comments=False)
        except ValueError:
            toks = part.split()
        if not toks:
            continue
        head = toks[0]
        args = [t for t in toks[1:] if not t.startswith("-")]
        if head == "tee":
            out.extend(args)
        elif head in ("touch", "install"):
            out.extend(args)
        elif head in ("cp", "mv", "ln"):
            # 마지막 비옵션 인자가 대상. 디렉터리면 .kt 로 끝나지 않아 어차피 통과한다.
            if args:
                out.append(args[-1])
        elif head == "dd":
            for t in toks[1:]:
                if t.startswith("of="):
                    out.append(t[3:])

seen = set()
for v in out:
    v = v.strip().strip(chr(34)).strip(chr(39))
    if v and v not in seen:
        seen.add(v)
        print(v)
' 2>/dev/null) || exit 0

[ -n "$targets" ] || exit 0

# --------------------------------------------------- 게이트 자신인가? (원칙 4)
# 이 파일들은 전부 SRC_ROOT 밖이라 아래 TDD 검사에 걸리지 않는다. 그래서 모델이
# 지적을 없애는 가장 쉬운 길이 "면제 목록에 한 줄 추가" 였다. 차단하지는 않는다 —
# 리팩터가 불가능해지기 때문이다. 대신 사람에게 올린다.
is_harness_file() {
  case "$1" in
    .claude/tdd-exempt.txt|.claude/tdd-baseline|.claude/tdd-uncovered|.claude/.cycle-state) return 0 ;;
    plugins/sheetview-kit/hooks/*|plugins/sheetview-kit/scripts/*) return 0 ;;
    scripts/check.sh) return 0 ;;
  esac
  return 1
}

# ---------------------------------------------------------------- 후보 1개 판정
# 통과면 0, 막아야 하면 1을 돌려주고 사유를 stderr 에 쓴다.
check_one() {
  target=$1

  case "$target" in
    "$PROJECT_DIR"/*) rel=${target#"$PROJECT_DIR"/} ;;
    /*)               return 0 ;;   # 프로젝트 밖
    *)                rel=$target ;;
  esac

  # 게이트 자신이면 여기서는 통과시키고, 호출한 쪽이 ask 로 올린다.
  if is_harness_file "$rel"; then
    harness_hit="$harness_hit $rel"
    return 0
  fi

  # 새 테스트 파일이면 tdd-red.sh 가 "red 를 거쳤는지" 판정할 수 있게 표시를 남기고 통과.
  case "$rel" in
    "$TEST_ROOT"/*Test.kt)
      [ -e "$PROJECT_DIR/$rel" ] && return 0
      mkdir -p "$PROJECT_DIR/.claude" 2>/dev/null
      printf '%s\n' "$rel" >> "$NEW_TESTS" 2>/dev/null
      return 0
      ;;
    "$TEST_ROOT"/*) return 0 ;;
  esac

  # 본체 코틀린 파일만 게이트한다. plugin.xml, build.gradle.kts, README 등은 통과.
  case "$rel" in
    "$SRC_ROOT"/*.kt) ;;
    *) return 0 ;;
  esac

  # 이미 있는 파일 수정은 막지 않는다.
  [ -e "$PROJECT_DIR/$rel" ] && return 0

  inner=${rel#"$SRC_ROOT"/}          # 예: source/HtmlSanitizer.kt
  class=$(basename "$rel" .kt)       # 예: HtmlSanitizer

  # 면제 목록. 형식은 "<글로브><공백><사유>" 이고 사유가 빈 줄은 무효로 본다.
  if [ -f "$EXEMPT" ]; then
    while IFS= read -r line; do
      case "$line" in ''|'#'*) continue ;; esac
      glob=$(printf '%s' "$line" | awk '{print $1}')
      reason=$(printf '%s' "$line" | sed 's/^[^[:space:]]*[[:space:]]*//')
      [ -n "$glob" ] && [ -n "$reason" ] || continue
      # shellcheck disable=SC2254
      case "$inner" in
        $glob) return 0 ;;
      esac
    done < "$EXEMPT"
  fi

  # 클래스 이름을 언급하는 테스트가 하나라도 있어야 한다. 이름이 같은 파일이 없어도
  # 다른 테스트가 이 클래스를 쓰고 있으면 인정한다.
  if [ -d "$PROJECT_DIR/$TEST_ROOT" ] &&
     grep -rqlF "$class" "$PROJECT_DIR/$TEST_ROOT" 2>/dev/null; then
    return 0
  fi

  dir=${inner%%/*}
  {
    echo "TDD: 본체보다 테스트가 먼저입니다."
    echo "  만들려는 것:   $rel"
    echo "  먼저 필요한 것: $TEST_ROOT/dev/hugo/sheetview/${class}Test.kt  (실패하는 테스트 1개)"
    echo
    echo "권장: 로직을 순수 클래스로 빼내 테스트한다."
    case "$dir" in
      editor|filetype|preview)
        echo "이 경로는 헤드리스 테스트가 어려울 수 있습니다. 그래도 먼저 위를 시도하세요 —"
        echo "배너 개수·자식 유무·탭 순서처럼 IDE 없이 단정할 수 있는 것이 생각보다 많습니다."
        echo "정말 불가능하면 .claude/tdd-exempt.txt 에 면제를 추가할 수 있지만,"
        echo "그 파일을 고치는 것은 사람 승인을 거칩니다 — 게이트 자신이기 때문입니다."
        ;;
      *)
        echo "이 경로($dir/)는 순수 로직이라 헤드리스 테스트가 가능합니다. 면제 대상이 아닙니다."
        ;;
    esac
    echo
    echo "직접 확인: grep -rl $class $TEST_ROOT"
  } >&2
  return 1
}

# 파이프라인 안의 while 은 서브셸에서 돌아 종료 코드가 전파되지 않는다. IFS 로 돈다.
blocked=0
harness_hit=""
saved_ifs=$IFS
IFS='
'
for t in $targets; do
  IFS=$saved_ifs
  [ -n "$t" ] || continue
  check_one "$t" || blocked=1
  IFS='
'
done
IFS=$saved_ifs

# TDD 위반이 우선이다. 막을 것이 있으면 ask 까지 갈 것도 없다.
[ "$blocked" = 0 ] || exit 2

# 게이트 자신을 건드리는 중이면 사람에게 올린다.
if [ -n "$harness_hit" ]; then
  printf '%s' "$harness_hit" | python3 -c '
import json, sys
files = sys.stdin.read().split()
reason = (
    "게이트 자신을 수정하려 합니다: " + ", ".join(files) + "\n"
    "이 파일들은 TDD 게이트·래칫·훅 스크립트라서 다른 검사에 걸리지 않습니다.\n"
    "면제 추가나 래칫 하향으로 지적을 없애는 것이 아닌지 확인하세요."
)
print(json.dumps({"hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "ask",
    "permissionDecisionReason": reason,
}}))
' 2>/dev/null || exit 0
  exit 0
fi

exit 0
