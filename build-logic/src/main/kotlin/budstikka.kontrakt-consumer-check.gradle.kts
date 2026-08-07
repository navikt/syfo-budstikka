// Proves the published artifact works for a real producer: a standalone Kotlin 2.3 build (see
// kontrakt/consumer-fixture) resolves the Maven coordinate from an isolated Gradle home and runs the
// README quickstart against it. Requires the budstikka.kontrakt-publishing convention.
import no.nav.budstikka.buildlogic.CheckStandaloneConsumer

val contractVersion = providers.gradleProperty("contractVersion").orElse("0.1.0-local")

val currentGradleInstallation =
    gradle.gradleHomeDir ?: error("The standalone consumer checks require a Gradle installation home.")

tasks.register<CheckStandaloneConsumer>("checkLocalConsumer") {
    group = "verification"
    description = "Checks the standalone Kotlin 2.3 consumer against the local contract publication."
    dependsOn("publishContractPublicationToLocalContractRepository")
    fixtureDirectory = layout.projectDirectory.dir("consumer-fixture")
    repositoryDirectory = layout.buildDirectory.dir("local-maven-repository")
    gradleExecutable = currentGradleInstallation.resolve("bin/gradle")
    publishedVersion = contractVersion
    workDirectory = layout.buildDirectory.dir("local-consumer")
}

tasks.register<CheckStandaloneConsumer>("checkStagedConsumer") {
    group = "verification"
    description = "Checks the standalone Kotlin 2.3 consumer against the staged contract publication."
    dependsOn("publishContractPublicationToStagingContractRepository")
    fixtureDirectory = layout.projectDirectory.dir("consumer-fixture")
    repositoryDirectory = layout.buildDirectory.dir("staging-maven-repository")
    gradleExecutable = currentGradleInstallation.resolve("bin/gradle")
    publishedVersion = contractVersion
    workDirectory = layout.buildDirectory.dir("staged-consumer")
}
