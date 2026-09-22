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

## 사용법

### 여는 방법

- **`.xls` `.xlsx` `.xlsm` `.xltx` `.xltm`** — 프로젝트 뷰에서 더블클릭하면 바로 표로 열린다.
- **그 외 파일** (`.csv` `.tsv` `.html`, 확장자 없는 파일 등) — 우클릭 → **표로 열기**. 프로젝트 뷰와 에디터 탭 우클릭 메뉴 양쪽에 있다.

`csv`/`tsv`를 자동으로 가져가지 않는 이유: IDE 번들 플러그인이 이미 그 형식을 담당하고 **편집까지** 지원한다. 읽기 전용인 이쪽이 끼어들면 기능이 줄어든다.

### 화면

- 시트가 여러 개면 위쪽 **시트 탭**으로 전환한다.
- 왼쪽 **행 머리글**에 원본 행 번호가 붙는다 — 엑셀 원본과 행을 맞춰 볼 수 있다.
- 표에 포커스를 두고 **그냥 타이핑하면 검색**된다.
- 병합 셀(colspan/rowspan)은 펼쳐서 표시하므로 2단 헤더도 컬럼이 어긋나지 않는다.
- 파일이 너무 커서 일부만 읽었으면 **배너로 알린다** (읽은 행 수 / 전체 행 수).
- 읽을 수 없는 파일은 예외를 던지지 않고 **사유와 다음에 할 일을 설명하는 패널**을 보여준다.

### 표 탭 툴바

| 버튼 | 하는 일 |
|---|---|
| 다시 읽기 | 파일을 다시 읽는다 |
| 첫 행을 머리글로 | 토글. 머리글 행을 컬럼 이름으로 올린다 |
| 시트 전체 복사 | **탭 구분 텍스트(TSV)** 로 클립보드에 복사 — 엑셀·구글 시트에 그대로 붙여넣을 수 있다 |
| 내보내기… | 파일로 저장. **CSV / JSON / Markdown** |

내보내기는 CSV · JSON · Markdown 세 가지, 클립보드 복사는 TSV다.

### 원본 탭

아래쪽 **원본** 탭에서 파일을 있는 그대로 볼 수 있다. HTML 위장 `.xls` 는 파일 타입이 바이너리라
IDE 기본 텍스트 에디터가 붙지 않기 때문에, 이 플러그인이 직접 읽어 보여준다.
표 탭과 **같은 판별 결과**를 쓰므로 두 탭이 같은 파일을 다르게 해석하는 일은 없다.

| 모드 | 보여주는 것 |
|---|---|
| 미리보기 | HTML을 렌더해서 본다. 스크립트는 제거하고 이미지는 차단한다(출처를 알 수 없는 파일이라 외부 요청을 막는다). 배너로 무엇을 막았는지 알린다 |
| 소스 | 원본 텍스트 그대로. 진짜 `.xls`(OLE2) · `.xlsb` 처럼 바이너리면 `hexdump -C` 배치의 hex 덤프로 앞부분을 보여준다 — 매직바이트를 눈으로 확인할 수 있다 |
| 내부 파트 | XLSX/XLSB 같은 ZIP 안의 XML 파트 목록과 내용 |

툴바는 **다시 읽기**와 **원본 전체 복사** 두 개다. 내장 브라우저(JCEF)가 없는 환경에서는 미리보기
모드가 목록에서 아예 빠지고 소스 모드로 떨어진다 — 눌러도 안 되는 버튼은 두지 않는다.

**읽기 전용이다.** 셀을 고치거나 저장하는 기능은 없다.

## 설치

`Settings → Plugins → ⚙ → Install Plugin from Disk…` 에서 `build/distributions/excel-viewer-1.0.0.zip` 선택.

`com.intellij.modules.platform` 만 의존하고 `until-build`가 없어 WebStorm·IDEA·PyCharm·CLion·DataGrip에 같은 ZIP을 그대로 설치할 수 있다. `since-build=262` — Java 25 바이트코드는 JBR 21로 도는 구버전 IDE에서 로드되지 않는다.

## 배포

JetBrains IDE 플러그인으로 빌드해 **ZIP 한 개로 배포**한다. 플랫폼 모듈만 의존하므로 WebStorm · IntelliJ IDEA · PyCharm · CLion · DataGrip에 같은 파일을 설치한다.

```bash
./gradlew buildPlugin    # -> build/distributions/excel-viewer-1.0.0.zip
./gradlew verifyPlugin   # 다섯 IDE 호환성 검증
```

현재 배포 경로는 **ZIP 직접 설치**다. JetBrains Marketplace 게시는 아직 준비되지 않았다 — 서명·게시 설정과 플러그인 로고가 없고, 남은 항목은 [TODO.md](TODO.md)에 정리해 두었다.

라이선스는 MIT, 저장소는 <https://github.com/werwer748/excel-viewer>.

## 개발

빌드 · 설계 결정 · TDD 훅은 [CLAUDE.md](CLAUDE.md), 남은 일은 [TODO.md](TODO.md).
