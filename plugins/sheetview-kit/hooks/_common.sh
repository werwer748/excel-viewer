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

# ── Kotlin 파일의 package 선언 ────────────────────────────────────────────────
# BRE 의 `\+` 는 GNU 확장이다. macOS(BSD) sed 는 이것을 모르고 **조용히 빈 값**을 낸다 —
# tdd-red 가 FQCN 대신 단순 이름으로 테스트를 고르고 있었는데 아무 신호도 없었다. -E 로 쓴다.
sheetview_kotlin_package() {   # <file>
  sed -nE 's/^[[:space:]]*package[[:space:]]+([A-Za-z0-9_.]+).*/\1/p' "$1" 2>/dev/null | head -1
}

# ── 이 체크아웃의 프로세스 ───────────────────────────────────────────────────
# `pgrep -f` 는 머신 전체를 본다. 체크아웃이 하나일 때는 그래도 맞았는데, 워크트리가 생기자
# 두 방향으로 틀렸다: 본체의 샌드박스가 떠 있으면 워크트리의 커밋·red 검사가 "락 때문에"
# 멈췄고, 워크트리에서 `sandbox-up.sh down` 을 부르면 **본체의 IDE 를 죽였다.**
# Gradle 프로젝트 락은 디렉터리마다 따로다. 그래서 프로세스마다 주인을 가린다.
#
# 주인: 명령줄에 "<프로젝트>/" 가 있거나(gradlew 가 래퍼 jar 를, 샌드박스 IDE 가 config·log
# 경로를 절대경로로 넘긴다) cwd 가 프로젝트 아래다. 단 <프로젝트>/.claude/worktrees/ 아래는
# **남이다** — 워크트리가 본체 안에 중첩되므로 접두어만 보면 본체가 워크트리 것까지 제 것으로 센다.
# 가릴 단서가 전혀 없으면(cwd 를 못 읽고 명령줄에 경로도 없다) 예전처럼 제 것으로 본다 —
# 락이 걸린 줄 모르고 gradle 을 부르면 타임아웃까지 멈추기 때문이다.
sheetview_owns() {   # <물리 경로 프로젝트> <명령줄> <cwd>
  printf '%s\n%s\n' "$2" "$3" | awk -v p="$1/" -v w="$1/.claude/worktrees/" '
    function strip(s,  i) {
      while ((i = index(s, w)) > 0) s = substr(s, 1, i - 1) substr(s, i + length(w))
      return s
    }
    NR == 1 { cmd = strip($0) }
    NR == 2 { cwd = $0 }
    END {
      if (index(cmd, p) > 0) exit 0
      if (cwd == "") exit 0
      c = cwd "/"
      exit !(index(c, p) == 1 && index(c, w) != 1)
    }'
}

sheetview_pids() {   # <pgrep 패턴> <프로젝트> -> 이 체크아웃의 PID, 한 줄에 하나
  # 비교는 물리 경로로 한다. lsof 와 gradlew(pwd -P) 가 물리 경로로 답한다.
  _sv_root=$(CDPATH= cd -P -- "$2" 2>/dev/null && pwd -P) || _sv_root=$2
  for _sv_pid in $(pgrep -f -- "$1" 2>/dev/null); do
    _sv_cmd=$(ps -o command= -p "$_sv_pid" 2>/dev/null) || continue
    [ -n "$_sv_cmd" ] || continue            # 그새 끝났다
    _sv_cwd=$(lsof -a -p "$_sv_pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1)
    [ -n "$_sv_cwd" ] || _sv_cwd=$(readlink "/proc/$_sv_pid/cwd" 2>/dev/null)
    if sheetview_owns "$_sv_root" "$_sv_cmd" "$_sv_cwd"; then
      printf '%s\n' "$_sv_pid"
    fi
  done
  return 0
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

# 리뷰·검증 판정이 기록됐다 = 사이클이 앞으로 갔다. cycle-stop 의 되돌리기 횟수는
# "진전 없이 연속으로" 센 것이어야 하므로 여기서 0 으로 돌린다. 예전에는 한 번 오른 횟수가
# 사이클 내내 쌓여, 단계마다 한 번씩 멈추기만 해도 게이트가 꺼졌다.
sheetview_cycle_progress() {   # <project_dir>
  sheetview_cycle_set "$1" stop_blocked 0
}
