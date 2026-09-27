#!/bin/sh
# 샌드박스 로그의 "이번 기동 구간" 경계를 잡는다.
#
# 왜 스크립트인가: sandbox-run 스킬은 3단계에서 `MARK=$(wc -l < "$LOG")` 를 잡고
# 6단계에서 `tail -n +$((MARK+1))` 로 읽으라고 한다. 그런데 **Bash 호출 사이에 셸
# 상태가 유지되지 않는다** — 매 호출이 새 셸이라 $LOG 와 $MARK 가 그 시점에 사라진다.
# 지금은 모델이 값을 텍스트로 기억해 다시 써넣는 식으로 굴러가고 있고, 스킬 자신이
# 그 자리를 "가장 틀리기 쉬운 자리"라고 적어 두었다. 경계가 틀리면 지난 기동의 예외를
# 이번 것으로 보고한다 — 조용한 오보다. 상태를 파일에 두면 그 취약점이 사라진다.
#
# idea.log 는 기동할 때마다 덧붙는다. 지우고 새로 쓰지 않는다.
#
#   sandbox-log.sh mark    기동 직전에 현재 줄 수를 기록한다
#   sandbox-log.sh since   기록한 지점 이후만 출력한다
#   sandbox-log.sh path    로그 파일 경로만 출력한다
set -u

. "$(dirname -- "$0")/../hooks/_common.sh" 2>/dev/null || exit 1
PROJECT_DIR=$(sheetview_project_dir) || { echo "excel-viewer 저장소를 찾지 못했습니다." >&2; exit 1; }
cd "$PROJECT_DIR" || exit 1

MARK_FILE=".claude/.sandbox-mark"

# IDE 접두어(WS-2026.2.3 등)는 ~/.gradle/gradle.properties 의 localIdePath 에 따라
# 달라진다. 고정 경로를 쓰면 다른 머신에서 즉시 깨지므로 반드시 글로브로 찾는다.
find_log() {
  ls .intellijPlatform/sandbox/*/*/log_runIde/idea.log 2>/dev/null | head -1
}

case "${1:-}" in
  mark)
    log=$(find_log)
    if [ -z "$log" ]; then
      # 첫 기동이라 로그가 아직 없다. 0 행으로 두면 이후 전체가 이번 구간이 된다.
      mkdir -p .claude
      printf '%s\n0\n' "(아직 없음)" > "$MARK_FILE"
      echo "로그 없음 — 이번 기동이 처음입니다. 전체를 이번 구간으로 봅니다."
      exit 0
    fi
    mkdir -p .claude
    lines=$(wc -l < "$log" 2>/dev/null | tr -d "[:space:]")
    case "$lines" in ''|*[!0-9]*) lines=0 ;; esac
    printf '%s\n%s\n' "$log" "$lines" > "$MARK_FILE"
    printf '%s\n  이전 누적 %s행. 이 지점 이후만 이번 기동 구간입니다.\n' "$log" "$lines"
    ;;
  since)
    [ -f "$MARK_FILE" ] || { echo "경계가 없습니다. 기동 전에 'sandbox-log.sh mark' 를 부르세요." >&2; exit 1; }
    log=$(sed -n 1p "$MARK_FILE")
    mark=$(sed -n 2p "$MARK_FILE" | tr -d "[:space:]")
    case "$mark" in ''|*[!0-9]*) mark=0 ;; esac
    # 기동 전에 로그가 없었다면 그 사이 생겼을 수 있으므로 다시 찾는다.
    [ -f "$log" ] || log=$(find_log)
    [ -n "$log" ] && [ -f "$log" ] || { echo "로그 파일이 없습니다." >&2; exit 1; }
    tail -n +$((mark + 1)) "$log"
    ;;
  path)
    find_log
    ;;
  *)
    echo "사용법: sandbox-log.sh mark|since|path" >&2
    exit 2
    ;;
esac
