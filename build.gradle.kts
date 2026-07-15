import com.adarshr.gradle.testlogger.theme.ThemeType

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

    shadowJar {
        filesMatching("META-INF/services/**") {
            duplicatesStrategy = DuplicatesStrategy.WARN
        }
        mergeServiceFiles()
        archiveFileName.set("app.jar")
        archiveClassifier.set("")
        archiveVersion.set("")
    }
}
