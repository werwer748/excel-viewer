#!/bin/sh
# 샌드박스 기동의 앞뒤를 맡는다. sandbox-run(사람 인계)과 sandbox-verify(자가검증)가 공유한다.
#
# **기동 자체는 여기서 하지 않는다.** `./gradlew runIde` 는 부른 쪽이 반드시
# run_in_background 로 띄워야 한다 — 포그라운드로 부르면 IDE 수명만큼 세션이 멈춘다.
# 여기는 그 앞(preflight)과 뒤(wait)만 담당한다.
#
# 순서가 중요하다: runIde 가 뜬 뒤에는 **모든 ./gradlew 가 프로젝트 락 대기로 멈춘다.**
# 그래서 검사는 반드시 기동 전에 끝낸다. 순서를 어기면 세션이 조용히 멈춘다.
#
#   sandbox-up.sh status      떠 있나 (PID 또는 "없음")
#   sandbox-up.sh preflight   기동 전: 락 확인 -> check.sh -> 로그 경계 찍기
#   sandbox-up.sh wait        기동 완료까지 대기하고 PID·포트를 보고
#   sandbox-up.sh down        종료 요청
set -u

HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
. "$HERE/../hooks/_common.sh" 2>/dev/null || exit 1
PROJECT_DIR=$(sheetview_project_dir) || { echo "excel-viewer 저장소를 찾지 못했습니다." >&2; exit 1; }
cd "$PROJECT_DIR" || exit 1

SANDBOX_PAT="idea.plugin.in.sandbox.mode=true"

case "${1:-}" in
  status)
    pids=$(pgrep -f "$SANDBOX_PAT" 2>/dev/null | tr '\n' ' ')
    if [ -n "$pids" ]; then printf '떠 있음 PID=%s\n' "$pids"; else echo "없음"; fi
    ;;

  preflight)
    if pgrep -f "$SANDBOX_PAT" >/dev/null 2>&1; then
      printf '이미 떠 있습니다 (PID=%s).\n' "$(pgrep -f "$SANDBOX_PAT" | tr '\n' ' ')"
      printf '지금 떠 있는 IDE 에는 이번에 고친 코드가 들어 있지 않을 수 있습니다.\n'
      printf '그대로 쓸지, 닫고 새로 띄울지 정한 뒤 진행하세요. (검사는 락 때문에 건너뜁니다)\n'
      exit 3
    fi
    printf '검사 중 (기동 전에 끝내야 합니다 — 뜬 뒤에는 Gradle 락으로 멈춥니다)\n'
    if ! ./scripts/check.sh; then
      printf '\ncheck.sh 실패 — 띄우지 않습니다.\n' >&2
      printf 'TODO.md 의 진행 중인 기능이 테스트를 먼저 올려 둔 상태일 수 있습니다.\n' >&2
      exit 1
    fi
    sh "$HERE/sandbox-log.sh" mark
    printf '준비됨. 이제 ./gradlew runIde 를 **백그라운드로** 띄우세요.\n'
    ;;

  wait)
    i=0
    # localIdePath 가 비어 있으면 원격 아티팩트(~1GB)를 받아야 해서 훨씬 오래 걸린다.
    # 그건 실패가 아니다. 넉넉히 기다리되 무한히 기다리지는 않는다.
    while [ $i -lt 150 ]; do
      if pgrep -f "$SANDBOX_PAT" >/dev/null 2>&1; then
        # 내장 웹서버 포트는 고정이 아니다. 다른 IDE 가 63342 를 점유하고 있으면
        # 63343, 63344 로 밀린다 (실측 확인). 하드코딩하지 말고 로그에서 읽는다.
        port=$(sh "$HERE/sandbox-log.sh" since 2>/dev/null |
               grep -oE "built-in server started, port [0-9]+" | tail -1 |
               grep -oE "[0-9]+$")
        if [ -n "$port" ]; then
          printf 'PID=%s  포트=%s  (대기 %s초)\n' \
            "$(pgrep -f "$SANDBOX_PAT" | tr '\n' ' ')" "$port" "$((i * 2))"
          exit 0
        fi
      fi
      sleep 2
      i=$((i + 1))
    done
    echo "기동을 확인하지 못했습니다 ($((i * 2))초). 같은 명령을 다시 던지지 마세요 — 두 번째는 첫 번째 락에 걸립니다." >&2
    exit 1
    ;;

  down)
    pkill -f "$SANDBOX_PAT" 2>/dev/null
    echo "종료 요청했습니다. 락이 풀려야 ./gradlew 가 다시 돕니다."
    ;;

  *)
    echo "사용법: sandbox-up.sh status|preflight|wait|down" >&2
    exit 2
    ;;
esac
