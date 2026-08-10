import com.adarshr.gradle.testlogger.theme.ThemeType
import com.google.cloud.tools.jib.gradle.JibExtension
import org.gradle.api.tasks.Exec
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask
import java.net.URI

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
    alias(libs.plugins.apollo)
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
    compilerOptions {
        // Budstikka is the consuming side of its own contract: it decodes the envelope, routes the raw
        // content types and reads the eventId header. That is exactly what :kontrakt gates behind
        // @InternalBudstikkaWire, so this application opts in once, here, instead of sprinkling @OptIn
        // across the source. A Produsent gets no such line and is therefore held to the Budstikka facade.
        optIn.add("no.nav.budstikka.contract.InternalBudstikkaWire")
    }
}

// The container image is built with plain Jib (the Ktor plugin activates JibPlugin), not Ktor's
// docker{} tasks. Explicitly setting the Chainguard base (JRE 25) in from.image avoids
// the Ktor plugin's JRE validation and setupJibLocal path (a Task.project deprecation remains in
// the Jib plugin's own tasks: upstream, not our code).
//
// Jib cannot parse Chainguard's OCI Image Index v1.1 (`artifactType`; an unresolved upstream bug
// verified against our base). Pre-pull the base to the local Docker daemon, which supports OCI 1.1,
// and point Jib to the daemon image through the `docker://` reference.
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
    // The wire contract and its producer API. The app is a consumer of its own published contract:
    // there is exactly one contract model, in :kontrakt.
    implementation(project(":kontrakt"))
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
    implementation(libs.apollo.api)

    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.exposed.migration.core)
    testImplementation(libs.exposed.migration.jdbc)
    testImplementation(libs.ktor.server.test)
    testImplementation(libs.kotlin.test)
}

apollo {
    service("fager") {
        packageName.set("no.nav.budstikka.infrastructure.client.fager.generated")
        srcDir("src/main/graphql/fager")
        schemaFiles.from("src/main/graphql/fager/schema.graphqls")
        codegenModels.set("operationBased")
        generateAsInternal.set(true)
        generateMethods.set(emptyList())
        mapScalarToKotlinString("ISO8601Duration")
        failOnWarnings.set(true)
    }
}

val fagerRepository = "https://raw.githubusercontent.com/navikt/arbeidsgiver-notifikasjon-produsent-api"
val fagerSchemaPath = "app/src/main/resources/produsent.graphql"
val pinnedFagerRevision = "5c9251d6aaa850e08c559560bc6fed941842d5ea"

fun registerFagerSchemaCheck(
    taskName: String,
    revision: String,
    taskDescription: String,
) {
    tasks.register<Exec>(taskName) {
        group = "apollo"
        description = taskDescription

        val localSchema = layout.projectDirectory.file("src/main/graphql/fager/schema.graphqls")
        val downloadedSchema = layout.buildDirectory.file("fager-schema/$taskName/schema.graphqls")

        inputs.file(localSchema)
        outputs.upToDateWhen { false }

        doFirst {
            val destination = downloadedSchema.get().asFile
            destination.parentFile.mkdirs()
            URI("$fagerRepository/$revision/$fagerSchemaPath")
                .toURL()
                .openConnection()
                .apply {
                    connectTimeout = 10_000
                    readTimeout = 30_000
                }.getInputStream()
                .use { input ->
                    destination.outputStream().use(input::copyTo)
                }
        }

        commandLine(
            "diff",
            "-u",
            localSchema.asFile.absolutePath,
            downloadedSchema.get().asFile.absolutePath,
        )
    }
}

registerFagerSchemaCheck(
    taskName = "verifyFagerSchema",
    revision = pinnedFagerRevision,
    taskDescription = "Verifies the local Fager schema against its pinned upstream revision.",
)
registerFagerSchemaCheck(
    taskName = "checkFagerSchemaUpdate",
    revision = "main",
    taskDescription = "Checks the local Fager schema for changes on the upstream main branch.",
)

tasks {
    named<BaseKtLintCheckTask>("runKtlintCheckOverMainSourceSet") {
        setSource(
            fileTree("src/main/kotlin") {
                include("**/*.kt")
            },
        )
    }
    named<BaseKtLintCheckTask>("runKtlintFormatOverMainSourceSet") {
        setSource(
            fileTree("src/main/kotlin") {
                include("**/*.kt")
            },
        )
    }

    register("printVersion") {
        description = "Print the version of the app"
        doLast {
            println(project.version)
        }
    }

    test {
        useJUnitPlatform()
        // Slow full-boot e2e tests (Kotest tag "E2E") are excluded from the default run, so CI/CD
        // does not await Testcontainers startup on every deployment. Schema-drift and repository
        // integration tests are untagged and therefore still run here.
        systemProperty("kotest.tags", "!E2E")
        testlogger {
            theme = ThemeType.MOCHA_PARALLEL
            showFullStackTraces = true
            showSimpleNames = true
        }
    }

    // Opt-in: `./gradlew e2eTest` runs ONLY E2E-tagged specs. It is not connected to `check`, keeping
    // the default build fast; run it manually or in a separate nightly CI job.
    register<Test>("e2eTest") {
        description = "Runs opt-in full-boot e2e tests (Kotest tag E2E) against Testcontainers."
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

    // Boots the full application locally against Testcontainers (Postgres + Kafka), with fakes wired
    // through the test substrate in src/test. Uses the same main class as the e2e harness. See docs/teststrategi.md.
    register<JavaExec>("runLocal") {
        description = "Boots the application locally against Testcontainers (Postgres + Kafka) with fakes."
        group = "application"
        mainClass.set("no.nav.budstikka.LocalAppKt")
        classpath = sourceSets["test"].runtimeClasspath
        standardInput = System.`in`
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // Human-readable local logs (src/test/resources/logback-local.xml). Production still uses
        // JSON through logback.xml. This file exists only on the test classpath, never in the production JAR.
        systemProperty("logback.configurationFile", "logback-local.xml")
    }

    named("check") {
        dependsOn("ktlintCheck")
    }
}
