#!/bin/sh
# SessionStart 훅 — 하네스가 살아 있는지 세션마다 말하게 한다.
#
# 다른 훅은 전부 "판단이 안 되면 조용히 exit 0" 이다. 그 원칙 자체는 옳다 —
# 훅의 실패가 작업을 막는 실패로 번져선 안 된다. 문제는 그 통과가 **전부 침묵**이라
# 하네스가 죽었는지 살았는지 아무도 모른다는 것이다. 특히 _common.sh 의 마커
# (gradlew + src/main/kotlin/dev/hugo/sheetview)는 패키지 경로가 하드코딩돼 있어,
# 패키지를 리네임하면 훅 셋이 동시에 무력화되고 아무 신호도 남지 않는다.
#
# 이 훅은 아무것도 막지 않는다. fail-open 을 유지하되 **fail-open 했다는 사실을 남긴다.**
# stdout 이 그대로 세션 컨텍스트에 주입되므로, 조용할 때는 짧게 끝내는 것이 중요하다.
set -u

. "$(dirname -- "$0")/_common.sh" 2>/dev/null || exit 0

# ------------------------------------------------------- 루트 찾기 (2단계)
# 1) 정상 경로: 마커(gradlew + 소스 루트)가 둘 다 맞는 곳.
# 2) 마커가 깨진 경우: gradlew 는 있는데 소스 루트가 없다. 이때 무관한 Gradle
#    프로젝트까지 경고하지 않도록, 이 하네스의 흔적이 함께 있을 때만 경고한다.
PROJECT_DIR=$(sheetview_project_dir 2>/dev/null) || PROJECT_DIR=""

if [ -z "$PROJECT_DIR" ]; then
  for _start in "${CLAUDE_PROJECT_DIR:-}" "$PWD"; do
    [ -n "$_start" ] || continue
    _dir=$(CDPATH= cd -- "$_start" 2>/dev/null && pwd) || continue
    while [ -n "$_dir" ]; do
      if [ -f "$_dir/gradlew" ] &&
         { [ -f "$_dir/.claude/tdd-exempt.txt" ] || [ -d "$_dir/plugins/sheetview-kit" ]; }; then
        printf 'sheetview 하네스: **훅 3개가 무력화된 상태입니다.**\n'
        printf '  %s 에 하네스 흔적은 있는데 소스 루트가 없습니다:\n' "$_dir"
        printf '    기대: %s/%s\n' "$_dir" "$SHEETVIEW_SRC_ROOT"
        printf '  _common.sh 의 SHEETVIEW_SRC_ROOT 가 실제 패키지 경로와 어긋났습니다.\n'
        printf '  tdd-guard / tdd-red / pre-commit-check 가 전부 조용히 통과합니다.\n'
        exit 0
      fi
      [ "$_dir" = "/" ] && break
      _dir=$(dirname -- "$_dir")
    done
  done
  exit 0   # 이 저장소와 무관한 프로젝트. 아무 말도 하지 않는다.
fi

cd "$PROJECT_DIR" 2>/dev/null || exit 0

notes=""
add() { notes="$notes
  - $1"; }

# --------------------------------------------------------- 1. 잔여 .tdd-new
# tdd-guard 는 PreToolUse 에서 표시를 남기므로, 사용자가 Write 를 거절해도 표시가 남는다.
# tdd-red 가 소비하지 못한 표시는 다음에 그 경로를 처음 건드릴 때 엉뚱하게 발동한다.
if [ -s .claude/.tdd-new ]; then
  ghosts=$(grep -c . .claude/.tdd-new 2>/dev/null || echo 0)
  add "소비되지 않은 red 표시 ${ghosts}건 (.claude/.tdd-new). 거절된 Write 가 남긴 것일 수 있습니다."
fi

# ------------------------------------------------------------- 2. 래칫 상태
# check.sh 는 줄어들 때만 실패하고 늘어나면 안내만 한다. 그 안내는 실행 시점에
# 한 번 흐르고 사라지므로, 갱신이 밀린 채로 여러 세션이 지나간다.
baseline=$(cat .claude/tdd-baseline 2>/dev/null || echo "")
case "$baseline" in ''|*[!0-9]*) baseline="" ;; esac
if [ -n "$baseline" ] && ls build/test-results/test/TEST-*.xml >/dev/null 2>&1; then
  count=$(ls build/test-results/test/TEST-*.xml 2>/dev/null |
    xargs grep -ho 'tests="[0-9]*"' 2>/dev/null |
    sed 's/[^0-9]//g' | awk '{s+=$1} END {print s+0}')
  if [ "${count:-0}" -gt "$baseline" ] 2>/dev/null; then
    add "테스트 래칫이 밀려 있습니다: .claude/tdd-baseline 이 $baseline, 마지막 실행은 $count. 커밋 전에 올리세요."
  fi
fi

# ------------------------------------------------- 3. 면제 / 미커버 파일 수
# tdd-guard 는 **신규 생성만** 막는다. 훅 도입 이전에 생긴 파일은 영구 grandfather 라
# 면제 목록에도 없고 테스트도 없는 채로 남는다. 그 빚을 세션마다 보이게 한다.
if [ -d "$SHEETVIEW_SRC_ROOT" ]; then
  exempt_count=0
  [ -f .claude/tdd-exempt.txt ] &&
    exempt_count=$(grep -cE '^[^#[:space:]]+[[:space:]]+[^[:space:]]' .claude/tdd-exempt.txt 2>/dev/null || echo 0)

  uncovered=0
  for f in $(find "$SHEETVIEW_SRC_ROOT" -name '*.kt' 2>/dev/null); do
    inner=${f#"$SHEETVIEW_SRC_ROOT"/}
    class=$(basename "$f" .kt)

    skip=0
    if [ -f .claude/tdd-exempt.txt ]; then
      while IFS= read -r line; do
        case "$line" in ''|'#'*) continue ;; esac
        glob=$(printf '%s' "$line" | awk '{print $1}')
        reason=$(printf '%s' "$line" | sed 's/^[^[:space:]]*[[:space:]]*//')
        [ -n "$glob" ] && [ -n "$reason" ] || continue
        # shellcheck disable=SC2254
        case "$inner" in $glob) skip=1; break ;; esac
      done < .claude/tdd-exempt.txt
    fi
    [ "$skip" = 1 ] && continue

    grep -rqlF "$class" "$SHEETVIEW_TEST_ROOT" 2>/dev/null || uncovered=$((uncovered + 1))
  done

  [ "$uncovered" -gt 0 ] &&
    add "테스트에서 이름조차 안 나오는 본체 파일 ${uncovered}개 (면제 ${exempt_count}개는 제외한 수). tdd-guard 는 신규 생성만 막으므로 이 파일들은 계속 테스트 없이 고칠 수 있습니다."
fi

# ----------------------------------------------------------- 4. 현재 브랜치
branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
if [ "$branch" = "main" ] || [ "$branch" = "master" ]; then
  add "지금 $branch 브랜치입니다. 코드를 고치기 전에 작업 브랜치를 만드세요."
fi

# ------------------------------------------------------------------ 출력
if [ -n "$notes" ]; then
  printf 'sheetview 하네스 상태:%s\n' "$notes"
fi
exit 0
