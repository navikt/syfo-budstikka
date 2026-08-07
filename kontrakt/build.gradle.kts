import com.adarshr.gradle.testlogger.theme.ThemeType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.test.logger)
    // Publishing, compatibility gates and consumer/wire verification live in build-logic/ as
    // convention plugins, so this file stays an ordinary Kotlin library build.
    id("budstikka.kontrakt-publishing")
    id("budstikka.kontrakt-compatibility")
    id("budstikka.kontrakt-consumer-check")
    id("budstikka.kontrakt-wire-gate")
}

kotlin {
    // Built with the repository toolchain, but the *artifact* targets the producing applications, not
    // this service. Producers verified so far run JVM 25 and Kotlin 2.3.x, so the binary is pinned to
    // the lower bound they can all consume:
    //   - jvmTarget 21 (LTS floor) with -Xjdk-release, so no newer JDK API leaks into the bytecode;
    //   - language/apiVersion 2.3, because a Kotlin 2.3 compiler cannot read metadata written by 2.4.
    // Raising either bound is a breaking change for every producer below it.
    jvmToolchain(
        libs.versions.java
            .get()
            .toInt(),
    )
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        apiVersion = KotlinVersion.KOTLIN_2_3
        languageVersion = KotlinVersion.KOTLIN_2_3
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

dependencies {
    // `api`: the contract types are `@Serializable` and `dispatchJson` is public, so producers need
    // kotlinx-serialization on their own compile classpath. Nothing else may be added here — the
    // module must stay free of Ktor, Exposed, Flyway and kafka-clients.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    // ContractPrivacyTest walks the sealed hierarchies via `sealedSubclasses`, which needs Kotlin
    // reflection. Test-only: it must never leak onto `api`/`implementation` or the runtime classpath.
    testImplementation(libs.kotlin.reflect)
}

tasks {
    test {
        useJUnitPlatform()
        testlogger {
            theme = ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
            showSimpleNames = true
        }
    }

    named("check") {
        dependsOn("ktlintCheck")
    }
}
