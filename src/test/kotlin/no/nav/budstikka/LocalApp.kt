package no.nav.budstikka

import io.ktor.server.plugins.di.provide
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.fakes.FakeDeathLookup
import no.nav.budstikka.testsupport.BudstikkaTestApp
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch

private val logger = LoggerFactory.getLogger("no.nav.budstikka.LocalApp")

/**
 * Lokalt løp (B50/B53): booter HELE appen mot Testcontainers (Postgres + Kafka) med port-fakes
 * wiret inn via samme substrat som e2e-harnessen — ingen Texas/tokens/compose (B51). Kjøres med
 * `./gradlew runLocal`. Alt ligger i `src/test`, så fakene kan aldri havne i prod-jaren.
 *
 * Live-inspeksjon: koble psql/DataGrip mot den loggede JDBC-URL-en, og en Kafka-klient mot
 * bootstrap-serverne, så lenge prosessen lever. Avslutt med Ctrl+C.
 */
fun main() {
    val app =
        BudstikkaTestApp.start {
            // Demonstrerer fake-sømmen: den ekte PDL-adapteren byttes mot en styrbar in-memory-fake.
            provide<DeathLookup> { FakeDeathLookup() }
        }

    logger.info("Budstikka kjører lokalt mot Testcontainers")
    logger.info("  Kafka bootstrap servers : {}", app.bootstrapServers)
    logger.info("  Formidling-topic        : {}", app.formidlingTopic)
    logger.info("  Postgres JDBC-URL        : {}", app.jdbcUrl)
    logger.info("Trykk Ctrl+C for å stoppe.")

    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("Stopper Budstikka og river ned containerne...")
            app.close()
            latch.countDown()
        },
    )
    latch.await()
}
