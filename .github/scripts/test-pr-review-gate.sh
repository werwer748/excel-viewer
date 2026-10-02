#!/bin/sh
# pr-review-gate.sh 의 테스트. 이 스크립트가 틀리면 70점 미만 PR 이 조용히 머지되거나,
# 리뷰가 한 번 실패했다고 저장소 전체의 머지가 잠긴다.
#
# 경계(80·79·70·69)와 "판단이 안 되면 통과시키되 침묵하지 않는다"를 고정한다.
set -u

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd) || exit 1
GATE="$DIR/pr-review-gate.sh"
OUT=$(mktemp) || exit 1
trap 'rm -f "$OUT"' EXIT INT TERM

pass=0; fail=0

scores() {   # <security> <scope> <breaking_change> <test_coverage> <migration>
  printf '{"scores":{"security":%s,"scope":%s,"breaking_change":%s,"test_coverage":%s,"migration":%s},"report":"### Security\\n- 지적 없음"}' \
    "$1" "$2" "$3" "$4" "$5"
}

gate_case() {   # <설명> <기대 exit> <본문에 있어야 할 문자열> <입력 JSON>
  desc=$1; want=$2; needle=$3; input=$4
  : > "$OUT"
  printf '%s' "$input" | sh "$GATE" "$OUT" >/dev/null 2>&1
  got=$?
  if [ "$got" != "$want" ]; then
    fail=$((fail + 1)); printf '  FAIL  %-40s (기대 exit %s, 실제 %s)\n' "$desc" "$want" "$got"
  elif ! grep -qF -- "$needle" "$OUT"; then
    fail=$((fail + 1)); printf '  FAIL  %-40s (본문에 "%s" 없음)\n' "$desc" "$needle"
  else
    pass=$((pass + 1)); printf '  ok    %-40s exit %s\n' "$desc" "$got"
  fi
}

echo "pr-review-gate.sh — 점수 판정"

gate_case "100점은 통과"                    0 "100 / 100 — ✅ 통과"   "$(scores 30 20 20 15 15)"
gate_case "80점은 통과 (경고 아님)"         0 "80 / 100 — ✅ 통과"    "$(scores 30 20 20 10 0)"
gate_case "79점은 경고 후 통과"             0 "79 / 100 — ⚠️ 경고"    "$(scores 30 20 20 9 0)"
gate_case "70점은 경고 후 통과 (차단 아님)" 0 "70 / 100 — ⚠️ 경고"    "$(scores 0 20 20 15 15)"
gate_case "69점은 머지 차단"                1 "69 / 100 — ⛔ 머지 차단" "$(scores 0 20 20 15 14)"
gate_case "0점은 머지 차단"                 1 "0 / 100 — ⛔ 머지 차단"  "$(scores 0 0 0 0 0)"

# 합산은 스크립트가 한다. 모델이 만점을 넘겨 적어도 합계가 부풀지 않아야 한다.
gate_case "만점 초과는 만점으로 자른다"     1 "| Security | 30 / 30 |" "$(scores 99 0 0 0 0)"
gate_case "음수는 0 으로 자른다"            0 "| Scope | 0 / 20 |"     "$(scores 30 -5 20 15 15)"

gate_case "기준별 점수표가 있다"            0 "| Test Coverage | 15 / 15 |" "$(scores 30 20 20 15 15)"
gate_case "에이전트의 보고가 본문에 실린다" 0 "- 지적 없음"            "$(scores 30 20 20 15 15)"
# 워크플로가 이 마커로 기존 코멘트를 찾아 갱신한다. 첫 줄이 아니면 푸시마다 코멘트가 쌓인다.
gate_case "마커가 있다"                     0 "<!-- pr-reviewer -->"   "$(scores 30 20 20 15 15)"
if [ "$(head -n 1 "$OUT")" = "<!-- pr-reviewer -->" ]; then
  pass=$((pass + 1)); printf '  ok    %s\n' "마커가 첫 줄이다"
else
  fail=$((fail + 1)); printf '  FAIL  %s\n' "마커가 첫 줄이다"
fi

# 리뷰를 못 돌린 경우(토큰 없음·한도 소진·API 장애). 머지를 잠그지 않되 코멘트로 알린다.
gate_case "빈 입력은 통과 + 미실행 안내"    0 "리뷰를 돌리지 못했습니다" ""
gate_case "깨진 JSON 은 통과 + 미실행 안내" 0 "리뷰를 돌리지 못했습니다" "not json"
gate_case "scores 가 없으면 미실행"         0 "리뷰를 돌리지 못했습니다" '{"report":"x"}'
gate_case "기준이 하나 빠지면 미실행"       0 "리뷰를 돌리지 못했습니다" \
  '{"scores":{"security":30,"scope":20,"breaking_change":20,"test_coverage":15},"report":"x"}'
gate_case "점수가 숫자가 아니면 미실행"     0 "리뷰를 돌리지 못했습니다" \
  '{"scores":{"security":"30","scope":20,"breaking_change":20,"test_coverage":15,"migration":15},"report":"x"}'
# bool 은 int 의 하위 타입이다. isinstance 로 거르면 true 가 1점으로 채점된다.
gate_case "bool 점수는 미실행"              0 "리뷰를 돌리지 못했습니다" \
  '{"scores":{"security":true,"scope":20,"breaking_change":20,"test_coverage":15,"migration":15},"report":"x"}'
gate_case "실수 점수는 미실행"              0 "리뷰를 돌리지 못했습니다" \
  '{"scores":{"security":30.0,"scope":20,"breaking_change":20,"test_coverage":15,"migration":15},"report":"x"}'

printf '\n%s개 통과 · %s개 실패\n' "$pass" "$fail"
[ "$fail" = 0 ] || exit 1
