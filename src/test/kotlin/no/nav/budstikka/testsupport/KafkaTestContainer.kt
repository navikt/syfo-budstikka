package no.nav.budstikka.testsupport

import org.testcontainers.containers.Network
import org.testcontainers.kafka.ConfluentKafkaContainer
import org.testcontainers.utility.DockerImageName

/**
 * Delt Kafka-base for e2e-harnessen og det lokale løpet (B51): starter en Kafka-container fra
 * kode, ingen docker-compose. [bootstrapServers] mates inn i app-konfigen slik at den ekte
 * konsumenten poller mot containeren. Ferskt miljø per kjøring.
 *
 * Bruker Confluent-distribusjonens `cp-kafka`-image – den mest utprøvde Testcontainers-Kafka-en
 * på tvers av arkitekturer (inkl. arm64). Appen er distro-uavhengig; dette er en test-detalj.
 *
 * Med [enableNetworkListener] = true opprettes et delt Docker-[network] og en intern lytter på
 * `kafka:19092`, slik at ANDRE containere på samme nett (typisk Kafka UI i det lokale løpet) kan
 * nå Kafka via alias-navnet `kafka`. Default av — e2e trenger det ikke og skal ikke betale for det.
 */
class KafkaTestContainer(
    enableNetworkListener: Boolean = false,
) : AutoCloseable {
    /** Delt Docker-nett når [enableNetworkListener] er på; ellers null (e2e bruker default-nettet). */
    val network: Network? = if (enableNetworkListener) Network.newNetwork() else null

    /** Intern (container-til-container) bootstrap-adresse på det delte nettet; null uten lytter. */
    val internalBootstrapServers: String? = if (enableNetworkListener) INTERNAL_BOOTSTRAP else null

    private val container: ConfluentKafkaContainer = buildContainer(network)

    /** Host-mappet bootstrap-adresse — det appen (og host-verktøy) kobler seg på. */
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
         * Bygger Kafka-containeren. Er [network] satt (lokalt løp), plasseres broker-en på det delte
         * nettet med alias + intern lytter slik at Kafka UI kan nå den via `kafka:19092`.
         */
        private fun buildContainer(network: Network?): ConfluentKafkaContainer =
            ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.1")).apply {
                if (network != null) {
                    withNetwork(network)
                    withNetworkAliases("kafka")
                    // Broker-en BINDER lytteren til `kafka:19092`. To ting må stemme under podman:
                    //  1) hostname = kafka slik at `kafka` finnes i containerens /etc/hosts (bind-oppslag).
                    //  2) network-mode settes eksplisitt til det delte nettet — Testcontainers'
                    //     `withNetwork` fester seg ikke pålitelig på ConfluentKafkaContainer under
                    //     podman, så vi tvinger det via HostConfig. Speiler `hostname: kafka` + delt
                    //     nett i docker-compose.kafka.yaml, så `kafka` resolver til container-IP-en.
                    withCreateContainerCmdModifier { cmd ->
                        cmd.withHostName("kafka")
                        cmd.hostConfig?.withNetworkMode(network.id)
                    }
                    withListener(INTERNAL_BOOTSTRAP)
                }
            }
    }
}
