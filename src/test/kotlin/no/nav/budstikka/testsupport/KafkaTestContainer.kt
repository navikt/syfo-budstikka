package no.nav.budstikka.testsupport

import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Delt Kafka-base for e2e-harnessen og det lokale løpet (B51): starter en Kafka-container fra
 * kode, ingen docker-compose. [bootstrapServers] mates inn i app-konfigen slik at den ekte
 * konsumenten poller mot containeren. Ferskt miljø per kjøring.
 *
 * Bruker Confluent-distribusjonens `cp-kafka`-image – den mest utprøvde Testcontainers-Kafka-en
 * på tvers av arkitekturer (inkl. arm64). Appen er distro-uavhengig; dette er en test-detalj.
 */
class KafkaTestContainer : AutoCloseable {
    private val container: ConfluentKafkaContainer =
        ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1"))

    val bootstrapServers: String
        get() = container.bootstrapServers

    init {
        try {
            container.start()
        } catch (error: Exception) {
            System.err.println("=== KAFKA CONTAINER LOGS ===\n" + container.logs)
            throw error
        }
    }

    override fun close() {
        container.stop()
    }
}
