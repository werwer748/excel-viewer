---
name: spreadsheet-review
description: Spreadsheet Viewer(JetBrains 플러그인, Kotlin) 코드를 리뷰한다. 리더·스니퍼·파서(XlsxReader, ExcelHtmlReader, DelimitedReader, SpreadsheetMlReader, SpreadsheetSniffer)나 에디터(SheetFileEditor, SheetPanel), plugin.xml, build.gradle.kts 를 고친 뒤 커밋·배포 전에, 또는 "리뷰해줘 / 검토해줘 / 이대로 괜찮아? / 버그 없나 봐줘 / 이거 문제 없지?"라고 물을 때 반드시 사용할 것. 신뢰할 수 없는 파일을 IDE 프로세스 안에서 파싱하는 플러그인이라 포맷 오판·자원 상한 누락·EDT 블로킹·예외 규약 위반처럼 컴파일러도 테스트도 잡아주지 않는 결함을 사람 대신 찾아낸다. 사용자가 '리뷰'라는 말을 쓰지 않고 "이거 괜찮아?"라고만 물어도 대상이 이 플러그인 코드면 이 스킬을 쓸 것.
---

# Spreadsheet Viewer 코드 리뷰

이 플러그인의 입력은 **적대적**이다. 카드사·은행·관공서가 "엑셀로 저장"으로 뱉어낸,
확장자와 내용이 어긋난 파일을 받아 **IDE 프로세스 안에서** 파싱한다.
여기서 잘못되면 파일 하나가 깨지는 게 아니라 사용자의 IDE가 통째로 얼거나 죽는다.
그래서 일반적인 Kotlin 리뷰와 보는 곳이 다르다.

이 프로젝트의 세 가지 전제를 먼저 머리에 넣어라. 지적의 대부분이 여기서 나온다.

1. **확장자를 믿지 않는다.** 포맷은 `SpreadsheetSniffer`가 앞 8KB 내용으로 정한다.
   이 원칙을 깨는 변경이 이 플러그인이 존재하는 이유를 지운다.
2. **예외는 사용자에게 스택트레이스로 새지 않는다.** 모든 실패는
   `UnsupportedSpreadsheetException` / `SpreadsheetParseException`으로 모여 패널에 한국어로 표시된다.
3. **한 ZIP이 WebStorm·IDEA·PyCharm·CLion·DataGrip에 그대로 설치된다.**
   특정 IDE에만 있는 클래스나 번들 라이브러리에 기대면 다른 IDE에서 `NoClassDefFoundError`로 죽는다.

## 코드 지도

어디를 봐야 하는지 매번 찾지 않도록. **규칙** 열은 그 영역의 "되돌리면 안 되는 결정"과 측정 근거다 (`.claude/rules/` 기준).
서브에이전트에서 규칙이 자동으로 붙는다는 보장은 없으므로, 리뷰할 영역의 규칙 파일은 **직접 Read 한다.**
**체크리스트** 열은 그 영역이 바뀌었을 때 아래 ①~⑧ 중 볼 항목이다. 바뀐 경로들의 합집합만 본다.

| 영역 | 파일 | 규칙 | 체크리스트 |
|---|---|---|---|
| 포맷 판별 | `format/SpreadsheetSniffer.kt` | `filetype-and-tabs.md`, `errors-and-limits.md` | ①③ |
| 파일 타입·탭 | `filetype/SpreadsheetFileType.kt`, `filetype/TextFileTypes.kt`, `resources/META-INF/plugin.xml` | `filetype-and-tabs.md` | ①④⑦ |
| 진입점·크기 가드 | `format/SpreadsheetReaders.kt` | `errors-and-limits.md` | ②③ + 네 리더 교차 |
| 계약(상한·취소·예외) | `format/SpreadsheetReader.kt` | `errors-and-limits.md` | ②③ + 네 리더 교차 |
| 파서 | `format/XlsxReader.kt`, `ExcelHtmlReader.kt`, `SpreadsheetMlReader.kt`, `DelimitedReader.kt` | `table-parsing.md`, `errors-and-limits.md` | ②③⑤⑥ + 네 리더 교차 |
| 표 모델 | `model/SheetData.kt`, `format/CellTypeInference.kt` | `table-parsing.md` | ⑤⑥ |
| 내보내기 | `export/ExportFormats.kt` | `errors-and-limits.md` | ⑥ (EDT 에서 불리는 경로가 바뀌었으면 ④) |
| IDE 통합 | `editor/SheetFileEditor.kt`, `SheetPanel.kt`, `SheetEditorProvider.kt`, `actions/`, `resources/META-INF/plugin.xml` | `swing-editor.md`, `filetype-and-tabs.md` | ③④ |
| 원본 탭·미리보기 | `editor/Source*.kt`, `source/`, `preview/`, `resources/META-INF/sheetview-jcef.xml` | `source-tab-light.md`, `jcef-preview.md` | ③④⑦ |
| 빌드·호환성 | `build.gradle.kts`, `gradle.properties` | `build-and-deps.md` | ⑦ |

⑧(테스트)은 `src/main` 이 바뀌었으면 항상 본다. 고른 항목에 없는 체크리스트는 따지지 않고,
보고서에 `해당 없음: ①②⑦ (변경 경로 밖)` 한 줄만 남긴다.

## 순서

리뷰 시간은 도구가 아니라 **모델 턴 수**로 정해진다. TSV 사이클의 리뷰 두 번을 재 보니
도구 실행은 전체의 2~7%였고 나머지는 턴이었다(턴당 약 20초). 그래서 아래 절차는 턴을 줄이는 쪽으로 짜여 있다.

### 0. 브리핑을 먼저 본다

호출한 쪽이 리뷰 대상(git 범위), 바뀐 것, 자동 검사 결과, 규칙 파일, (재리뷰면) 이전 지적을 줬으면
그걸로 시작한다. 다시 찾지 않는다. 브리핑 템플릿은 `feature-cycle` 스킬의 ③ 에 있다.
이전 지적이 있으면 아래 '재리뷰(델타) 모드'로 간다.

### 1. 첫 턴에 한꺼번에 읽는다

- `git status --short` · `git diff --stat` · `git diff <범위>`, 그리고 브리핑에 있는 규칙 파일·변경 파일의 Read 를
  **한 메시지에 병렬로** 호출한다. 브리핑이 없으면 첫 턴은 git 만 하고, 둘째 턴에 Read 를 몰아서 한다.
- 바뀐 함수를 **호출하는 쪽**과 나머지 리더처럼 따라가며 읽을 파일도 한 턴에 묶는다.
  리더 한 곳을 고쳤으면 **나머지 세 리더의 같은 자리도 본다.** 네 리더가 같은 계약
  (`ReadLimits`·`checkCancelled`·직사각형 그리드)을 각자 구현하고 있어서, 한쪽만 고치면 조용히 갈라진다.
- **같은 파일을 두 번 Read 하지 않는다.** 일부만 필요해 보여도 처음에 전체를 읽는다 — 이 프로젝트의 파일은 전부 작다.
- grep 은 목적별로 흩지 말고 `-e` 를 여러 개 붙여 한 번에 묶는다.
- 범위를 모르겠으면 전체를 보되 위험도 순서(파서 → 에디터 → 나머지)로 본다. 전체를 볼 수 있는 규모다.

### 2. 기계가 잡을 수 있는 건 기계에 맡긴다

- **브리핑에 자동 검사 결과가 있으면 다시 돌리지 않는다.**
- 없으면 `./gradlew test` 를 1번의 병렬 호출에 같이 넣는다. 파서 테스트는 실제 명세서와 픽스처 기반이라 회귀를 잘 잡는다.
- `./scripts/check.sh` 는 돌리지 않는다. 빌드까지 해서 느리고, 커밋 전에 호출한 쪽이 돌린다.
- `./gradlew verifyPlugin` 도 직접 돌리지 않는다(몇 분 걸린다). `plugin.xml`·`build.gradle.kts`·플랫폼 API 를 건드렸는데
  브리핑에 결과가 없으면 🟡 에 "verifyPlugin 필요"로 적는다.

리더·모델은 플랫폼 클래스를 쓰지 않아 순수 JVM 테스트로 돈다. **이게 이 프로젝트의 설계 자산이다** —
리뷰 중에 리더 쪽으로 플랫폼 의존이 새어 들어오는 변경을 보면 그 자체가 지적거리다.

테스트가 깨졌다면 **그것부터 보고한다.** 나머지 리뷰는 그 다음이다.
**진행 중인 기능이 테스트를 먼저 올려 둬서 이미 red 일 수 있다.** 실패를 지적하기 전에
그것이 요청받은 변경 때문인지 확인하고, 아니면 `TODO.md`의 진행 중 항목을 근거로 그 사실만 적는다.

### 3. 코드 지도가 고른 체크리스트만 훑는다

위쪽이 더 치명적이다. 코드 지도의 **체크리스트** 열로 고른 항목만 본다.
각 항목에서 **"이 코드를 깨뜨리는 구체적인 파일"** 을 떠올려 보고, 떠오르지 않으면 지적하지 않는다.

**① 포맷 판별 — 확장자를 믿는 순간이 있는가**

- 새 분기를 `SpreadsheetSniffer`에 넣었다면 `SpreadsheetReaders.readerFor` **두 곳이 모두**
  갱신됐는가. enum이 늘면 `when`이 컴파일 에러로 알려주지만, 기존 enum의 의미를 바꾼 경우는
  조용히 어긋난다. (내용 기반 `fileTypeDetector`는 쓰지 않는다 — 확장자 매핑이 이긴다는 것을
  측정으로 확인했다. 근거는 `.claude/rules/filetype-and-tabs.md`.)
- 스니퍼는 **앞 8KB만** 본다. 파일 전체가 있다고 가정한 판별 로직(예: 닫는 태그 확인, 전체 길이 검사)은 틀린다.
- 선행 공백/CRLF 건너뛰기와 BOM 처리가 살아 있는가. 대상 파일은 `<html`이 오프셋 0에 없다 —
  이게 기존 플러그인들이 죽는 바로 그 지점이다.
- 판별을 **더 관대하게** 만드는 변경이 특히 위험하다. CSV로 떨어지는 조건이 넓어지면
  깨진 바이너리가 CSV로 열려 쓰레기 표가 나온다. "알 수 없음"이 잘못된 표보다 낫다.
- `fileTypeDetector`를 **다시 도입하려는 변경이면** 그 자체를 지적한다 — `extensions` 매핑이
  탐지기보다 먼저 평가되므로 불리지 않는다(`FileTypeAndTabsTest`가 회귀를 잡는다). 도입이
  불가피하다면 탐지기는 VFS 인덱싱 경로에서 불리므로 무거운 일(전체 파싱, 파일 재열기,
  다른 탐지기 재귀 호출)을 하면 IDE 전체가 느려진다는 점까지 확인한다.

**② 자원 상한과 취소 — 악의적이지 않아도 큰 파일은 온다**

- 모든 행/셀 루프가 `ctx.limits`(`maxRows`·`maxColumns`·`maxCells`·`maxSharedStrings`·`maxScanRows`)를
  존중하는가. **행만 막는 것은 부족하다** — Excel HTML은 빈 `<td>`를 수천 열씩 뿜어
  행 상한 안에서도 메모리를 다 쓴다. 그래서 `rowLimit(columnCount)`가 있다.
- 루프 안에서 `ctx.checkCancelled()`를 주기적으로 부르는가 (기존 코드는 2048행/8192항목 간격).
  이게 빠지면 탭을 닫아도 파싱 스레드가 계속 돌고 취소가 먹지 않는다.
- 새 `XMLInputFactory`를 만들었다면 `SUPPORT_DTD = false`,
  `IS_SUPPORTING_EXTERNAL_ENTITIES = false`가 설정됐는가.
  빠지면 XXE와 billion-laughs가 그대로 열린다 (기존 두 리더에는 들어 있다 — 새로 추가한 쪽만 확인하면 된다).
- ZIP 항목 이름을 경로로 쓰는 곳(`resolveTarget`의 `../` 처리)이 ZIP 바깥으로 나가지 않는가.
- `SpreadsheetReaders.guardSize`의 전제가 유지되는가 — 스트리밍이면 512MB, **파일 전체를
  String으로 올리는 경로면 64MB**. 새 리더가 전체를 메모리에 올리는데 스트리밍 상한을 쓰면 OOM이다.
- 잘랐으면 `truncated`와 `totalRowCount`로 사용자에게 알리는가. 조용히 자르면 사용자는 없는 데이터를 없다고 믿는다.

**③ 실패 경로 — 예외가 사용자에게 어떻게 보이는가**

- 새로 던지는 예외가 `UnsupportedSpreadsheetException`(읽을 수 없지만 정상적인 상황) /
  `SpreadsheetParseException`(포맷은 맞는데 깨짐) 중 맞는 쪽인가.
  `userMessage`는 한국어 한 문장이고, `hint`는 **사용자가 할 수 있는 다음 행동**인가.
  "파싱 실패"는 메시지가 아니다. "엑셀에서 .xlsx로 저장한 뒤 다시 열어 주세요"가 메시지다.
- **`ProcessCanceledException`과 `CancellationException`을 삼키지 않는가.**
  `catch (t: Throwable)`이나 `runCatching`이 이 둘보다 **먼저** 오면 그게 삼키는 것이다.
  catch 절의 순서를 눈으로 확인하라 — 이건 컴파일러가 잡아주지 않는다.
- 반대로, 사용자에게 보여야 할 실패가 로그로만 가고 패널이 빈 채 남는 경우가 없는가.
- `finally`에서 로딩 표시를 걷어내는 경로가 취소 시에도 도는가 (`NonCancellable`이 그 역할이다).

**④ IDE 플랫폼 규약 — 한 줄이 IDE를 얼린다**

- **Swing 객체는 EDT에서만 만진다.** 파싱 결과를 패널에 넣는 지점이 `Dispatchers.EDT`
  안에 있는가. 반대로 **파싱이 EDT에서 돌지 않는가** — `Dispatchers.Default`로 나가야 한다.
- `FileEditorProvider.accept`는 파일을 열 때마다 모든 provider에 대해 불린다.
  여기서 **파일 내용을 읽으면 안 된다.** O(1) 확장자 검사로 유지되는가.
- `acceptRequiresReadAction() = false`인데 PSI/인덱스를 건드리는 코드가 들어오지 않았는가.
  PSI를 쓸 거면 읽기 락이 필요하고, 그러면 이 선언이 거짓말이 된다.
- `AnAction`에 `getActionUpdateThread()`가 있는가. 없으면 런타임 경고가 뜬다.
  `update()`에서 무거운 일을 하면 BGT라도 툴바가 버벅인다.
- `dispose()`가 **연 것을 전부 닫는가** — 코루틴 스코프, 임시 파일, 메시지버스 연결.
  `messageBus.connect(this)`처럼 Disposable에 묶인 것은 자동이지만, 직접 만든 것은 직접 닫아야 한다.
  에디터 탭은 수십 번 열리고 닫히므로 누수는 반드시 쌓인다.
- VFS 변경 리스너가 **자기가 쓴 파일에 반응해 무한 재읽기**로 빠지지 않는가.

**⑤ 표 모델 정합성 — 화면이 어긋나는 대부분의 원인**

- 모든 행이 같은 `columnCount`를 갖는 **직사각형 그리드**인가. `SheetTableModel`은 이걸 전제한다.
  짧은 행이 하나라도 섞이면 렌더링에서 `IndexOutOfBounds`가 난다 (`Sheet.cell()`이 막아주지만,
  그건 안전망이지 계약이 아니다).
- colspan/rowspan(HTML)과 `MergeAcross`/`MergeDown`(SpreadsheetML)을 **점유 맵으로 펼치는가.**
  2단 헤더가 있는 실제 명세서는 이게 없으면 열 정렬이 통째로 밀린다.
- 헤더 행 수를 `<thead>`로 잡는가. **"`<th>`를 포함한 선두 행" 규칙은 틀린다** —
  본문 첫 행에 행 방향 헤더(`rowspan`이 걸린 `<th>`)가 있는 표가 실제로 존재한다.
  이 규칙으로 되돌리는 변경을 보면 무조건 지적한다.
- 희소 행/희소 열을 **절대 위치**로 채우는가 (XLSX는 빈 셀을 생략한다). 순서대로 밀어 넣으면 열이 어긋난다.
- `headerLabels`의 중복 제거가 유지되는가 — rowspan 때문에 같은 값이 여러 행에 복제된다.

**⑥ 인코딩과 로케일 — 국내 파일에서만 터진다**

- BOM → 문서 선언 charset → UTF-8 유효성 → CP949 순서가 유지되는가.
  같은 경로로 내려오는 국내 파일이 EUC-KR인 경우가 흔해 폴백이 필요하다.
- UTF-8 유효성 검사가 프로브 **끝에서 잘린 멀티바이트 문자**를 오탐하지 않는가 (마지막 3바이트 여유).
- 숫자/날짜 추론 정규식이 넓어지지 않았는가. `1361.50`이 날짜가 되거나 `2026`이 연도가 아닌
  숫자로 읽히는 식의 오판이 전형적이다. 범위 검사(월 1..12, 일 1..31)가 살아 있는가.
- `String.format`/`"%,.1f".format()`은 **기본 로케일**을 쓴다. 숫자 구분자가 로케일에 따라 달라지는 자리에
  쓰였으면 지적한다. 파일에 쓰는 값이면 특히.
- 내보내기(CSV/TSV/JSON/Markdown)에서 이스케이프가 맞는가 — 값에 들어 있는 쉼표·따옴표·개행·파이프.
  TSV 는 따옴표 이스케이프가 없어 탭·CR·LF 를 공백으로 바꾸는데, **헤더 라벨도** 거쳐야 한다.
  줄바꿈을 다루는 곳은 LF 만이 아니라 **CRLF·단독 CR** 까지 보는가 — `DelimitedReader` 가 따옴표 필드 안의 CRLF 를 셀에 그대로 담는다.
- 내보낸 CSV·TSV 를 **이 플러그인의 `DelimitedReader` 로 다시 열면** 같은 표가 되는가. 받는 쪽 중 가장 가까운 것이 우리 리더다 —
  예: 짝 없는 `"` 로 시작하는 TSV 셀은 `DelimitedReader` 가 따옴표로 보고 파일 끝까지 한 필드로 읽는다.

**⑦ 이식성 — 다섯 IDE에 같은 ZIP이 들어간다**

- `com.intellij.modules.platform`에 없는 클래스를 쓰지 않았는가 (WebStorm·DataGrip 전용 API).
- **jsoup을 번들하지 않는가.** IDE가 부트 클래스패스에 이미 싣고 있어 `compileOnly`로만 참조해야 한다.
  `implementation`으로 바뀌면 버전 충돌이 난다.
- 새 런타임 의존성이 추가됐는가. 특히 Apache POI는 ~7MB와 클래스로더 충돌을 끌고 온다 —
  이 프로젝트는 BIFF `.xls`를 **의도적으로** 포기하고 안내 패널을 택했다. 되돌리려면 그 판단부터 다시 해야 한다.
- `plugin.xml`에 `<idea-version>`이 다시 들어오지 않았는가 (build.gradle.kts가 주입하므로 두 곳에 두면 갈린다).
- `untilBuild`가 되살아나지 않았는가 — 기본값 `262.*`면 PyCharm 263에서 로드가 거부된다.
- `csv`/`tsv`/`html` 확장자를 선점하지 않는가. 번들 `grid-core-plugin`이 편집까지 지원하므로 그쪽이 이겨야 한다.

**⑧ 테스트 — 새 동작에 픽스처가 있는가**

- 새 포맷 분기·새 경계 조건에 `src/test/resources/fixtures/` 픽스처와 테스트가 붙었는가.
- 테스트가 **플랫폼 클래스를 끌어오지 않는가.** 끌어오는 순간 순수 JVM 테스트가 아니게 되고 느려진다.
- 리더를 거치는 테스트가 **의도한 경로를 탔는지도** 단언하는가. 예를 들어 헤더 경로를 고정한다면서 `headerRowCount` 를 단언하지 않으면,
  헤더 판정이 깨져 데이터 경로로 나가도 출력이 같아 통과한다.
- 회귀 픽스처(`corrupt.xlsx`, `empty.xls`, `binary.xlsb`, `cp949.csv`, `legacy.xls`)가 커버하던
  동작을 바꿨다면 그 테스트도 같이 바뀌었는가 — 테스트를 느슨하게 고쳐 통과시킨 흔적은 지적한다.

## 범위 밖

측정해 보니 리뷰 시간의 상당 부분이 여기에 들었다. 이것들은 다른 곳이 지킨다.

- **하네스 내부** — 훅 스크립트, `hooks.json`, `check.sh`, `test-hooks.sh` 는 읽지 않는다. `test-hooks.sh` 가 지킨다.
  diff 에 게이트 파일(`.claude/tdd-exempt.txt`·`tdd-uncovered`·`tdd-baseline`)이 있으면 **면제나 미커버 목록이 늘었는지만** 본다.
  줄어든 것은 정상이다.
- **`tdd-baseline` 을 갱신했는지** — 커밋 절차이고, `check.sh` 가 올릴 값을 안내한다.
- **문서에 적힌 개수·숫자가 맞는지** — CLAUDE.md 가 문서에 개수를 하드코딩하지 말라고 정해 두었다. 그런 숫자를 찾아 grep 하지 않는다.
- **예외: 사용자에게 보이는 문구.** diff 가 사용자에게 보이는 목록이나 문구(내보내기 형식, 지원 확장자 등)를 바꿨으면
  옛 문구로 **한 번** grep 해서 `plugin.xml` 의 `<description>` 과 README 에 옛 문구가 남았는지 본다.
  TSV 를 추가할 때 이 확인으로 `plugin.xml` 설명문에서 TSV 가 빠진 것을 잡았다.

## 재리뷰(델타) 모드

브리핑에 이전 라운드의 지적 목록이 있으면 이 모드로 본다. 처음부터 다시 리뷰하지 않는다.

- 각 지적이 해소됐는지는 **그 지적에 해당하는 hunk 만** 보고 판정한다.
- 체크리스트는 이번 수정 hunk 가 **새로 만든** 문제에만 적용한다. 이전 라운드의 "확인했고 문제없던 것"은 다시 보지 않는다.
- 보고서에는 `### 이전 지적 확인` 섹션을 **`### 🔴` 섹션 뒤에** 두고, `- 🟡3 해소 — 파일:줄` 처럼 `-` 목록으로 적는다.
  **이 헤더에는 🔴 를 넣지 않는다.** `cycle-review.sh` 가 🔴 가 든 첫 `###` 헤더를 판정 섹션으로 잡기 때문이다.
- **해소되지 않은 이전 🔴 는 `### 🔴` 섹션에 번호 목록으로 다시 적는다.** 훅은 그 섹션의 번호만 센다.

## 보고 형식

이 형식을 그대로 쓴다. 사람이 위에서부터 읽으며 바로 고칠 수 있어야 한다.
**섹션 헤더 문자열과 마지막 판단 한 줄은 바꾸지 않는다** — `cycle-review.sh` 가 `### 🔴` 섹션의 번호 목록을 센다.

보고서를 쓰는 턴도 길다(TSV 1차에서 약 100초). 그래서 분량을 정해 둔다.

- **자동 검사**: 한 줄.
- **🔴**: 아래 형식 그대로 쓴다(재현·원인·수정). 코드 조각은 🔴 에만 쓴다.
- **🟡**: 한 줄 요약 + `파일:줄`, 재현 한 줄, 수정 한 줄.
- **🟢**: 최대 3줄.
- **확인했고 문제없던 것**: 본 체크리스트 항목마다 한 줄, 그리고 `해당 없음` 한 줄.

```markdown
## 리뷰: <대상>

**자동 검사**: `./gradlew test` ✓ N개 통과   ← 돌렸다면 결과를 먼저 (N은 실제 출력값)

### 🔴 고치고 커밋해야 함
1. **<한 줄 요약>** — `format/XlsxReader.kt:249`
   재현: <어떤 파일을 열면 무슨 일이 일어나는가>
   원인: <왜 그렇게 되는가>
   수정: <무엇을 어떻게>

### 이전 지적 확인   ← 재리뷰일 때만. 이 헤더에는 빨간 원 이모지를 넣지 않는다
- 🟡3 해소 — `export/ExportFormats.kt:142`

### 🟡 고치면 좋음
1. **<한 줄 요약>** — `파일:줄`
   재현: <한 줄>
   수정: <한 줄>

### 🟢 참고
- <취향·대안 수준의 메모, 최대 3줄>

### 확인했고 문제없던 것
- ⑥ <본 항목마다 한 줄 — 리뷰 범위를 보여준다>
- 해당 없음: ①②⑦ (변경 경로 밖)
```

## 지적의 기준

**🔴는 재현 시나리오를 못 쓰면 🔴가 아니다.** "OOM 위험이 있습니다" 말고
"열이 3만 개인 Excel HTML을 열면 maxRows 안에서도 힙을 다 쓴다"라고 쓴다.
어떤 파일이 그렇게 만드는지 못 적겠으면 🟡이나 🟢로 내리거나 빼라.

**이 코드의 주석은 대부분 실측 기록이다.** "mso-number-format이 한 곳도 없었다",
"CRLF 34바이트가 앞에 붙어 있다", "`<th>` 규칙은 틀린다" 같은 주석은 취향이 아니라
실제 파일을 열어보고 남긴 결론이다. 이상해 보이는 코드를 지적하기 전에 **주석과 해당 영역의 `.claude/rules/` 규칙을 먼저 읽어라.**
거기 이유가 적혀 있는데도 지적하면 리뷰 전체의 신뢰가 떨어진다.

**`TODO.md` 에 이미 판단 보류로 적힌 문제라도, 이번 변경이 노출을 넓혔거나 새 근거(실측, 새로 깨지는 경로)를 찾았으면
심각도를 내리지 않는다.** TODO 에 있다는 사실만으로 🟢 로 내리면 그 새 근거가 사라진다.

지적 건수를 채우려 하지 마라. 문제가 없으면 없다고 말하는 게 훨씬 쓸모 있다.
반대로 네 리더 사이에 갈라진 구현처럼 눈에 띄는 정리 기회는 🟢에 한두 줄 남겨 두면 다음 사람이 고맙다.
