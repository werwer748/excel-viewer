plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.hugo"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// 플랫폼 의존성으로 쓸 로컬 IDE 경로. 설치된 IDE를 직접 참조하면 ~1GB 다운로드를 피할 수 있다.
// 경로는 사람마다 다르므로 값은 저장소에 두지 않고 ~/.gradle/gradle.properties 에서 받는다.
// 비어 있으면 원격 아티팩트를 받는다 - clone 직후 아무 설정 없이 빌드되게 하기 위함이다.
val localIdePath: String = providers.gradleProperty("localIdePath").orNull.orEmpty().trim()

// build 262 = 2026.2 계열. sinceBuild 와 맞춰 둔다.
val fallbackIdeVersion = "2026.2.3"

dependencies {
    intellijPlatform {
        if (localIdePath.isNotEmpty()) local(file(localIdePath)) else create("IC", fallbackIdeVersion)
        pluginVerifier()
    }
    // jsoup 1.22.1은 IDE가 lib/intellij.libraries.jsoup.jar 로 부트 클래스패스에 이미 싣고 있다.
    // 같은 버전을 compileOnly로만 참조해 플러그인에는 넣지 않는다 (버전 충돌 방지).
    compileOnly("org.jsoup:jsoup:1.22.1")

    // 리더와 모델은 플랫폼 클래스를 전혀 쓰지 않으므로 순수 JVM 테스트로 검증할 수 있다.
    testImplementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("org.jsoup:jsoup:1.22.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

// 플랫폼 262 클래스는 Java 25 바이트코드(major 69)라 JDK 25가 필요하다.
// gradle.properties 가 번들 JBR 25를 툴체인으로 등록해 둔다.
kotlin { jvmToolchain(25) }

intellijPlatform {
    // 설정 UI가 없으므로 헤드리스 IDE를 띄우는 이 단계를 건너뛴다.
    buildSearchableOptions = false
    // .form 파일도, @NotNull 계측 대상도 없다.
    instrumentCode = false

    pluginConfiguration {
        // id / name / vendor / description 은 plugin.xml 에만 둔다 (소스 하나 유지).
        version = project.version.toString()
        ideaVersion {
            sinceBuild = "262"
            // until-build를 두지 않아 이후 IDE 업데이트에도 깨지지 않는다.
            // (설치된 PyCharm은 263이라 기본값 "262.*"면 로드가 거부된다.)
            untilBuild = provider { null }
        }
    }

    // 한 빌드를 여러 IDE에 설치하므로, 특정 IDE 전용 클래스를 실수로 쓰지 않았는지 검증한다.
    // 설치된 IDE들로 검증하려면 verifyIdePaths 에 쉼표로 구분해 경로를 넣는다.
    // 비어 있으면 JetBrains 가 권장하는 IDE 조합을 내려받아 검증한다.
    pluginVerification {
        ides {
            val paths = providers.gradleProperty("verifyIdePaths").orNull.orEmpty()
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (paths.isEmpty()) recommended() else paths.forEach { local(file(it)) }
        }
    }
}
