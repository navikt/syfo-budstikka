package no.nav.budstikka.testsupport

import java.nio.file.Path

private object Anchor

/**
 * Går opp frå test-klassane si plassering til prosjektrota (der `build.gradle.kts` ligg). * Brukast av container-klassar for å finna provisioning-filer og dashboards. */
internal fun projectRoot(): Path {
    val classDir =
        Path.of(
            Anchor::class.java
                .protectionDomain
                .codeSource
                .location
                .toURI()
                .path,
        )
    return generateSequence(classDir) { it.parent }
        .first {
            it.resolve("build.gradle.kts").toFile().exists()
        }
}
