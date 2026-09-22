plugins {
    // 로컬에 JDK 25가 없을 때를 대비한 폴백. gradle.properties 가 번들 JBR을
    // 먼저 제시하므로 보통 다운로드는 일어나지 않는다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "excel-viewer"
