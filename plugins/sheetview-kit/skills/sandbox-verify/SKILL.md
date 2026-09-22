---
name: sandbox-verify
description: 작업 사이클 안에서 샌드박스 IDE 를 띄워 **기계가 관측할 수 있는 것만** 확인하고 증거를 남긴다. 사람에게 넘기지 않고 판정 한 줄로 끝낸다. 플러그인이 실제로 로드되는가, 이번 기동 구간 로그에 예외·EDT 블로킹·누수 신호가 있는가를 본다. 사람이 "띄워줘 / 직접 써볼래"라고 한 경우에는 이 스킬이 아니라 sandbox-run 을 쓸 것 — 그쪽은 사람에게 인계하는 절차다.
---

# 샌드박스 자가검증

사이클(리뷰 → 수정 → **검증** → 인계)의 검증 단계다. 사람을 부르지 않고 끝낸다.

## 무엇을 확인하고 무엇을 확인하지 **못**하는가

이 경계를 지키는 것이 이 스킬의 전부다. 넘으면 자기 채점이 된다.

**확인할 수 있는 것 (관측에서 직접 나온다)**

- 플러그인이 실제로 로드되는가 — 기동 명령에 `-Didea.required.plugins.id=dev.hugo.spreadsheet-viewer` 가 자동으로 붙으므로 **프로세스가 살아 있으면 로드는 성공한 것**이다. 공짜로 얻는 가장 강한 신호다.
- 이번 기동 구간 로그에 예외·EDT 블로킹·누수 신호가 있는가.
- 내장 웹서버가 떴는가(포트).

**확인할 수 없는 것 — 여기서 판정하지 마라**

- 표가 제대로 그려졌는가, 2단 헤더가 정렬됐는가, 한글이 깨지지 않는가
- 탭 순서, 배너가 쌓이는가, 미리보기 스타일이 살아 있는가
- 미지원 파일에 안내 패널이 뜨는가

**왜 못 하는가** (실측으로 확인한 사실이다. 같은 시도를 반복하지 마라):

- `tasks.runIde { args("<픽스처 경로>") }` 로 파일을 넘기면 IDE 가 그것을 **에디터 탭이 아니라 프로젝트 루트로** 연다 (`ProjectUtil - No processor found for project in …`).
- `/api/remote-driver/hierarchy` 는 `-Dexpose.ui.hierarchy.url=true` 를 줘도 **HTTP 400** 이다. `/api/about` 은 200 이지만 `?registeredFileTypes` 파라미터는 무시된다.
- 널리 퍼진 `http://localhost:63342/api/file` 은 **2026.2 에 존재하지 않는다**.

**그래서 로그가 깨끗한 것은 "동작이 맞다"가 아니다.** 예외 없이 잘못된 표가 그려지는 것이 이 플러그인의 전형적 실패다. 보이는 것의 검증은 ⑦ 인계 단계에서 사람이 한다. 보고에 이 한계를 반드시 적는다.

## 절차

### 1. 기동 전 검사 (순서를 어기면 세션이 멈춘다)

```
sh plugins/sheetview-kit/scripts/sandbox-up.sh preflight
```

락 확인 → `check.sh` → 로그 경계 찍기를 한 번에 한다. **runIde 가 뜬 뒤에는 모든 `./gradlew` 가 프로젝트 락 대기로 멈추므로** 검사는 반드시 기동 전이다.

- exit 1 (검사 실패): 띄우지 않고 보고한다. `TODO.md` 의 진행 중인 기능이 테스트를 먼저 올려 둔 상태일 수 있다.
- exit 3 (이미 떠 있음): 지금 떠 있는 IDE 에는 이번 변경이 없을 수 있다. 사람에게 물은 뒤 진행한다.

### 2. 백그라운드로 띄운다

```
./gradlew runIde
```

**반드시 `run_in_background: true` 로 부른다.** 포그라운드로 부르면 IDE 수명만큼 세션이 멈춘다.

### 3. 기동을 기다린다

```
sh plugins/sheetview-kit/scripts/sandbox-up.sh wait
```

PID 와 포트를 돌려준다. 포트는 **고정이 아니다** — 다른 IDE 가 63342 를 쓰고 있으면 63343 으로 밀린다(실측). 하드코딩하지 말고 이 출력을 쓴다.

실패하면 원인을 보고하고 **멈춘다. 같은 명령을 다시 던지지 않는다** — 두 번째 시도는 첫 번째 락에 걸린다.

### 4. 관측한다

```
sh plugins/sheetview-kit/scripts/sandbox-log.sh since
```

이번 기동 구간만 나온다. `idea.log` 는 기동할 때마다 덧붙으므로(지금 4천 행 넘게 쌓여 있다) 경계 없이 읽으면 **지난 기동의 예외를 이번 것으로 보고하게 된다.**

찾는 것:

| 신호 | 뜻 |
|---|---|
| `SEVERE` / `Unhandled exception` / `PluginException` | 진짜 고장 |
| 스택에 `dev.hugo.sheetview` | 우리 코드에서 난 것 — 무조건 🔴 |
| `ms to grab EDT` | EDT 블로킹 |
| `Slow operations are prohibited on EDT` | 같음 |
| `already disposed` / `Memory leak detected` | 해제 규약 위반 |
| `EditorComposite.createTabbedPaneWrapper` 의 `IndexOutOfBoundsException` | 탭 조립 깨짐 |
| `not registered as a service` | 확장 포인트로 찾아야 할 것을 서비스로 찾았다 |
| 맨몸 스택트레이스 | `printStackTrace()` 규약 위반 |

**무시할 잡음** (실측으로 확인된 것들 — 건수를 채우려고 올리지 마라):

- `jcef_cache_temp/… NoSuchFileException … is deleted while indexing`
- `GitRepositoriesHolder - State of repository … is not synchronized`
- `GitCommitTemplateTracker - Empty or blank commit template`
- `CodeWithMeCleanup - Starting the logs folder cleanup`
- `build/test-results/… is deleted while indexing is running`
- `DataSourceStorage… - Attempting to store empty state`
- `LoadingState - Should be called at least in the state COMPONENTS_LOADED` 및 그 뒤에 딸려 나오는 제품·JDK·OS 헤더 줄들

### 5. 닫는다

```
sh plugins/sheetview-kit/scripts/sandbox-up.sh down
```

락이 풀려야 이후 `./gradlew` 가 돈다. **닫지 않고 끝내지 마라.**

## 보고 형식

```markdown
## 샌드박스 검증: <이번에 고친 것>

**검사**: check.sh ✓ (N개 통과)   ← N 은 실제 출력값
**기동**: PID <pid> / 포트 <port> / 로그 구간 <M>행
**로드**: 플러그인 로드 성공 (프로세스 생존 = required.plugins.id 충족)

### 🔴 이번 구간에서 실제로 깨진 것
1. **<한 줄>** — <로그 원문 인용>

### 확인했고 문제없던 것
- <무엇을 봤는지 — 범위를 보여준다>

### 이 단계가 확인하지 못한 것
- 표 렌더·탭 순서·인코딩·미리보기는 기계가 볼 수 없습니다. 인계 단계에서 사람이 확인해야 합니다.

<판정 한 줄>
```

**판정 한 줄은 반드시 둘 중 하나로 끝낸다** — `cycle-verify.sh` 훅이 이 줄을 읽어 사이클 상태를 갱신한다:

- `샌드박스 확인 통과`
- `🔴 N건 — 샌드박스에서 실제로 깨졌다`

## 지어내지 않는다

PID·포트·버전·테스트 개수는 **실제 출력에서 읽은 값만** 쓴다. 기동을 확인하지 못했으면 "떴다"고 말하지 않는다. 로그를 읽지 못했으면 "깨끗하다"고 말하지 않는다 — 그건 "문제를 못 찾았다"와 다르다.
