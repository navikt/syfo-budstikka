// Publishes :kontrakt as no.nav.syfo:budstikka-kontrakt. The version comes from the release tag via
// -PcontractVersion (see .github/workflows/publish-kontrakt.yml); local builds get a non-releasable
// default that the tag guard below rejects.
import org.gradle.jvm.tasks.Jar

plugins {
    `maven-publish`
}

val contractVersion = providers.gradleProperty("contractVersion").orElse("0.1.0-local")

group = "no.nav.syfo"
version = contractVersion.get()

// The staging bytes are compared and attested before publication, so every archive must be
// byte-identical across repeated builds of the same sources.
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications {
        create<MavenPublication>("contract") {
            from(components["java"])
            groupId = "no.nav.syfo"
            artifactId = "budstikka-kontrakt"
            version = contractVersion.get()
            pom {
                name.set("Budstikka contract")
                description.set("Producer-facing contract for sending notifications through Budstikka.")
            }
        }
    }
    repositories {
        maven {
            name = "localContract"
            url =
                layout.buildDirectory
                    .dir("local-maven-repository")
                    .get()
                    .asFile
                    .toURI()
        }
        maven {
            name = "stagingContract"
            url =
                layout.buildDirectory
                    .dir("staging-maven-repository")
                    .get()
                    .asFile
                    .toURI()
        }
        maven {
            name = "githubPackages"
            url = uri("https://maven.pkg.github.com/navikt/syfo-budstikka")
            credentials {
                username = "x-access-token"
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}

val expectedPublishTag = contractVersion.map { "kontrakt/v$it" }
val githubActionsEnvironment = providers.environmentVariable("GITHUB_ACTIONS")
val githubRefNameEnvironment = providers.environmentVariable("GITHUB_REF_NAME")

tasks.named("publishContractPublicationToGithubPackagesRepository") {
    doFirst {
        val expectedTag = expectedPublishTag.get()
        check(
            githubActionsEnvironment.orNull == "true" &&
                githubRefNameEnvironment.orNull == expectedTag,
        ) {
            "Publishing to GitHub Packages requires GitHub Actions on the exact tag $expectedTag."
        }
    }
}
