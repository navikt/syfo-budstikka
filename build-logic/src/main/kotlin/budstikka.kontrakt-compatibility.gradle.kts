// The two producer-API compatibility gates, both wired into `check`:
//
//   1. `checkKotlinAbi` — diffs the public API surface against the checked-in dump in `api/`, so
//      every API change is a reviewable diff in the PR. This is the Kotlin Gradle plugin's built-in
//      ABI validation: its DSL is still marked experimental, but it is JetBrains' successor to
//      kotlinx binary-compatibility-validator (now in maintenance mode), it ships with the pinned
//      compiler so it can never be version-incompatible, and it supports the wildcard filters below.
//   2. `checkPublishedContractCompatibility` (japicmp) — compares the built jar against the previous
//      *published* artifact resolved from the Nav mirror, so consumer breakage is caught against
//      reality rather than a file that can be edited in the same PR.
//
// Assumes the consuming project applies the Kotlin JVM plugin first.
import me.champeau.gradle.japicmp.JapicmpTask
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("me.champeau.gradle.japicmp")
}

// Raw-wire families excluded from both API gates. The entries are name-prefix wildcards: a future
// producer-facing public type must not reuse one of these prefixes, or it would silently fall
// outside both gates.
val rawWireGeneratedClassPatterns =
    listOf(
        "no.nav.budstikka.contract.AltinnExternalVarsling**",
        "no.nav.budstikka.contract.AltinnResource**",
        "no.nav.budstikka.contract.ArbeidsgiverMeldingstype",
        "no.nav.budstikka.contract.ArbeidsgiverRecipient**",
        "no.nav.budstikka.contract.ArbeidsgivervarselCreate**",
        "no.nav.budstikka.contract.ArbeidsgivervarselInactivate**",
        "no.nav.budstikka.contract.BrevCreate**",
        "no.nav.budstikka.contract.Brukervarsel**",
        "no.nav.budstikka.contract.DittSykefravaerCreate**",
        "no.nav.budstikka.contract.DittSykefravaerInactivate**",
        "no.nav.budstikka.contract.DispatchKt",
        "no.nav.budstikka.contract.Dispatch**",
        "no.nav.budstikka.contract.Ledervarsel**",
        "no.nav.budstikka.contract.Microfrontend**",
        "no.nav.budstikka.contract.NarmesteLeder**",
        "no.nav.budstikka.contract.Sakstilknytning**",
    )

extensions.configure<KotlinJvmProjectExtension>("kotlin") {
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        // The facade is the published producer surface. Raw wire declarations are deliberately
        // @InternalBudstikkaWire and must not make an API change look producer-visible.
        referenceDumpDir.set(layout.projectDirectory.dir("api"))
        filters {
            exclude {
                annotatedWith.add("no.nav.budstikka.contract.InternalBudstikkaWire")
                byNames.add("no.nav.budstikka.contract.InternalBudstikkaWire")
                // Generated serializers, companions and file classes do not carry their declaration's
                // property/class annotation, so exclude the raw-wire implementation families explicitly.
                byNames.addAll(rawWireGeneratedClassPatterns)
            }
        }
    }
}

val previousContractVersion = providers.gradleProperty("previousContractVersion")
val previousContractRepository =
    providers
        .gradleProperty("previousContractRepository")
        .orElse("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
val stableSemVer = Regex("""^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$""")

repositories {
    maven(url = previousContractRepository) {
        content {
            includeModule("no.nav.syfo", "budstikka-kontrakt")
        }
    }
    mavenCentral {
        content {
            excludeModule("no.nav.syfo", "budstikka-kontrakt")
        }
    }
}

val previousContractArtifact =
    configurations.create("previousContractArtifact") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
val previousContractClasspath =
    configurations.create("previousContractClasspath") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = true
    }

previousContractVersion.orNull?.let { previousVersion ->
    require(stableSemVer.matches(previousVersion)) {
        "previousContractVersion must be a stable SemVer X.Y.Z without leading zeroes."
    }
    val previousCoordinate = "no.nav.syfo:budstikka-kontrakt:$previousVersion"
    dependencies {
        add(previousContractArtifact.name, previousCoordinate)
        add(previousContractClasspath.name, previousCoordinate)
    }
}

val checkPublishedContractCompatibility =
    tasks.register<JapicmpTask>("checkPublishedContractCompatibility") {
        group = "verification"
        description = "Fails on binary or source-incompatible changes from the previous published contract."
        dependsOn(tasks.named("jar"))
        oldArchives.from(previousContractArtifact)
        oldClasspath.from(previousContractClasspath)
        newArchives.from(tasks.named("jar"))
        newClasspath.from(configurations.named("runtimeClasspath"))
        onlyModified.set(true)
        failOnModification.set(true)
        failOnSourceIncompatibility.set(true)
        ignoreMissingClasses.set(false)
        includeSynthetic.set(true)
        accessModifier.set("public")
        // Raw wire is protected by golden serialization and new-topic compatibility rules; japicmp
        // guards the producer-facing Kotlin/JVM API.
        annotationExcludes.add("@no.nav.budstikka.contract.InternalBudstikkaWire")
        classExcludes.addAll(rawWireGeneratedClassPatterns)
        txtOutputFile.set(layout.buildDirectory.file("reports/japicmp/contract-compatibility.txt"))
        htmlOutputFile.set(layout.buildDirectory.file("reports/japicmp/contract-compatibility.html"))
        onlyIf {
            if (previousContractVersion.isPresent) {
                true
            } else {
                logger.lifecycle(
                    "No previousContractVersion supplied: explicit first-release compatibility path; " +
                        "no published baseline is compared.",
                )
                false
            }
        }
    }

tasks.named("check") {
    dependsOn("checkKotlinAbi")
    dependsOn(checkPublishedContractCompatibility)
}
