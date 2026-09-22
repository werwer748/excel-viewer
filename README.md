# Spreadsheet Viewer

IDE 탭에서 스프레드시트를 표로 보는 JetBrains 플러그인. **확장자가 아니라 파일 내용으로 형식을 판별**한다. 읽기 전용.

## 왜 만들었나

`이용대금명세서_2609(신용.체크)_20260922105612.xls` 를 열 때 기존 엑셀 뷰어 플러그인이 죽었다. 원인:

```
$ file …xls
HTML document text, UTF-8 text, with CRLF line terminators

$ xxd …xls | head -3
00000000: 0d0a 0d0a 0d0a … 0d0a                     ← 개행 34바이트
00000020: 0d0a 0d0a 3c68 746d 6c20 786d 6c6e 733a   ....<html xmlns:
00000030: 6f3d 2275 726e 3a73 6368 656d 6173 2d6d   o="urn:schemas-m
```

확장자만 `.xls`인 **HTML 표**다. 카드사·은행·관공서의 "엑셀로 저장"이 흔히 쓰는 Excel HTML export.
진짜 `.xls`(OLE2/BIFF8)는 `D0CF11E0A1B11AE1`로 시작하는 바이너리인데 이 파일은 전혀 다르다.
Apache POI로 워크북을 열려는 플러그인은 헤더 검증에서 예외를 던진다. **확장자를 믿고 포맷을 검사하지 않는 설계 문제**이며, 다음 달 명세서도 같은 포맷으로 내려오므로 파일을 고쳐 쓰는 것으로는 해결되지 않는다.

## 지원 형식

`SpreadsheetSniffer`가 앞 8KB를 보고 판별한다 (`format/SpreadsheetSniffer.kt`).

| 조건 | 형식 | 리더 |
|---|---|---|
| `50 4B 03 04` (`PK`) | XLSX / OOXML | `XlsxReader` — 순수 JDK (zip + StAX 스트리밍) |
| 공백 건너뛴 뒤 `<html` / `<table` | Excel HTML | `ExcelHtmlReader` — jsoup |
| `<?mso-application progid="Excel.Sheet"?>` 또는 `urn:…:office:spreadsheet` | Excel 2003 XML | `SpreadsheetMlReader` — StAX |
| 그 외 텍스트 | CSV/TSV | `DelimitedReader` — 인코딩·구분자 감지 |
| `D0 CF 11 E0 A1 B1 1A E1` | 진짜 XLS (BIFF) | **미지원 안내 패널** (예외 없음) |
| ZIP + `xl/workbook.bin` | XLSB | **미지원 안내 패널** |

진짜 BIFF `.xls`는 의도적으로 지원하지 않는다. Apache POI를 넣으면 `xmlbeans`·`log4j-api`·`SparseBitSet` 등 약 7MB가 따라오고 IDE 클래스로더와 충돌 여지가 생긴다. (참고: DataGrip도 POI를 플러그인 클래스로더 **밖** Grape 캐시에 격리해 쓴다.)

## 설계 메모

- **자동 선점 확장자는 `xls xlsx xlsm xltx xltm` 만.** `csv`/`tsv`는 번들 `grid-core-plugin`의 `csv-data-editor`가 이미 담당하고 편집까지 지원하므로 건드리지 않는다. `html`도 제외. 그런 파일은 우클릭 → **표로 열기**로 명시 진입한다.
- **`fileTypeDetector` 가 필수다.** 어떤 번들 플러그인도 `xls`/`xlsx`를 등록하지 않아 `UnknownFileType`이 된다. 그러면 텍스트 에디터가 붙지 않아 `PLACE_BEFORE_DEFAULT_EDITOR`를 써도 Text 탭이 아예 생기지 않는다. 내용이 텍스트면 `PlainTextFileType`을 돌려줘 원본 Text 탭을 살린다.
- **헤더 행은 `<thead>` 기준.** "`<th>`를 포함한 선두 행" 규칙은 틀린다 — 요약내역 표 본문 첫 행에 `<th>이번달</th>`(rowspan=3, 행 방향 헤더)가 있어 헤더를 2행으로 오인한다.
- **colspan/rowspan은 점유 맵으로 펼친다.** 상세내역 표가 `rowspan="2"` + `colspan="2"` 2단 헤더라 이게 없으면 컬럼 정렬이 깨진다.
- **셀 타입은 텍스트에서 추론한다.** 이 파일에는 `mso-number-format`·`x:num`이 하나도 없다. `x:num`이 있으면 그쪽을 우선한다.
- **jsoup은 번들하지 않는다.** IDE가 1.22.1을 부트 클래스패스에 이미 싣고 있어 `compileOnly`로만 참조한다.
- 모든 실패는 `UnsupportedSpreadsheetException` / `SpreadsheetParseException`으로 모여 패널에 한국어 설명으로 표시된다. `ProcessCanceledException`과 `CancellationException`은 절대 삼키지 않는다.

## 함정 메모

실제로 물렸던 것들. 파서 테스트나 Plugin Verifier로는 잡히지 않는 종류라 적어둔다.

- **`JBLoadingPanel` 을 직접 비우면 안 된다.** `add()` 는 재정의해 내부 content 패널로 위임하지만 `removeAll()` 은 재정의하지 않는다. `removeAll()` 을 부르면 화면에 붙어 있는 `LoadingDecorator` 컴포넌트가 떨어져 나가고, 이후 `add()` 한 내용은 분리된 패널로 들어가 **화면이 빈 채로 아무 오류도 안 난다**. 전용 content 패널(`SheetPanel.content`)을 하나 넣고 그 자식만 교체한다.
- **텍스트 내용에 `PlainTextFileType` 을 주면 번들 grid 플러그인의 `csv-data-editor` 가 가로챈다.** 두 번째 탭이 "Data" 가 되어 원본을 보기 어려워진다. 내용에 맞는 타입(`FileTypeRegistry.findFileTypeByName("HTML")`)을 주고, 없는 IDE에서만 PlainText로 떨어뜨린다.
- **`FileTypeDetector` 결과는 VFS에 캐시된다.** 탐지기를 고친 뒤에는 샌드박스의 `system_runIde` 를 지워야 재판별된다. (`getVersion()` 은 262에서 deprecated + 제거 예정이라 쓰지 않는다.)
- **`EditorNotificationPanel(Status)` 단일 인자 생성자는 없다.** `(Color?, Status)` 를 쓴다.
- **`TableSpeedSearch` 생성자는 deprecated.** `TableSpeedSearch.installOn(table)` 을 쓴다.
- **플러그인 description 은 라틴 문자로 시작해야 한다.** 한국어로 시작하면 Plugin Verifier가 구조 오류로 반려한다 (Marketplace 규칙). 로컬 설치에는 영향 없지만 `verifyPlugin` 이 막힌다.

## 빌드

clone 후 바로 빌드된다. 플랫폼 262 클래스는 **Java 25 바이트코드(major 69)** 라 JDK 25가 필요한데, 없으면 foojay 리졸버가 받아 온다. 플랫폼 의존성도 지정이 없으면 원격 아티팩트를 받는다.

**이미 JetBrains IDE가 설치돼 있다면** `~/.gradle/gradle.properties`에 아래를 넣어 두면 ~1GB 다운로드가 사라진다. 번들 JBR이 `javac 25`를 포함한 완전한 JDK이므로 JDK 설치도 필요 없다.

```properties
org.gradle.java.installations.paths=/경로/WebStorm.app/Contents/jbr/Contents/Home
localIdePath=/경로/WebStorm.app
verifyIdePaths=/경로/WebStorm.app,/Applications/IntelliJ IDEA.app
```

```bash
./scripts/check.sh      # lint -> build -> test 한 번에 (커밋 전 훅이 부르는 것과 같다)
./gradlew test          # 파서 테스트 21개 (전부 합성 픽스처 기반)
./gradlew buildPlugin   # -> build/distributions/excel-viewer-1.0.0.zip
./gradlew verifyPlugin  # WebStorm / IDEA / PyCharm / DataGrip 호환성 검증
./gradlew runIde --args="/열어볼/디렉터리"                  # 샌드박스 IDE
```

## 설치

`Settings → Plugins → ⚙ → Install Plugin from Disk…` 에서 `build/distributions/excel-viewer-1.0.0.zip` 선택.

`com.intellij.modules.platform` 만 의존하고 `until-build`가 없어 WebStorm·IDEA·PyCharm·CLion·DataGrip에 같은 ZIP을 그대로 설치할 수 있다. `since-build=262` — Java 25 바이트코드는 JBR 21로 도는 구버전 IDE에서 로드되지 않는다.
