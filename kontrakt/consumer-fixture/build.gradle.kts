import org.gradle.api.tasks.JavaExec

plugins {
    kotlin("jvm") version "2.3.21"
}

val contractRepository =
    providers.gradleProperty("contractRepository").orNull
        ?: error("The standalone consumer requires -PcontractRepository=<file Maven repository>.")
val contractVersion =
    providers.gradleProperty("contractVersion").orNull
        ?: error("The standalone consumer requires -PcontractVersion=<published contract version>.")

providers.gradleProperty("consumerBuildDirectory").orNull?.let { consumerBuildDirectory ->
    layout.buildDirectory.set(file(consumerBuildDirectory))
}

repositories {
    maven(url = contractRepository)
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // This must remain a Maven coordinate. A project dependency or substitution would bypass
    // the published POM/module metadata this fixture is intended to test.
    implementation("no.nav.syfo:budstikka-kontrakt:$contractVersion")

    // kafka-clients is intentionally owned by the producer, never by budstikka-kontrakt.
    implementation("org.apache.kafka:kafka-clients:4.3.1")
}

tasks.register<JavaExec>("verifyQuickstart") {
    group = "verification"
    description = "Runs the README ProducerRecord mapping against the published Maven artifact."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("no.nav.budstikka.consumerfixture.QuickstartKt")
}

tasks.named("check") {
    dependsOn("verifyQuickstart")
}
