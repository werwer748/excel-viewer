---
paths:
  - "src/test/kotlin/**/*.kt"
---

# 테스트

테스트를 읽거나 쓸 때 붙는다. 테스트 자체의 규약과, TDD 게이트(`tdd-guard` · `tdd-red`)가 테스트를 어떻게 보는지.

## 두 종류

**테스트가 두 종류다.** (a) 순수 JVM JUnit 5 — 리더·모델·export. 플랫폼 클래스를 쓰지 않으므로 IDE 없이 몇 초에 돈다. 이 자산을 지키려면 리더 쪽에 플랫폼 의존을 들이지 않는다. (b) `BasePlatformTestCase` — 파일 타입과 탭 구성처럼 `FileTypeRegistry` · `FileEditorProviderManager` 가 필요한 것. **JUnit3 계열이라 메서드 이름이 `test` 로 시작해야 발견**되고 vintage engine 으로 돈다.

## 돌리기

- **메서드 이름이 백틱으로 감싼 한국어 문장이다.** 메서드 단위로 필터하려면 공백이 들어가므로 반드시 인용한다.
- **테스트가 red 인데 내가 고친 것과 무관해 보이면** 진행 중인 기능이 테스트를 먼저 올려 둔 상태일 수 있다. `TODO.md` 의 '추가할 기능'을 먼저 확인하고, 내 변경분만 돌려 본다(`./gradlew test --tests "…"`). 그 상태에서는 `check.sh` 가 실패하므로 **Claude 는 커밋할 수 없다** — 사람이 터미널에서 직접 커밋해야 한다.

## 게이트가 테스트를 보는 방식

- **통과 조건은 같은 이름의 테스트 파일이 아니다.** `src/test/kotlin` 아래 **어느 파일이든** 그 클래스 이름 문자열을 포함하면 인정한다 (`grep -rqlF`). 로직을 순수 클래스로 빼내 기존 테스트에서 쓰는 것이 가장 쉬운 통과 경로다.
- **`tdd-red.sh` 는 한 번만 판정한다.** `tdd-guard.sh` 가 `.claude/.tdd-new` 에 남긴 표시를 **읽고 지운다**. 새 테스트를 만든 직후 그 파일을 또 수정하면 두 번째부터는 red 검사가 돌지 않는다.
- **컴파일 실패는 red 가 아니다.** `tdd-red.sh` 가 구분해서 `exit 2` 로 돌려준다.

## 테스트 수 래칫의 숫자

`.claude/tdd-baseline` 에 적는 숫자는 `check.sh` 가 출력하는 `(N개 통과)` 를 그대로 쓴다 — `@Test` 개수가 아니라 JUnit XML 의 `tests=` 합계이고, `BasePlatformTestCase` 쪽은 `@Test` 가 없으므로 세어서 맞추려 하면 틀린다.
