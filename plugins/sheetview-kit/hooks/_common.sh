# 훅 셋이 공유하는 것 — 프로젝트 루트 찾기와 경로 상수. 실행 파일이 아니라 source 전용이다.
#
# 훅이 <project>/.claude/hooks/ 에 있던 시절에는 자기 위치에서 루트를 역산했다
# (dirname "$0"/../..). 플러그인으로 옮긴 뒤로 그 전제는 없다 — 스크립트는 플러그인
# 설치 경로에 있고 프로젝트와 아무 관계가 없다. 그래서 환경변수와 cwd 에서 찾는다.
#
# 마커로 확인하는 이유: 이 플러그인은 사용자 범위로 설치될 수 있고, 그러면 훅은 이 저장소와
# 무관한 프로젝트에서도 불린다. 마커가 없으면 "여기는 내 프로젝트가 아니다"로 보고 부른 쪽이
# 조용히 통과하게 한다. 훅의 원칙(판단이 안 되면 통과)의 연장이다.

SHEETVIEW_SRC_ROOT="src/main/kotlin/dev/hugo/sheetview"
SHEETVIEW_TEST_ROOT="src/test/kotlin"

# 마커: gradlew 와 이 플러그인의 소스 루트가 함께 있는 디렉터리.
sheetview_is_project() {
  [ -f "$1/gradlew" ] && [ -d "$1/$SHEETVIEW_SRC_ROOT" ]
}

# CLAUDE_PROJECT_DIR 과 cwd 를 각각 위로 훑는다. 둘 다 필요하다:
# CLAUDE_PROJECT_DIR 이 상위 폴더를 가리키는 경우(모노레포처럼 excel-viewer 가 하위일 때)를
# cwd 쪽이 받아내고, cwd 가 프로젝트 밖인 경우를 CLAUDE_PROJECT_DIR 쪽이 받아낸다.
sheetview_project_dir() {
  for _sv_start in "${CLAUDE_PROJECT_DIR:-}" "$PWD"; do
    [ -n "$_sv_start" ] || continue
    _sv_dir=$(CDPATH= cd -- "$_sv_start" 2>/dev/null && pwd) || continue
    while [ -n "$_sv_dir" ]; do
      if sheetview_is_project "$_sv_dir"; then
        printf '%s\n' "$_sv_dir"
        return 0
      fi
      [ "$_sv_dir" = "/" ] && break
      _sv_dir=$(dirname -- "$_sv_dir")
    done
  done
  return 1
}

# ── 작업 사이클 상태 (.claude/.cycle-state) ───────────────────────────────────
# cycle-review / cycle-verify / cycle-stop 이 공유하는 유일한 상태다.
# 형식은 key=value 한 줄씩이고, 파일이 없으면 "사이클이 없다"는 뜻이다 —
# 평소 세션에서는 이 훅들이 아무 일도 하지 않아야 한다.
#
# tdd-red.sh 의 교훈을 적용한다: **읽고 지우지 않는다.** 그 훅은 .tdd-new 표시를
# 소비해 버려서 같은 파일을 두 번째 고칠 때부터 판정이 돌지 않는 버그를 냈다.

sheetview_cycle_get() {   # <project_dir> <key>
  _sv_f="$1/.claude/.cycle-state"
  [ -f "$_sv_f" ] || return 1
  _sv_v=$(sed -n "s/^$2=//p" "$_sv_f" | head -1)
  [ -n "$_sv_v" ] || return 1
  printf '%s' "$_sv_v"
}

sheetview_cycle_set() {   # <project_dir> <key> <value>
  _sv_f="$1/.claude/.cycle-state"
  mkdir -p "$1/.claude" 2>/dev/null || return 1
  _sv_t="$_sv_f.tmp.$$"
  {
    if [ -f "$_sv_f" ]; then grep -v "^$2=" "$_sv_f" || true; fi
    printf '%s=%s\n' "$2" "$3"
  } > "$_sv_t" 2>/dev/null || return 1
  mv -f "$_sv_t" "$_sv_f" 2>/dev/null
}

sheetview_cycle_active() { [ -f "$1/.claude/.cycle-state" ]; }
