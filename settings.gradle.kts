pluginManagement {
    // Convention plugins for :kontrakt's publishing and verification machinery (build-logic/).
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
    }
    versionCatalogs {
        create("ktorLibs").from("io.ktor:ktor-version-catalog:3.5.2")
    }
}

rootProject.name = "syfo-budstikka"

// The wire contract and its producer API live in a separate, Kafka-free subproject so producing
// applications can compile against it without pulling in budstikka's runtime (Ktor, Exposed, Flyway,
// kafka-clients). Publication and versioning of the artifact are handled separately.
include("kontrakt")
