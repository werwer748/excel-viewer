#!/bin/sh
# pr-reviewer 가 낸 점수를 합산해 판정하고, PR 에 달 코멘트 본문을 쓴다.
#
#   사용:  pr-review-gate.sh <코멘트를 쓸 파일>  < 구조화 출력 JSON
#   종료:  0 통과(경고 포함) · 1 머지 차단
#
# **합산과 판정은 모델에게 맡기지 않는다.** 모델은 기준별 점수와 근거만 내고, 더하고 자르고
# 80/70 을 가르는 일은 여기서 한다. 코멘트의 숫자와 체크의 성패가 어긋날 수 없게 하려는 것이다.
# 채점표(무엇을 얼마나 깎는가)는 plugins/sheetview-kit/agents/pr-reviewer.md 에 있다.
# 만점을 바꾸면 그쪽과 아래 CRITERIA 를 함께 고친다.
#
# **점수를 읽지 못하면 통과시키되 침묵하지 않는다.** 토큰 만료·구독 한도 소진·API 장애로
# 리뷰가 안 돌 때마다 저장소 전체의 머지가 잠기면 안 된다. 대신 "돌리지 못했다"는 코멘트를 남긴다.
# (하네스 훅의 원칙과 같다 — .claude/rules/harness.md 의 설계 원칙 2·5.)
set -u

out=${1:?코멘트를 쓸 파일 경로가 필요합니다}

python3 -c '
import json, sys

WARN_BELOW, BLOCK_BELOW = 80, 70
CRITERIA = (
    ("security", "Security", 30),
    ("scope", "Scope", 20),
    ("breaking_change", "Breaking Change", 20),
    ("test_coverage", "Test Coverage", 15),
    ("migration", "Migration", 15),
)
MARKER = "<!-- pr-reviewer -->"
FOOTER = (
    "<sub>`pr-reviewer` 에이전트가 채점했습니다. "
    + str(WARN_BELOW) + "점 미만은 경고, " + str(BLOCK_BELOW) + "점 미만은 머지 차단입니다.</sub>"
)

def write(lines):
    with open(sys.argv[1], "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

def unreadable():
    write([
        MARKER,
        "## PR 리뷰 — ⚠️ 리뷰를 돌리지 못했습니다",
        "",
        "점수를 읽지 못해 **통과로 처리했습니다.** 이 PR 은 채점되지 않았습니다.",
        "Actions 로그에서 원인(토큰 만료 · 구독 한도 · API 장애)을 확인하고, 그 전에는 사람이 직접 리뷰하세요.",
    ])
    print("점수 없음 — 통과 처리")
    sys.exit(0)

try:
    data = json.load(sys.stdin)
    raw = data["scores"]
    report = data.get("report", "")
    # bool 은 int 의 하위 타입이라 따로 거른다.
    if not all(type(raw[key]) is int for key, _, _ in CRITERIA) or not isinstance(report, str):
        raise ValueError
except Exception:
    unreadable()

rows, total = [], 0
for key, label, full in CRITERIA:
    score = max(0, min(full, raw[key]))
    total += score
    rows.append("| " + label + " | " + str(score) + " / " + str(full) + " |")

if total < BLOCK_BELOW:
    verdict, code = "⛔ 머지 차단", 1
    note = str(BLOCK_BELOW) + "점 미만이라 이 체크가 실패합니다. 아래 감점을 고친 뒤 다시 푸시하세요."
elif total < WARN_BELOW:
    verdict, code = "⚠️ 경고", 0
    note = str(WARN_BELOW) + "점 미만입니다. 머지는 가능하지만 아래 감점을 확인하세요."
else:
    verdict, code = "✅ 통과", 0
    note = ""

lines = [MARKER, "## PR 리뷰 " + str(total) + " / 100 — " + verdict, ""]
if note:
    lines += [note, ""]
lines += ["| 기준 | 점수 |", "|---|---|"] + rows + ["", report.strip(), "", FOOTER]
write(lines)
print(str(total) + " / 100 — " + verdict)
sys.exit(code)
' "$out"
