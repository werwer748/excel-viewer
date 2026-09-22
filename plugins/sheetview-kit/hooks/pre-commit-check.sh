#!/bin/sh
# PreToolUse(Bash) 훅 — Claude가 git commit 을 실행하려 할 때 scripts/check.sh 를 먼저 돌린다.
# 통과하면 조용히 빠지고(exit 0), 실패하면 exit 2 로 커밋을 막고 stderr 를 Claude에게 돌려준다.
#
# 이건 git 훅이 아니라 Claude Code 훅이다. 사람이 터미널에서 직접 치는 커밋은 막지 않는다.
# 그쪽까지 막으려면 .git/hooks/pre-commit 에서 같은 스크립트를 부르면 된다:
#     #!/bin/sh
#     exec ./scripts/check.sh
set -u

payload=$(cat)

# `git add -A && git commit -m ...` 같은 합성 명령도 놓치지 않으려고 payload 전체를 훑는다.
# 명령문에 "git commit" 이라는 글자가 들어가기만 해도 검사가 도는 건 감수한다 — 놓치는 것보다 낫다.
case "$payload" in
  *"git commit"*) ;;
  *) exit 0 ;;
esac

PROJECT_DIR=${CLAUDE_PROJECT_DIR:-$PWD}
CHECK="$PROJECT_DIR/scripts/check.sh"

# 검사할 것이 없는 상태라면 커밋을 막을 근거도 없다.
[ -x "$CHECK" ] || exit 0

if output=$(cd "$PROJECT_DIR" && "$CHECK" 2>&1); then
  exit 0
fi

{
  echo "커밋 전 검사가 실패해서 커밋을 막았습니다."
  echo "아래 문제를 고친 뒤 다시 커밋하세요. 직접 돌려보려면: ./scripts/check.sh"
  echo
  echo "$output"
} >&2
exit 2
