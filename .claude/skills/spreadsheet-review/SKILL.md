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

어디를 봐야 하는지 매번 찾지 않도록:

| 영역 | 파일 |
|---|---|
| 포맷 판별 | `format/SpreadsheetSniffer.kt`, `filetype/SpreadsheetFileTypeDetector.kt` |
| 진입점·크기 가드 | `format/SpreadsheetReaders.kt` |
| 계약(상한·취소·예외) | `format/SpreadsheetReader.kt` |
| 파서 | `format/XlsxReader.kt`, `ExcelHtmlReader.kt`, `SpreadsheetMlReader.kt`, `DelimitedReader.kt` |
| 표 모델 | `model/SheetData.kt`, `format/CellTypeInference.kt` |
| IDE 통합 | `editor/SheetFileEditor.kt`, `SheetPanel.kt`, `SheetEditorProvider.kt`, `actions/`, `resources/META-INF/plugin.xml` |
| 빌드·호환성 | `build.gradle.kts`, `gradle.properties` |

## 순서

### 1. 기계가 잡을 수 있는 건 먼저 기계에 맡긴다

```bash
./gradlew test        # 파서 테스트. 실제 명세서 + 픽스처 기반이라 회귀를 잘 잡는다
```

리더·모델은 플랫폼 클래스를 쓰지 않아 순수 JVM 테스트로 돈다. **이게 이 프로젝트의 설계 자산이다** —
리뷰 중에 리더 쪽으로 플랫폼 의존이 새어 들어오는 변경을 보면 그 자체가 지적거리다.

`plugin.xml`이나 `build.gradle.kts`를 건드린 변경이면 `./gradlew verifyPlugin`도 돌릴 값어치가 있다.
다만 IDE 4개를 검증해 몇 분 걸리니, 플랫폼 API를 건드리지 않은 변경에는 생략하고 그 사실만 보고에 적는다.

테스트가 깨졌다면 **그것부터 보고한다.** 나머지 리뷰는 그 다음이다.

### 2. 변경 범위를 파악한다

**이 저장소는 git 저장소가 아니다.** `git diff`를 기대하지 마라. 대신:

- 호출한 쪽이 "무엇을 고쳤는지" 알려줬으면 그 파일과 **그 함수를 호출하는 쪽까지** 읽는다.
- 모르면 최근 수정 파일을 기준으로 잡는다:
  `find src -name '*.kt' -newer build.gradle.kts` 또는 `ls -lt` 로 최근 것부터.
- 그래도 모르겠으면 전체를 보되, 위험도 순서(파서 → 에디터 → 나머지)로 본다. 2,600줄 남짓이라 가능하다.

리더 한 곳을 고쳤으면 **나머지 세 리더의 같은 자리도 본다.** 네 리더가 같은 계약
(`ReadLimits`·`checkCancelled`·직사각형 그리드)을 각자 구현하고 있어서, 한쪽만 고치면 조용히 갈라진다.

### 3. 체크리스트를 순서대로 훑는다

위쪽이 더 치명적이다. 각 항목에서 **"이 코드를 깨뜨리는 구체적인 파일"** 을 떠올려 보고,
떠오르지 않으면 지적하지 않는다.

**① 포맷 판별 — 확장자를 믿는 순간이 있는가**

- 새 분기를 `SpreadsheetSniffer`에 넣었다면 `SpreadsheetReaders.readerFor`와
  `SpreadsheetFileTypeDetector.detect` **세 곳이 모두** 갱신됐는가.
  enum이 늘면 `when`이 컴파일 에러로 알려주지만, 기존 enum의 의미를 바꾼 경우는 조용히 어긋난다.
- 스니퍼는 **앞 8KB만** 본다. 파일 전체가 있다고 가정한 판별 로직(예: 닫는 태그 확인, 전체 길이 검사)은 틀린다.
- 선행 공백/CRLF 건너뛰기와 BOM 처리가 살아 있는가. 대상 파일은 `<html`이 오프셋 0에 없다 —
  이게 기존 플러그인들이 죽는 바로 그 지점이다.
- 판별을 **더 관대하게** 만드는 변경이 특히 위험하다. CSV로 떨어지는 조건이 넓어지면
  깨진 바이너리가 CSV로 열려 쓰레기 표가 나온다. "알 수 없음"이 잘못된 표보다 낫다.
- `FileTypeDetector`는 VFS 인덱싱 경로에서 불린다. 여기서 무거운 일(전체 파싱, 파일 재열기,
  다른 탐지기 재귀 호출)을 하면 IDE 전체가 느려진다.

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
- 내보내기(CSV/JSON/Markdown)에서 이스케이프가 맞는가 — 값에 들어 있는 쉼표·따옴표·개행·파이프.

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
- 회귀 픽스처(`corrupt.xlsx`, `empty.xls`, `binary.xlsb`, `cp949.csv`, `legacy.xls`)가 커버하던
  동작을 바꿨다면 그 테스트도 같이 바뀌었는가 — 테스트를 느슨하게 고쳐 통과시킨 흔적은 지적한다.

## 보고 형식

이 형식을 그대로 쓴다. 사람이 위에서부터 읽으며 바로 고칠 수 있어야 한다.

```markdown
## 리뷰: <대상>

**자동 검사**: `./gradlew test` ✓ 20개 통과   ← 돌렸다면 결과를 먼저

### 🔴 고치고 커밋해야 함
1. **<한 줄 요약>** — `format/XlsxReader.kt:249`
   재현: <어떤 파일을 열면 무슨 일이 일어나는가>
   원인: <왜 그렇게 되는가>
   수정: <무엇을 어떻게>

### 🟡 고치면 좋음
...같은 형식...

### 🟢 참고
- <취향·대안 수준의 메모>

### 확인했고 문제없던 것
- <검토했지만 이상 없던 항목을 짧게 — 리뷰 범위를 보여준다>
```

## 지적의 기준

**🔴는 재현 시나리오를 못 쓰면 🔴가 아니다.** "OOM 위험이 있습니다" 말고
"열이 3만 개인 Excel HTML을 열면 maxRows 안에서도 힙을 다 쓴다"라고 쓴다.
어떤 파일이 그렇게 만드는지 못 적겠으면 🟡이나 🟢로 내리거나 빼라.

**이 코드의 주석은 대부분 실측 기록이다.** "mso-number-format이 한 곳도 없었다",
"CRLF 34바이트가 앞에 붙어 있다", "`<th>` 규칙은 틀린다" 같은 주석은 취향이 아니라
실제 파일을 열어보고 남긴 결론이다. 이상해 보이는 코드를 지적하기 전에 **주석과 README를 먼저 읽어라.**
거기 이유가 적혀 있는데도 지적하면 리뷰 전체의 신뢰가 떨어진다.

지적 건수를 채우려 하지 마라. 문제가 없으면 없다고 말하는 게 훨씬 쓸모 있다.
반대로 네 리더 사이에 갈라진 구현처럼 눈에 띄는 정리 기회는 🟢에 한두 줄 남겨 두면 다음 사람이 고맙다.
