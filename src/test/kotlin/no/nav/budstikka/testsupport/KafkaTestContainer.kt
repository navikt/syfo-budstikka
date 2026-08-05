package no.nav.budstikka.testsupport

import org.testcontainers.containers.Network
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Kafka container shared by end-to-end tests and the local run. [enableNetworkListener] adds a
 * shared Docker network and `kafka:19092` listener for other local containers.
 */
class KafkaTestContainer(
    enableNetworkListener: Boolean = false,
) : AutoCloseable {
    val network: Network? = if (enableNetworkListener) Network.newNetwork() else null

    val internalBootstrapServers: String? = if (enableNetworkListener) INTERNAL_BOOTSTRAP else null

    private val container: ConfluentKafkaContainer = buildContainer(network)

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
        network?.close()
    }

    private companion object {
        const val INTERNAL_BOOTSTRAP = "kafka:19092"

        private fun buildContainer(network: Network?): ConfluentKafkaContainer =
            ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1")).apply {
                if (network != null) {
                    withNetwork(network)
                    withNetworkAliases("kafka")
                    // The broker binds its listener to `kafka:19092`. Two things must hold under Podman:
                    //  1) hostname = kafka so `kafka` exists in the container's /etc/hosts (bind lookup).
                    //  2) network mode is explicitly set to the shared network — Testcontainers'
                    //     `withNetwork` does not reliably attach ConfluentKafkaContainer under Podman,
                    //     so HostConfig forces it. This mirrors `hostname: kafka` plus the shared network
                    //     in docker-compose.kafka.yaml, so `kafka` resolves to the container IP.
                    withCreateContainerCmdModifier { cmd ->
                        cmd.withHostName("kafka")
                        cmd.hostConfig?.withNetworkMode(network.id)
                    }
                    withListener(INTERNAL_BOOTSTRAP)
                }
            }
    }
}
