---
paths:
  - "src/main/kotlin/**/*.kt"
---

# 실패 · 취소 · 자원 상한 규약

본체 코드를 읽을 때 붙는다. 입력은 신뢰할 수 없는 파일이고 파싱은 IDE 프로세스 안에서 일어나므로, 여기서 틀리면 파일 하나가 아니라 IDE 가 얼거나 죽는다.

- 사용자에게 보이는 실패는 **두 타입으로만** 모인다: `UnsupportedSpreadsheetException`(정상적인 미지원) / `SpreadsheetParseException`(형식은 맞지만 깨짐). 둘 다 `userMessage` 와 선택적 `hint` 를 들고 패널에 **한국어로** 표시된다.
- `userMessage` 는 무엇이 안 됐는지 한 문장. `hint` 는 사용자가 할 수 있는 다음 행동. **"파싱 실패"는 메시지가 아니다.**
- **`ProcessCanceledException` 과 `CancellationException` 은 절대 삼키지 않는다.** 재던진다. `catch` 순서를 눈으로 확인해야 한다 — 넓은 `catch (t: Throwable)` 가 위에 오면 조용히 먹는다. 정리 코드는 `NonCancellable` 로 보장한다.
- **`printStackTrace()` 를 쓰지 않는다.** 스택트레이스를 콘솔에 뱉는 경로는 위 규약을 깨고, `check.sh` lint 가 막는다.
- 취소는 `ReadContext.checkCancelled` 하나로 전파된다. 리더는 행·항목 일정 간격마다 이걸 부른다.
- 새 StAX 파서를 만들면 `SUPPORT_DTD` 와 `IS_SUPPORTING_EXTERNAL_ENTITIES` 를 **반드시** `false` 로 둔다(XXE·billion-laughs). `check.sh` lint 가 `XMLInputFactory.newInstance()` 를 쓰는 파일에서 이걸 검사한다.
- 자원 상한은 `ReadLimits` 에 모여 있다. 새 리더를 추가하면 상한을 **실제로 적용**해야 한다 — 선언만 있고 적용이 빠진 자리가 이미 있다(`TODO.md` 참고).
