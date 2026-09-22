---
name: sandbox-run
description: Spreadsheet Viewer(JetBrains 플러그인)를 샌드박스 IDE에 띄워 사람이 직접 써볼 수 있게 넘긴다. "띄워줘 / 실행해봐 / 직접 써볼래 / 샌드박스로 확인하고 싶어 / 진짜 되는지 눈으로 보고 싶어 / 작업 결과물 확인하고 싶어"라고 할 때 쓸 것. 테스트로는 확인할 수 없는 것 — Swing 조립, 탭 순서와 전환, JCEF 미리보기, 탭을 닫을 때의 취소·해제 — 을 고친 뒤라면 특히 그렇다. 빌드·검사를 먼저 끝내고 백그라운드로 IDE를 올린 뒤 이번 변경분에 맞는 확인 항목을 뽑아 넘기고, 사람이 다 써보고 나면 이번 기동 구간 로그만 잘라 예외·EDT 블로킹을 보고한다. UI를 대신 조작하지는 않는다 — 써보는 건 사람이다.
---

# 샌드박스에 띄워 사람에게 넘긴다

이 플러그인에는 **읽어서 판단할 수 없는 부분**이 있다. `.claude/tdd-exempt.txt` 가 그걸 이미 인정한다 — 면제된 파일 열 개에 붙은 사유가 전부 "IDE 없이 단정 불가"이고, 파일 머리에 이렇게 적혀 있다:

> 면제는 "테스트하지 않아도 된다"가 아니라 **"샌드박스에서 사람이 확인한다"**는 뜻이다.

이 스킬이 그 "사람이 확인한다"의 절차다. 면제의 대가를 실제로 치르는 자리다.

## 이 절차의 경계

**띄우는 데까지가 범위다.** 써보는 건 사람이 직접 한다.

- UI를 대신 조작하지 않는다. 자동 클릭도, 스크린샷 판독도 없다.
- 코드를 고치지 않는다. 샌드박스에서 문제가 나오면 **보고**하고 끝낸다.
- 하는 일은 셋이다: ① 띄울 수 있는 상태로 만든다 ② 백그라운드로 띄운다 ③ **무엇을 볼지** 알려주고, 다 본 뒤 로그로 뒷받침한다.

세 번째가 이 절차의 값어치다. 사람은 이미 샌드박스를 띄울 줄 안다. 모르는 건 "이번 변경 때문에 무엇이 깨질 수 있는가"이고, 놓치는 건 **로그에만 남는 예외**다.

---

## 순서

### 0. 이미 떠 있는지부터 본다

```bash
pgrep -f "idea.plugin.in.sandbox.mode=true"
```

걸리면 **새로 띄우지 않는다.** 두 번째 인스턴스는 샌드박스 config를 두고 다투고, 어차피 Gradle 락에 걸려 뜨지도 않는다. PID와 로그 경로를 보고하고 **"이미 떠 있는 걸 그대로 쓸지, 닫고 새로 띄울지"** 묻고 멈춘다.

그대로 쓰기로 했다면 2단계(검사)를 건너뛰고 — 락 때문에 어차피 못 돈다 — 3단계 로그 마킹부터 한다. 단 **지금 떠 있는 IDE에는 이번에 고친 코드가 안 들어 있을 수 있다**는 사실을 반드시 같이 적는다.

### 1. 띄우기 전에 무엇을 볼지 정한다

```bash
git status --short
git diff --stat
```

그 다음 **`.claude/tdd-exempt.txt` 를 읽는다.** 면제 목록은 곧 "샌드박스에서 확인하기로 한 것"의 목록이고, 사유 칸에 **무엇을 확인해야 하는지가 이미 적혀 있다**:

| 바뀐 파일 | 사유가 지시하는 확인 |
|---|---|
| `editor/SheetPanel.kt` | JBLoadingPanel/JBTable 렌더 — 로딩 중과 로딩 후가 둘 다 보이는가 |
| `editor/SourcePanel.kt` | 모드 전환 — 파트를 여러 개 골라도 배너가 쌓이지 않는가 |
| `editor/AsyncFileEditor.kt` | **탭을 닫아** 취소 규약 확인. 큰 파일을 여는 중에 닫아본다 |
| `editor/TextViewer.kt` | EditorFactory 누수 — 탭을 여러 번 열고 닫아본다 |
| `preview/JcefHtmlPreview.kt` | 미리보기 버튼이 IDE를 멈추지 않는가, 스타일이 살아 있는가 |
| `editor/SheetTableModel.kt` | 표가 직사각형인가, 병합 셀이 펼쳐지는가 |

변경된 파일이 면제 목록에 있으면 **그게 1순위 확인 항목**이다. 없으면(리더·모델·export만 고쳤다면) 테스트가 이미 답한 영역이므로 확인 항목을 억지로 만들지 말고 공통 항목으로 간다.

확인 항목은 **"무엇을 연다 → 무엇이 보여야 한다"** 한 쌍으로만 쓴다. "SheetPanel이 잘 동작하는지 확인" 같은 건 항목이 아니다.

#### 픽스처 대응표

픽스처는 저장소 안에 있고 샌드박스는 직전에 열었던 프로젝트를 다시 연다. **복사하거나 준비할 것이 없다** — 경로만 적어주면 된다.

| 열 파일 (`src/test/resources/fixtures/`) | 보여야 하는 것 |
|---|---|
| `statement.xls` | HTML 위장 `.xls`. **표** 탭이 먼저 선택, 2단 헤더(rowspan+colspan) 정렬, `원본` 탭에 스타일 살아있는 원문 |
| `shuffled-parts.xlsx`, `sheet-order.xlsx` | zip 엔트리 순서·시트 순서가 섞여도 시트 탭 순서가 맞는가 |
| `big.xlsx` | 여는 동안 IDE가 얼지 않는가. 상한에 걸리면 부분 읽기 배너 |
| `legacy.xls` (진짜 BIFF), `binary.xlsb` | 예외가 아니라 **미지원 안내 패널**. 한국어 한 문장 + 다음 행동 |
| `corrupt.xlsx`, `empty.xls`, `no-table.xls` | 빈 화면이 아니라 안내 패널. **조용한 빈 패널이 제일 나쁜 결과다** |
| `cp949.csv` | 우클릭 → **표로 열기**. 한글이 깨지지 않고 `"1,500"` 이 한 셀 |
| `sml2003.xml` | 우클릭 → **표로 열기** |
| `notsheet.xlsx` | ZIP이지만 워크북이 아니다 — 안내 패널 |

**여기에 더해 항상 넣는 항목 하나**: *"본인이 가진 실제 명세서·업무 파일도 하나 열어보세요."* 픽스처는 내가 만든 것이라 내가 상상한 모양만 담고 있다. 실제 파일에서만 드러나는 자리가 있다.

#### 공통 항목 (어떤 변경이든)

- 탭 순서 — **표**가 먼저 선택되는가 (`plugin.xml` 의 `fileEditorProvider` 등록 순서)
- 툴바 4개: 다시 읽기 / 첫 행을 머리글로 / 시트 전체 복사 / 내보내기
- 타이핑 검색
- **탭을 닫았다 다시 여는 것** — 누수와 취소 규약은 이걸로만 드러난다

### 2. 검사를 먼저 끝낸다 — Gradle 락 때문에

```bash
./scripts/check.sh
```

**runIde가 뜬 뒤에는 어떤 `./gradlew` 명령도 프로젝트 락 대기로 멈춘다.** 새로 알아낸 사실이 아니다 — `sheetview-kit` 플러그인의 `hooks/tdd-red.sh` 가 `pgrep -f 'runIde'` 로 red 검사를 건너뛰는 이유가 바로 이것이다. **순서를 어기면 세션이 조용히 멈춘다.** 검사는 전부 띄우기 전에 끝낸다.

`check.sh` 가 실패하면 **띄우지 않고** 실패를 보고한다. 단 `CLAUDE.md` 의 "테스트가 red인데 내가 고친 것과 무관해 보이면" 항목대로, 진행 중인 기능이 테스트를 먼저 올려 둔 상태일 수 있다. `TODO.md` 의 '추가할 기능'을 확인하고 그 가능성을 함께 적어 사람 판단을 구한다.

### 3. 로그 경계를 찍는다

```bash
LOG=$(ls .intellijPlatform/sandbox/excel-viewer/*/log_runIde/idea.log 2>/dev/null | head -1)
MARK=$(wc -l < "$LOG" 2>/dev/null || echo 0)
```

**`idea.log` 는 기동할 때마다 덧붙는다. 지우고 새로 쓰지 않는다.** 지금도 한 파일 안에 서로 다른 세 번의 실행이 섞여 있다. 이 줄 수를 기록해두지 않으면 6단계에서 **지난 기동의 예외를 이번 것으로 착각해 보고한다** — 이 절차에서 가장 틀리기 쉬운 자리다.

IDE 접두어(`WS-2026.2.3`)는 `~/.gradle/gradle.properties` 의 `localIdePath` 에 따라 달라진다. 고정 경로로 쓰지 말고 글로브로 찾는다. 로그 파일이 아직 없으면 `MARK=0` 이다.

### 4. 백그라운드로 띄운다

```bash
./gradlew runIde
```

**반드시 `run_in_background: true` 로 부른다.** 이 한 줄을 포그라운드로 부르는 순간 세션이 IDE 수명 내내 멈추고 이 절차 전체가 무의미해진다. `CLAUDE.md` 의 runIde 금지 조항이 예외를 허용하는 것도 이 조건 하나 때문이다.

기동 확인은 폴링으로 한다 (~150초까지 기다린다. `localIdePath` 가 없어 원격 IDE 아티팩트를 받아야 하면 훨씬 더 걸릴 수 있고, 그건 실패가 아니다):

```bash
pgrep -f "idea.plugin.in.sandbox.mode=true"
tail -n +$((MARK+1)) "$LOG" | grep -E "AppStarter - (IDE|PID):"
```

**플러그인이 로드되지 않으면 IDE가 바로 죽는다** — 기동 명령에 `-Didea.required.plugins.id=dev.hugo.spreadsheet-viewer` 가 자동으로 붙기 때문이다. 즉 **프로세스가 살아 있으면 플러그인 로드는 성공한 것**이고, 떴다가 죽었다면 그 자체가 결과다.

뜨지 않으면 백그라운드 출력과 `MARK` 이후 로그를 근거로 원인을 보고하고 **멈춘다.** 같은 명령을 다시 던지지 않는다 — 두 번째 시도는 첫 번째가 남긴 락에 걸린다.

### 5. 인계한다

여기서 한 번 끊는다. 사람이 쓸 차례다.

### 6. 사후 점검 — 사람이 "확인 끝"이라고 하면

```bash
tail -n +$((MARK+1)) "$LOG"
```

**이번 구간만 본다.** 여기서 찾는 것:

- `SEVERE` / `Unhandled exception` / `PluginException`
- 스택에 `dev.hugo.sheetview` 가 들어간 모든 것 — 남의 예외보다 훨씬 중하다
- `ms to grab EDT` — EDT 블로킹. 이미 `351 ms to grab EDT for SheetPanel$ToggleHeaderAction` 이 찍힌 적이 있다
- `Slow operations are prohibited on EDT`
- `already disposed` / `Memory leak detected` — dispose 누수
- `EditorComposite.createTabbedPaneWrapper` 가 든 `IndexOutOfBoundsException` — 에디터 아래 탭 줄(**표** / **원본** / `Data`)의 선택 인덱스가 실제 탭 수와 어긋난 것. 이 플러그인이 `fileEditorProvider` 를 두 개 등록하고 번들 `scripted-data-editor` 가 하나 더 붙이는 바로 그 자리라 남의 일이 아니다. **이미 한 번 찍힌 적이 있다** — `Index 2 out of bounds for length 2`, 탭을 바꾸던 중. 스택에 `dev.hugo` 프레임이 없다고 넘기지 마라: 탭 구성은 우리가 만들고 선택은 플랫폼이 한다.
- `not registered as a service` — `CLAUDE.md` 가 경고한 JCEF 확장 포인트 오사용 신호
- 백그라운드 출력에 스택트레이스가 맨몸으로 찍혔는지 — `printStackTrace()` 금지 규약 위반

#### 무시할 잡음 (실제 로그에서 확인한 것들)

이걸 지적으로 올리면 보고가 쓸모없어진다:

- `jcef_cache_temp/... NoSuchFileException ... is deleted while indexing` — JCEF가 임시 캐시를 지우는 동안 인덱서가 읽은 것
- `GitRepositoriesHolder - State of repository ... is not synchronized`
- `GitCommitTemplateTracker - Empty or blank commit template`
- `CodeWithMeCleanup - Starting the logs folder cleanup`
- `build/test-results/... is deleted while indexing is running`
- `DataSourceStorage... - Attempting to store empty state`

---

## 보고 형식

### 인계할 때 (5단계)

```markdown
## 샌드박스 기동: <이번에 고친 것>

**검사**: `./scripts/check.sh` ✓ (N개 통과)     ← N은 실제 출력값
**IDE**: <제품 버전> / PID <pid>
**로그**: <경로> (이번 구간은 <MARK>행 이후)

### 이번 변경 때문에 꼭 볼 것
1. `src/test/resources/fixtures/statement.xls` 를 연다 → <무엇이 보여야 하는가>
2. <파일> → <보여야 하는 것>

### 겸사겸사
- <공통 항목 중 관련 있는 것>
- 본인이 가진 실제 파일도 하나 열어보세요

---
다 보시면 **"확인 끝"** 이라고 알려주세요. 이번 기동 구간 로그만 훑어 보고하겠습니다.
닫는 법: IDE 창을 그냥 닫으면 됩니다 — 그래야 Gradle 락이 풀려 `check.sh` 가 다시 돕니다.
안 닫히면: `pkill -f "idea.plugin.in.sandbox.mode=true"`
```

### 사후 점검 (6단계)

`sheetview-kit:spreadsheet-review` 와 같은 형식을 쓴다.

```markdown
## 샌드박스 로그: <기동 구간 N행 이후, 총 M행>

### 🔴 샌드박스에서 실제로 깨졌다
1. **<한 줄 요약>** — `idea.log:<줄>`
   증상: <로그에 찍힌 것>
   지목: <우리 코드 어디인가 — 스택에서 dev.hugo 프레임>
   재현: <사람이 무엇을 했을 때인가>

### 🟡 로그에 남았지만 치명적이지 않다
### 🟢 참고
### 확인했고 문제없던 것
- <이번 구간에서 훑었지만 깨끗했던 항목>
```

마지막 줄은 판정 하나: **"샌드박스 확인 통과"** 또는 **"🔴 N건 — 샌드박스에서 실제로 깨졌다"**.

## 판단의 기준

- **로그가 깨끗한 것과 동작이 맞는 것은 다르다.** 예외 없이 잘못된 표가 그려지는 것이 이 플러그인의 전형적 실패다. 로그 보고에 "사람이 눈으로 본 결과가 최종"이라는 걸 흐리지 마라.
- **지난 구간의 예외를 이번 것으로 보고하지 마라.** `MARK` 을 놓쳤으면 놓쳤다고 말하고 전체를 보고하되 그 사실을 적는다.
- **없으면 없다고 말한다.** 잡음을 🟡로 올려 건수를 채우는 것이 이 보고를 쓸모없게 만드는 가장 빠른 길이다.
