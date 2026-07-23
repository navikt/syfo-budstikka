import com.adarshr.gradle.testlogger.theme.ThemeType
import com.google.cloud.tools.jib.gradle.JibExtension
import org.gradle.api.tasks.Exec

buildscript {
    dependencies {
        classpath(libs.flyway.database.postgresql)
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.flyway)
    alias(libs.plugins.test.logger)
}

group = "no.nav.syfo"
version = "1.0.0-SNAPSHOT"

val javaMajorVersion = libs.versions.java.get()
val chainguardBaseImage =
    "europe-north1-docker.pkg.dev/cgr-nav/pull-through/nav.no/jre:openjdk-$javaMajorVersion"
val dockerImageRepository =
    providers
        .gradleProperty("docker.image.repository")
        .orElse(providers.environmentVariable("DOCKER_IMAGE_REPOSITORY"))
        .orElse(project.name)
val dockerImageTag =
    providers
        .gradleProperty("docker.image.tag")
        .orElse(providers.environmentVariable("DOCKER_IMAGE_TAG"))
        .orElse(providers.provider { project.version.toString() })

application {
    mainClass = "io.ktor.server.netty.EngineMain"
    applicationDefaultJvmArgs += "--enable-native-access=ALL-UNNAMED"
}

kotlin {
    jvmToolchain(
        libs.versions.java
            .get()
            .toInt(),
    )
}

// Container-image bygges med ren Jib (Ktor-pluginen aktiverer JibPlugin), ikke Ktor sine
// docker{}-tasks — se ADR 0009. Å sette Chainguard-basen (JRE 25) eksplisitt i from.image fjerner
// Ktor-pluginens JRE-validering og setupJibLocal-stien (en Task.project-deprecation gjenstår i
// jib-pluginens egne tasks — upstream, ikke vår kode).
//
// Jib kan ikke parse Chainguards OCI Image Index v1.1 (feltet "artifactType", ufikset upstream-bug
// verifisert mot vår base). Derfor pre-pulles basen til lokal Docker-daemon (som håndterer OCI 1.1)
// og Jib peker på daemon-imaget via docker://-referansen.
val pullChainguardBaseImage =
    tasks.register<Exec>("pullChainguardBaseImage") {
        group = "jib"
        description = "Pre-pulls Chainguard base image to the local Docker daemon for Jib."
        commandLine("docker", "pull", chainguardBaseImage)
    }

configure<JibExtension> {
    from {
        image = "docker://$chainguardBaseImage"
    }
    to {
        image = "${dockerImageRepository.get()}:${dockerImageTag.get()}"
    }
    container {
        mainClass = application.mainClass.get()
        ports = listOf("8080")
        jvmFlags = listOf("-XX:MaxRAMPercentage=75", "--enable-native-access=ALL-UNNAMED")
        environment = mapOf("TZ" to "Europe/Oslo")
    }
}

listOf("jib", "jibDockerBuild", "jibBuildTar").forEach { jibTask ->
    tasks.named(jibTask) {
        dependsOn(pullChainguardBaseImage)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.slf4j)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.di)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.hikari)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.json)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.postgresql)
    implementation(libs.logback.classic)
    implementation(libs.logstash.logback.encoder)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.kafka.clients)
    implementation(libs.tms.varsel.java.builder)
    implementation(libs.tms.mikrofrontend.selector.builder)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.exposed.migration.core)
    testImplementation(libs.exposed.migration.jdbc)
}

tasks {
    register("printVersion") {
        description = "Print the version of the app"
        doLast {
            println(project.version)
        }
    }

    test {
        useJUnitPlatform()
        // De trege full-boot-e2e-testene (Kotest-tag "E2E") ekskluderes fra default-løpet slik at
        // CI/CD ikke venter på Testcontainers-oppstart ved hver deploy. Schema-drift- og
        // repository-integrasjonstestene er ikke tagget og kjører derfor uansett her.
        systemProperty("kotest.tags", "!E2E")
        testlogger {
            theme = ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
            showSimpleNames = true
        }
    }

    // Opt-in: `./gradlew e2eTest` kjører KUN de E2E-taggede specene. Ikke koblet til `check`, så
    // default-bygget forblir raskt; kjøres manuelt eller i en egen/nattlig CI-jobb.
    register<Test>("e2eTest") {
        description = "Kjører opt-in full-boot-e2e (Kotest-tag E2E) mot Testcontainers."
        group = "verification"
        useJUnitPlatform()
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        systemProperty("kotest.tags", "E2E")
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        shouldRunAfter("test")
        testlogger {
            theme = ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
            showSimpleNames = true
        }
    }

    // Booter hele appen lokalt mot Testcontainers (Postgres + Kafka) med fakes wiret inn via
    // test-substratet i src/test. Samme main-klasse som e2e-harnessen bruker. Se docs/teststrategi.md.
    register<JavaExec>("runLocal") {
        description = "Booter appen lokalt mot Testcontainers (Postgres + Kafka) med fakes."
        group = "application"
        mainClass.set("no.nav.budstikka.LocalAppKt")
        classpath = sourceSets["test"].runtimeClasspath
        standardInput = System.`in`
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // Menneskelig lesbar logg lokalt (src/test/resources/logback-local.xml). Prod bruker
        // fortsatt JSON via logback.xml. Filen finnes bare i test-classpath, aldri i prod-jaren.
        systemProperty("logback.configurationFile", "logback-local.xml")
    }

    named("check") {
        dependsOn("ktlintCheck")
    }
}
