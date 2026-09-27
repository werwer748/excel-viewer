---
paths:
  - "src/main/kotlin/dev/hugo/sheetview/format/**/*.kt"
  - "src/main/kotlin/dev/hugo/sheetview/model/**/*.kt"
  - "src/test/resources/fixtures/**/*"
---

# 표 파싱

리더 · 모델 · 픽스처를 읽을 때 붙는다. 실제 카드사·은행 명세서를 열어 보고 얻은 결론이다.

- **헤더 행은 `<thead>` 기준이다.** "`<th>` 를 포함한 선두 행" 규칙은 **틀린다** — 요약내역 표 본문 첫 행에 `<th>이번달</th>`(rowspan=3, 행 방향 헤더)가 있어 헤더를 2행으로 오인한다. 이 규칙으로 되돌리는 변경은 무조건 지적 대상이다.
- **colspan/rowspan 은 점유 맵으로 펼친다.** 상세내역 표가 `rowspan="2"` + `colspan="2"` 2단 헤더라 이게 없으면 컬럼 정렬이 깨진다. 결과 그리드는 직사각형이어야 한다.
- **셀 타입은 텍스트에서 추론한다.** 대상 파일에는 `mso-number-format`·`x:num` 이 하나도 없다. `x:num` 이 있으면 그쪽을 우선한다.
