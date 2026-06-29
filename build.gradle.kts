import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SonatypeHost
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.vanniktech.mavenPublish)
  alias(libs.plugins.kover)
  alias(libs.plugins.dokka)
  alias(libs.plugins.binaryCompatibilityValidator)
  alias(libs.plugins.ktlint)
  alias(libs.plugins.detekt)
}

group = "com.expys"
// Overridden at release time via -PsdkVersion=X.Y.Z (from the kotlin/vX.Y.Z tag).
version = (findProperty("sdkVersion") as String?) ?: "0.0.0"

repositories {
  mavenCentral()
}

dependencies {
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)

  testImplementation(kotlin("test"))
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.junit.jupiter)
  testRuntimeOnly(libs.junit.platform.launcher)
  // Exercises the real OkHttp transport end-to-end in IntegrationTest.
  testImplementation(libs.okhttp.mockwebserver)
}

kotlin {
  // The public boundary is compiler-enforced: every public symbol must declare an
  // explicit visibility modifier and explicit return/property types. Accidental
  // public surface fails the build.
  explicitApi()
  // Compile to JVM 17 bytecode using whatever JDK runs Gradle (CI uses 21), so
  // no toolchain auto-provisioning is needed. Plain Kotlin/JVM - no Android/AGP.
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
  useJUnitPlatform()
}

// Compile the reference example (it lives outside src/main and is not in the
// published jar) against the SDK + its runtime deps, so a broken example fails CI.
sourceSets {
  val examples by creating {
    kotlin.srcDir("examples")
    compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
  }
}

tasks.named("check") {
  dependsOn("compileExamplesKotlin")
}

// Bundle the R8/ProGuard keep rules into the jar at META-INF/proguard so a consuming
// Android app's R8 applies them automatically when shrinking a release build. The SDK
// stays pure Kotlin/JVM (no Android Gradle Plugin); this is the JVM-jar equivalent of
// an Android library's consumerProguardFiles.
tasks.jar {
  from("consumer-rules.pro") {
    into("META-INF/proguard")
    rename { "expys-sdk.pro" }
  }
}

// Coverage gate on the hand-written logic (transport, auth, retry, error mapping,
// version). The generated DTOs in com.expys.sdk.models are excluded - they are
// not hand-written. Enforced in CI via `./gradlew koverVerify`.
kover {
  reports {
    filters {
      excludes {
        // Generated DTOs and the reference example are not hand-written logic.
        packages("com.expys.sdk.models", "com.expys.sdk.examples")
      }
    }
    verify {
      rule {
        // Raised from 90/80 after broadening behavioural coverage; the suite reports
        // ~98% line / ~88% branch, so these gates lock in the gain with headroom.
        minBound(95, CoverageUnit.LINE)
        minBound(85, CoverageUnit.BRANCH)
      }
    }
  }
}

// ktlint (format + lint) and detekt (static analysis), both wired into `check`.
// The generated DTOs in com.expys.sdk.models are excluded - they are produced by
// OpenAPI Generator, not hand-written, and are gated by the model-drift job.
ktlint {
  filter {
    exclude { entry -> entry.file.toString().contains("${File.separator}models${File.separator}") }
  }
}

detekt {
  buildUponDefaultConfig = true
  config.setFrom(files("detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
  exclude("**/models/**")
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
  exclude("**/models/**")
}

dokka {
  moduleName.set("Expys Kotlin SDK")
  dokkaSourceSets.configureEach {
    // Flag any hand-written public symbol that is missing a KDoc. The generated DTOs
    // carry generator-level docs, so they are not held to the hand-written bar.
    reportUndocumented.set(true)
    perPackageOption {
      matchingRegex.set("com\\.expys\\.sdk\\.models.*")
      reportUndocumented.set(false)
    }
  }
}

mavenPublishing {
  // Ship the sources jar and a real Dokka-generated javadoc jar (Maven Central
  // requires a javadoc artifact; an empty one would pass but help no one).
  configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"), sourcesJar = true))
  // Maven Central via the Central Portal. Signing + portal credentials are
  // supplied at publish time (CI secrets); `build`/`test` do not need them.
  publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
  signAllPublications()
  coordinates("com.expys", "sdk", version.toString())
  pom {
    name.set("Expys SDK")
    description.set("Official Expys data SDK for Kotlin/JVM - embed Expys experiences into your app.")
    inceptionYear.set("2026")
    url.set("https://github.com/Utopia-Members-Club-Inc/utopia")
    licenses {
      license {
        name.set("MIT License")
        url.set("https://opensource.org/licenses/MIT")
      }
    }
    developers {
      developer {
        id.set("expys")
        name.set("Expys")
        url.set("https://github.com/Utopia-Members-Club-Inc")
      }
    }
    scm {
      url.set("https://github.com/Utopia-Members-Club-Inc/utopia")
      connection.set("scm:git:git://github.com/Utopia-Members-Club-Inc/utopia.git")
      developerConnection.set("scm:git:ssh://git@github.com/Utopia-Members-Club-Inc/utopia.git")
    }
  }
}
