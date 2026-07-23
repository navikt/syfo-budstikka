import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    `maven-publish`
}

group = "no.nav.syfo"
// Kontraktbibliotekets versjon er FRIKOBLET fra app-modulen (ADR 0010, B63). Settes av
// release-workflowen fra git-tag `kontrakt/vX.Y.Z`; default SNAPSHOT lokalt. 0.x fram til prod.
version = providers.gradleProperty("kontraktVersion").getOrElse("0.1.0-SNAPSHOT")

// Bygges mot JVM 21 (både Java- og Kotlin-target) for bred konsument-kompatibilitet: appen kjører
// på 25, men et publisert bibliotek må ikke tvinge konsumentene opp på nyeste JDK (25-runtime
// laster 21-bytecode fint). Verifiser konsumentenes JDK-gulv før prod.
private val consumerJvmTarget = 21

kotlin {
    jvmToolchain(consumerJvmTarget)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

// Sources-jar gir konsumentene IDE-navigasjon inn i kontrakten.
java {
    withSourcesJar()
}

dependencies {
    // `api`: konsumentene trenger serialization transitivt for @Serializable-typene + dispatchJson.
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "budstikka-kontrakt"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/navikt/syfo-budstikka")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
