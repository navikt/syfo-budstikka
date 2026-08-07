// Compile-time proof of the @InternalBudstikkaWire gate. The gate is a compile-time claim, so the
// evidence has to be a compilation, not an assertion about one. Two halves, both wired into `check`:
//
//   1. `producerFixture` — a source set standing in for a producing application. Facade only, no
//      opt-in anywhere. The ordinary Kotlin plugin compiles it, so the build fails if a type a
//      Produsent needs disappears behind the marker.
//   2. `verifyWireOptInGate` — compiles src/wireGateFixture twice and asserts the raw wire is
//      reachable WITH the opt-in and rejected WITHOUT it.
//
// Neither fixture ends up in the jar or on any published classpath.
// Assumes the consuming project applies the Kotlin JVM plugin first.
import no.nav.budstikka.buildlogic.VerifyWireOptInGate
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

val producerFixture: SourceSet = sourceSets.create("producerFixture")

dependencies {
    add(producerFixture.implementationConfigurationName, sourceSets["main"].output)
    add(
        producerFixture.implementationConfigurationName,
        libs.findLibrary("kotlinx-serialization-json").orElseThrow().get(),
    )
}

val verifyWireOptInGate =
    tasks.register<VerifyWireOptInGate>("verifyWireOptInGate") {
        group = "verification"
        description = "Proves the raw wire contract requires an explicit opt-in at compile time."
        dependsOn(tasks.named("classes"))
        fixtureDirectory = layout.projectDirectory.dir("src/wireGateFixture")
        kotlinCompilerClasspath.from(configurations.named("kotlinCompilerClasspath"))
        contractClasspath.from(sourceSets["main"].output, configurations.named("runtimeClasspath"))
        optInMarker = "no.nav.budstikka.contract.InternalBudstikkaWire"
        jvmTarget = JvmTarget.JVM_21.target
        javaLauncher =
            javaToolchains.launcherFor {
                languageVersion =
                    JavaLanguageVersion.of(
                        libs
                            .findVersion("java")
                            .orElseThrow()
                            .requiredVersion
                            .toInt(),
                    )
            }
        workDirectory = layout.buildDirectory.dir("wire-opt-in-gate")
    }

tasks.named("check") {
    dependsOn(producerFixture.classesTaskName)
    dependsOn(verifyWireOptInGate)
}
