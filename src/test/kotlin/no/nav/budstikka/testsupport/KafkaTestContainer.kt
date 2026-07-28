package no.nav.budstikka.testsupport

import org.testcontainers.containers.Network
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Shared Kafka base for the e2e harness and the local run (B51): starts a Kafka container in
 * code. [bootstrapServers] is supplied to the app configuration, so the real consumer polls the
 * container. Fresh environment per run.
 *
 * With [enableNetworkListener] = true, it creates a shared Docker [network] and an internal listener on
 * `kafka:19092`, so other containers on the same network (typically Kafka UI in the local run) can
 * reach Kafka through the `kafka` alias. Disabled by default — e2e does not need it and should not pay for it.
 */
class KafkaTestContainer(
    enableNetworkListener: Boolean = false,
) : AutoCloseable {
    /** Shared Docker network when [enableNetworkListener] is enabled; otherwise null (e2e uses the default network). */
    val network: Network? = if (enableNetworkListener) Network.newNetwork() else null

    /** Internal container-to-container bootstrap address on the shared network; null without a listener. */
    val internalBootstrapServers: String? = if (enableNetworkListener) INTERNAL_BOOTSTRAP else null

    private val container: ConfluentKafkaContainer = buildContainer(network)

    /** Host-mapped bootstrap address — the address used by the app and host tools. */
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

        /**
         * Builds the Kafka container. When [network] is set (local run), the broker is placed on the shared
         * network with an alias and internal listener so Kafka UI can reach it through `kafka:19092`.
         */
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
