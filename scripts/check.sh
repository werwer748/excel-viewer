#!/bin/sh
# lint -> build -> test. 사람이 직접 돌려도 되고, Claude 커밋 전 훅이 부르기도 한다.
#
# 통과하면 exit 0, 어느 단계든 실패하면 실패한 단계까지만 보고하고 exit 1.
# 출력은 그대로 사람에게(또는 훅을 통해 Claude에게) 전달되므로, 무엇을 고쳐야 하는지가
# 마지막 줄들에 남도록 실패 시에만 로그를 뱉는다.
set -u

cd "$(dirname "$0")/.." || exit 1

GRADLE="./gradlew --console=plain --quiet"
fail() { printf '\n%s\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- 1. lint
# 이 프로젝트에는 ktlint/detekt가 없다. 대신 (a) 컴파일러 경고를 0으로 유지하고,
# (b) CLAUDE.md 와 .claude/rules/ 에 근거가 적힌 불변식을 기계로 지킨다. 전부 컴파일러가 잡아주지 않는 것들이다.
printf 'lint  ... '

violations=""
add() { violations="$violations
  - $1"; }

# Apache POI는 의도적으로 넣지 않는다 (~7MB + IDE 클래스로더 충돌). .claude/rules/build-and-deps.md 참고.
grep -qE 'org\.apache\.poi' build.gradle.kts 2>/dev/null &&
  add "build.gradle.kts 에 Apache POI 의존성. 진짜 BIFF .xls는 안내 패널로 처리하기로 한 결정을 되돌리는 변경이다."

# jsoup은 IDE가 부트 클래스패스에 이미 싣고 있어 compileOnly 로만 참조해야 한다.
grep -qE '^[[:space:]]*(implementation|api|runtimeOnly)[[:space:]]*\([[:space:]]*"org\.jsoup' build.gradle.kts 2>/dev/null &&
  add "jsoup 이 compileOnly 가 아닌 스코프로 선언됨. 번들되면 IDE의 1.22.1과 충돌한다."

# since/until-build 는 build.gradle.kts 가 주입한다. plugin.xml 에 두면 소스가 갈린다.
# (주석이 이 태그를 언급하고 있으므로, 속성이 붙은 진짜 태그만 잡는다.)
grep -qE '<idea-version[[:space:]]+[a-z-]+=' src/main/resources/META-INF/plugin.xml 2>/dev/null &&
  add "plugin.xml 에 <idea-version>. 버전은 build.gradle.kts 의 ideaVersion DSL 한 곳에서만 정한다."

# csv/tsv/html 은 번들 grid-core-plugin 과 HTML 에디터가 이겨야 한다.
grep -E 'extensions="[^"]*"' src/main/resources/META-INF/plugin.xml 2>/dev/null |
  grep -qE 'extensions="[^"]*(csv|tsv|html)' &&
  add "plugin.xml 의 fileType 이 csv/tsv/html 을 선점한다. 그쪽은 편집까지 지원하는 번들 플러그인 담당이다."

# StAX 를 새로 만들면서 XXE 방어를 빠뜨리면 billion-laughs 와 외부 엔티티가 그대로 열린다.
for f in $(grep -rlE 'XMLInputFactory\.(newInstance|newFactory)\(' src/main/kotlin 2>/dev/null); do
  grep -q 'SUPPORT_DTD' "$f" ||
    add "$f 의 XMLInputFactory 에 SUPPORT_DTD/IS_SUPPORTING_EXTERNAL_ENTITIES = false 가 없다 (XXE)."
done

# 모든 실패는 패널에 한국어로 보여준다. 스택트레이스를 콘솔에 뱉는 경로는 그 규약을 깬다.
# 위반이 여러 줄이라 add() 대신 sed 로 접두어를 붙인다. 명령 치환으로 받으므로
# 파이프라인 while 이 서브셸에서 도는 문제(예전에 /tmp 파일을 거친 이유)가 없다.
traces=$(grep -rn 'printStackTrace()' src/main/kotlin 2>/dev/null |
  sed 's/^/  - 스택트레이스 출력: /')
if [ -n "$traces" ]; then
  violations="$violations
$traces"
fi

# 플러그인 메타데이터는 plugin.json 한 곳에만 둔다. marketplace.json 엔트리에도 적으면
# 두 곳이 갈라진다 — 실제로 description(영문 vs 한국어)과 keywords 가 어긋난 적이 있다.
if [ -f .claude-plugin/marketplace.json ] && [ -f plugins/sheetview-kit/.claude-plugin/plugin.json ]; then
  dup=$(python3 -c "
import json
try:
    m = json.load(open('.claude-plugin/marketplace.json'))['plugins'][0]
    q = json.load(open('plugins/sheetview-kit/.claude-plugin/plugin.json'))
    print(','.join(sorted((set(m) & set(q)) - {'name'})))
except Exception:
    print('')
" 2>/dev/null)
  [ -n "$dup" ] &&
    add "marketplace.json 엔트리가 plugin.json 과 필드를 중복 선언한다 ($dup). 메타데이터는 plugin.json 한 곳에만."
fi

# 영역 규칙(.claude/rules)은 paths 글로브에 맞는 파일을 Read 할 때만 컨텍스트에 붙는다.
# 패키지를 옮기면 글로브가 아무것도 가리키지 않게 되고 규칙은 조용히 영영 안 붙는다 —
# 훅 마커(SHEETVIEW_SRC_ROOT)와 같은 실패 모양이다. 반대로 paths 가 없는 규칙은 매 세션
# 즉시 로드되어 CLAUDE.md 와 갈라진다. 중괄호 글로브는 python glob 이 펼치지 못해 막는다.
if [ -d .claude/rules ]; then
  rules=$(python3 -c '
import glob, pathlib, re
out = []
for f in sorted(pathlib.Path(".claude/rules").rglob("*.md")):
    front = re.match(r"---\n(.*?)\n---\n", f.read_text(encoding="utf-8"), re.S)
    block = re.search(r"^paths:[ \t]*\n((?:[ \t]+-.*(?:\n|$))+)", front.group(1) + "\n", re.M) if front else None
    globs = re.findall(r"^[ \t]+-[ \t]*[\"\x27]?(.*?)[\"\x27]?[ \t]*$", block.group(1), re.M) if block else []
    if not globs:
        out.append(f"{f}: frontmatter 에 paths 목록이 없다. 매 세션 즉시 로드되므로 그런 내용은 CLAUDE.md 에 둔다.")
    for g in globs:
        if "{" in g:
            out.append(f"{f}: 중괄호 글로브 {g} — lint 가 검사할 수 없다. 항목을 나눠 적는다.")
        elif not glob.glob(g, recursive=True):
            out.append(f"{f}: {g} 에 맞는 파일이 없다. 이 규칙은 영영 붙지 않는다.")
print("\n".join(out))
' 2>/dev/null | grep . | sed 's/^/  - 영역 규칙: /')
  if [ -n "$rules" ]; then
    violations="$violations
$rules"
  fi
fi

if [ -n "$violations" ]; then
  printf 'FAIL\n'
  fail "린트 위반:$violations"
fi

# 타입 검사. 본체와 테스트를 함께 컴파일해 둬야 뒤 단계가 증분으로 빨리 끝난다.
#
# 컴파일러 경고를 게이트로 삼지 않는 이유: 이 툴체인(KGP 2.4 + JBR 25)은 Gradle 출력으로
# 경고를 전혀 내보내지 않는다. 도달 불가 코드를 심어도 --info 에서조차 한 줄도 안 나온다.
# 잡지 못하는 검사를 통과시켜 안심하게 만드는 것보다, 없는 편이 정직하다.
# 스타일 린트가 필요해지면 ktlint/detekt 를 붙이고 이 자리에 넣으면 된다.
lint_log=$($GRADLE compileKotlin compileTestKotlin 2>&1) || {
  printf 'FAIL\n'
  fail "컴파일 실패:
$lint_log"
}
printf 'OK\n'

# --------------------------------------------------------------- 2. build
printf 'build ... '
build_log=$($GRADLE buildPlugin 2>&1) || { printf 'FAIL\n'; fail "빌드 실패:
$build_log"; }
printf 'OK  -> build/distributions/\n'

# ---------------------------------------------------------------- 3. test
printf 'test  ... '
test_log=$($GRADLE test 2>&1) || { printf 'FAIL\n'; fail "테스트 실패:
$test_log

리포트: build/reports/tests/test/index.html"; }
count=$(ls build/test-results/test/TEST-*.xml 2>/dev/null |
  xargs grep -ho 'tests="[0-9]*"' 2>/dev/null |
  sed 's/[^0-9]//g' | awk '{s+=$1} END {print s+0}')
printf 'OK  (%s개 통과)\n' "$count"

# 테스트가 조용히 사라지는 것을 막는 래칫. 통과해도 자동으로 갱신하지 않는다 —
# 테스트를 줄이는 것은 명시적 결정이어야 한다.
baseline=$(cat .claude/tdd-baseline 2>/dev/null || echo 0)
case "$baseline" in
  ''|*[!0-9]*) baseline=0 ;;
esac
if [ "$count" -lt "$baseline" ]; then
  fail "테스트가 $baseline -> $count 로 줄었습니다.
의도한 통합이라면 .claude/tdd-baseline 을 $count 로 갱신하세요."
fi
if [ "$count" -gt "$baseline" ]; then
  printf 'ratchet: .claude/tdd-baseline 을 %s -> %s 로 올리세요.\n' "$baseline" "$count"
fi


# ------------------------------------------------- 4. 미커버 래칫
# tdd-guard 는 **신규 생성만** 막는다. 훅이 생기기 전부터 있던 본체 파일은 면제 목록에도
# 없이 테스트 없이 계속 고칠 수 있다. 그 사각지대가 조용히 넓어지는 것을 막는다.
# 줄어드는 것은 자유다 — 테스트를 붙였으면 .claude/tdd-uncovered 에서 그 줄을 지운다.
UNCOV_SRC="src/main/kotlin/dev/hugo/sheetview"
if [ -d "$UNCOV_SRC" ] && [ -f .claude/tdd-uncovered ]; then
  printf 'uncov ... '
  found=""
  for f in $(find "$UNCOV_SRC" -name '*.kt' | sort); do
    inner=${f#"$UNCOV_SRC"/}
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
    grep -rqlF "$class" src/test/kotlin 2>/dev/null || found="$found$inner
"
  done
  known_f=$(mktemp); found_f=$(mktemp)
  grep -v '^#' .claude/tdd-uncovered 2>/dev/null | grep . | sort > "$known_f"
  printf '%s' "$found" | grep . | sort > "$found_f"
  added=$(comm -13 "$known_f" "$found_f")
  removed=$(comm -23 "$known_f" "$found_f")
  n=$(grep -c . "$found_f" 2>/dev/null || echo 0)
  rm -f "$known_f" "$found_f"
  if [ -n "$added" ]; then
    printf 'FAIL\n'
    fail "테스트에 이름조차 없는 본체 파일이 늘었습니다:
$(printf '%s\n' "$added" | sed 's/^/  - /')

테스트를 붙이거나, 정말 불가능하면 .claude/tdd-exempt.txt 에 사유와 함께 면제하세요.
(면제는 \"샌드박스에서 사람이 확인한다\"는 뜻입니다.)"
  fi
  printf 'OK  (%s개)\n' "$n"
  [ -n "$removed" ] &&
    printf 'ratchet: 커버된 파일이 생겼습니다. .claude/tdd-uncovered 에서 지우세요:\n%s\n' \
      "$(printf '%s\n' "$removed" | sed 's/^/  - /')"
fi

exit 0
