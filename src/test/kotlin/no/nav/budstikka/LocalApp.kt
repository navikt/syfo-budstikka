package no.nav.budstikka

import no.nav.budstikka.application.port.DocumentDistributor
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.domain.foundation.ReservationLookup
import no.nav.budstikka.fakes.FakeDeathLookup
import no.nav.budstikka.fakes.FakeDocumentDistributor
import no.nav.budstikka.fakes.FakeReservationLookup
import no.nav.budstikka.testsupport.BudstikkaTestApp
import no.nav.budstikka.testsupport.KafkaTestContainer
import no.nav.budstikka.testsupport.KafkaUiContainer
import no.nav.budstikka.testsupport.MonitoringContainers
import no.nav.budstikka.testsupport.installLocalProduceApi
import org.slf4j.LoggerFactory
import java.util.concurrent.CountDownLatch

private val logger = LoggerFactory.getLogger("no.nav.budstikka.LocalApp")

// Fixed app port for the local run, so the Bruno collection and Prometheus scrape have a stable URL.
// Outside the typical reserved ports (3000/8080/8081/9090). E2e uses port 0 (random) for parallelism.
private const val LOCAL_PORT = 8282

fun main() {
    val kafka = KafkaTestContainer(enableNetworkListener = true)
    val app =
        BudstikkaTestApp.start(
            kafka = kafka,
            port = LOCAL_PORT,
            // Monitoring (Grafana/Prometheus) is controlled by monitoring.enabled in application-local.conf.
            withMonitoring = ::MonitoringContainers,
        ) {
            // Demonstrates the fake seam: the real PDL adapter is replaced with a configurable in-memory fake.
            provide<DeathLookup> { FakeDeathLookup() }
            // The local KRR lookup uses no Texas token or external service.
            provide<ReservationLookup> { FakeReservationLookup() }
            // The local BREV flow must not call dokdist/Texas.
            provide<DocumentDistributor> { FakeDocumentDistributor() }
        }
    app.installLocalProduceApi()

    // Kafka UI connects to Kafka through the shared Docker network (internal address kafka:19092); local only.
    val kafkaUi = KafkaUiContainer(app.network!!, app.internalBootstrapServers!!)

    logger.info("Budstikka runs locally against Testcontainers")
    logger.info("  App                     : http://localhost:{}", LOCAL_PORT)
    logger.info("  Kafka bootstrap servers : {}", app.bootstrapServers)
    logger.info("  Budstikka-topic         : {}", app.budstikkaTopic)
    logger.info("  Postgres JDBC-URL        : {}", app.jdbcUrl)
    logger.info("  Kafka UI                : {}", kafkaUi.url)
    app.grafanaUrl?.let { logger.info("  Grafana URL             : {}", it) }
    logger.info("Press Ctrl+C to stop.")

    val latch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            logger.info("Stopping Budstikka and tearing down containers...")
            kafkaUi.close()
            app.close()
            latch.countDown()
        },
    )
    latch.await()
}
