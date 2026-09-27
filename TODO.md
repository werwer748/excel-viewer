# TODO

각 줄에 **왜 필요한지**를 같이 적는다. 근거 없는 할 일은 나중에 판단할 수 없다.
설계 결정과 빌드 규약은 [CLAUDE.md](CLAUDE.md)에 있다.

## 마켓플레이스 등록까지 해야 할 일

현재는 ZIP 직접 설치만 된다. JetBrains Marketplace에 올리려면 아래가 남았다.

- [ ] **`<vendor>` 에 email · url 추가** — 지금은 `<vendor>Hugo</vendor>` 한 줄이다. Marketplace 승인 가이드라인이 "유효하고 작동하는" 이메일과 웹사이트를 요구한다. ⚠️ **이 값은 플러그인 페이지와 공개 저장소에 그대로 노출되므로 어느 주소를 쓸지 직접 정할 것.** (SDK 문서에는 선택이라고 되어 있지만 승인 가이드라인이 더 엄격하다.)

- [ ] **`src/main/resources/META-INF/pluginIcon.svg` 만들기** — 스토어 카드에 뜨는 로고. **40×40**, SVG 필수(PNG 불가), 2–3kB 이하, 테두리에 최소 2px 투명 여백. `pluginIcon_dark.svg` 는 선택(밝은·어두운 배경 모두에서 읽히는 색을 쓰면 생략 가능).
  기존 `src/main/resources/icons/spreadsheet.svg` 는 **재사용할 수 없다** — 16×16 선언, 단색 `#6C707E` 1px 헤어라인, 381바이트인 *파일 타입* 아이콘이다. 40px로 키우면 선이 뭉개지고 IDE UI 회색은 카드에서 눈에 띄지 않는다. 두 아이콘은 역할이 다르니 `SpreadsheetFileType` 의 `IconLoader` 경로는 그대로 둔다.

- [ ] **`build.gradle.kts` 에 `signing` · `publishing` 추가** — IntelliJ Platform Gradle Plugin 2.19.0에서 정식 위치는 태스크(`signPlugin { }`)가 아니라 `intellijPlatform { }` 안의 extension이다. 공식 서명 문서는 아직 1.x 형태를 보여주므로 그대로 베끼면 안 된다.
  값은 저장소에 두지 않고 `~/.gradle/gradle.properties` + 환경변수로 받는다(기존 `localIdePath` 규약과 같은 모양). **함정 둘**:
  1. 값 없는 provider를 그냥 대입하면 **기본 환경변수가 지워진다** (`PRIVATE_KEY` / `CERTIFICATE_CHAIN` / `PRIVATE_KEY_PASSWORD` / `PUBLISH_TOKEN`). 기본값으로 되돌아오지 않으므로 `.orElse(providers.environmentVariable(...))` 로 체인해야 한다.
  2. `providers.gradleProperty("x")` 는 `x=` 처럼 **빈 값이어도 "존재함"** 을 돌려준다. `.filter { it.isNotEmpty() }` 를 쓰고, `gradle.properties` 에 빈 키를 넣지 말고 **주석으로만** 문서화한다 — 이 점은 `localIdePath` 와 다르게 처리해야 한다.
  값이 없을 때 `buildPlugin` 과 `scripts/check.sh` 가 깨지지 않아야 한다(둘 다 서명·게시 태스크를 타지 않으므로 lazy provider만 쓰면 영향 없다).

- [ ] **서명 인증서 발급** — 서명이 없으면 IDE 설치 중 경고 대화상자가 뜬다(필수는 아니지만 권장).
  ```
  openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
  openssl rsa -in private_encrypted.pem -out private.pem
  openssl req -key private.pem -new -x509 -days 365 -out chain.crt
  ```
  `private.pem` · `chain.crt` 는 **저장소 밖**(예: `~/.config/spreadsheet-viewer-signing/`)에 두고 경로만 `~/.gradle/gradle.properties` 에 적는다. 안전망은 `.gitignore` 에 이미 있다(`*.pem` · `*.crt` · `*.p12` · `*.jks` · `*.key`) — 키를 만들기 전에 먼저 넣어 뒀다. ⚠️ `-days 365` 이므로 **1년 만료** — 갱신 시점을 기억해야 한다.

- [ ] **`<change-notes>` 추가** — 디스크립터 스펙상 필수는 아니지만, 비워 두면 업로드 시 `DefaultChangeNotes` 경고가 붙는다. 1.0.0 초기 릴리스 요약 2–3줄. 텍스트 메타데이터는 `plugin.xml` 에 둔다는 기존 규약에 맞춘다(`org.jetbrains.changelog` 플러그인 도입은 지금 규모에 과하다).

- [ ] **`<description>` 의 영어 부분을 독립적으로 완결된 설명으로 키우기** — 승인 가이드라인이 "주 언어는 영어"를 요구한다. 지금은 영어 두 문단 뒤 한국어 본문인데, 영어만 읽어도 무엇을/왜/어떤 형식을 지원하는지 알 수 있어야 안전하다. 업로드 에러 기준(`ShortDescription` 40자 미만, `NonLatinDescription`)은 이미 통과한다. 링크를 넣으면 **https만**.

- [ ] **이름 충돌 확인** — Marketplace에 XLSX Lens · Fast Excel Viewer · TableKit 등 유사 플러그인이 있다(정확히 같은 이름은 확인되지 않았다). `<name>Spreadsheet Viewer` 는 18자로 30자 제한을 통과하고 금지어("Plugin"/"IntelliJ"/"JetBrains")도 없다.
  ⚠️ `<id>dev.hugo.spreadsheet-viewer` 는 형식상 유효하지만 **공개 후 변경할 수 없다** — 올리기 전에 확정할 것.

- [ ] **첫 게시는 수동으로** — 공식 문서가 첫 업로드는 항상 수동이라고 못박는다. Marketplace → Add new plugin 에서 ZIP 업로드. Vendor 프로필 생성 + Developer Agreement 동의, **태그 필수**, 오픈소스 라이선스를 고르면 **소스 URL 필수**(MIT LICENSE + 공개 저장소가 있으므로 충족). 게시 토큰은 프로필 My Tokens 에서 발급하고 **한 번만 보인다**. 이후 버전부터 `./gradlew publishPlugin` 을 쓸 수 있고, 자격증명이 있으면 `signPlugin` 이 그 앞에 자동 실행된다.

- [ ] **게시 전 검증** — 승인 가이드라인이 업로드마다 Plugin Verifier 검증을 요구한다. `./gradlew verifyPlugin` 과 `./gradlew verifyPluginStructure`(아이콘·vendor·description 완결성)를 돌린다.

- [ ] **`untilBuild` 무제한은 그대로 유지** — 확인만 하고 되돌리지 않는다. 공식 권장이 "이 속성을 설정하지 않는 것"이고 게시에도 문제가 없다. `SuspiciousUntilBuild` 경고는 값을 이상하게 *설정*했을 때의 것이다. 대가는 미래의 비호환 IDE에도 설치가 허용된다는 점인데, 263 계열 PyCharm 실측 근거가 있어 유지가 맞다.

## 하네스에 남은 구멍

게이트를 손보면서 **실측으로 확인한** 것들이다. 추측이 아니다.

- [ ] **인터프리터를 거친 파일 쓰기는 `tdd-guard` 를 빠져나간다** — `python3 - <<'PY' … p.write_text(…)` 나 `perl -e`, `node -e` 는 리다이렉션도 `touch` 도 아니라 경로 추출에 걸리지 않는다. 실제로 이 하네스를 만드는 동안 그 경로로 게이트 파일을 여러 번 고쳤고 `ask` 가 뜨지 않았다.
  인터프리터 안의 코드를 파싱하는 것은 불가능하므로 "모르면 통과" 원칙에 맞기는 하다. 다만 **게이트 자신**(`tdd-exempt.txt` · `tdd-baseline` · `tdd-uncovered` · 훅 · `check.sh`)에 대해서는 감지하는 편이 낫다 — command 문자열에 그 경로가 나타나면 `ask` 로 올리는 정도면 충분하다. 오탐(읽기만 하는 경우)은 감수할 만하다.

- [ ] **`tdd-red.sh` 는 여전히 한 번만 판정한다** — `.tdd-new` 표시를 읽고 **지우므로**, 새 테스트를 만든 직후 같은 파일을 한 번 더 고치면 red 검사가 영영 돌지 않는다. "테스트를 만들고 곧바로 green 으로 고치기"가 재편집 한 번으로 통과된다.
  `.cycle-state` 를 만들 때 이 교훈을 적용해 "읽고 지우지 않는" 구조로 갔다. `tdd-red` 도 같은 방식으로 바꿀 수 있다(표시를 남겨 두고 "판정함" 플래그만 따로 두기).

- [ ] **`test-hooks.sh` 가 저장소 작업 트리를 건드린다** — `src/test/kotlin/…/HookProbeTest.kt` 를 실제로 만들었다 지운다. `trap cleanup` 이 `.tdd-new.saved` 를 복구하도록은 고쳤지만, 임시 디렉터리에 합성 프로젝트를 만들어 돌리는 편이 낫다. 사이클 훅 테스트는 이미 그렇게 한다.

- [ ] **면제 글로브가 인용 없이 `case` 에 들어간다** — `.claude/tdd-exempt.txt` 에 `*  <사유>` 한 줄이면 전 경로가 면제된다. 지금은 그 파일 수정에 `ask` 가 걸려 사람이 한 번 보게 되지만, 글로브 자체를 검증하지는 않는다.

## 샌드박스 자동 검증: 실측으로 닫힌 길

같은 시도를 반복하지 않도록 남긴다. 셋 다 **직접 돌려보고** 확인했다.

- `tasks.runIde { args("<픽스처 경로>") }` — IDE 가 그 파일을 **에디터 탭이 아니라 프로젝트 루트로** 연다 (`ProjectUtil - No processor found for project in …`).
- `/api/remote-driver/hierarchy` — `-Dexpose.ui.hierarchy.url=true` 를 JVM 에 전달해도 **HTTP 400**. 번들 `performanceTesting` 플러그인에 서비스는 들어 있지만 이 방법으로는 열리지 않는다.
- `/api/about?registeredFileTypes` — `/api/about` 은 200 이지만 쿼리 파라미터가 무시되어 파일타입 목록이 오지 않는다(145바이트).
- 참고: 널리 퍼진 `http://localhost:63342/api/file` 은 **2026.2 에 존재하지 않는다**. 그리고 내장 웹서버 포트는 고정이 아니다 — 다른 IDE 가 63342 를 쓰고 있으면 **63343** 으로 밀린다.

그래서 `sandbox-verify` 는 "프로세스 생존(= 플러그인 로드 성공) + 구간 로그 예외"까지만 본다. 보이는 것의 검증은 사람이 한다.

- [ ] **사이클을 실제로 한 바퀴 돌려본다** — 훅·스킬·에이전트는 전부 단위로 검증했지만(`test-hooks.sh` 107 케이스), **처음부터 끝까지 이어서 돌려본 적은 없다.** 단위가 다 맞아도 이어붙인 흐름이 맞는다는 보장은 없다.
  대상은 **TSV 내보내기**가 좋다 — 작고, 순수 로직이고, 테스트가 먼저 필요해 TDD 게이트를 정확히 탄다. 확인할 것:
  1. `feature-cycle` 스킬이 실제로 트리거되는가 (description 이 맞는가)
  2. `tdd-guard` 가 구현을 막고, 테스트를 먼저 쓰면 풀리는가
  3. `sheet-reviewer` 가 끝났을 때 `cycle-review.sh` 가 **실제 보고서**를 파싱하는가 — 합성 보고서로만 테스트했다. 진짜 리뷰어가 형식을 지키지 않으면 `unknown` 이 나온다
  4. `cycle-stop.sh` 가 세션을 되돌리는 것이 **실제로 체감되는가**, 그리고 빠져나올 수 있는가
  5. `sandbox-verify` 가 판정 한 줄을 형식대로 내는가 → `cycle-verify.sh` 가 읽는가
  ⚠️ 한 바퀴 돌리는 동안 **하네스가 자기를 고치려 드는지** 지켜볼 것. 리뷰 지적을 없애는 가장 쉬운 길이 면제 추가이고, 그게 이 설계가 가장 경계하는 실패다. `harness_touched=yes` 가 남으면 왜 그랬는지 확인한다.

- [ ] **헤드리스 테스트 확대로 면제를 줄인다** — 위가 막혔으므로 여기가 실질적인 대안이다. 조사에서 확인된 것들:
  `UIUtil.findComponentsOfType(panel, X::class).size` 로 배너 누적, 부모 확인으로 `JBLoadingPanel` 분리, `manager.splitters.getAllComposites()` 로 탭 순서, `provider.createEditor(project, file)` 직접 호출(`withContext(Dispatchers.UiWithModelAccess)` + `writeIntentReadAction` + `finally { Disposer.dispose }`).
  **JCEF 도 헤드리스가 된다**: `Registry.get("ide.browser.jcef.headless.enabled").setValue(true, testRootDisposable)` + `if (!JBCefApp.isSupported()) return`. `ide.browser.jcef.enabled=false` 동반 테스트로 `<depends optional>` 경로까지 덮을 수 있다.
  ⚠️ 헤드리스는 **opt-out** 이다(`UITestUtil.getAndSetHeadlessProperty()` 가 강제). `JFrame` 은 못 만들고 `isShowing()` 은 항상 false. 컴포넌트에 `name` 을 붙여 두면(`component.name = "sheetview.table"`) 탐색이 클래스 동일성에 의존하지 않는다.

## 추가할 기능

- [x] **원본 탭** (`SourceEditorProvider`, editor type id `sheetview.source`) — 착륙했다.
  미리보기 / 소스 / 내부 파트 3모드, hex 덤프 폴백, JCEF 없는 환경에서는 미리보기 모드 제외.
  설계 제약(JCEF 는 optional 번들 플러그인, `Jsoup.clean` 금지, `releaseEditor` 누수 등)은 CLAUDE.md 에 있다.

- [ ] **TSV 내보내기** — `ExportFormats.toTsv()` 는 이미 있지만 "시트 전체 복사"의 클립보드 포맷으로만 쓰인다. `ExportFormat` enum 에 항목을 더하면 내보내기 목록에도 나온다. TDD 훅 때문에 테스트가 먼저다(기존 `ExportFormats` 테스트에 케이스 추가).

- [ ] **`maxCells` 상한이 두 리더에 적용되지 않는다** — `ReadLimits` 에 `maxCells` 가 선언돼 있고 `rowLimit(columnCount)` 도 있지만, 실제로 쓰는 곳은 `XlsxReader` 와 `ExcelHtmlReader` 뿐이다. `SpreadsheetMlReader` 와 `DelimitedReader` 는 `maxRows` · `maxColumns` 만 보므로 최악의 경우 선언된 상한을 훌쩍 넘는 그리드를 만들 수 있다. 입력 크기 상한(64MB)이 간접 방어로 남아 있어 당장 터지지는 않지만, 선언과 적용이 어긋난 상태다.

- [ ] **진짜 BIFF `.xls`** — 지금은 미지원 안내 패널이다. 지원하더라도 **Apache POI 는 쓰지 않는다**(근거는 CLAUDE.md). 순수 JDK로 OLE2/BIFF8을 직접 읽는 범위를 어디까지 할지 정해야 하는 일이라, 필요해질 때 다시 판단한다.

- [ ] **리더 네 곳의 중복 로직 통합** — 같은 계약을 각자 구현하고 있어 한쪽만 고치면 조용히 갈라진다. 후보: `key(row, col)` 비트 패킹(`ExcelHtmlReader` · `SpreadsheetMlReader`), `looksLikeHeader`(리더 세 곳), 직사각형 패딩(`XlsxReader` · `DelimitedReader`), 숫자 평문화(`XlsxReader.formatNumber` · `ExportFormats.plainNumber`), NBSP 치환(리더 세 곳).
