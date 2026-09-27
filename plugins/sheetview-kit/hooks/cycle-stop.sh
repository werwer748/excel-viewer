#!/bin/sh
# Stop 훅 — 사이클을 끝내지 않은 채 세션을 마치려 하면 되돌린다.
#
# **이 훅은 세션을 가둘 수 있다.** Stop + command 타입의 exit 2 는 "끝내지 말고 계속하라"는
# 뜻이라서, 조건이 영영 참이 되지 않으면 세션이 빠져나가지 못한다. 그래서 탈출구를 셋 둔다:
#
#   1. .claude/.cycle-state 가 없으면 즉시 통과 — 평소 세션에는 아무 영향이 없어야 한다.
#   2. stop_blocked 가 한도에 닿으면 포기하고 통과 — 플랫폼은 무한루프를 막아주지 않는다.
#   3. 판단이 안 되는 모든 경우 통과 — 다른 훅들과 같은 원칙이다.
#
# 막는 것은 여기 하나뿐이고, 상태를 만드는 것은 cycle-review / cycle-verify 다.
set -u

MAX_BLOCK=3        # 이만큼 되돌렸는데도 안 끝나면 포기한다
MAX_ROUNDS=3       # 리뷰->수정 라운드 상한. 수렴하지 않는 것을 무한히 돌리지 않는다

. "$(dirname -- "$0")/_common.sh" 2>/dev/null || exit 0
PROJECT_DIR=$(sheetview_project_dir) || exit 0
sheetview_cycle_active "$PROJECT_DIR" || exit 0        # 탈출구 1

get() { sheetview_cycle_get "$PROJECT_DIR" "$1" 2>/dev/null || printf '%s' "${2:-}"; }

blocked=$(get stop_blocked 0)
case "$blocked" in ''|*[!0-9]*) blocked=0 ;; esac
round=$(get round 0)
case "$round" in ''|*[!0-9]*) round=0 ;; esac
max_rounds=$(get max_rounds "$MAX_ROUNDS")
case "$max_rounds" in ''|*[!0-9]*) max_rounds=$MAX_ROUNDS ;; esac

review=$(get review pending)
verify=$(get verify pending)
harness=$(get harness_touched no)

# 게이트 자신을 건드린 사이클은 결과와 무관하게 반드시 알린다.
harness_note=""
[ "$harness" = "yes" ] &&
  harness_note='
  ⚠ 이번 사이클에서 게이트 자신(면제 목록·래칫·훅)을 수정했습니다.
    지적을 없애려고 고친 것이 아닌지 확인하세요.'

# 탈출구 2 — 되돌리기 한도
if [ "$blocked" -ge "$MAX_BLOCK" ]; then
  printf '사이클이 %s번 되돌려도 끝나지 않아 통과시킵니다. 상태: review=%s verify=%s%s\n' \
    "$blocked" "$review" "$verify" "$harness_note" >&2
  sheetview_cycle_set "$PROJECT_DIR" verify halted
  exit 0
fi

# 라운드 상한 — 수렴 실패는 막는 것이 아니라 사람을 부르는 것으로 끝낸다
if [ "$round" -ge "$max_rounds" ] && [ "$review" != "clean" ]; then
  printf '사이클: 리뷰 라운드가 상한(%s)에 닿았는데 🔴 가 남아 있습니다 (review=%s).\n' \
    "$max_rounds" "$review" >&2
  printf '자동으로 더 돌리지 않습니다. 사람이 판단해야 합니다.%s\n' "$harness_note" >&2
  sheetview_cycle_set "$PROJECT_DIR" verify halted
  exit 0
fi

# 완료 판정
if [ "$review" = "clean" ] && [ "$verify" = "clean" ]; then
  [ -n "$harness_note" ] && printf '%s\n' "$harness_note" >&2
  exit 0
fi

# 아직 남았다 — 되돌린다
sheetview_cycle_set "$PROJECT_DIR" stop_blocked "$((blocked + 1))"
{
  echo "사이클이 끝나지 않았습니다. 남은 단계:"
  case "$review" in
    clean)   ;;
    red:*)   echo "  - 리뷰 지적($review)을 고치고 sheet-reviewer 로 다시 리뷰하세요." ;;
    pending) echo "  - sheet-reviewer 에이전트로 코드 리뷰를 받으세요." ;;
    *)       echo "  - 리뷰 결과를 읽지 못했습니다($review). 보고 형식을 확인하거나 사람에게 물으세요." ;;
  esac
  case "$verify" in
    clean)   ;;
    red:*)   echo "  - 샌드박스에서 나온 문제($verify)를 고치고 리뷰부터 다시 도세요." ;;
    pending) [ "$review" = "clean" ] && echo "  - sandbox-runner 에이전트로 샌드박스 검증을 하세요." ;;
    halted)  ;;
    *)       echo "  - 샌드박스 검증 결과를 읽지 못했습니다($verify)." ;;
  esac
  printf '%s' "$harness_note"
  echo
  echo "(사이클을 그만두려면 .claude/.cycle-state 를 지우면 됩니다.)"
} >&2
exit 2
