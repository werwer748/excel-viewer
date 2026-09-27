---
paths:
  - "build.gradle.kts"
  - "settings.gradle.kts"
  - "gradle.properties"
  - "gradle/**/*"
  - "src/main/resources/META-INF/*.xml"
  - ".github/workflows/*.yml"
---

# 의존성 · 이식성 · 빌드 환경

빌드 스크립트 · 디스크립터 · CI 를 읽을 때 붙는다. 한 ZIP 이 WebStorm · IDEA · PyCharm · CLion · DataGrip 에 그대로 설치되어야 한다는 전제를 지키는 결정들이다.

## 의존성과 이식성

- **Apache POI 를 넣지 않는다.** `xmlbeans`·`log4j-api`·`SparseBitSet` 등 약 7MB가 따라오고 IDE 클래스로더와 충돌 여지가 생긴다. 진짜 BIFF `.xls` 는 예외 대신 **미지원 안내 패널**로 처리한다 — 여기서 안내를 띄우는 것이 POI 를 끌고 오는 것보다 낫다는 판단이다. (참고: DataGrip 도 POI 를 플러그인 클래스로더 **밖** Grape 캐시에 격리해 쓴다.)
- **jsoup 은 번들하지 않는다.** IDE 가 1.22.1 을 부트 클래스패스에 이미 싣고 있어 `compileOnly` 로만 참조한다. 번들하면 버전이 충돌한다.
- **원격 플랫폼 의존성에 `create("IC", …)` 를 쓰지 않는다.** IntelliJ IDEA Community 는 **2025.3(253)부터 배포가 중단**되어 `idea:ideaIC:<버전>` 이 존재하지 않는다. `intellijIdea(version)` 을 쓴다(좌표가 `idea:idea` 가 된다). 이 자리는 `localIdePath` 가 설정된 로컬에서는 **아예 타지 않으므로** 깨져 있어도 눈치채기 어렵다 — 실제로 CI 를 붙이고 나서야 처음 드러났다. "clone 직후 아무 설정 없이 빌드된다"는 전제는 CI 만이 지켜준다.
- **`<idea-version>` 을 `plugin.xml` 에 두지 않는다.** `build.gradle.kts` 의 `ideaVersion` DSL 이 `patchPluginXml` 로 주입한다. 두 곳에 두면 소스가 갈린다. id·name·vendor·description 은 반대로 `plugin.xml` 에만 둔다.
- **`untilBuild` 를 되살리지 않는다.** 무제한이라 이후 IDE 업데이트에도 깨지지 않는다. 기본값 `"262.*"` 로 되돌리면 263 계열 IDE(설치된 PyCharm)가 로드를 거부한다. 공식 권장도 이 속성을 설정하지 않는 쪽이다.
- **플러그인 description 은 라틴 문자로 시작해야 한다.** 한국어로 시작하면 Plugin Verifier 가 구조 오류로 반려한다(Marketplace 규칙). 로컬 설치에는 영향 없지만 `verifyPlugin` 이 막힌다.
- **플랫폼 모듈(`com.intellij.modules.platform`) 밖의 클래스를 쓰지 않는다.** 특정 IDE 전용 클래스가 섞이면 다섯 IDE 중 일부에서 깨진다. `verifyPlugin` 이 이걸 잡는다.

## 빌드 환경

- **JDK 25 가 필요하다.** 플랫폼 262 클래스가 Java 25 바이트코드(major 69)다. 없으면 foojay 리졸버가 받아 오고, 플랫폼 의존성도 지정이 없으면 원격 아티팩트를 받는다 — clone 직후 아무 설정 없이 빌드된다.
- **개인 경로는 저장소에 두지 않는다.** `~/.gradle/gradle.properties` 에 `org.gradle.java.installations.paths` / `localIdePath` / `verifyIdePaths` 를 넣으면 ~1GB 다운로드가 사라진다(설치된 IDE의 번들 JBR이 `javac 25` 를 포함한 완전한 JDK다). 저장소의 `gradle.properties` 에는 빈 키와 설명만 있다.
